package com.zevclip.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager

class AirPlayScreenCaptureActivity : Activity() {
    private var requestedCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        configureTransparentHandoffWindow()
        startScreenMirror()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Android framework, but still the compatibility path for this Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            AirPlayScreenMirrorService.start(this, resultCode, data)
        } else {
            updateStatus(getString(R.string.airplay_capture_permission_missing))
        }
        finish()
    }

    private fun startScreenMirror() {
        if (requestedCapture) return
        if (ZevClipPreferences.isAirPlayScreenMirroring(this)) {
            AirPlayScreenMirrorService.stop(this)
            updateStatus(getString(R.string.airplay_screen_mirror_stopped))
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus(getString(R.string.airplay_capture_android_q_required))
            finish()
            return
        }
        val endpoint = ZevClipPreferences.endpoint(this)
        if (endpoint == null) {
            updateStatus(getString(R.string.airplay_capture_pairing_needed))
            finish()
            return
        }
        if (ZevClipPreferences.isAirPlayStreaming(this)) {
            AirPlayAudioCaptureService.stop(this)
        }
        if (ZevClipPreferences.isAirPlayBroadcastStreaming(this)) {
            AirPlayBroadcastAudioService.stop(this)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            updateStatus(getString(R.string.airplay_capture_record_audio_needed))
            return
        }

        requestedCapture = true
        updateStatus(getString(R.string.airplay_screen_mirror_connecting))
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScreenMirror()
        } else {
            updateStatus(getString(R.string.airplay_capture_record_audio_needed))
            finish()
        }
    }

    private fun updateStatus(message: String) {
        ZevClipPreferences.setAirPlayTestStatus(this, message)
        ZevClipStatusNotification.update(this)
    }

    private fun configureTransparentHandoffWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.decorView.visibility = View.INVISIBLE
        window.setLayout(1, 1)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            width = 1
            height = 1
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3202
        private const val REQUEST_RECORD_AUDIO = 3203
    }
}
