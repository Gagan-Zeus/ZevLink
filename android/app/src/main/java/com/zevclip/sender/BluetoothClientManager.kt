package com.zevclip.sender

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@SuppressLint("MissingPermission")
object BluetoothClientManager {
    private const val TAG = "ZevClipBT"

    val SERVICE_UUID: UUID = UUID.fromString("17c17210-9df8-4c8e-a361-9ee85b9e7801")
    val RX_CHAR_UUID: UUID = UUID.fromString("17c17211-9df8-4c8e-a361-9ee85b9e7801")
    val TX_CHAR_UUID: UUID = UUID.fromString("17c17212-9df8-4c8e-a361-9ee85b9e7801")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val MAXIMUM_FRAME_PAYLOAD = 1_048_576

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val incomingBuffer = ArrayList<Byte>()
    private val outgoingQueue = ArrayDeque<ByteArray>()
    private var isWriting = false
    private var reconnectRunnable: Runnable? = null
    private var scanFallbackRunnable: Runnable? = null
    private var backgroundScanRunnable: Runnable? = null
    private var directConnectFailures = 0
    private var disconnectedAtMillis = 0L
    private var connectedAtMillis = 0L
    private var lastIncomingMessageAtMillis = 0L
    @Volatile
    private var isProbingWifi = false

    private val periodicPresenceRunnable = object : Runnable {
        override fun run() {
            if (isConnected()) {
                val context = ZevClipApplication.instance
                sendPresence(context, null)
                mainHandler.postDelayed(this, 25_000L)
            }
        }
    }

    private fun startPeriodicPresence() {
        mainHandler.removeCallbacks(periodicPresenceRunnable)
        mainHandler.postDelayed(periodicPresenceRunnable, 25_000L)
    }

    private fun stopPeriodicPresence() {
        mainHandler.removeCallbacks(periodicPresenceRunnable)
    }

    var onStatusChanged: ((String) -> Unit)? = null
    var onMessageReceived: ((String) -> Unit)? = null

    fun isConnected(): Boolean {
        return notificationsEnabled && rxCharacteristic != null && bluetoothGatt != null
    }

    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
        backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
        backgroundScanRunnable = null
        disconnectedAtMillis = 0L
        isScanning = false
        stopPeriodicPresence()

        if (isConnected()) {
            try {
                val disconnectMsg = buildEnvelope(appContext, "/disconnect").toString()
                sendMessage(appContext, disconnectMsg)
            } catch (_: Exception) {}

            try {
                bluetoothGatt?.let { gatt ->
                    val txChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)
                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, false)
                        val descriptor = txChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        rxCharacteristic = null
        synchronized(incomingBuffer) { incomingBuffer.clear() }

        mainHandler.postDelayed({
            disconnectAndClose()
        }, 150L)

        updateStatus(appContext, "Bluetooth sync stopped")
        ZevClipPreferences.setActiveTransportMode(appContext, "Disconnected")
        ZevClipPreferences.setMacBatteryStatus(appContext, -1, isCharging = false, isAvailable = false, seenAtMillis = 0L)
        ZevClipStatusNotification.update(appContext)
    }

