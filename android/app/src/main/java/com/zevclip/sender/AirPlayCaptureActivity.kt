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
import android.view.Window
import android.view.WindowManager

class AirPlayCaptureActivity : Activity() {
    private var requestedCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        overridePendingTransition(0, 0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        super.onCreate(savedInstanceState)
        startAirPlayCapture()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startAirPlayCapture()
        } else {
            ZevClipPreferences.setAirPlayTestStatus(
                this,
                getString(R.string.airplay_capture_record_audio_needed)
            )
            ZevClipStatusNotification.update(this)
            finish()
        }
    }

    @Deprecated("Deprecated in Android framework, but still the compatibility path for this Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AIRPLAY_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            AirPlayAudioCaptureService.start(this, resultCode, data)
        } else {
            ZevClipPreferences.setAirPlayTestStatus(
                this,
                getString(R.string.airplay_capture_permission_missing)
            )
            ZevClipStatusNotification.update(this)
        }
        finish()
    }

    private fun startAirPlayCapture() {
        if (requestedCapture || ZevClipPreferences.isAirPlayStreaming(this)) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_android_q_required))
            ZevClipStatusNotification.update(this)
            finish()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        requestedCapture = true
        ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_starting))
        ZevClipStatusNotification.update(this)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_AIRPLAY_CAPTURE
        )
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 3101
        private const val REQUEST_AIRPLAY_CAPTURE = 3102
    }
}
