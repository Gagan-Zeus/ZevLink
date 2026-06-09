package com.zevclip.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class AirPlayCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ZevClipPreferences.isAirPlayStreaming(this)) {
            AirPlayAudioCaptureService.stop(this)
            finish()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_AIRPLAY_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AIRPLAY_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                AirPlayAudioCaptureService.start(this, resultCode, data)
            } else {
                ZevClipPreferences.setAirPlayTestStatus(
                    this,
                    getString(R.string.airplay_capture_permission_missing)
                )
            }
            ZevClipStatusNotification.update(this)
        }
        finish()
    }

    companion object {
        private const val REQUEST_AIRPLAY_CAPTURE = 2004
    }
}
