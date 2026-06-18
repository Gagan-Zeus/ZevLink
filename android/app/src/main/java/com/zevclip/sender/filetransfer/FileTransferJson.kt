package com.zevclip.sender.filetransfer

import org.json.JSONArray
import org.json.JSONObject

object FileTransferJson {
    fun manifestToJson(manifest: FileTransferManifest): JSONObject {
        return JSONObject()
            .put("protocolVersion", manifest.protocolVersion)
            .put("transferId", manifest.transferId)
            .put("senderDeviceId", manifest.senderDeviceId)
            .put("senderName", manifest.senderName)
            .put("createdAt", manifest.createdAt)
            .put("nonce", manifest.nonce)
            .put("totalBytes", manifest.totalBytes)
            .put("entryCount", manifest.entryCount)
            .put("requestedStreamCount", manifest.requestedStreamCount)
            .put("entries", JSONArray().also { array ->
                manifest.entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("fileId", entry.fileId)
                            .put("kind", entry.kind.wireValue)
                            .put("relativePath", entry.relativePath)
                            .put("size", entry.size)
                            .put("modifiedAt", entry.modifiedAt)
                            .put("sha256", entry.sha256)
                            .put("mediaType", entry.mediaType)
                    )
                }
            })
    }

    fun manifestFromJson(json: JSONObject): FileTransferManifest {
        val entriesJson = json.getJSONArray("entries")
        val entries = (0 until entriesJson.length()).map { index ->
            val entryJson = entriesJson.getJSONObject(index)
            FileTransferEntry(
                fileId = entryJson.getString("fileId"),
                kind = FileTransferEntryKind.fromWireValue(entryJson.getString("kind"))
                    ?: error("Unknown entry kind."),
                relativePath = entryJson.getString("relativePath"),
                size = entryJson.optLongOrNull("size"),
                modifiedAt = entryJson.optStringOrNull("modifiedAt"),
                sha256 = entryJson.optStringOrNull("sha256"),
                mediaType = entryJson.optStringOrNull("mediaType")
            )
        }
        return FileTransferManifest(
            protocolVersion = json.optInt("protocolVersion", ZevLinkTransferProtocol.VERSION),
            transferId = json.getString("transferId"),
            senderDeviceId = json.getString("senderDeviceId"),
            senderName = json.getString("senderName"),
            createdAt = json.getString("createdAt"),
            nonce = json.getString("nonce"),
            totalBytes = json.getLong("totalBytes"),
            entryCount = json.getInt("entryCount"),
            requestedStreamCount = json.getInt("requestedStreamCount"),
            entries = entries
        )
    }

    fun offerResponseToJson(response: FileTransferOfferResponse): JSONObject {
        return JSONObject()
            .put("transferId", response.transferId)
            .put("state", response.state.wireValue)
            .put("chunkSize", response.chunkSize)
            .put("streamCount", response.streamCount)
            .put("resumeToken", response.resumeToken)
            .put("candidates", JSONArray())
    }

    fun rangesToJson(ranges: List<TransferVerifiedFileRanges>): JSONArray {
        return JSONArray().also { array ->
            ranges.forEach { fileRanges ->
                array.put(
                    JSONObject()
                        .put("fileId", fileRanges.fileId)
                        .put("verifiedRanges", JSONArray().also { rangesArray ->
                            fileRanges.verifiedRanges.forEach { range ->
                                rangesArray.put(
                                    JSONObject()
                                        .put("startChunk", range.startChunk)
                                        .put("endChunkExclusive", range.endChunkExclusive)
                                )
                            }
                        })
                )
            }
        }
    }

    fun rangesFromJson(array: JSONArray): List<TransferVerifiedFileRanges> {
        return (0 until array.length()).map { index ->
            val fileJson = array.getJSONObject(index)
            val rangeJson = fileJson.getJSONArray("verifiedRanges")
            TransferVerifiedFileRanges(
                fileId = fileJson.getString("fileId"),
                verifiedRanges = (0 until rangeJson.length()).map { rangeIndex ->
                    val json = rangeJson.getJSONObject(rangeIndex)
                    TransferChunkRange(
                        startChunk = json.getLong("startChunk"),
                        endChunkExclusive = json.getLong("endChunkExclusive")
                    )
                }
            )
        }
    }

    fun transferIdFromJson(bytes: ByteArray): String {
        return JSONObject(String(bytes, Charsets.UTF_8)).getString("transferId")
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeUnless { it.isEmpty() }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return getLong(name)
    }
}
