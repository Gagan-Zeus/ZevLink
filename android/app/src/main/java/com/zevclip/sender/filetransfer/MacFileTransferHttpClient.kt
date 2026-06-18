package com.zevclip.sender.filetransfer

import android.util.Log
import com.zevclip.sender.NetworkInputValidator
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MacFileTransferHttpClient(
    private val host: String,
    private val port: Int,
    private val token: String
) {
    private val sender = FileTransferAndroidSender()

    fun send(
        manifest: FileTransferManifest,
        sources: List<FileTransferSource>,
        isCancelled: AtomicBoolean,
        onProgress: (Long) -> Unit
    ) {
        val offerBody = postJson(
            "/transfer/offer",
            FileTransferJson.manifestToJson(manifest).toString(),
            manifest.transferId
        )
        val maximumStreamCount = JSONObject(offerBody).getInt("streamCount")
        val rangesBody = postJson(
            "/transfer/ranges",
            JSONObject().put("transferId", manifest.transferId).toString(),
            manifest.transferId
        )
        val verifiedRanges = runCatching {
            FileTransferJson.rangesFromJson(org.json.JSONArray(rangesBody))
        }.getOrDefault(emptyList())

        val sourceByFileId = sources.associateBy { it.fileId }
        val work = manifest.fileEntries.flatMap { entry ->
            val source = sourceByFileId[entry.fileId] ?: return@flatMap emptyList<Pair<FileTransferSource, Long>>()
            val verified = verifiedRanges.firstOrNull { it.fileId == entry.fileId }
            (0 until entry.chunkCount)
                .filter { chunkIndex -> verified?.isChunkVerified(chunkIndex) != true }
                .map { chunkIndex -> source to chunkIndex }
        }

        val sentBytes = AtomicLong(0L)
        var workOffset = 0
        val samples = mutableListOf<FileTransferStreamBenchmarkSample>()
        FileTransferAdaptiveStreamSelector.CANDIDATE_STREAM_COUNTS
            .filter { it <= maximumStreamCount }
            .forEach { streamCount ->
                val sampleCount = minOf(work.size - workOffset, streamCount * 2)
                if (sampleCount < streamCount) {
                    return@forEach
                }
                val sampleWork = work.subList(workOffset, workOffset + sampleCount)
                val startedNanos = System.nanoTime()
                val bytes = uploadBatch(
                    transferId = manifest.transferId,
                    work = sampleWork,
                    streamCount = streamCount,
                    isCancelled = isCancelled,
                    sentBytes = sentBytes,
                    onProgress = onProgress
                )
                val durationMillis = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)
                samples += FileTransferStreamBenchmarkSample(streamCount, bytes, durationMillis)
                workOffset += sampleCount
            }

        val selectedStreamCount = FileTransferAdaptiveStreamSelector()
            .chooseBestStreamCount(samples)
            .coerceAtMost(maximumStreamCount)
        Log.i(
            TAG,
            "Stream benchmark ${samples.joinToString { "${it.streamCount}=${"%.1f".format(it.bytesPerSecond / 1_000_000.0)} MB/s" }}; " +
                "selected $selectedStreamCount"
        )
        uploadBatch(
            transferId = manifest.transferId,
            work = work.subList(workOffset, work.size),
            streamCount = selectedStreamCount,
            isCancelled = isCancelled,
            sentBytes = sentBytes,
            onProgress = onProgress
        )

        if (isCancelled.get()) {
            cancel(manifest.transferId)
            throw InterruptedException("Transfer cancelled.")
        }

        postJson(
            "/transfer/complete",
            JSONObject().put("transferId", manifest.transferId).toString(),
            manifest.transferId
        )
    }

    fun cancel(transferId: String) {
        runCatching {
            postJson(
                "/transfer/cancel",
                JSONObject().put("transferId", transferId).toString(),
                transferId
            )
        }.onFailure { error ->
            Log.w(TAG, "Could not notify Mac that transfer $transferId was cancelled.", error)
        }
    }

    private fun uploadBatch(
        transferId: String,
        work: List<Pair<FileTransferSource, Long>>,
        streamCount: Int,
        isCancelled: AtomicBoolean,
        sentBytes: AtomicLong,
        onProgress: (Long) -> Unit
    ): Long {
        val executor = Executors.newFixedThreadPool(streamCount)
        val batchBytes = AtomicLong(0L)
        try {
            val futures = work.map { (source, chunkIndex) ->
                executor.submit {
                    if (isCancelled.get()) {
                        throw InterruptedException("Transfer cancelled.")
                    }
                    uploadChunk(transferId, source, chunkIndex)
                    val length = ZevLinkTransferProtocol.byteRangeForChunk(source.size, chunkIndex).length
                    batchBytes.addAndGet(length)
                    onProgress(sentBytes.addAndGet(length))
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        return batchBytes.get()
    }

    private fun uploadChunk(transferId: String, source: FileTransferSource, chunkIndex: Long) {
        val range = ZevLinkTransferProtocol.byteRangeForChunk(source.size, chunkIndex)
        val connection = openConnection("/transfer/chunk", transferId).apply {
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-ZevLink-File-Id", source.fileId)
            setRequestProperty("X-ZevLink-Chunk-Index", chunkIndex.toString())
            setFixedLengthStreamingMode(range.length)
        }
        connection.outputStream.use { output ->
            sender.streamChunk(source, chunkIndex, output, computeSha256 = false)
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Mac rejected chunk $chunkIndex ($code): ${connection.errorText()}")
        }
        connection.inputStream.use { input ->
            val buffer = ByteArray(256)
            while (input.read(buffer) >= 0) {
                // Drain the response so HttpURLConnection can reuse this socket.
            }
        }
    }

    private fun postJson(path: String, body: String, transferId: String): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val connection = openConnection(path, transferId).apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        connection.outputStream.use { it.write(bodyBytes) }
        val code = connection.responseCode
        val responseText = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else connection.errorText()
        if (code !in 200..299) {
            throw IllegalStateException("Mac transfer endpoint failed ($code): $responseText")
        }
        return responseText
    }

    private fun openConnection(path: String, transferId: String): HttpURLConnection {
        val normalizedHost = NetworkInputValidator.normalizeHost(host)
        val hostPart = if (normalizedHost.contains(":")) "[$normalizedHost]" else normalizedHost
        return (URL("http://$hostPart:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 120_000
            setRequestProperty("X-ZevClip-Token", token)
            setRequestProperty("X-ZevLink-Transfer-Id", transferId)
        }
    }

    private fun HttpURLConnection.errorText(): String {
        return (errorStream ?: inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    companion object {
        private const val TAG = "ZevLinkFileTransfer"
    }
}
