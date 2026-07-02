package com.zevclip.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

class QrScannerActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var statusText: TextView
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private var previewSize: Size = Size(1280, 720)
    private var decoded = false
    private var lastDecodeAttemptAt = 0L
    private val qrReader = MultiFormatReader().apply {
        setHints(
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                put(DecodeHintType.TRY_HARDER, true)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(CameraManager::class.java)
        setContentView(createContentView())
    }

    override fun onResume() {
        super.onResume()
        decoded = false
        cameraThread = HandlerThread("ZevLinkQrScanner").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onPause() {
        closeCamera()
        if (::cameraThread.isInitialized) {
            cameraThread.quitSafely()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else if (requestCode == REQUEST_CAMERA) {
            Toast.makeText(this, R.string.qr_scanner_camera_needed, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun createContentView(): FrameLayout {
        preview = TextureView(this)
        statusText = TextView(this).apply {
            text = getString(R.string.qr_scanner_align)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val cancelButton = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                preview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            addView(
                cancelButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(32)
                }
            )
        }
    }

    private fun startCameraWhenReady() {
        if (preview.isAvailable) {
            openCamera()
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val selectedCameraId = selectCamera() ?: run {
            showScannerFailure("No back camera found")
            return
        }
        try {
            cameraManager.openCamera(selectedCameraId, cameraStateCallback, cameraHandler)
        } catch (error: Exception) {
            showScannerFailure(error.message ?: "Camera failed")
        }
    }

    private fun selectCamera(): String? {
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }?.also { id ->
            cameraId = id
            val sizes = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                .orEmpty()
            previewSize = sizes
                .filter { it.width >= 640 && it.height >= 480 }
                .minByOrNull { kotlin.math.abs((it.width * it.height) - (1280 * 720)) }
                ?: sizes.firstOrNull()
                ?: Size(1280, 720)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            showScannerFailure("Camera error $error")
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        val reader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ availableReader ->
            availableReader.acquireLatestImage()?.use(::decodeImage)
        }, cameraHandler)

        try {
            camera.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        showScannerFailure("Camera preview failed")
                    }
                },
                cameraHandler
            )
        } catch (error: Exception) {
            showScannerFailure(error.message ?: "Camera preview failed")
        }
    }

    private fun decodeImage(image: Image) {
        val now = System.currentTimeMillis()
        if (decoded || now - lastDecodeAttemptAt < DECODE_INTERVAL_MS) {
            return
        }
        lastDecodeAttemptAt = now

        val yBytes = image.copyLumaPlane()
        val source = PlanarYUVLuminanceSource(
            yBytes,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )
        try {
            val result = qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            decoded = true
            runOnUiThread {
                setResult(
                    RESULT_OK,
                    Intent().putExtra("SCAN_RESULT", result.text)
                )
                finish()
            }
        } catch (_: NotFoundException) {
            qrReader.reset()
        } catch (error: Exception) {
            qrReader.reset()
        }
    }

    private fun Image.copyLumaPlane(): ByteArray {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val output = ByteArray(width * height)
        var outputOffset = 0
        val row = ByteArray(rowStride)
        repeat(height) {
            val rowLength = if (buffer.remaining() < rowStride) buffer.remaining() else rowStride
            buffer.get(row, 0, rowLength)
            var inputOffset = 0
            repeat(width) {
                output[outputOffset++] = row[inputOffset]
                inputOffset += pixelStride
            }
        }
        return output
    }

    private fun showScannerFailure(message: String) {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.qr_scan_failed, message), Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_CAMERA = 3101
        private const val DECODE_INTERVAL_MS = 180L
    }
}
