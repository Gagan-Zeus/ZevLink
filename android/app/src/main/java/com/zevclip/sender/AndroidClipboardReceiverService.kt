package com.zevclip.sender

import android.app.Service
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import java.text.DateFormat
import java.util.Date

class AndroidClipboardReceiverService : Service() {
    private var receiver: AndroidClipboardHttpReceiver? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        nsdManager = getSystemService(NsdManager::class.java)
        Log.i(TAG, "Android clipboard receiver service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startReceiver()
            ACTION_STOP -> {
                stopReceiver("Stopped.")
                stopSelf(startId)
            }
            else -> Log.w(TAG, "Ignoring unknown receiver service action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopReceiver("Stopped because the service was destroyed.")
        Log.i(TAG, "Android clipboard receiver service destroyed")
        super.onDestroy()
    }

    private fun startReceiver() {
        if (receiver != null) {
            Log.d(TAG, "Android clipboard receiver already running")
            return
        }

        ZevClipPreferences.setAndroidReceiverState(
            this,
            isRunning = false,
            status = "Starting Android receiver on port ${AndroidClipboardHttpReceiver.DEFAULT_PORT}…"
        )
        ZevClipStatusNotification.update(this)

        val newReceiver = AndroidClipboardHttpReceiver(
            context = this,
            port = AndroidClipboardHttpReceiver.DEFAULT_PORT,
            onReady = { readyPort ->
                ZevClipPreferences.setAndroidReceiverState(
                    this,
                    isRunning = true,
                    status = "Listening on port $readyPort. Starting local discovery advertising…"
                )
                ZevClipStatusNotification.update(this)
                Log.i(TAG, "Android clipboard receiver listening on port $readyPort")
                startAdvertising(readyPort)
            },
            onFailure = { message ->
                stopAdvertising()
                ZevClipPreferences.setAndroidReceiverState(
                    this,
                    isRunning = false,
                    status = message
                )
                ZevClipStatusNotification.update(this)
                Log.w(TAG, message)
                receiver = null
                stopSelf()
            },
            onTextReceived = { text ->
                val now = System.currentTimeMillis()
                ZevClipPreferences.setAndroidReceiverLastReceived(
                    this,
                    timestampMillis = now,
                    status = "Received ${text.utf8Size()} bytes from Mac at ${formatTime(now)}."
                )
                ZevClipStatusNotification.update(this)
                Log.i(TAG, "Received ${text.length} characters from Mac")
            },
            onNotificationAction = { notificationKey ->
                AndroidNotificationMirrorService.cancelMirroredNotification(notificationKey)
            }
        )

        receiver = newReceiver
        newReceiver.start()
    }

    private fun stopReceiver(status: String) {
        stopAdvertising()
        receiver?.stop()
        receiver = null
        ZevClipPreferences.setAndroidReceiverState(
            this,
            isRunning = false,
            status = status
        )
        ZevClipStatusNotification.update(this)
    }

    private fun startAdvertising(port: Int) {
        stopAdvertising()

        val manager = nsdManager ?: run {
            ZevClipPreferences.setAndroidReceiverAdvertising(
                this,
                isAdvertising = false,
                status = "Listening on port $port, but local discovery is unavailable."
            )
            return
        }

        val androidDeviceId = ZevClipPreferences.androidDeviceId(this)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute(TXT_DEVICE_ID, androidDeviceId)
            currentBatteryPercentage()?.let { percentage ->
                setAttribute(TXT_BATTERY_PERCENTAGE, percentage.toString())
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val registeredName = serviceInfo.serviceName
                ZevClipPreferences.setAndroidReceiverAdvertising(
                    this@AndroidClipboardReceiverService,
                    isAdvertising = true,
                    status = "Listening on port $port and advertising as $registeredName."
                )
                ZevClipStatusNotification.update(this@AndroidClipboardReceiverService)
                Log.i(TAG, "Android receiver advertising as $registeredName ($SERVICE_TYPE)")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registrationListener === this) {
                    registrationListener = null
                }
                ZevClipPreferences.setAndroidReceiverAdvertising(
                    this@AndroidClipboardReceiverService,
                    isAdvertising = false,
                    status = "Listening on port $port, but advertising failed (error $errorCode)."
                )
                ZevClipStatusNotification.update(this@AndroidClipboardReceiverService)
                Log.w(TAG, "Android receiver advertising failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                if (registrationListener === this) {
                    registrationListener = null
                }
                ZevClipPreferences.setAndroidReceiverAdvertising(
                    this@AndroidClipboardReceiverService,
                    isAdvertising = false
                )
                ZevClipStatusNotification.update(this@AndroidClipboardReceiverService)
                Log.i(TAG, "Android receiver advertising stopped")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registrationListener === this) {
                    registrationListener = null
                }
                ZevClipPreferences.setAndroidReceiverAdvertising(
                    this@AndroidClipboardReceiverService,
                    isAdvertising = false,
                    status = "Advertising stop failed (error $errorCode)."
                )
                ZevClipStatusNotification.update(this@AndroidClipboardReceiverService)
                Log.w(TAG, "Android receiver advertising stop failed: $errorCode")
            }
        }

        registrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            registrationListener = null
            ZevClipPreferences.setAndroidReceiverAdvertising(
                this,
                isAdvertising = false,
                status = "Listening on port $port, but advertising failed: ${error.message ?: "unknown error"}"
            )
            ZevClipStatusNotification.update(this)
            Log.w(TAG, "Android receiver advertising threw an exception", error)
        }
    }

    private fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null

        try {
            nsdManager?.unregisterService(listener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Android receiver advertising was already stopped", error)
            ZevClipPreferences.setAndroidReceiverAdvertising(this, isAdvertising = false)
            ZevClipStatusNotification.update(this)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Android receiver advertising stop threw an exception", error)
            ZevClipPreferences.setAndroidReceiverAdvertising(
                this,
                isAdvertising = false,
                status = "Advertising stop failed: ${error.message ?: "unknown error"}"
            )
            ZevClipStatusNotification.update(this)
        }
    }

    private fun formatTime(timestampMillis: Long): String {
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestampMillis))
    }

    private fun currentBatteryPercentage(): Int? {
        val batteryManager = getSystemService(BatteryManager::class.java)
        val percentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percentage.takeIf { it in 0..100 }
    }

    private fun String.utf8Size(): Int {
        return toByteArray(Charsets.UTF_8).size
    }

    companion object {
        const val ACTION_START = "com.zevclip.sender.action.START_ANDROID_RECEIVER"
        const val ACTION_STOP = "com.zevclip.sender.action.STOP_ANDROID_RECEIVER"
        const val SERVICE_TYPE = "_zevclip-android._tcp."
        const val SERVICE_NAME = "ZevClip Android Receiver"

        private const val TAG = "ZevClipAndroidReceiver"
        private const val TXT_DEVICE_ID = "deviceId"
        private const val TXT_BATTERY_PERCENTAGE = "battery"

        fun start(context: Context) {
            context.startService(Intent(context, AndroidClipboardReceiverService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AndroidClipboardReceiverService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
