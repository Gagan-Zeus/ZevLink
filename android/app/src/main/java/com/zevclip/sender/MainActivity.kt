package com.zevclip.sender

import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
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
    private lateinit var accessibilityStatusText: TextView
    private lateinit var backgroundHealthStatusText: TextView
    private lateinit var recheckAccessibilityButton: Button
    private lateinit var lastAutoStatusText: TextView
    private lateinit var tileStatusText: TextView
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
            key == ZevClipPreferences.KEY_LAST_AUTO_SEND_AT
        ) {
            runOnUiThread { refreshSyncStatuses() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
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
        refreshSyncStatuses()
        scheduleAccessibilityRechecks()
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

        content.addView(sectionTitle(R.string.discovery_title))
        content.addView(
            textView(getString(R.string.discovery_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
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
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.qr_pairing_title))
        content.addView(
            textView(getString(R.string.qr_pairing_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        scanPairingQrButton = Button(this).apply {
            text = getString(R.string.scan_pairing_qr)
            isAllCaps = false
            setOnClickListener { scanPairingQr() }
        }
        content.addView(scanPairingQrButton, matchWidth(topMargin = 8))
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.pairing_title))
        content.addView(
            textView(getString(R.string.pairing_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
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
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(pairingStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.auto_send_title))
        accessibilityStatusText = textView("", 15f, Color.DKGRAY)
        content.addView(accessibilityStatusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWidth(topMargin = 8))

        recheckAccessibilityButton = Button(this).apply {
            text = getString(R.string.recheck_accessibility_permission)
            isAllCaps = false
            setOnClickListener {
                AccessibilityServiceStatus.logCurrentState(
                    this@MainActivity,
                    "Manual accessibility recheck"
                )
                refreshSyncStatuses()
            }
        }
        content.addView(recheckAccessibilityButton, matchWidth(topMargin = 8))

        lastAutoStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(lastAutoStatusText, matchWidth())

        content.addView(divider(), dividerLayoutParams(topMargin = 12))
        content.addView(sectionTitle(R.string.background_health_title))
        backgroundHealthStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(backgroundHealthStatusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.open_app_info)
            isAllCaps = false
            setOnClickListener { openAppInfoSettings() }
        }, matchWidth(topMargin = 8))

        content.addView(Button(this).apply {
            text = getString(R.string.open_battery_optimization_settings)
            isAllCaps = false
            setOnClickListener { openBatteryOptimizationSettings() }
        }, matchWidth(topMargin = 8))

        content.addView(Button(this).apply {
            text = getString(R.string.request_ignore_battery_optimization)
            isAllCaps = false
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }, matchWidth(topMargin = 8))

        content.addView(Button(this).apply {
            text = getString(R.string.open_autostart_settings)
            isAllCaps = false
            setOnClickListener { openAutoStartSettings() }
        }, matchWidth(topMargin = 8))

        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.quick_settings_title))
        content.addView(
            textView(getString(R.string.quick_settings_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        content.addView(Button(this).apply {
            text = getString(R.string.add_quick_settings_tile)
            isAllCaps = false
            setOnClickListener { requestQuickSettingsTile() }
        }, matchWidth(topMargin = 8))

        tileStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(tileStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.manual_send_title))
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

        content.addView(fieldLabel(R.string.text_label))
        textInput = EditText(this).apply {
            hint = getString(R.string.text_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 12
        }
        content.addView(textInput, matchWidth())

        sendButton = Button(this).apply {
            text = getString(R.string.send)
            isAllCaps = false
            setOnClickListener { sendText() }
        }
        content.addView(sendButton, matchWidth(topMargin = 18))

        statusText = textView(getString(R.string.ready), 15f, Color.DKGRAY).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(statusText, matchWidth())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
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
        accessibilityStatusText.setTextColor(
            if (accessibilityState.enabled) Color.rgb(24, 120, 54) else Color.rgb(180, 32, 32)
        )
        accessibilityStatusText.text = getString(
            R.string.accessibility_state_details,
            if (accessibilityState.enabled) {
                getString(R.string.accessibility_permission_enabled)
            } else {
                getString(R.string.accessibility_permission_disabled)
            },
            if (accessibilityState.serviceBound) {
                getString(R.string.yes)
            } else {
                getString(R.string.no)
            },
            accessibilityState.lastServiceEvent,
            accessibilityState.diagnostic
        )

        val lastAutoStatus = if (ZevClipPreferences.endpoint(this) == null) {
            getString(R.string.auto_send_waiting_for_endpoint)
        } else {
            ZevClipPreferences.lastAutoStatus(this)
        }

        lastAutoStatusText.text = getString(R.string.last_auto_send_status, lastAutoStatus)
        refreshBackgroundHealthStatus(accessibilityState)
        tileStatusText.text = getString(
            R.string.last_tile_status,
            ZevClipPreferences.lastTileStatus(this)
        )
        discoveryStatusText.text = getString(
            R.string.discovery_status,
            ZevClipPreferences.discoveryStatus(this)
        )

        val hasToken = ZevClipPreferences.pairingToken(this).isNotEmpty()
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

    private fun refreshBackgroundHealthStatus(accessibilityState: AccessibilityServiceStatus.State) {
        val isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations()
        val warning = if (isIgnoringBatteryOptimizations) {
            ""
        } else {
            "\n${getString(R.string.battery_optimization_warning)}"
        }

        backgroundHealthStatusText.setTextColor(
            if (accessibilityState.enabled && isIgnoringBatteryOptimizations) {
                Color.rgb(24, 120, 54)
            } else {
                Color.rgb(180, 92, 0)
            }
        )
        backgroundHealthStatusText.text = getString(
            R.string.background_health_details,
            yesNo(accessibilityState.enabled),
            yesNo(isIgnoringBatteryOptimizations),
            getString(R.string.autostart_status_unknown_open_settings),
            formatTimestamp(ZevClipPreferences.lastServiceConnectedAt(this)),
            formatTimestamp(ZevClipPreferences.lastAutoSendAt(this)),
            warning
        )
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

}
