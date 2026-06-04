package com.zevclip.sender

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile(
            subtitle = ZevClipPreferences.lastTileSubtitle(this),
            state = Tile.STATE_INACTIVE
        )
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Sync Clipboard tile tapped")
        updateTile("Sending…", Tile.STATE_ACTIVE)

        unlockAndRun {
            openClipboardSyncActivity()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openClipboardSyncActivity() {
        val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(subtitle: String, state: Int) {
        qsTile?.apply {
            label = getString(R.string.sync_clipboard_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                this.subtitle = subtitle
            }
            this.state = state
            updateTile()
        }
    }

    companion object {
        const val TAG = "ZevClip"
    }
}
