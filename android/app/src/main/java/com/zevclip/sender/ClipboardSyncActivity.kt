package com.zevclip.sender

import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Bundle
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class ClipboardSyncActivity : Activity() {
    private var syncStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.syncing_clipboard)
                textSize = 20f
                gravity = Gravity.CENTER
            }
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || syncStarted) {
            return
        }

        syncStarted = true
        window.decorView.postDelayed({
            syncClipboard()
            finish()
        }, CLIPBOARD_READ_DELAY_MS)
    }

    private fun syncClipboard() {
        val clipboardText = try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: SecurityException) {
            Log.w(ClipboardTileService.TAG, "Android denied clipboard access for tile action", error)
            complete("Failed", "Failed: Android denied clipboard access.")
            return
        }

        ClipboardSyncCoordinator.sendIfChanged(
            context = applicationContext,
            text = clipboardText
        ) { result ->
            when (result) {
                is ClipboardSyncResult.Success -> {
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    Log.i(ClipboardTileService.TAG, "Quick Settings clipboard sync succeeded")
                    complete("Sent", "Sent at $time (${result.characterCount} characters).")
                }
                ClipboardSyncResult.Empty -> {
                    Log.i(
                        ClipboardTileService.TAG,
                        "Quick Settings clipboard sync skipped: empty clipboard"
                    )
                    complete("Empty", "Empty clipboard.")
                }
                ClipboardSyncResult.NoEndpoint -> {
                    Log.i(
                        ClipboardTileService.TAG,
                        "Quick Settings clipboard sync skipped: no Mac IP"
                    )
                    complete("No Mac IP", "No valid Mac IP and port.")
                }
                ClipboardSyncResult.Duplicate -> {
                    Log.i(
                        ClipboardTileService.TAG,
                        "Quick Settings clipboard sync skipped: already sent"
                    )
                    complete("Sent", "Already sent.")
                }
                is ClipboardSyncResult.Failure -> {
                    Log.w(
                        ClipboardTileService.TAG,
                        "Quick Settings clipboard sync failed: ${result.message}"
                    )
                    complete("Failed", "Failed: ${result.message}")
                }
            }
        }
    }

    private fun complete(tileSubtitle: String, status: String) {
        ZevClipPreferences.setLastTileResult(applicationContext, tileSubtitle, status)
        TileService.requestListeningState(
            applicationContext,
            ComponentName(applicationContext, ClipboardTileService::class.java)
        )
    }

    private companion object {
        const val CLIPBOARD_READ_DELAY_MS = 250L
    }
}
