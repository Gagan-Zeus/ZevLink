package com.zevclip.sender

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairingTokenInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendButton: Button
    private lateinit var scanPairingQrButton: Button
    private lateinit var statusText: TextView
    private lateinit var discoverButton: Button
    private lateinit var discoveryStatusText: TextView
    private lateinit var pairingStatusText: TextView
    private lateinit var androidReceiverStatusText: TextView
    private lateinit var androidReceiverLastReceivedText: TextView
    private lateinit var startClipboardSyncButton: Button
    private lateinit var stopClipboardSyncButton: Button
    private lateinit var accessibilityStatusText: TextView
    private lateinit var notificationMirrorStatusText: TextView
    private lateinit var lastAutoStatusText: TextView
    private lateinit var discoveryManager: MacDiscoveryManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
        _, key ->
        if (
            key == ZevClipPreferences.KEY_LAST_AUTO_STATUS ||
            key == ZevClipPreferences.KEY_LAST_TILE_STATUS ||
            key == ZevClipPreferences.KEY_DISCOVERY_STATUS ||
            key == ZevClipPreferences.KEY_ACCESSIBILITY_SERVICE_BOUND ||
            key == ZevClipPreferences.KEY_LAST_ACCESSIBILITY_SERVICE_EVENT ||
            key == ZevClipPreferences.KEY_LAST_SERVICE_CONNECTED_AT ||
            key == ZevClipPreferences.KEY_LAST_AUTO_SEND_AT ||
            key == ZevClipPreferences.KEY_ANDROID_RECEIVER_RUNNING ||
            key == ZevClipPreferences.KEY_ANDROID_RECEIVER_STATUS ||
            key == ZevClipPreferences.KEY_ANDROID_RECEIVER_ADVERTISING ||
            key == ZevClipPreferences.KEY_ANDROID_RECEIVER_LAST_RECEIVED_AT ||
            key == ZevClipPreferences.KEY_ANDROID_RECEIVER_LAST_RECEIVED_STATUS ||
            key == ZevClipPreferences.KEY_CLIPBOARD_SYNC_ENABLED ||
            key == ZevClipPreferences.KEY_NOTIFICATION_MIRROR_CONNECTED ||
            key == ZevClipPreferences.KEY_NOTIFICATION_MIRROR_STATUS
        ) {
            runOnUiThread { refreshSyncStatuses() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        requestNotificationPermissionIfNeeded()
        discoveryManager = MacDiscoveryManager(
            context = this,
            onStatusChanged = { status, isDiscovering ->
                if (!isDestroyed) {
                    discoverButton.isEnabled = !isDiscovering
                    discoveryStatusText.text = getString(R.string.discovery_status, status)
                }
            },
            onEndpointResolved = { host, port ->
                if (!isDestroyed) {
                    ipAddressInput.setText(host)
                    portInput.setText(String.format(Locale.US, "%d", port))
                    ZevClipPreferences.saveEndpoint(this, host, port.toString())
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        ZevClipPreferences.preferences(this)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
    }

    override fun onResume() {
        super.onResume()
        AccessibilityServiceStatus.logCurrentState(this, "MainActivity.onResume")
        if (ZevClipPreferences.isClipboardSyncEnabled(this)) {
            AndroidClipboardReceiverService.start(this)
        }
        refreshSyncStatuses()
        scheduleAccessibilityRechecks()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            ZevClipStatusNotification.update(this)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacksAndMessages(null)
        ZevClipPreferences.saveEndpoint(
            this,
            ipAddressInput.text.toString(),
            portInput.text.toString()
        )
        ZevClipPreferences.savePairingToken(this, pairingTokenInput.text.toString())
        super.onPause()
    }

    override fun onStop() {
        ZevClipPreferences.preferences(this)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        discoveryManager.stop()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val preferences = ZevClipPreferences.preferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(textView(getString(R.string.screen_title), 26f, Color.BLACK).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(textView(getString(R.string.screen_description), 15f, Color.DKGRAY).apply {
            setPadding(0, dp(6), 0, dp(20))
        })

        content.addView(sectionTitle(R.string.clipboard_sync_title))

        statusText = textView(getString(R.string.ready), 15f, Color.DKGRAY).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(statusText, matchWidth())

        androidReceiverStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(androidReceiverStatusText, matchWidth())

        lastAutoStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(lastAutoStatusText, matchWidth())

        androidReceiverLastReceivedText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(androidReceiverLastReceivedText, matchWidth())

        startClipboardSyncButton = Button(this).apply {
            text = getString(R.string.start_clipboard_sync)
            isAllCaps = false
            setOnClickListener { startClipboardSync() }
        }
        content.addView(startClipboardSyncButton, matchWidth(topMargin = 8))

        stopClipboardSyncButton = Button(this).apply {
            text = getString(R.string.stop_clipboard_sync)
            isAllCaps = false
            setOnClickListener { stopClipboardSync() }
        }
        content.addView(stopClipboardSyncButton, matchWidth(topMargin = 8))

        accessibilityStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(accessibilityStatusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWidth(topMargin = 8))

        notificationMirrorStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(notificationMirrorStatusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.open_notification_access_settings)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, matchWidth(topMargin = 8))

        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.pairing_title))
        content.addView(
            textView(getString(R.string.pairing_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )

        scanPairingQrButton = Button(this).apply {
            text = getString(R.string.scan_pairing_qr)
            isAllCaps = false
            setOnClickListener { scanPairingQr() }
        }
        content.addView(scanPairingQrButton, matchWidth(topMargin = 8))

        discoverButton = Button(this).apply {
            text = getString(R.string.discover_mac)
            isAllCaps = false
            setOnClickListener { discoveryManager.discover() }
        }
        content.addView(discoverButton, matchWidth(topMargin = 8))

        discoveryStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(discoveryStatusText, matchWidth())

        content.addView(fieldLabel(R.string.pairing_token_label))
        pairingTokenInput = EditText(this).apply {
            hint = getString(R.string.pairing_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(preferences.getString(ZevClipPreferences.KEY_PAIRING_TOKEN, ""))
        }
        content.addView(pairingTokenInput, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.save_pairing_token)
            isAllCaps = false
            setOnClickListener { savePairingTokenFromUi() }
        }, matchWidth(topMargin = 8))

        pairingStatusText = textView(getString(R.string.pairing_token_not_saved), 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(pairingStatusText, matchWidth())

        content.addView(fieldLabel(R.string.mac_ip_label))
        ipAddressInput = EditText(this).apply {
            hint = getString(R.string.mac_ip_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(preferences.getString(ZevClipPreferences.KEY_IP_ADDRESS, ""))
        }
        content.addView(ipAddressInput, matchWidth())

        content.addView(fieldLabel(R.string.port_label))
        portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(
                preferences.getString(
                    ZevClipPreferences.KEY_PORT,
                    ZevClipPreferences.DEFAULT_PORT
                )
            )
        }
        content.addView(portInput, matchWidth())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            applySystemBarPadding(content)
        }
    }

    private fun sendText() {
        val ipAddress = ipAddressInput.text.toString().trim()
        val portText = portInput.text.toString().trim()
        val text = textInput.text.toString()

        if (!NetworkInputValidator.validateHost(ipAddress)) {
            showFailure("Enter a valid Mac IP or host, for example 192.168.1.10.")
            ipAddressInput.requestFocus()
            return
        }

        val port = NetworkInputValidator.parsePort(portText)
        if (port == null) {
            showFailure("Enter a port between 1 and 65535.")
            portInput.requestFocus()
            return
        }

        if (text.isEmpty()) {
            showFailure("Enter text to send.")
            textInput.requestFocus()
            return
        }

        val pairingToken = pairingTokenInput.text.toString().trim()
        if (pairingToken.isEmpty()) {
            showFailure("Enter the pairing token shown on the Mac.")
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.saveEndpoint(this, ipAddress, port.toString())
        ZevClipPreferences.savePairingToken(this, pairingToken)

        sendButton.isEnabled = false
        statusText.setTextColor(Color.DKGRAY)
        statusText.text = getString(
            R.string.sending_to_endpoint,
            "http://${ipAddress.formatEndpointHost()}:$port/clipboard"
        )

        thread(name = "ZevClipSender") {
            val result = ResilientClipboardSender.sendSavedEndpoint(applicationContext, text)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread

                refreshEndpointInputsFromPreferencesIfUnfocused()
                sendButton.isEnabled = true
                when (result) {
                    is SendResult.Success -> showSuccess(result.message)
                    is SendResult.Failure -> showFailure(result.message)
                }
            }
        }
    }

    private fun startClipboardSync() {
        saveEndpointAndTokenFromUi()
        ZevClipPreferences.setClipboardSyncEnabled(this, true)
        AndroidClipboardReceiverService.start(this)
        discoveryManager.discover()

        val accessibilityState = AccessibilityServiceStatus.currentState(this)
        if (accessibilityState.enabled) {
            showSuccess(getString(R.string.sync_started))
        } else {
            showFailure(getString(R.string.sync_started_accessibility_needed))
            Toast.makeText(
                this,
                getString(R.string.sync_started_accessibility_needed),
                Toast.LENGTH_LONG
            ).show()
        }

        refreshSyncStatuses()
    }

    private fun stopClipboardSync() {
        ZevClipPreferences.setClipboardSyncEnabled(this, false)
        AndroidClipboardReceiverService.stop(this)
        ZevClipPreferences.setLastAutoStatus(this, getString(R.string.sync_stopped))
        showSuccess(getString(R.string.sync_stopped))
        refreshSyncStatuses()
    }

    private fun saveEndpointAndTokenFromUi() {
        ZevClipPreferences.saveEndpoint(
            this,
            ipAddressInput.text.toString(),
            portInput.text.toString()
        )
        ZevClipPreferences.savePairingToken(this, pairingTokenInput.text.toString())
    }

    private fun scanPairingQr() {
        scanPairingQrButton.isEnabled = false
        statusText.setTextColor(Color.DKGRAY)
        statusText.text = getString(R.string.qr_scan_starting)

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                scanPairingQrButton.isEnabled = true
                val rawValue = barcode.rawValue
                if (rawValue.isNullOrBlank()) {
                    showFailure(getString(R.string.qr_scan_empty))
                } else {
                    savePairingQrPayload(rawValue)
                }
            }
            .addOnCanceledListener {
                scanPairingQrButton.isEnabled = true
                showFailure(getString(R.string.qr_scan_cancelled))
            }
            .addOnFailureListener { error ->
                scanPairingQrButton.isEnabled = true
                showFailure(getString(R.string.qr_scan_failed, error.message ?: "scanner failed"))
            }
    }

    private fun savePairingQrPayload(rawValue: String) {
        PairingQrPayload.parse(rawValue)
            .onSuccess { payload ->
                scanPairingQrButton.isEnabled = false
                statusText.setTextColor(Color.DKGRAY)
                statusText.text = getString(R.string.qr_pairing_checking)

                thread(name = "ZevClipQrEndpointProbe") {
                    val reachableHost = EndpointSelector.selectReachableHost(payload.hosts, payload.port)
                    runOnUiThread {
                        scanPairingQrButton.isEnabled = true
                        if (isDestroyed) return@runOnUiThread

                        val selectedHost = reachableHost ?: payload.host
                        ipAddressInput.setText(selectedHost)
                        portInput.setText(String.format(Locale.US, "%d", payload.port))
                        pairingTokenInput.setText(payload.token)
                        ZevClipPreferences.saveEndpoint(this, selectedHost, payload.port.toString())
                        ZevClipPreferences.savePairingToken(this, payload.token)
                        ZevClipPreferences.saveDeviceId(this, payload.deviceId)
                        ZevClipPreferences.setClipboardSyncEnabled(this, true)
                        AndroidClipboardReceiverService.start(this)
                        ZevClipPreferences.setDiscoveryStatus(
                            this,
                            getString(
                                R.string.qr_pairing_saved_discovery_status,
                                payload.name,
                                selectedHost.formatEndpointHost(),
                                payload.port
                            )
                        )
                        refreshSyncStatuses()
                        if (reachableHost == null) {
                            showFailure(getString(R.string.qr_pairing_saved_unverified, payload.name, selectedHost.formatEndpointHost(), payload.port))
                        } else {
                            showSuccess(getString(R.string.qr_pairing_saved, payload.name, selectedHost.formatEndpointHost(), payload.port))
                        }
                    }
                }
            }
            .onFailure { error ->
                showFailure(error.message ?: getString(R.string.qr_scan_invalid))
            }
    }

    private fun savePairingTokenFromUi() {
        val token = pairingTokenInput.text.toString().trim()
        if (token.isEmpty()) {
            pairingStatusText.setTextColor(Color.rgb(180, 32, 32))
            pairingStatusText.text = getString(R.string.pairing_token_empty)
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.savePairingToken(this, token)
        pairingStatusText.setTextColor(Color.rgb(24, 120, 54))
        pairingStatusText.text = getString(R.string.pairing_token_saved)
    }

    private fun showSuccess(message: String) {
        statusText.setTextColor(Color.rgb(24, 120, 54))
        statusText.text = message
    }

    private fun showFailure(message: String) {
        statusText.setTextColor(Color.rgb(180, 32, 32))
        statusText.text = message
    }

    private fun refreshSyncStatuses() {
        val accessibilityState = AccessibilityServiceStatus.currentState(this)
        val syncEnabled = ZevClipPreferences.isClipboardSyncEnabled(this)
        val endpoint = ZevClipPreferences.endpoint(this)
        val hasToken = ZevClipPreferences.pairingToken(this).isNotEmpty()
        val receiverRunning = ZevClipPreferences.isAndroidReceiverRunning(this)
        val canSendToMac = syncEnabled && accessibilityState.enabled && endpoint != null && hasToken
        val canReceiveFromMac = syncEnabled && receiverRunning

        statusText.setTextColor(
            when {
                canSendToMac && canReceiveFromMac -> Color.rgb(24, 120, 54)
                syncEnabled -> Color.rgb(180, 92, 0)
                else -> Color.DKGRAY
            }
        )
        statusText.text = getString(
            R.string.sync_summary,
            if (syncEnabled) getString(R.string.on) else getString(R.string.off),
            if (canSendToMac) getString(R.string.ready) else getString(R.string.needs_setup),
            if (canReceiveFromMac) getString(R.string.ready) else getString(R.string.stopped)
        )

        accessibilityStatusText.setTextColor(
            if (accessibilityState.enabled) Color.rgb(24, 120, 54) else Color.rgb(180, 32, 32)
        )
        accessibilityStatusText.text = getString(
            R.string.accessibility_compact_status,
            if (accessibilityState.enabled) {
                getString(R.string.accessibility_permission_enabled)
            } else {
                getString(R.string.accessibility_permission_disabled)
            }
        )

        val notificationAccessEnabled = NotificationAccessStatus.isEnabled(this)
        val notificationMirrorConnected = ZevClipPreferences.isNotificationMirrorConnected(this)
        notificationMirrorStatusText.setTextColor(
            if (notificationAccessEnabled && notificationMirrorConnected) {
                Color.rgb(24, 120, 54)
            } else {
                Color.rgb(180, 92, 0)
            }
        )
        notificationMirrorStatusText.text = getString(
            R.string.notification_mirror_compact_status,
            if (notificationAccessEnabled) getString(R.string.accessibility_permission_enabled)
            else getString(R.string.accessibility_permission_disabled),
            ZevClipPreferences.notificationMirrorStatus(this)
        )

        val lastAutoStatus = if (endpoint == null) {
            getString(R.string.auto_send_waiting_for_endpoint)
        } else {
            ZevClipPreferences.lastAutoStatus(this)
        }

        lastAutoStatusText.text = getString(R.string.last_auto_send_status, lastAutoStatus)
        discoveryStatusText.text = getString(
            R.string.discovery_status,
            ZevClipPreferences.discoveryStatus(this)
        )

        pairingStatusText.setTextColor(
            if (hasToken) Color.rgb(24, 120, 54) else Color.DKGRAY
        )
        pairingStatusText.text = getString(
            if (hasToken) {
                R.string.pairing_token_saved
            } else {
                R.string.pairing_token_not_saved
            }
        )

        refreshAndroidReceiverStatus()
        ZevClipStatusNotification.update(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    private fun refreshAndroidReceiverStatus() {
        val isRunning = ZevClipPreferences.isAndroidReceiverRunning(this)
        androidReceiverStatusText.setTextColor(
            if (isRunning) Color.rgb(24, 120, 54) else Color.DKGRAY
        )
        androidReceiverStatusText.text = getString(
            R.string.android_receiver_compact_status,
            if (isRunning) getString(R.string.running) else getString(R.string.stopped),
            AndroidClipboardHttpReceiver.DEFAULT_PORT
        )

        val lastReceivedAt = ZevClipPreferences.androidReceiverLastReceivedAt(this)
        androidReceiverLastReceivedText.text = getString(
            R.string.android_receiver_last_received,
            if (lastReceivedAt > 0L) formatTimestamp(lastReceivedAt) else getString(R.string.never),
            ZevClipPreferences.androidReceiverLastReceivedStatus(this)
        )

        startClipboardSyncButton.isEnabled = !ZevClipPreferences.isClipboardSyncEnabled(this) || !isRunning
        stopClipboardSyncButton.isEnabled = ZevClipPreferences.isClipboardSyncEnabled(this) || isRunning
    }

    private fun scheduleAccessibilityRechecks() {
        listOf(750L, 2_000L, 4_000L).forEach { delayMs ->
            mainHandler.postDelayed({
                if (!isDestroyed) {
                    AccessibilityServiceStatus.logCurrentState(
                        this,
                        "MainActivity delayed accessibility recheck ${delayMs}ms"
                    )
                    refreshSyncStatuses()
                }
            }, delayMs)
        }
    }

    private fun openAppInfoSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun openBatteryOptimizationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )

        openFirstAvailableIntent(
            intents,
            getString(R.string.settings_screen_unavailable)
        )
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            showSuccess(getString(R.string.battery_optimization_already_ignored))
            return
        }

        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }

        openFirstAvailableIntent(
            listOf(requestIntent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            getString(R.string.battery_optimization_request_unavailable)
        )
    }

    private fun openAutoStartSettings() {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.pem",
                    "com.vivo.pem.MainActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.PowerManagerSettingsActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.activity.AppOptimizationActivity"
                )
            )
        )

        openFirstAvailableIntent(
            intents,
            getString(R.string.autostart_settings_manual_instructions)
        )
    }

    private fun openFirstAvailableIntent(intents: List<Intent>, fallbackMessage: String) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }

        showFailure(fallbackMessage)
        Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun yesNo(value: Boolean): String {
        return getString(if (value) R.string.yes else R.string.no)
    }

    private fun String.formatEndpointHost(): String {
        return if (contains(':')) "[$this]" else this
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        if (timestampMillis <= 0L) {
            return getString(R.string.never)
        }

        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(timestampMillis))
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            val componentName = ComponentName(this, ClipboardTileService::class.java)
            val icon = Icon.createWithResource(this, R.drawable.ic_sync_clipboard)

            statusBarManager.requestAddTileService(
                componentName,
                getString(R.string.sync_clipboard_tile_label),
                icon,
                mainExecutor
            ) { result ->
                val status = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        getString(R.string.tile_add_result_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        getString(R.string.tile_add_result_already_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        getString(R.string.tile_add_result_not_added)
                    else -> getString(R.string.tile_add_result_open_instructions)
                }

                ZevClipPreferences.setLastTileStatus(this, status)
                refreshSyncStatuses()
            }
        } else {
            ZevClipPreferences.setLastTileStatus(
                this,
                getString(R.string.tile_add_result_open_instructions)
            )
        }
    }

    private fun refreshEndpointInputsFromPreferences() {
        val endpoint = ZevClipPreferences.endpoint(this) ?: return
        ipAddressInput.setText(endpoint.ipAddress)
        portInput.setText(String.format(Locale.US, "%d", endpoint.port))
    }

    private fun refreshEndpointInputsFromPreferencesIfUnfocused() {
        if (!ipAddressInput.hasFocus() && !portInput.hasFocus()) {
            refreshEndpointInputsFromPreferences()
        }
    }

    private fun sectionTitle(textResource: Int): TextView {
        return textView(getString(textResource), 18f, Color.BLACK).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        }
    }

    private fun fieldLabel(textResource: Int): TextView {
        return textView(getString(textResource), 14f, Color.DKGRAY).apply {
            setPadding(0, dp(14), 0, 0)
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.LTGRAY)
        }
    }

    private fun dividerLayoutParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun textView(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        }
    }

    private fun View.applySystemBarPadding(content: View) {
        val basePadding = dp(24)

        setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }

            setPadding(0, bars.top, 0, bars.bottom)
            content.setPadding(
                basePadding + bars.left,
                basePadding,
                basePadding + bars.right,
                basePadding
            )
            insets
        }

        requestApplyInsets()
    }

    private fun matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 2001
    }

}
