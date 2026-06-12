package com.zevclip.sender.airplay

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val sink: AirPlayScreenSampleSink,
    private val running: AtomicBoolean
) : Closeable {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun start(projection: MediaProjection) {
        val frameRate = TARGET_FRAME_RATE
        val bitRate = targetBitRate(width, height, frameRate)
        Log.i(TAG, "Starting screen encoder at ${width}x$height dpi=$densityDpi fps=$frameRate bitrate=$bitRate")
        val nextCodec = configureEncoder(frameRate, bitRate)
        codec = nextCodec
        inputSurface = nextCodec.createInputSurface()
        nextCodec.start()

        virtualDisplay = projection.createVirtualDisplay(
            "ZevLink AirPlay Screen",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        drain(nextCodec)
    }

    private fun configureEncoder(frameRate: Int, bitRate: Int): MediaCodec {
        return runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
                codec.configure(videoFormat(frameRate, bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }.getOrElse { error ->
            Log.w(TAG, "Preferred 60fps screen encoder config failed; falling back to 30fps", error)
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
                codec.configure(
                    videoFormat(FALLBACK_FRAME_RATE, targetBitRate(width, height, FALLBACK_FRAME_RATE)),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            }
        }
    }

    private fun videoFormat(frameRate: Int, bitRate: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }
    }

    private fun drain(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
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

    override fun close() {
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
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MIN_BIT_RATE = 10_000_000
        private const val MAX_BIT_RATE = 24_000_000
        private const val BITS_PER_PIXEL_FRAME_X100 = 28L

        fun scaledSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int = 1280): Pair<Int, Int> {
            if (sourceWidth <= 0 || sourceHeight <= 0) return 1280 to 720
            val longEdge = maxOf(sourceWidth, sourceHeight)
            if (longEdge <= maxWidth) return sourceWidth.even() to sourceHeight.even()
            val ratio = maxWidth.toDouble() / longEdge.toDouble()
            return (sourceWidth * ratio).toInt().even() to (sourceHeight * ratio).toInt().even()
        }

        private fun targetBitRate(width: Int, height: Int, frameRate: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val scaled = (pixels * frameRate * BITS_PER_PIXEL_FRAME_X100 / 100L).toInt()
            return scaled.coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
        }

        private const val TAG = "ZevClipAirPlayEncoder"
    }
}

interface AirPlayScreenSampleSink {
    fun setCodecConfig(config: ByteArray)
    fun writeSample(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean)
}

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

private fun Int.even(): Int {
    return if (this % 2 == 0) this else this - 1
}