    fun onBluetoothAdapterDisabled(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "Bluetooth adapter disabled by user/system")
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
        backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
        backgroundScanRunnable = null
        disconnectedAtMillis = 0L
        isScanning = false
        stopPeriodicPresence()
        disconnectAndClose()
        updateStatus(appContext, "Bluetooth is disabled")
        val currentMode = ZevClipPreferences.activeTransportMode(appContext)
        if (currentMode != "Wi-Fi") {
            ZevClipPreferences.setMacBatteryStatus(appContext, -1, isCharging = false, isAvailable = false, seenAtMillis = 0L)
            ZevClipPreferences.setActiveTransportMode(appContext, "Disconnected")
            ZevClipStatusNotification.update(appContext)
        }
    }

    fun onBluetoothAdapterEnabled(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "Bluetooth adapter enabled by user/system")
        if (ZevClipPreferences.isClipboardSyncEnabled(appContext)) {
            disconnectedAtMillis = 0L
            directConnectFailures = 0
            connect(appContext, forceScan = false)
        }
    }

    fun connect(context: Context, forceScan: Boolean = false) {
        val appContext = context.applicationContext
        Log.i(TAG, "connect() called: forceScan=$forceScan, isConnected=${isConnected()}")
        if (!ZevClipPreferences.isClipboardSyncEnabled(appContext)) {
            Log.i(TAG, "Ignoring Bluetooth connect request because clipboard sync is disabled")
            return
        }

        if (!hasBluetoothPermissions(appContext)) {
            updateStatus(appContext, "Bluetooth permission is required for offline sync")
            return
        }

        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            updateStatus(appContext, "Bluetooth is disabled")
            return
        }

        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
        backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
        backgroundScanRunnable = null
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null

        val savedAddress = ZevClipPreferences.bluetoothMacAddress(appContext)

        if (!forceScan && savedAddress.isNotBlank()) {
            updateStatus(appContext, "Connecting to saved Mac ($savedAddress)...")
            try {
                val device = adapter.getRemoteDevice(savedAddress)
                connectGattToDevice(appContext, device, autoConnect = false)

                // If direct connect doesn't establish within 5.0 seconds, scan to rediscover
                val fallback = Runnable {
                    if (!isConnected() && ZevClipPreferences.isClipboardSyncEnabled(appContext)) {
                        Log.i(TAG, "Direct GATT connection to $savedAddress timed out. Scanning for Mac...")
                        startScanForMac(appContext, adapter, scanDurationMs = 5_000L)
                    }
                }
                scanFallbackRunnable = fallback
                mainHandler.postDelayed(fallback, 5_000L)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to connect to saved device: ${error.message}", error)
                startScanForMac(appContext, adapter, scanDurationMs = 5_000L)
            }
        } else {
            startScanForMac(appContext, adapter, scanDurationMs = 5_000L)
        }
    }

    private fun startScanForMac(
        context: Context,
        adapter: BluetoothAdapter,
        scanDurationMs: Long = 5_000L,
        isBackgroundScan: Boolean = false
    ) {
        if (!hasBluetoothPermissions(context)) {
            updateStatus(context, "Bluetooth permission is required for offline sync")
            return
        }
        if (isScanning || isConnected()) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateStatus(context, "Bluetooth LE scanner unavailable")
            return
        }

        val pairedDeviceId = ZevClipPreferences.deviceId(context)
        val expectedPrefix = if (pairedDeviceId.isNotBlank()) "ZevLink:" + pairedDeviceId.take(8) else ""

        updateStatus(context, if (isBackgroundScan) "Searching for Mac in background..." else "Scanning for Mac...")
        isScanning = true

        val scanMode = if (isBackgroundScan) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val record = result.scanRecord
                val advertisedName = record?.deviceName ?: device.name ?: ""

                val hasOurService = record?.serviceUuids?.any {
                    it.uuid == SERVICE_UUID
                } == true

                val isZevLinkName = advertisedName.startsWith("ZevLink", ignoreCase = true)
                val matchesExpected = expectedPrefix.isBlank() || advertisedName.startsWith(expectedPrefix, ignoreCase = true)

                val isMatch = (hasOurService && (advertisedName.isBlank() || matchesExpected)) ||
                              (isZevLinkName && matchesExpected)

                if (!isMatch) {
                    if (advertisedName.isNotBlank() && isZevLinkName) {
                        Log.d(TAG, "Ignoring different ZevLink Mac: $advertisedName (expected: $expectedPrefix)")
                    }
                    return
                }

                Log.i(TAG, "Discovered paired ZevLink Mac: $advertisedName (${device.address})")

                isScanning = false
                try {
                    scanner.stopScan(this)
                } catch (_: Exception) {}

                backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
                backgroundScanRunnable = null
                reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                reconnectRunnable = null
                scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                scanFallbackRunnable = null
                disconnectedAtMillis = 0L

                // Save Mac address in preferences
                ZevClipPreferences.saveBluetoothMacAddress(context, device.address)
                directConnectFailures = 0
                updateStatus(context, "Mac found (${device.address})! Connecting...")

                connectGattToDevice(context, device, autoConnect = false)
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                Log.e(TAG, "BLE scan failed: $errorCode")
                updateStatus(context, "Scan failed (error $errorCode)")
                scheduleNextBackgroundScan(context)
            }
        }

        mainHandler.postDelayed({
            if (isScanning) {
                isScanning = false
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {}
                updateStatus(context, "Searching for Mac in background...")

                val saved = ZevClipPreferences.bluetoothMacAddress(context)
                if (saved.isNotBlank() && ZevClipPreferences.isClipboardSyncEnabled(context)) {
                    try {
                        val device = adapter.getRemoteDevice(saved)
                        if (bluetoothGatt == null) {
                            connectGattToDevice(context, device, autoConnect = true)
                        }
                    } catch (_: Exception) {}
                }
                scheduleNextBackgroundScan(context)
            }
        }, scanDurationMs)

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (error: Exception) {
            isScanning = false
            updateStatus(context, "Scan error: ${error.message}")
            scheduleNextBackgroundScan(context)
        }
    }

    private fun scheduleNextBackgroundScan(context: Context) {
        backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
        backgroundScanRunnable = null

        if (isConnected() || !ZevClipPreferences.isClipboardSyncEnabled(context)) {
            return
        }

        val elapsed = if (disconnectedAtMillis > 0L) System.currentTimeMillis() - disconnectedAtMillis else 0L
        val delayMs = when {
            elapsed < 2 * 60 * 1000L -> 30_000L      // First 2 mins: every 30s
            elapsed < 10 * 60 * 1000L -> 60_000L     // 2-10 mins: every 60s
            else -> 180_000L                         // After 10 mins: every 3 mins
        }

        val runnable = Runnable {
            if (isConnected() || !ZevClipPreferences.isClipboardSyncEnabled(context)) return@Runnable
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter != null && adapter.isEnabled) {
                Log.d(TAG, "Triggering background BLE scan (disconnected for ${elapsed / 1000}s)...")
                startScanForMac(context, adapter, scanDurationMs = 4_000L, isBackgroundScan = true)
            } else {
                scheduleNextBackgroundScan(context)
            }
        }
        backgroundScanRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun connectGattToDevice(context: Context, device: BluetoothDevice, autoConnect: Boolean = false) {
        if (!hasBluetoothPermissions(context)) {
            updateStatus(context, "Bluetooth permission is required for offline sync")
            return
        }
        disconnectAndClose()

        Log.i(TAG, "Initiating GATT connection to ${device.address} (autoConnect=$autoConnect)")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, gattCallback)
        }
    }

    private fun disconnectAndClose() {
        stopPeriodicPresence()
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        notificationsEnabled = false
        resetSessionState()
    }

    private fun resetSessionState() {
        notificationsEnabled = false
        connectedAtMillis = 0L
        lastIncomingMessageAtMillis = 0L
        synchronized(incomingBuffer) { incomingBuffer.clear() }
        synchronized(outgoingQueue) {
            outgoingQueue.clear()
            isWriting = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val context = ZevClipApplication.instance
            if (bluetoothGatt !== gatt) {
                Log.i(TAG, "Ignoring callback from stale GATT session")
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Requesting MTU & discovering services...")
                updateStatus(context, "Connected. Discovering services...")
                scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                scanFallbackRunnable = null
                reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                reconnectRunnable = null
                backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
                backgroundScanRunnable = null
                disconnectedAtMillis = 0L
                directConnectFailures = 0
                gatt.requestMtu(512)
                mainHandler.postDelayed({
                    if (bluetoothGatt === gatt) {
                        gatt.discoverServices()
                    }
                }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server (status: $status).")
                stopPeriodicPresence()
                rxCharacteristic = null
                notificationsEnabled = false
                resetSessionState()

                if (disconnectedAtMillis == 0L) {
                    disconnectedAtMillis = System.currentTimeMillis()
                }

                // Immediately clear Mac battery & mark disconnected if not active on Wi-Fi
                val currentMode = ZevClipPreferences.activeTransportMode(context)
                if (currentMode != "Wi-Fi") {
                    ZevClipPreferences.setMacBatteryStatus(context, -1, isCharging = false, isAvailable = false, seenAtMillis = 0L)
                    ZevClipPreferences.setActiveTransportMode(context, "Disconnected")
                    ZevClipStatusNotification.update(context)
                }

                if (!ZevClipPreferences.isClipboardSyncEnabled(context)) {
                    updateStatus(context, "Bluetooth sync stopped")
                    return
                }

                directConnectFailures++
                val saved = ZevClipPreferences.bluetoothMacAddress(context)
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter

                if (adapter == null || !adapter.isEnabled) {
                    updateStatus(context, "Bluetooth is disabled")
                    return
                }

                if (saved.isNotBlank()) {
                    updateStatus(context, "Disconnected (Auto-reconnecting...)")
                    scheduleAutoReconnect(context, saved, delayMs = 1_500L)
                } else {
                    updateStatus(context, "Scanning for Mac...")
                    startScanForMac(context, adapter, scanDurationMs = 5_000L)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val context = ZevClipApplication.instance
            if (bluetoothGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateStatus(context, "Service discovery failed: $status")
                return
            }

            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                updateStatus(context, "ZevLink BLE service not found on device")
                return
            }

            val txChar = service.getCharacteristic(TX_CHAR_UUID)
            val rxChar = service.getCharacteristic(RX_CHAR_UUID)

            if (txChar != null && rxChar != null) {
                rxCharacteristic = rxChar
                notificationsEnabled = false
                scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                scanFallbackRunnable = null
                directConnectFailures = 0
                updateStatus(context, "Connected. Enabling Bluetooth notifications...")

                gatt.setCharacteristicNotification(txChar, true)
                val descriptor = txChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } else {
                    updateStatus(context, "Bluetooth notification descriptor missing")
                    disconnectAndClose()
                }
            } else {
                updateStatus(context, "Missing required Bluetooth characteristics")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val context = ZevClipApplication.instance
            if (bluetoothGatt !== gatt) return
            Log.i(TAG, "CCCD descriptor write completed with status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notificationsEnabled = true
                connectedAtMillis = System.currentTimeMillis()
                lastIncomingMessageAtMillis = 0L
                updateStatus(context, "Connected to Mac via Bluetooth (Offline ready)")
                Log.i(TAG, "Bluetooth ready for bidirectional communication!")
                val currentMode = ZevClipPreferences.activeTransportMode(context)
                if (currentMode != "Wi-Fi") {
                    ZevClipPreferences.setActiveTransportMode(context, "Bluetooth")
                    ZevClipStatusNotification.update(context)
                }
                mainHandler.postDelayed({
                    sendPresence(context, null)
                    startPeriodicPresence()
                }, 100)
            } else {
                notificationsEnabled = false
                updateStatus(context, "Bluetooth notification setup failed: $status")
                disconnectAndClose()
            }
        }

        @Deprecated("Deprecated in Java for API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (bluetoothGatt !== gatt) return
            @Suppress("DEPRECATION")
            handleIncomingBytes(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (bluetoothGatt !== gatt) return
            handleIncomingBytes(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (gatt == null || bluetoothGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Bluetooth chunk write failed: $status")
                synchronized(outgoingQueue) {
                    isWriting = false
                    outgoingQueue.clear()
                }
                updateStatus(ZevClipApplication.instance, "Bluetooth send failed: $status")
                disconnectAndClose()
                return
            }
            isWriting = false
            processNextOutgoingChunk()
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val context = ZevClipApplication.instance
        synchronized(incomingBuffer) {
            for (b in bytes) {
                incomingBuffer.add(b)
            }

            while (incomingBuffer.size >= 4) {
                val lengthBytes = byteArrayOf(
                    incomingBuffer[0],
                    incomingBuffer[1],
                    incomingBuffer[2],
                    incomingBuffer[3]
                )
                val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int

                if (length < 0 || length > MAXIMUM_FRAME_PAYLOAD) {
                    incomingBuffer.clear()
                    break
                }

                val totalSize = 4 + length
                if (incomingBuffer.size >= totalSize) {
                    val payload = ByteArray(length)
                    for (i in 0 until length) {
                        payload[i] = incomingBuffer[4 + i]
                    }
                    incomingBuffer.subList(0, totalSize).clear()

                    val message = String(payload, Charsets.UTF_8)
                    Log.i(TAG, "Received message via BT: $message")
                    mainHandler.post {
                        processIncomingMessage(context, message)
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun processIncomingMessage(context: Context, rawMessage: String) {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) {
            Log.i(TAG, "Ignored incoming Bluetooth message because sync is stopped")
            return
        }
        try {
            val json = JSONObject(rawMessage)

            // Validate pairing token
            val expectedToken = ZevClipPreferences.pairingToken(context).trim()
            val receivedToken = json.optString("token").trim()
            if (expectedToken.isEmpty() || receivedToken != expectedToken) {
                Log.w(TAG, "Rejected incoming Bluetooth message: invalid or missing pairing token")
                updateStatus(context, "Bluetooth message rejected: token mismatch")
                return
            }

            lastIncomingMessageAtMillis = System.currentTimeMillis()
            ZevClipStatusNotification.update(context)

            // 1. Check Mac battery
            if (json.has("macBattery")) {
                val percentage = json.optInt("macBattery")
                val isCharging = json.optBoolean("macCharging", false)
                ZevClipPreferences.setMacBatteryStatus(
                    context,
                    percentage,
                    isCharging,
                    true,
                    System.currentTimeMillis()
                )
            }

            // 2. Check Mac Wi-Fi / Hotspot IPs shared via BLE
            val macIps = json.optJSONArray("macIps")
            val macPort = json.optInt("macPort", 9876)
            if (macIps != null && macIps.length() > 0) {
                val ipList = mutableListOf<String>()
                for (i in 0 until macIps.length()) {
                    val ip = macIps.optString(i)
                    if (ip.isNotBlank()) ipList.add(ip)
                }
                probeAndApplyMacEndpoint(context, ipList, macPort)
            }

            val path = json.optString("path")
            val body = json.optString("body")

            when (path) {
                "/clipboard" -> {
                    if (body.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("ZevLink", body))
                        val now = System.currentTimeMillis()
                        ZevClipPreferences.setAndroidReceiverLastReceived(
                            context,
                            now,
                            "Received ${body.length} characters via Bluetooth."
                        )
                        val currentMode = ZevClipPreferences.activeTransportMode(context)
                        if (currentMode != "Wi-Fi") {
                            ZevClipPreferences.setActiveTransportMode(context, "Bluetooth")
                            ZevClipStatusNotification.update(context)
                        }
                    }
                }
                "/call-action" -> {
                    val action = json.optString("action")
                    val callId = json.optString("callId")
                    AndroidCallMirrorService.performCallAction(action, callId)
                }
                "/notification-action" -> {
                    val key = json.optString("notificationKey")
                    val actionId = json.optString("actionId")
                    val replyText = json.optString("replyText").takeIf { it.isNotEmpty() }
                    if (actionId.isEmpty() || actionId == "dismiss") {
                        AndroidNotificationMirrorService.cancelMirroredNotification(key)
                    } else {
                        AndroidNotificationMirrorService.performMirroredNotificationAction(key, actionId, replyText)
                    }
                }
                "/find-phone" -> {
                    val action = json.optString("action")
                    if (action == "stop") {
                        FindMyPhoneRinger.stop(context)
                    } else {
                        FindMyPhoneRinger.start(context)
                    }
                }
                "/media-command" -> {
                    val action = json.optString("action")
                    AirPlayMediaControlDispatcher.dispatch(context, action.toAirPlayDacpCommand())
                }
                "/status-request" -> {
                    Log.i(TAG, "Received /status-request from Mac; sending presence update with battery")
                    sendPresence(context, null)
                }
                "/intent-broadcast" -> {
                    val result = IntentBroadcastHandler(context).handle(body.toByteArray(Charsets.UTF_8))
                    sendIntentBroadcastAcknowledgement(context, result)
                    if (result.success) {
                        Log.i(TAG, "Broadcasted Intent request ${result.requestId} via Bluetooth")
                    } else {
                        Log.w(TAG, "Intent broadcast request ${result.requestId} failed via Bluetooth: ${result.message}")
                    }
                }
            }

            ZevClipPreferences.setBluetoothLastReceived(context, rawMessage)
            onMessageReceived?.invoke(rawMessage)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling incoming Bluetooth message: ${e.message}")
        }
    }

    private fun scheduleAutoReconnect(context: Context, savedAddress: String, delayMs: Long = 1_500L) {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isConnected() || !ZevClipPreferences.isClipboardSyncEnabled(context)) return@Runnable
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter != null && adapter.isEnabled) {
                try {
                    Log.i(TAG, "Attempting automatic direct GATT reconnection to $savedAddress...")
                    val device = adapter.getRemoteDevice(savedAddress)
                    connectGattToDevice(context, device, autoConnect = true)
                } catch (error: Exception) {
                    Log.w(TAG, "Auto-reconnect attempt failed: ${error.message}")
                    startScanForMac(context, adapter, scanDurationMs = 5_000L)
                }
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    // --- Outgoing message dispatchers ---

    private fun setBluetoothActiveTransportMode(context: Context) {
        val currentMode = ZevClipPreferences.activeTransportMode(context)
        if (currentMode != "Wi-Fi") {
            ZevClipPreferences.setActiveTransportMode(context, "Bluetooth")
        }
    }

    fun sendClipboard(context: Context, text: String): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val payload = buildEnvelope(context, "/clipboard")
            .put("body", text)
        val success = sendEnvelope(context, payload)
        if (success) {
            setBluetoothActiveTransportMode(context)
        }
        return success
    }

    fun sendNotification(context: Context, notificationPayload: AndroidNotificationMirrorPayload): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val payload = buildEnvelope(context, "/android-notification")
            .put("body", String(notificationPayload.toJsonBytes(), Charsets.UTF_8))
        val success = sendEnvelope(context, payload)
        if (success) {
            setBluetoothActiveTransportMode(context)
        }
        return success
    }

    fun sendCall(context: Context, callPayload: AndroidCallMirrorPayload): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val payload = buildEnvelope(context, "/android-call")
            .put("body", String(callPayload.toJsonBytes(), Charsets.UTF_8))
        val success = sendEnvelope(context, payload)
        if (success) {
            setBluetoothActiveTransportMode(context)
        }
        return success
    }

    fun sendPresence(context: Context, findPhoneRinging: Boolean?): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val payload = buildEnvelope(context, "/android-presence").apply {
            findPhoneRinging?.let { put("isRinging", it) }
        }
        val success = sendEnvelope(context, payload)
        if (success) {
            setBluetoothActiveTransportMode(context)
        }
        return success
    }

    fun sendRemote(context: Context, action: String, url: String?): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val remoteBody = JSONObject().put("action", action).apply {
            if (!url.isNullOrBlank()) put("url", url.trim())
        }.toString()
        val payload = buildEnvelope(context, "/remote")
            .put("body", remoteBody)
        val success = sendEnvelope(context, payload)
        if (!success) {
            updateStatus(context, "Bluetooth remote command could not be sent")
        }
        if (success) {
            setBluetoothActiveTransportMode(context)
        }
        return success
    }

    private fun sendIntentBroadcastAcknowledgement(
        context: Context,
        result: IntentBroadcastResult
    ): Boolean {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return false
        val payload = buildEnvelope(context, "/intent-broadcast-ack")
            .put("body", result.toJson().toString())
        return sendEnvelope(context, payload)
    }

    fun sendTestPing(context: Context) {
        if (!ZevClipPreferences.isClipboardSyncEnabled(context)) return
        val payload = buildEnvelope(context, "/ping")
            .put("body", "Hello from Android via Bluetooth!")
        sendEnvelope(context, payload)
    }

    fun getBatteryPercentage(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) return capacity

        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                return (level * 100) / scale
            }
        } catch (_: Exception) {}
        return -1
    }

    private fun buildEnvelope(context: Context, path: String): JSONObject {
        val batteryLevel = getBatteryPercentage(context)
        val localIps = LocalNetworkAccess.localIPv4Addresses()
        val jsonIps = org.json.JSONArray()
        localIps.forEach { jsonIps.put(it) }

        return JSONObject()
            .put("path", path)
            .put("token", ZevClipPreferences.pairingToken(context))
            .put("deviceId", ZevClipPreferences.androidDeviceId(context))
            .put("battery", if (batteryLevel in 0..100) batteryLevel else JSONObject.NULL)
            .put("phoneIps", jsonIps)
            .put("phonePort", AndroidClipboardHttpReceiver.DEFAULT_PORT)
    }

    private fun sendEnvelope(context: Context, envelope: JSONObject): Boolean {
        val token = ZevClipPreferences.pairingToken(context)
        if (token.isBlank()) {
            updateStatus(context, "Bluetooth pairing token is unavailable")
            return false
        }
        if (!envelope.has("token")) {
            envelope.put("token", token)
        }
        return sendMessage(context, envelope.toString())
    }

    fun sendMessage(context: Context, text: String): Boolean {
        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt == null || rxChar == null || !notificationsEnabled) {
            updateStatus(context, "Cannot send: Mac is not connected via Bluetooth")
            return false
        }

        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > MAXIMUM_FRAME_PAYLOAD) {
            updateStatus(context, "Bluetooth message is too large to send")
            return false
        }
        val frame = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()

        val chunkSize = 180
        var offset = 0
        synchronized(outgoingQueue) {
            while (offset < frame.size) {
                val len = minOf(chunkSize, frame.size - offset)
                val chunk = ByteArray(len)
                System.arraycopy(frame, offset, chunk, 0, len)
                outgoingQueue.add(chunk)
                offset += len
            }
        }

        ZevClipPreferences.setBluetoothLastSent(context, text)
        processNextOutgoingChunk()
        return true
    }

    private fun processNextOutgoingChunk() {
        val gatt = bluetoothGatt ?: return
        val rxChar = rxCharacteristic ?: return

        synchronized(outgoingQueue) {
            if (isWriting || outgoingQueue.isEmpty()) return
            val chunk = outgoingQueue.removeFirstOrNull() ?: return
            isWriting = true

            val writeSuccess = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val code = gatt.writeCharacteristic(rxChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    code == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    rxChar.value = chunk
                    rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(rxChar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error writing BLE characteristic: ${e.message}")
                false
            }

            if (!writeSuccess) {
                isWriting = false
                outgoingQueue.addFirst(chunk)
                mainHandler.postDelayed({ processNextOutgoingChunk() }, 100)
            }
        }
    }

    private fun probeAndApplyMacEndpoint(context: Context, ips: List<String>, port: Int) {
        if (isProbingWifi) return
        isProbingWifi = true
        kotlin.concurrent.thread(name = "ZevClipBleWifiProbe") {
            try {
                val reachable = EndpointSelector.selectReachableHost(ips, port)
                if (reachable != null) {
                    Log.i(TAG, "Reachable Mac Wi-Fi IP discovered via BLE: $reachable:$port")
                    val currentEndpoint = ZevClipPreferences.endpoint(context)
                    val isNewEndpoint = currentEndpoint == null ||
                            currentEndpoint.ipAddress != reachable ||
                            currentEndpoint.port != port
                    val currentMode = ZevClipPreferences.activeTransportMode(context)

                    if (isNewEndpoint || currentMode != "Wi-Fi") {
                        ZevClipPreferences.saveEndpoint(context, reachable, port.toString())
                        ZevClipPreferences.setActiveTransportMode(context, "Wi-Fi")
                        ZevClipStatusNotification.update(context)
                        try {
                            AndroidPresenceSender.sendSavedEndpoint(
                                context,
                                AndroidClipboardHttpReceiver.DEFAULT_PORT
                            )
                        } catch (_: Exception) {}
                    }
                } else if (isConnected()) {
                    val currentMode = ZevClipPreferences.activeTransportMode(context)
                    val currentEndpoint = ZevClipPreferences.endpoint(context)
                    val isCurrentStillReachable = currentEndpoint != null && EndpointSelector.isReachable(currentEndpoint.ipAddress, currentEndpoint.port)
                    if (!isCurrentStillReachable && currentMode != "Bluetooth") {
                        ZevClipPreferences.setActiveTransportMode(context, "Bluetooth")
                        ZevClipStatusNotification.update(context)
                    }
                }
            } finally {
                isProbingWifi = false
            }
        }
    }

    fun forgetMac(context: Context) {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
        backgroundScanRunnable?.let { mainHandler.removeCallbacks(it) }
        backgroundScanRunnable = null
        disconnectedAtMillis = 0L
        disconnectAndClose()
        isScanning = false
        ZevClipPreferences.saveBluetoothMacAddress(context, "")
        updateStatus(context, "Pairing cleared. Next connection will scan once.")
    }

    private fun updateStatus(context: Context, status: String) {
        Log.i(TAG, "Status: $status")
        mainHandler.post {
            ZevClipPreferences.setBluetoothStatus(context, status)
            onStatusChanged?.invoke(status)
        }
    }
}
