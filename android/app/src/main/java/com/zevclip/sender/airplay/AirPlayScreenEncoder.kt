package com.zevclip.sender.airplay

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayScreenEncoder(
    initialWidth: Int,
    initialHeight: Int,
    initialDensityDpi: Int,
    private val sink: AirPlayScreenSampleSink,
    private val running: AtomicBoolean
) : Closeable {
    private var currentWidth = initialWidth
    private var currentHeight = initialHeight
    private var currentDensityDpi = initialDensityDpi
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val resizeRequest = AtomicReference<EncoderSize?>(null)

    fun start(projection: MediaProjection) {
        val frameRate = TARGET_FRAME_RATE
        val bitRate = targetBitRate(currentWidth, currentHeight, frameRate)
        Log.i(TAG, "Starting screen encoder at ${currentWidth}x$currentHeight dpi=$currentDensityDpi fps=$frameRate bitrate=$bitRate")
        val nextCodec = configureEncoder(currentWidth, currentHeight, frameRate, bitRate)
        codec = nextCodec
        inputSurface = nextCodec.createInputSurface()
        nextCodec.start()

        virtualDisplay = projection.createVirtualDisplay(
            "ZevLink AirPlay Screen",
            currentWidth,
            currentHeight,
            currentDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        drain(nextCodec)
    }

    fun requestResize(width: Int, height: Int, densityDpi: Int) {
        if (width <= 0 || height <= 0 || densityDpi <= 0) return
        if (width == currentWidth && height == currentHeight && densityDpi == currentDensityDpi) return
        resizeRequest.set(EncoderSize(width, height, densityDpi))
    }

    private fun configureEncoder(width: Int, height: Int, frameRate: Int, bitRate: Int): MediaCodec {
        val level = avcLevelFor(width, height, frameRate)
        val attempts = listOf(
            EncoderConfig(
                frameRate = frameRate,
                bitRate = bitRate,
                bitRateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                level = level,
                name = "60fps high-profile vbr"
            ),
            EncoderConfig(
                frameRate = frameRate,
                bitRate = bitRate,
                bitRateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                level = level,
                name = "60fps high-profile cbr"
            ),
            EncoderConfig(
                frameRate = frameRate,
                bitRate = bitRate,
                bitRateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                level = level,
                name = "60fps baseline vbr"
            ),
            EncoderConfig(
                frameRate = frameRate,
                bitRate = bitRate,
                bitRateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                level = level,
                name = "60fps baseline cbr"
            ),
            EncoderConfig(
                frameRate = FALLBACK_FRAME_RATE,
                bitRate = targetBitRate(width, height, FALLBACK_FRAME_RATE),
                bitRateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                level = avcLevelFor(width, height, FALLBACK_FRAME_RATE),
                name = "30fps baseline"
            )
        )
        var lastError: Throwable? = null
        attempts.forEach { attempt ->
            val codec = runCatching { createAvcEncoder() }
                .getOrElse { error ->
                    lastError = error
                    return@forEach
            }
            runCatching {
                codec.configure(
                    videoFormat(
                        width = width,
                        height = height,
                        frameRate = attempt.frameRate,
                        bitRate = attempt.bitRate,
                        bitRateMode = attempt.bitRateMode,
                        profile = attempt.profile,
                        level = attempt.level
                    ),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                Log.i(TAG, "Configured AirPlay screen encoder ${codec.name} using ${attempt.name} at bitrate=${attempt.bitRate}")
                return codec
            }.onFailure { error ->
                lastError = error
                Log.w(TAG, "AirPlay screen encoder rejected ${attempt.name}; trying fallback", error)
                runCatching { codec.release() }
            }
        }
        throw IllegalStateException("No AirPlay screen encoder configuration worked.", lastError)
    }

    private fun createAvcEncoder(): MediaCodec {
        val codecName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info ->
                    info.isEncoder &&
                        info.isHardwareAccelerated &&
                        info.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                }
                ?.name
        } else {
            null
        }
        return if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
    }

    private fun videoFormat(
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        bitRateMode: Int,
        profile: Int,
        level: Int
    ): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            } else {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS.toInt())
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, bitRateMode)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, profile)
                setInteger(MediaFormat.KEY_LEVEL, level)
                setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger("low-latency", 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setInteger("allow-frame-drop", 1)
            }
        }
    }

    private fun drain(startingCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var activeCodec = startingCodec
        while (running.get()) {
            resizeRequest.getAndSet(null)?.let { requestedSize ->
                activeCodec = resizeEncoder(activeCodec, requestedSize)
            }
            val outputIndex = activeCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sink.setCodecConfig(activeCodec.outputFormat.codecConfig())
                }
                outputIndex >= 0 -> {
                    val buffer = activeCodec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        val flags = info.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sink.setCodecConfig(bytes)
                        } else {
                            val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            sink.writeSample(bytes, info.presentationTimeUs, keyFrame)
                        }
                    }
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun resizeEncoder(activeCodec: MediaCodec, requestedSize: EncoderSize): MediaCodec {
        if (
            requestedSize.width == currentWidth &&
            requestedSize.height == currentHeight &&
            requestedSize.densityDpi == currentDensityDpi
        ) {
            return activeCodec
        }

        val frameRate = TARGET_FRAME_RATE
        val bitRate = targetBitRate(requestedSize.width, requestedSize.height, frameRate)
        Log.i(
            TAG,
            "Resizing screen encoder to ${requestedSize.width}x${requestedSize.height} " +
                "dpi=${requestedSize.densityDpi} fps=$frameRate bitrate=$bitRate"
        )

        val nextCodec = configureEncoder(requestedSize.width, requestedSize.height, frameRate, bitRate)
        val nextSurface = nextCodec.createInputSurface()
        nextCodec.start()

        val oldSurface = inputSurface
        codec = nextCodec
        inputSurface = nextSurface
        currentWidth = requestedSize.width
        currentHeight = requestedSize.height
        currentDensityDpi = requestedSize.densityDpi
        virtualDisplay?.resize(currentWidth, currentHeight, currentDensityDpi)
        virtualDisplay?.setSurface(nextSurface)

        runCatching { activeCodec.stop() }
        runCatching { activeCodec.release() }
        runCatching { oldSurface?.release() }
        return nextCodec
    }

    override fun close() {
        resizeRequest.set(null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    companion object {
        private const val TARGET_FRAME_RATE = 60
        private const val FALLBACK_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 0.5f
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MIN_BIT_RATE = 16_000_000
        private const val MAX_BIT_RATE = 80_000_000
        private const val BITS_PER_PIXEL_FRAME_X100 = 48L

        fun scaledSize(
            sourceWidth: Int,
            sourceHeight: Int,
            maxLongEdge: Int = 3840,
            maxPixels: Int = 3840 * 2160
        ): Pair<Int, Int> {
            if (sourceWidth <= 0 || sourceHeight <= 0) return 1280 to 720
            val longEdgeScale = maxLongEdge.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble()
            val pixelScale = kotlin.math.sqrt(maxPixels.toDouble() / (sourceWidth.toDouble() * sourceHeight.toDouble()))
            val ratio = minOf(1.0, longEdgeScale, pixelScale)
            return (sourceWidth * ratio).toInt().evenAtLeast(2) to (sourceHeight * ratio).toInt().evenAtLeast(2)
        }

        private fun targetBitRate(width: Int, height: Int, frameRate: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val scaled = (pixels * frameRate * BITS_PER_PIXEL_FRAME_X100 / 100L).toInt()
            return scaled.coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
        }

        private fun avcLevelFor(width: Int, height: Int, frameRate: Int): Int {
            val macroblocks = ((width + 15) / 16) * ((height + 15) / 16)
            val macroblocksPerSecond = macroblocks * frameRate
            return when {
                width > 2048 || height > 2048 || macroblocksPerSecond > 522_240 ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel52
                width > 1920 || height > 1088 || macroblocksPerSecond > 245_760 ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            }
        }

        private const val TAG = "ZevClipAirPlayEncoder"
    }
}

interface AirPlayScreenSampleSink {
    fun setCodecConfig(config: ByteArray)
    fun writeSample(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean)
}

private data class EncoderConfig(
    val frameRate: Int,
    val bitRate: Int,
    val bitRateMode: Int,
    val profile: Int,
    val level: Int,
    val name: String
)

private data class EncoderSize(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

private fun MediaFormat.codecConfig(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    getByteBuffer("csd-0")?.let { buffer ->
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        output.write(bytes)
    }
    getByteBuffer("csd-1")?.let { buffer ->
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        output.write(bytes)
    }
    return output.toByteArray()
}

private fun Int.evenAtLeast(minimum: Int): Int {
    val even = if (this % 2 == 0) this else this - 1
    return maxOf(minimum, even)
}
