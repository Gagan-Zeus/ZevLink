package com.zevclip.sender

import android.content.Context
import android.os.BatteryManager
import com.zevclip.sender.filetransfer.FileTransferIdentityStore
import java.net.HttpURLConnection

object AndroidReceiverIdentityHeaders {
    fun apply(context: Context, connection: HttpURLConnection, port: Int = AndroidClipboardHttpReceiver.DEFAULT_PORT) {
        val appContext = context.applicationContext
        connection.setRequestProperty("X-ZevClip-Android-Device-ID", ZevClipPreferences.androidDeviceId(appContext))
        connection.setRequestProperty("X-ZevClip-Android-Receiver-Name", AndroidClipboardReceiverService.SERVICE_NAME)
        connection.setRequestProperty("X-ZevClip-Android-Receiver-Port", port.toString())
        runCatching {
            FileTransferIdentityStore.loadOrCreate(appContext).certificateSha256
        }.getOrNull()?.let { fingerprint ->
            connection.setRequestProperty("X-ZevLink-Transfer-Cert", fingerprint)
        }
        currentBatteryPercentage(appContext)?.let { percentage ->
            connection.setRequestProperty("X-ZevClip-Android-Battery", percentage.toString())
        }
    }

    private fun currentBatteryPercentage(context: Context): Int? {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val percentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percentage.takeIf { it in 0..100 }
    }
}
