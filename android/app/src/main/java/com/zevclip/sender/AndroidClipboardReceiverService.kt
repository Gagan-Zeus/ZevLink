package com.zevclip.sender

import android.app.Service
import android.os.BatteryManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class AndroidClipboardReceiverService : Service() {
    private var receiver: AndroidClipboardHttpReceiver? = null
    private var nsdManager: NsdManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var activePort = AndroidClipboardHttpReceiver.DEFAULT_PORT
    private var networkCallbackRegistered = false
    private var networkReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val advertisingRefreshRunnable = Runnable {
        refreshAdvertising("network changed")
    }

    private val periodicAdvertisingRefreshRunnable = object : Runnable {
        override fun run() {
            refreshAdvertising("periodic discovery refresh")
            mainHandler.postDelayed(this, ADVERTISING_HEALTH_REFRESH_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleAdvertisingRefresh("network available")
        }

        override fun onLost(network: Network) {
            scheduleAdvertisingRefresh("network lost")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: android.net.NetworkCapabilities
        ) {
            scheduleAdvertisingRefresh("network capabilities changed")
        }
    }

    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleAdvertisingRefresh(intent?.action ?: "network broadcast")
        }
    }

    override fun onCreate() {
        super.onCreate()
        nsdManager = getSystemService(NsdManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        registerNetworkWatchers()
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
        unregisterNetworkWatchers()
        mainHandler.removeCallbacks(advertisingRefreshRunnable)
        mainHandler.removeCallbacks(periodicAdvertisingRefreshRunnable)
        stopReceiver("Stopped because the service was destroyed.")
        Log.i(TAG, "Android clipboard receiver service destroyed")
        super.onDestroy()
    }

    private fun startReceiver() {
        if (receiver != null) {
            Log.d(TAG, "Android clipboard receiver already running; refreshing discovery advertising")
            scheduleAdvertisingRefresh("start requested while already running")
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
                activePort = readyPort
                startAdvertising(readyPort)
                schedulePeriodicAdvertisingRefresh()
                sendPresenceToMac("receiver ready")
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
            },
            onCallAction = { action, callId ->
                AndroidCallMirrorService.performCallAction(action, callId)
            },
            onMediaControlAction = { action ->
                AirPlayMediaControlDispatcher.dispatch(this, action.toAirPlayDacpCommand())
            }
        )

        receiver = newReceiver
        newReceiver.start()
    }

    private fun stopReceiver(status: String) {
        mainHandler.removeCallbacks(advertisingRefreshRunnable)
        mainHandler.removeCallbacks(periodicAdvertisingRefreshRunnable)
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
        activePort = port

        nsdManager = getSystemService(NsdManager::class.java)
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

    private fun scheduleAdvertisingRefresh(reason: String) {
        if (receiver == null || !ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        Log.d(TAG, "Scheduling Android receiver discovery refresh: $reason")
        mainHandler.removeCallbacks(advertisingRefreshRunnable)
        mainHandler.postDelayed(advertisingRefreshRunnable, NETWORK_REFRESH_DELAY_MS)
    }

    private fun refreshAdvertising(reason: String) {
        if (receiver == null || !ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        Log.i(TAG, "Refreshing Android receiver discovery advertising: $reason")
        ZevClipPreferences.setAndroidReceiverAdvertising(
            this,
            isAdvertising = false,
            status = "Refreshing Android receiver discovery advertising…"
        )
        ZevClipStatusNotification.update(this)

        stopAdvertising()
        mainHandler.postDelayed(
            {
                if (receiver != null && ZevClipPreferences.isClipboardSyncEnabled(this)) {
                    startAdvertising(activePort)
                    sendPresenceToMac("discovery refresh")
                }
            },
            NSD_REREGISTER_DELAY_MS
        )
    }

    private fun sendPresenceToMac(reason: String, attempt: Int = 1) {
        if (receiver == null || !ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        thread(name = "ZevClipAndroidPresence") {
            when (val result = AndroidPresenceSender.sendSavedEndpoint(applicationContext, activePort)) {
                is SendResult.Success -> Log.i(TAG, "${result.message} ($reason)")
                is SendResult.Failure -> {
                    Log.w(TAG, "Android presence update failed ($reason, attempt $attempt): ${result.message}")
                    if (attempt < PRESENCE_UPDATE_ATTEMPTS) {
                        mainHandler.postDelayed(
                            { sendPresenceToMac(reason, attempt + 1) },
                            PRESENCE_RETRY_DELAY_MS
                        )
                    }
                }
            }
        }
    }

    private fun schedulePeriodicAdvertisingRefresh() {
        mainHandler.removeCallbacks(periodicAdvertisingRefreshRunnable)
        mainHandler.postDelayed(periodicAdvertisingRefreshRunnable, ADVERTISING_HEALTH_REFRESH_MS)
    }

    @Suppress("DEPRECATION")
    private fun registerNetworkWatchers() {
        val manager = connectivityManager ?: return

        if (!networkCallbackRegistered) {
            try {
                manager.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not register Android receiver network callback", error)
            }
        }

        if (!networkReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }

            try {
                registerReceiver(networkChangeReceiver, filter)
                networkReceiverRegistered = true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not register Android receiver network receiver", error)
            }
        }
    }

    private fun unregisterNetworkWatchers() {
        if (networkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not unregister Android receiver network callback", error)
            }
            networkCallbackRegistered = false
        }

        if (networkReceiverRegistered) {
            try {
                unregisterReceiver(networkChangeReceiver)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not unregister Android receiver network receiver", error)
            }
            networkReceiverRegistered = false
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
        const val SERVICE_NAME = "ZevLink Android Receiver"

        private const val TAG = "ZevClipAndroidReceiver"
        private const val TXT_DEVICE_ID = "deviceId"
        private const val TXT_BATTERY_PERCENTAGE = "battery"
        private const val NETWORK_REFRESH_DELAY_MS = 2_500L
        private const val NSD_REREGISTER_DELAY_MS = 1_200L
        private const val ADVERTISING_HEALTH_REFRESH_MS = 60_000L
        private const val PRESENCE_RETRY_DELAY_MS = 5_000L
        private const val PRESENCE_UPDATE_ATTEMPTS = 4

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

private fun String.toAirPlayDacpCommand(): AirPlayDacpCommand {
    return when (lowercase()) {
        "play" -> AirPlayDacpCommand.PlayPause
        "pause" -> AirPlayDacpCommand.Pause
        "next", "nextitem", "skip_next" -> AirPlayDacpCommand.Next
        "previous", "prev", "previtem", "skip_previous" -> AirPlayDacpCommand.Previous
        else -> AirPlayDacpCommand.PlayPause
    }
}
