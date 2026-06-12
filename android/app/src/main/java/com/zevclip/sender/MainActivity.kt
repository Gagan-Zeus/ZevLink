package com.zevclip.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.util.TypedValue
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
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
import com.zevclip.sender.airplay.AirPlayIdentityStore
import com.zevclip.sender.airplay.AirPlayPairSetupClient
import com.zevclip.sender.airplay.AirPlayTarget
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairingTokenInput: EditText
    private lateinit var scanPairingQrButton: Button
    private lateinit var statusText: TextView
    private lateinit var discoverButton: Button
    private lateinit var discoveryStatusText: TextView
    private lateinit var pairingStatusText: TextView
    private lateinit var quickSettingsStatusText: TextView
    private lateinit var androidReceiverStatusText: TextView
    private lateinit var androidReceiverLastReceivedText: TextView
    private lateinit var startClipboardSyncButton: Button
    private lateinit var stopClipboardSyncButton: Button
    private lateinit var accessibilityStatusText: TextView
    private lateinit var notificationMirrorStatusText: TextView
    private lateinit var callMirrorStatusText: TextView
    private lateinit var airPlayTestStatusText: TextView
    private lateinit var airPlayAudioButton: Button
    private lateinit var airPlayScreenMirrorButton: Button
    private lateinit var airPlayBroadcastStatusText: TextView
    private lateinit var airPlayBroadcastDiscoverButton: Button
    private lateinit var airPlayBroadcastDevicesContainer: LinearLayout
    private lateinit var airPlayBroadcastStartButton: Button
    private lateinit var lastAutoStatusText: TextView
    private lateinit var discoveryManager: MacDiscoveryManager
    private lateinit var airPlayReceiverDiscoveryManager: AirPlayReceiverDiscoveryManager
    private lateinit var colors: DynamicPalette
    private var showingSettings = false
    private var showingAirPlayBroadcast = false
    private var airPlayBroadcastReceivers: List<AirPlayDiscoveredReceiver> = emptyList()
    private val selectedAirPlayBroadcastReceivers = linkedSetOf<String>()
    private var pendingAirPlayBroadcastTargets: List<AirPlayBroadcastAudioService.TargetSpec> = emptyList()
    private var pendingAirPlayScreenCode: String? = null
    private var pendingAirPlayCaptureAfterPermission: PendingAirPlayCapture? = null
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
            key == ZevClipPreferences.KEY_NOTIFICATION_MIRROR_STATUS ||
            key == ZevClipPreferences.KEY_CALL_MIRROR_STATUS ||
            key == ZevClipPreferences.KEY_AIRPLAY_TEST_STATUS ||
            key == ZevClipPreferences.KEY_AIRPLAY_PASSCODE ||
            key == ZevClipPreferences.KEY_AIRPLAY_STREAMING ||
            key == ZevClipPreferences.KEY_AIRPLAY_SCREEN_MIRRORING ||
            key == ZevClipPreferences.KEY_AIRPLAY_BROADCAST_STATUS ||
            key == ZevClipPreferences.KEY_AIRPLAY_BROADCAST_STREAMING
        ) {
            runOnUiThread { refreshSyncStatuses() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colors = resolveDynamicPalette()
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        discoveryManager = MacDiscoveryManager(
            context = this,
            onStatusChanged = { status, isDiscovering ->
                if (!isDestroyed) {
                    if (::discoverButton.isInitialized) {
                        discoverButton.isEnabled = !isDiscovering
                    }
                    if (::discoveryStatusText.isInitialized) {
                        discoveryStatusText.text = getString(R.string.discovery_status, status)
                    }
                }
            },
            onEndpointResolved = { host, port ->
                if (!isDestroyed) {
                    if (::ipAddressInput.isInitialized) {
                        ipAddressInput.setText(host)
                    }
                    if (::portInput.isInitialized) {
                        portInput.setText(String.format(Locale.US, "%d", port))
                    }
                    ZevClipPreferences.saveEndpoint(this, host, port.toString())
                }
            }
        )
        airPlayReceiverDiscoveryManager = AirPlayReceiverDiscoveryManager(
            context = this,
            onReceiversChanged = { receivers ->
                if (!isDestroyed) {
                    airPlayBroadcastReceivers = receivers
                    selectedAirPlayBroadcastReceivers.retainAll(receivers.map { it.key }.toSet())
                    if (::airPlayBroadcastDevicesContainer.isInitialized) {
                        renderAirPlayBroadcastReceivers()
                    }
                    refreshSyncStatuses()
                }
            },
            onStatusChanged = { status, isDiscovering ->
                if (!isDestroyed) {
                    ZevClipPreferences.setAirPlayBroadcastStatus(this, status)
                    if (::airPlayBroadcastDiscoverButton.isInitialized) {
                        airPlayBroadcastDiscoverButton.isEnabled = !isDiscovering
                    }
                    if (::airPlayBroadcastStatusText.isInitialized) {
                        airPlayBroadcastStatusText.text = getString(R.string.airplay_broadcast_status, status)
                    }
                }
            }
        )
        showHomePage()
        handleNotificationAction(intent)
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationAction(intent)
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
            requestPhoneCallPermissionsIfNeeded()
            AndroidClipboardReceiverService.start(this)
            AndroidCallMirrorService.start(this)
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
        } else if (requestCode == REQUEST_PHONE_CALLS) {
            if (ZevClipPreferences.isClipboardSyncEnabled(this)) {
                AndroidCallMirrorService.start(this)
            }
            refreshSyncStatuses()
        } else if (requestCode == REQUEST_RECORD_AUDIO) {
            val pendingCapture = pendingAirPlayCaptureAfterPermission
            pendingAirPlayCaptureAfterPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                when (pendingCapture) {
                    PendingAirPlayCapture.Audio -> toggleAirPlayAudioCapture()
                    PendingAirPlayCapture.Screen -> toggleAirPlayScreenMirror()
                    PendingAirPlayCapture.Broadcast -> toggleAirPlayBroadcastCapture()
                    null -> Unit
                }
            }
            refreshSyncStatuses()
        }
    }

    @Deprecated("Deprecated in Android framework, but still the compatibility path for this Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AIRPLAY_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                AirPlayAudioCaptureService.start(this, resultCode, data)
                refreshSyncStatuses()
            } else {
                ZevClipPreferences.setAirPlayTestStatus(
                    this,
                    getString(R.string.airplay_capture_permission_missing)
                )
                refreshSyncStatuses()
            }
        } else if (requestCode == REQUEST_AIRPLAY_BROADCAST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                AirPlayBroadcastAudioService.start(this, resultCode, data, pendingAirPlayBroadcastTargets)
                pendingAirPlayBroadcastTargets = emptyList()
                refreshSyncStatuses()
            } else {
                pendingAirPlayBroadcastTargets = emptyList()
                ZevClipPreferences.setAirPlayBroadcastStatus(
                    this,
                    getString(R.string.airplay_capture_permission_missing)
                )
                refreshSyncStatuses()
            }
        } else if (requestCode == REQUEST_AIRPLAY_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val screenCode = pendingAirPlayScreenCode.orEmpty()
                pendingAirPlayScreenCode = null
                if (screenCode.isBlank()) {
                    ZevClipPreferences.setAirPlayTestStatus(
                        this,
                        getString(R.string.airplay_screen_code_missing)
                    )
                    refreshSyncStatuses()
                    return
                }
                AirPlayScreenMirrorService.start(this, resultCode, data, screenCode)
                refreshSyncStatuses()
            } else {
                pendingAirPlayScreenCode = null
                ZevClipPreferences.setAirPlayTestStatus(
                    this,
                    getString(R.string.airplay_capture_permission_missing)
                )
                refreshSyncStatuses()
            }
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::ipAddressInput.isInitialized && ::portInput.isInitialized) {
            ZevClipPreferences.saveEndpoint(
                this,
                ipAddressInput.text.toString(),
                portInput.text.toString()
            )
        }
        if (::pairingTokenInput.isInitialized) {
            ZevClipPreferences.savePairingToken(this, pairingTokenInput.text.toString())
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Android framework, but still the compatibility path for this Activity.")
    override fun onBackPressed() {
        if (showingAirPlayBroadcast) {
            showHomePage()
        } else if (showingSettings) {
            showHomePage()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStop() {
        ZevClipPreferences.preferences(this)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        discoveryManager.stop()
        airPlayReceiverDiscoveryManager.stop()
        super.onDestroy()
    }

    private fun showHomePage() {
        if (::airPlayReceiverDiscoveryManager.isInitialized) {
            airPlayReceiverDiscoveryManager.stop()
        }
        showingSettings = false
        showingAirPlayBroadcast = false
        setContentView(createHomeView())
        refreshSyncStatuses()
    }

    private fun handleNotificationAction(intent: Intent?) {
        if (intent?.action != ACTION_START_AIRPLAY_SCREEN_MIRROR) return
        showHomePage()
        mainHandler.post {
            toggleAirPlayScreenMirror()
        }
    }

    private fun showSettingsPage() {
        if (::airPlayReceiverDiscoveryManager.isInitialized) {
            airPlayReceiverDiscoveryManager.stop()
        }
        showingSettings = true
        showingAirPlayBroadcast = false
        setContentView(createSettingsView())
        refreshSyncStatuses()
    }

    private fun showAirPlayBroadcastPage() {
        showingSettings = false
        showingAirPlayBroadcast = true
        setContentView(createAirPlayBroadcastView())
        renderAirPlayBroadcastReceivers()
        refreshSyncStatuses()
    }

    private fun createHomeView(): View {
        val content = pageContent()

        content.addView(headerRow(
            title = getString(R.string.screen_title),
            action = tonalButton(getString(R.string.settings_title)) { showSettingsPage() }
        ))
        content.addView(textView(getString(R.string.screen_description), 18f, colors.muted).apply {
            setPadding(0, dp(6), 0, dp(18))
            setLineSpacing(0f, 1.08f)
        })

        content.addView(syncCard(), matchWidth(topMargin = 2))
        content.addView(airPlayCard(), matchWidth(topMargin = 16))

        return scrollPage(content)
    }

    private fun createSettingsView(): View {
        val content = pageContent()

        content.addView(headerRow(
            title = getString(R.string.settings_title),
            action = tonalButton(getString(R.string.done)) { showHomePage() }
        ))
        content.addView(textView(getString(R.string.settings_description), 17f, colors.muted).apply {
            setPadding(0, dp(6), 0, dp(18))
            setLineSpacing(0f, 1.06f)
        })

        content.addView(card(colors.surface).apply {
            addView(cardTitle(getString(R.string.permissions_title)))
            addView(textView(getString(R.string.permissions_description), 14f, colors.muted).apply {
                setPadding(0, dp(6), 0, dp(8))
                setLineSpacing(0f, 1.06f)
            })

            accessibilityStatusText = textView("", 15f, colors.muted)
            addView(accessibilityStatusText, matchWidth(topMargin = 8))

            notificationMirrorStatusText = textView("", 15f, colors.muted)
            addView(notificationMirrorStatusText, matchWidth(topMargin = 8))

            callMirrorStatusText = textView("", 15f, colors.muted)
            addView(callMirrorStatusText, matchWidth(topMargin = 8))

            addView(horizontalButtons(
                tonalButton(getString(R.string.open_accessibility_settings)) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                tonalButton(getString(R.string.open_notification_access_settings)) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ), matchWidth(topMargin = 16))

            addView(quietButton(getString(R.string.open_app_info)) {
                openAppInfoSettings()
            }, matchWidth(topMargin = 10))
        }, matchWidth(topMargin = 2))

        content.addView(card(colors.accentContainer).apply {
            addView(cardTitle(getString(R.string.pair_mac_title)))
            addView(textView(getString(R.string.pair_mac_description), 15f, colors.muted).apply {
                setPadding(0, dp(6), 0, dp(14))
                setLineSpacing(0f, 1.06f)
            })

            scanPairingQrButton = primaryButton(getString(R.string.scan_pairing_qr)) { scanPairingQr() }
            addView(scanPairingQrButton, matchWidth())

            discoverButton = quietButton(getString(R.string.discover_mac)) {
                discoveryManager.discover()
            }
            addView(discoverButton, matchWidth(topMargin = 10))

            discoveryStatusText = textView("", 14f, colors.muted).apply {
                setPadding(0, dp(12), 0, 0)
            }
            addView(discoveryStatusText, matchWidth())

            pairingStatusText = textView(getString(R.string.pairing_token_not_saved), 14f, colors.muted).apply {
                setPadding(0, dp(8), 0, 0)
            }
            addView(pairingStatusText, matchWidth())
        }, matchWidth(topMargin = 16))

        content.addView(card(colors.surface).apply {
            addView(cardTitle(getString(R.string.quick_settings_title)))
            addView(textView(getString(R.string.quick_settings_instructions), 14f, colors.muted).apply {
                setPadding(0, dp(6), 0, dp(14))
                setLineSpacing(0f, 1.06f)
            })

            addView(tonalButton(getString(R.string.add_quick_settings_tile)) {
                requestQuickSettingsTile()
                refreshSyncStatuses()
            }, matchWidth())

            quickSettingsStatusText = textView("", 14f, colors.muted).apply {
                setPadding(0, dp(10), 0, 0)
            }
            addView(quickSettingsStatusText, matchWidth())
        }, matchWidth(topMargin = 16))

        return scrollPage(content)
    }

    private fun startClipboardSync() {
        if (::startClipboardSyncButton.isInitialized) {
            startClipboardSyncButton.visibility = View.GONE
            startClipboardSyncButton.isEnabled = false
        }
        if (::stopClipboardSyncButton.isInitialized) {
            stopClipboardSyncButton.visibility = View.VISIBLE
            stopClipboardSyncButton.isEnabled = true
        }
        saveEndpointAndTokenFromUi()
        ZevClipPreferences.setClipboardSyncEnabled(this, true)
        AndroidClipboardReceiverService.start(this)
        requestPhoneCallPermissionsIfNeeded()
        AndroidCallMirrorService.start(this)
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
        if (::startClipboardSyncButton.isInitialized) {
            startClipboardSyncButton.visibility = View.VISIBLE
            startClipboardSyncButton.isEnabled = true
        }
        if (::stopClipboardSyncButton.isInitialized) {
            stopClipboardSyncButton.visibility = View.GONE
            stopClipboardSyncButton.isEnabled = false
        }
        ZevClipPreferences.setClipboardSyncEnabled(this, false)
        AndroidClipboardReceiverService.stop(this)
        AndroidCallMirrorService.stop(this)
        ZevClipPreferences.setLastAutoStatus(this, getString(R.string.sync_stopped))
        showSuccess(getString(R.string.sync_stopped))
        refreshSyncStatuses()
    }

    private fun saveEndpointAndTokenFromUi() {
        if (::ipAddressInput.isInitialized && ::portInput.isInitialized) {
            ZevClipPreferences.saveEndpoint(
                this,
                ipAddressInput.text.toString(),
                portInput.text.toString()
            )
        }
        if (::pairingTokenInput.isInitialized) {
            ZevClipPreferences.savePairingToken(this, pairingTokenInput.text.toString())
        }
    }

    private fun scanPairingQr() {
        scanPairingQrButton.isEnabled = false
        showPageStatus(getString(R.string.qr_scan_starting), colors.muted)

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
                showPageStatus(getString(R.string.qr_pairing_checking), colors.muted)

                thread(name = "ZevClipQrEndpointProbe") {
                    val reachableHost = EndpointSelector.selectReachableHost(payload.hosts, payload.port)
                    runOnUiThread {
                        scanPairingQrButton.isEnabled = true
                        if (isDestroyed) return@runOnUiThread

                        val selectedHost = reachableHost ?: payload.host
                        if (::ipAddressInput.isInitialized) {
                            ipAddressInput.setText(selectedHost)
                        }
                        if (::portInput.isInitialized) {
                            portInput.setText(String.format(Locale.US, "%d", payload.port))
                        }
                        if (::pairingTokenInput.isInitialized) {
                            pairingTokenInput.setText(payload.token)
                        }
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
            pairingStatusText.setTextColor(colors.error)
            pairingStatusText.text = getString(R.string.pairing_token_empty)
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.savePairingToken(this, token)
        pairingStatusText.setTextColor(colors.success)
        pairingStatusText.text = getString(R.string.pairing_token_saved)
    }

    private fun showSuccess(message: String) {
        showPageStatus(message, colors.success)
    }

    private fun showFailure(message: String) {
        showPageStatus(message, colors.error)
    }

    private fun showPageStatus(message: String, color: Int) {
        when {
            showingSettings && ::discoveryStatusText.isInitialized -> {
                discoveryStatusText.setTextColor(color)
                discoveryStatusText.text = message
            }
            ::statusText.isInitialized -> {
                statusText.setTextColor(color)
                statusText.text = message
            }
            ::discoveryStatusText.isInitialized -> {
                discoveryStatusText.setTextColor(color)
                discoveryStatusText.text = message
            }
        }
    }

    private fun refreshSyncStatuses() {
        val accessibilityState = AccessibilityServiceStatus.currentState(this)
        val syncEnabled = ZevClipPreferences.isClipboardSyncEnabled(this)
        val endpoint = ZevClipPreferences.endpoint(this)
        val hasToken = ZevClipPreferences.pairingToken(this).isNotEmpty()
        val receiverRunning = ZevClipPreferences.isAndroidReceiverRunning(this)
        val canSendToMac = syncEnabled && accessibilityState.enabled && endpoint != null && hasToken
        val canReceiveFromMac = syncEnabled && receiverRunning

        if (::statusText.isInitialized) {
            statusText.setTextColor(
                when {
                    canSendToMac && canReceiveFromMac -> colors.success
                    syncEnabled -> colors.warning
                    else -> colors.muted
                }
            )
            statusText.text = when {
                canSendToMac && canReceiveFromMac -> getString(R.string.sync_ready_summary)
                syncEnabled && endpoint == null -> getString(R.string.sync_waiting_for_mac)
                syncEnabled && !accessibilityState.enabled -> getString(R.string.sync_needs_accessibility)
                syncEnabled && !hasToken -> getString(R.string.sync_needs_pairing)
                syncEnabled -> getString(R.string.sync_starting_summary)
                else -> getString(R.string.sync_off_summary)
            }
        }

        if (::accessibilityStatusText.isInitialized) {
            accessibilityStatusText.setTextColor(if (accessibilityState.enabled) colors.success else colors.error)
            accessibilityStatusText.text = getString(
                if (accessibilityState.enabled) R.string.accessibility_ready else R.string.accessibility_needed
            )
        }

        val notificationAccessEnabled = NotificationAccessStatus.isEnabled(this)
        val notificationMirrorConnected = ZevClipPreferences.isNotificationMirrorConnected(this)
        if (::notificationMirrorStatusText.isInitialized) {
            notificationMirrorStatusText.setTextColor(
                if (notificationAccessEnabled && notificationMirrorConnected) colors.success else colors.warning
            )
            notificationMirrorStatusText.text = when {
                notificationAccessEnabled && notificationMirrorConnected -> getString(R.string.notifications_ready)
                notificationAccessEnabled -> getString(R.string.notifications_waiting)
                else -> getString(R.string.notifications_needed)
            }
        }

        val callPermissionsGranted = hasPhoneCallPermissions()
        if (::callMirrorStatusText.isInitialized) {
            callMirrorStatusText.setTextColor(if (callPermissionsGranted) colors.success else colors.warning)
            callMirrorStatusText.text = getString(
                if (callPermissionsGranted) R.string.calls_ready else R.string.calls_needed
            )
        }

        if (::airPlayTestStatusText.isInitialized) {
            airPlayTestStatusText.text = ZevClipPreferences.airPlayTestStatus(this)
        }
        if (::airPlayAudioButton.isInitialized) {
            airPlayAudioButton.text = getString(
                if (ZevClipPreferences.isAirPlayStreaming(this)) {
                    R.string.stop_airplay_audio
                } else {
                    R.string.start_airplay_audio
                }
            )
        }
        if (::airPlayScreenMirrorButton.isInitialized) {
            airPlayScreenMirrorButton.text = getString(
                if (ZevClipPreferences.isAirPlayScreenMirroring(this)) {
                    R.string.stop_screen_mirror
                } else {
                    R.string.start_screen_mirror
                }
            )
        }
        if (::airPlayBroadcastStatusText.isInitialized) {
            airPlayBroadcastStatusText.text = getString(
                R.string.airplay_broadcast_status,
                ZevClipPreferences.airPlayBroadcastStatus(this)
            )
        }
        if (::airPlayBroadcastStartButton.isInitialized) {
            airPlayBroadcastStartButton.text = getString(
                if (ZevClipPreferences.isAirPlayBroadcastStreaming(this)) {
                    R.string.stop_airplay_broadcast
                } else {
                    R.string.start_airplay_broadcast
                }
            )
        }

        if (::lastAutoStatusText.isInitialized) {
            lastAutoStatusText.text = when {
                endpoint == null -> getString(R.string.android_copy_waiting_for_pairing)
                canSendToMac -> getString(R.string.android_copy_ready)
                accessibilityState.enabled -> getString(R.string.android_copy_waiting_for_connection)
                else -> getString(R.string.android_copy_needs_accessibility)
            }
        }
        if (::discoveryStatusText.isInitialized) {
            discoveryStatusText.setTextColor(colors.muted)
            discoveryStatusText.text = getString(
                R.string.discovery_status,
                ZevClipPreferences.discoveryStatus(this)
            )
        }

        if (::pairingStatusText.isInitialized) {
            pairingStatusText.setTextColor(if (hasToken) colors.success else colors.muted)
            pairingStatusText.text = getString(
                if (hasToken) {
                    R.string.mac_pairing_saved
                } else {
                    R.string.pairing_token_not_saved
                }
            )
        }
        if (::quickSettingsStatusText.isInitialized) {
            quickSettingsStatusText.text = getString(
                R.string.last_tile_status,
                ZevClipPreferences.lastTileStatus(this)
            )
        }

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

    private fun requestPhoneCallPermissionsIfNeeded() {
        val missingPermissions = phoneCallPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_PHONE_CALLS)
        }
    }

    private fun hasPhoneCallPermissions(): Boolean {
        return phoneCallPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun phoneCallPermissions(): List<String> {
        return listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
    }

    private fun airPlayCard(): LinearLayout {
        return card(colors.surface).apply {
            addView(cardTitle(getString(R.string.airplay_title)))
            addView(textView(getString(R.string.airplay_description), 15f, colors.muted).apply {
                setPadding(0, dp(6), 0, dp(12))
            })

            airPlayTestStatusText = textView(
                ZevClipPreferences.airPlayTestStatus(this@MainActivity),
                15f,
                colors.muted
            ).apply {
                setPadding(0, 0, 0, dp(12))
            }
            addView(airPlayTestStatusText, matchWidth())

            airPlayScreenMirrorButton = primaryButton(
                getString(
                    if (ZevClipPreferences.isAirPlayScreenMirroring(this@MainActivity)) {
                        R.string.stop_screen_mirror
                    } else {
                        R.string.start_screen_mirror
                    }
                )
            ) {
                toggleAirPlayScreenMirror()
            }
            addView(airPlayScreenMirrorButton, matchWidth())

            airPlayAudioButton = tonalButton(
                getString(
                    if (ZevClipPreferences.isAirPlayStreaming(this@MainActivity)) {
                        R.string.stop_airplay_audio
                    } else {
                        R.string.start_airplay_audio
                    }
                )
            ) {
                toggleAirPlayAudioCapture()
            }
            addView(airPlayAudioButton, matchWidth(topMargin = 10))

            addView(quietButton(getString(R.string.airplay_broadcast_audio)) {
                showAirPlayBroadcastPage()
            }, matchWidth(topMargin = 10))
        }
    }

    private fun createAirPlayBroadcastView(): View {
        val content = pageContent()

        content.addView(headerRow(
            title = getString(R.string.airplay_broadcast_title),
            action = tonalButton(getString(R.string.done)) { showHomePage() }
        ))
        content.addView(textView(getString(R.string.airplay_broadcast_description), 16f, colors.muted).apply {
            setPadding(0, dp(6), 0, dp(16))
            setLineSpacing(0f, 1.06f)
        })

        airPlayBroadcastStatusText = textView(
            getString(R.string.airplay_broadcast_status, ZevClipPreferences.airPlayBroadcastStatus(this)),
            15f,
            colors.muted
        )
        content.addView(airPlayBroadcastStatusText, matchWidth())

        airPlayBroadcastDiscoverButton = primaryButton(getString(R.string.discover_airplay_receivers)) {
            airPlayReceiverDiscoveryManager.discover()
        }
        content.addView(airPlayBroadcastDiscoverButton, matchWidth(topMargin = 12))

        airPlayBroadcastStartButton = primaryButton(
            getString(
                if (ZevClipPreferences.isAirPlayBroadcastStreaming(this)) {
                    R.string.stop_airplay_broadcast
                } else {
                    R.string.start_airplay_broadcast
                }
            )
        ) {
            toggleAirPlayBroadcastCapture()
        }
        content.addView(airPlayBroadcastStartButton, matchWidth(topMargin = 10))

        airPlayBroadcastDevicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(airPlayBroadcastDevicesContainer, matchWidth(topMargin = 16))

        return scrollPage(content)
    }

    private fun renderAirPlayBroadcastReceivers() {
        if (!::airPlayBroadcastDevicesContainer.isInitialized) return
        airPlayBroadcastDevicesContainer.removeAllViews()
        if (airPlayBroadcastReceivers.isEmpty()) {
            airPlayBroadcastDevicesContainer.addView(
                textView(getString(R.string.airplay_broadcast_no_devices), 15f, colors.muted),
                matchWidth()
            )
            return
        }

        airPlayBroadcastReceivers.forEach { receiver ->
            val target = receiver.target
            val displayName = target.name ?: receiver.serviceName
            val delayMs = ZevClipPreferences.airPlayBroadcastDelayMs(this, receiver.key)
            val selected = receiver.key in selectedAirPlayBroadcastReceivers
            val status = when {
                selected -> getString(R.string.airplay_broadcast_selected)
                else -> getString(R.string.airplay_broadcast_ready)
            }
            val detail = listOfNotNull(
                target.model?.takeIf { it.isNotBlank() },
                "${target.host}:${target.port}",
                getString(R.string.airplay_broadcast_delay_value, delayMs),
                status
            ).joinToString(" · ")

            airPlayBroadcastDevicesContainer.addView(card(colors.surface).apply {
                addView(cardTitle(displayName))
                addView(textView(detail, 14f, colors.muted).apply {
                    setPadding(0, dp(6), 0, dp(12))
                })
                addView(tonalButton(
                    getString(
                        if (selected) {
                            R.string.airplay_broadcast_selected
                        } else {
                            R.string.airplay_broadcast_not_selected
                        }
                    )
                ) {
                    if (selected) {
                        selectedAirPlayBroadcastReceivers.remove(receiver.key)
                    } else {
                        selectedAirPlayBroadcastReceivers.add(receiver.key)
                    }
                    renderAirPlayBroadcastReceivers()
                }, matchWidth())
                addView(quietButton(getString(R.string.airplay_broadcast_sync_delay)) {
                    promptForAirPlayBroadcastDelay(receiver)
                }, matchWidth(topMargin = 10))
            }, matchWidth(topMargin = 10))
        }
    }

    private fun promptForAirPlayBroadcastDelay(receiver: AirPlayDiscoveredReceiver) {
        val target = receiver.target
        val displayName = target.name ?: receiver.serviceName
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            isSingleLine = true
            setText(ZevClipPreferences.airPlayBroadcastDelayMs(this@MainActivity, receiver.key).toString())
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.airplay_broadcast_delay_title))
            .setMessage(getString(R.string.airplay_broadcast_delay_message, displayName))
            .setView(input)
            .setPositiveButton(getString(R.string.airplay_broadcast_save_delay)) { _, _ ->
                val delayMs = input.text.toString().trim().toIntOrNull()
                if (delayMs == null || delayMs !in -1_000..1_000) {
                    ZevClipPreferences.setAirPlayBroadcastStatus(
                        this,
                        getString(R.string.airplay_broadcast_delay_invalid)
                    )
                } else {
                    ZevClipPreferences.setAirPlayBroadcastDelayMs(this, receiver.key, delayMs)
                }
                renderAirPlayBroadcastReceivers()
                refreshSyncStatuses()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleAirPlayAudioCapture() {
        if (ZevClipPreferences.isAirPlayStreaming(this)) {
            AirPlayAudioCaptureService.stop(this)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_stopped))
            refreshSyncStatuses()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_android_q_required))
            refreshSyncStatuses()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAirPlayCaptureAfterPermission = PendingAirPlayCapture.Audio
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_record_audio_needed))
            refreshSyncStatuses()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_starting))
        refreshSyncStatuses()
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_AIRPLAY_CAPTURE
        )
    }

    private fun toggleAirPlayScreenMirror() {
        if (ZevClipPreferences.isAirPlayScreenMirroring(this)) {
            AirPlayScreenMirrorService.stop(this)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_screen_mirror_stopped))
            refreshSyncStatuses()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_android_q_required))
            refreshSyncStatuses()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAirPlayCaptureAfterPermission = PendingAirPlayCapture.Screen
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_record_audio_needed))
            refreshSyncStatuses()
            return
        }

        val endpoint = ZevClipPreferences.endpoint(this)
        if (endpoint == null) {
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_capture_pairing_needed))
            refreshSyncStatuses()
            return
        }

        if (ZevClipPreferences.isAirPlayStreaming(this)) {
            AirPlayAudioCaptureService.stop(this)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_stopped))
        }
        if (ZevClipPreferences.isAirPlayBroadcastStreaming(this)) {
            AirPlayBroadcastAudioService.stop(this)
            ZevClipPreferences.setAirPlayBroadcastStatus(this, getString(R.string.airplay_broadcast_stopped))
        }

        requestScreenAirPlayCode(endpoint)
    }

    private fun requestScreenAirPlayCode(endpoint: Endpoint) {
        ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_screen_code_waiting))
        refreshSyncStatuses()
        thread(name = "zevclip-airplay-screen-code", isDaemon = true) {
            val promptStarted = runCatching {
                val identity = AirPlayIdentityStore.getOrCreate(applicationContext)
                val target = AirPlayTarget(
                    host = endpoint.ipAddress,
                    port = AirPlayTarget.DEFAULT_RTSP_PORT,
                    name = "Paired Mac AirPlay"
                )
                AirPlayPairSetupClient(target, identity).use { setup ->
                    setup.pairPinStart()
                }
            }.getOrDefault(false)
            runOnUiThread {
                showScreenAirPlayCodeDialog(promptStarted)
            }
        }
    }

    private fun showScreenAirPlayCodeDialog(promptStarted: Boolean) {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = roundedDrawable(colors.surface, 30)
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            translationY = dp(22).toFloat()
        }

        content.addView(textView(getString(R.string.airplay_screen_code_dialog_title), 27f, colors.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }, matchWidth())

        content.addView(textView(
            getString(
                if (promptStarted) {
                    R.string.airplay_screen_code_dialog_message
                } else {
                    R.string.airplay_screen_code_dialog_message_retry
                }
            ),
            16f,
            colors.muted
        ).apply {
            setLineSpacing(0f, 1.08f)
        }, matchWidth())

        val errorText = textView("", 13f, colors.error).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        val codeBoxes = mutableListOf<EditText>()
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) { index ->
            val box = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = if (index == 3) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(1))
                textSize = 24f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.text)
                setHintTextColor(colors.muted)
                background = roundedDrawable(colors.inputSurface, 18, colors.outline, 1)
                setSelectAllOnFocus(true)
            }
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    errorText.visibility = View.GONE
                    codeBoxes.forEach {
                        it.background = roundedDrawable(colors.inputSurface, 18, colors.outline, 1)
                    }
                    if ((s?.length ?: 0) == 1 && index < 3) {
                        codeBoxes[index + 1].requestFocus()
                    }
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isEmpty() &&
                    index > 0
                ) {
                    codeBoxes[index - 1].requestFocus()
                    codeBoxes[index - 1].setSelection(codeBoxes[index - 1].text.length)
                    true
                } else {
                    false
                }
            }
            codeBoxes.add(box)
            codeRow.addView(
                box,
                LinearLayout.LayoutParams(dp(54), dp(58)).apply {
                    if (index > 0) {
                        leftMargin = dp(8)
                    }
                }
            )
        }
        content.addView(codeRow, matchWidth(topMargin = 18))
        content.addView(errorText, matchWidth())

        val buttonRow = horizontalButtons(
            quietButton(getString(android.R.string.cancel)) {
                pendingAirPlayScreenCode = null
                ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_screen_mirror_stopped))
                refreshSyncStatuses()
                dialog.dismiss()
            },
            primaryButton(getString(R.string.airplay_screen_code_start)) {
                val code = codeBoxes.joinToString("") { it.text.toString().trim() }
                if (code.length != 4) {
                    errorText.text = getString(R.string.airplay_screen_code_missing)
                    errorText.visibility = View.VISIBLE
                    codeBoxes.forEach {
                        it.background = roundedDrawable(colors.inputSurface, 18, colors.error, 1)
                    }
                    codeRow.animate()
                        .translationX(dp(5).toFloat())
                        .setDuration(70)
                        .withEndAction {
                            codeRow.animate()
                                .translationX(0f)
                                .setDuration(90)
                                .start()
                        }
                        .start()
                    (codeBoxes.firstOrNull { it.text.isBlank() } ?: codeBoxes.first()).requestFocus()
                    ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_screen_code_missing))
                    refreshSyncStatuses()
                    return@primaryButton
                }
                pendingAirPlayScreenCode = code
                dialog.dismiss()
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_screen_mirror_connecting))
                refreshSyncStatuses()
                startActivityForResult(
                    projectionManager.createScreenCaptureIntent(),
                    REQUEST_AIRPLAY_SCREEN_CAPTURE
                )
            }
        )
        content.addView(buttonRow, matchWidth(topMargin = 20))

        codeBoxes.last().setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                (buttonRow.getChildAt(1) as Button).performClick()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            if (pendingAirPlayScreenCode == null) {
                refreshSyncStatuses()
            }
        }

        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.58f
            }
            setLayout(resources.displayMetrics.widthPixels - dp(42), WindowManager.LayoutParams.WRAP_CONTENT)
        }

        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
        codeRow.postDelayed({
            codeRow.animate()
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(150)
                .withEndAction {
                    codeRow.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(170)
                        .start()
                }
                .start()
        }, 260)
        codeBoxes.first().requestFocus()
    }

    private fun toggleAirPlayBroadcastCapture() {
        if (ZevClipPreferences.isAirPlayBroadcastStreaming(this)) {
            AirPlayBroadcastAudioService.stop(this)
            ZevClipPreferences.setAirPlayBroadcastStatus(this, getString(R.string.airplay_broadcast_stopped))
            refreshSyncStatuses()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ZevClipPreferences.setAirPlayBroadcastStatus(this, getString(R.string.airplay_capture_android_q_required))
            refreshSyncStatuses()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAirPlayCaptureAfterPermission = PendingAirPlayCapture.Broadcast
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            ZevClipPreferences.setAirPlayBroadcastStatus(this, getString(R.string.airplay_capture_record_audio_needed))
            refreshSyncStatuses()
            return
        }

        val selectedTargets = airPlayBroadcastReceivers
            .filter { it.key in selectedAirPlayBroadcastReceivers }
            .map { receiver ->
                AirPlayBroadcastAudioService.TargetSpec(
                    target = receiver.target,
                    password = "",
                    delayMs = ZevClipPreferences.airPlayBroadcastDelayMs(this, receiver.key),
                    receiverKey = receiver.key
                )
            }

        if (selectedTargets.isEmpty()) {
            ZevClipPreferences.setAirPlayBroadcastStatus(this, getString(R.string.airplay_broadcast_select_devices))
            refreshSyncStatuses()
            return
        }

        if (ZevClipPreferences.isAirPlayStreaming(this)) {
            AirPlayAudioCaptureService.stop(this)
            ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_stopped))
        }

        pendingAirPlayBroadcastTargets = selectedTargets
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        ZevClipPreferences.setAirPlayBroadcastStatus(
            this,
            getString(R.string.airplay_broadcast_connecting, selectedTargets.size)
        )
        refreshSyncStatuses()
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_AIRPLAY_BROADCAST_CAPTURE
        )
    }

    private fun refreshAndroidReceiverStatus() {
        val isRunning = ZevClipPreferences.isAndroidReceiverRunning(this)
        val syncEnabled = ZevClipPreferences.isClipboardSyncEnabled(this)
        if (::androidReceiverStatusText.isInitialized) {
            androidReceiverStatusText.setTextColor(if (isRunning) colors.success else colors.muted)
            androidReceiverStatusText.text = getString(
                if (isRunning) R.string.mac_copy_ready else R.string.mac_copy_stopped
            )
        }

        val lastReceivedAt = ZevClipPreferences.androidReceiverLastReceivedAt(this)
        if (::androidReceiverLastReceivedText.isInitialized) {
            androidReceiverLastReceivedText.text = if (lastReceivedAt > 0L) {
                getString(R.string.last_mac_copy, formatTimestamp(lastReceivedAt))
            } else {
                getString(R.string.last_mac_copy_none)
            }
        }

        if (::startClipboardSyncButton.isInitialized) {
            startClipboardSyncButton.visibility = if (syncEnabled || isRunning) View.GONE else View.VISIBLE
            startClipboardSyncButton.isEnabled = !syncEnabled && !isRunning
        }
        if (::stopClipboardSyncButton.isInitialized) {
            stopClipboardSyncButton.visibility = if (syncEnabled || isRunning) View.VISIBLE else View.GONE
            stopClipboardSyncButton.isEnabled = syncEnabled || isRunning
        }
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

    private fun syncCard(): LinearLayout {
        return card(colors.primaryContainer).apply {
            addView(cardTitle(getString(R.string.clipboard_sync_title)).apply {
                textSize = 24f
            })
            addView(textView(getString(R.string.clipboard_sync_description), 15f, colors.muted).apply {
                setPadding(0, dp(6), 0, dp(12))
                setLineSpacing(0f, 1.06f)
            }, matchWidth())

            statusText = textView(getString(R.string.ready), 18f, colors.primary).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(16))
            }
            addView(statusText, matchWidth())

            val actionRow = horizontalButtons(
                primaryButton(getString(R.string.start_clipboard_sync)) { startClipboardSync() },
                quietButton(getString(R.string.stop_clipboard_sync)) { stopClipboardSync() }
            )
            addView(actionRow)
            startClipboardSyncButton = actionRow.getChildAt(0) as Button
            stopClipboardSyncButton = actionRow.getChildAt(1) as Button
        }
    }

    private fun pageContent(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(colors.background)
        }
    }

    private fun headerRow(title: String, action: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textView(title, 34f, colors.text).apply {
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
            })
        }
    }

    private fun fieldLabel(textResource: Int): TextView {
        return textView(getString(textResource), 13f, colors.muted).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
    }

    private fun card(backgroundColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = roundedDrawable(backgroundColor, 28)
        }
    }

    private fun cardTitle(value: String): TextView {
        return textView(value, 20f, colors.text).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun textView(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
        }
    }

    private fun styledEditText(): EditText {
        return EditText(this).apply {
            textSize = 15f
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            minHeight = dp(58)
            setPadding(dp(18), dp(4), dp(18), dp(4))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = roundedDrawable(colors.inputSurface, 20)
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return styledButton(label, colors.primary, colors.onPrimary, onClick)
    }

    private fun tonalButton(label: String, onClick: () -> Unit): Button {
        return styledButton(label, colors.tonal, colors.text, onClick)
    }

    private fun quietButton(label: String, onClick: () -> Unit): Button {
        return styledButton(label, colors.quiet, colors.text, onClick)
    }

    private fun styledButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            translationZ = 0f
            minHeight = dp(54)
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER
            background = roundedDrawable(backgroundColor, 18)
            setOnClickListener { onClick() }
        }
    }

    private fun horizontalButtons(first: Button, second: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            })
        }
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun scrollPage(content: LinearLayout): ScrollView {
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(colors.background)
            addView(content)
            applySystemBarPadding(content)
        }
    }

    private fun View.applySystemBarPadding(content: View) {
        val basePadding = dp(20)

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

    private fun resolveDynamicPalette(): DynamicPalette {
        val fallbackPrimary = resolveThemeColor(android.R.attr.colorAccent, FALLBACK_PRIMARY)
        val primary = systemColor("system_accent1_600", fallbackPrimary)
        val primaryContainer = systemColor("system_accent1_50", blend(FALLBACK_BACKGROUND, primary, 0.10f))
        val accentContainer = systemColor("system_accent2_50", blend(FALLBACK_BACKGROUND, primary, 0.08f))
        val tonal = systemColor("system_accent1_100", blend(FALLBACK_BACKGROUND, primary, 0.16f))
        val background = systemColor("system_neutral1_10", FALLBACK_BACKGROUND)
        val surface = systemColor("system_neutral1_0", Color.WHITE)
        val quiet = systemColor("system_neutral2_100", FALLBACK_QUIET)
        val outline = systemColor("system_neutral2_200", FALLBACK_OUTLINE)
        val inputSurface = systemColor("system_neutral2_50", blend(surface, primary, 0.05f))

        return DynamicPalette(
            background = background,
            surface = surface,
            inputSurface = inputSurface,
            text = systemColor("system_neutral1_900", FALLBACK_TEXT),
            muted = systemColor("system_neutral2_700", FALLBACK_MUTED),
            outline = outline,
            primary = primary,
            onPrimary = Color.WHITE,
            primaryContainer = primaryContainer,
            accentContainer = accentContainer,
            tonal = tonal,
            quiet = quiet,
            success = systemColor("system_accent1_700", FALLBACK_SUCCESS),
            warning = FALLBACK_WARNING,
            error = FALLBACK_ERROR
        )
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attribute, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }

    private fun systemColor(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return fallback
        }

        val resourceId = resources.getIdentifier(name, "color", "android")
        return if (resourceId != 0) {
            getColor(resourceId)
        } else {
            fallback
        }
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(base) * inverse + Color.red(overlay) * amount).toInt(),
            (Color.green(base) * inverse + Color.green(overlay) * amount).toInt(),
            (Color.blue(base) * inverse + Color.blue(overlay) * amount).toInt()
        )
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

    private data class DynamicPalette(
        val background: Int,
        val surface: Int,
        val inputSurface: Int,
        val text: Int,
        val muted: Int,
        val outline: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val accentContainer: Int,
        val tonal: Int,
        val quiet: Int,
        val success: Int,
        val warning: Int,
        val error: Int
    )

    private enum class PendingAirPlayCapture {
        Audio,
        Screen,
        Broadcast
    }

    companion object {
        const val REQUEST_POST_NOTIFICATIONS = 2001
        const val ACTION_START_AIRPLAY_SCREEN_MIRROR = "com.zevclip.sender.action.START_AIRPLAY_SCREEN_MIRROR"
        const val REQUEST_PHONE_CALLS = 2002
        const val REQUEST_RECORD_AUDIO = 2003
        const val REQUEST_AIRPLAY_CAPTURE = 2004
        const val REQUEST_AIRPLAY_BROADCAST_CAPTURE = 2005
        const val REQUEST_AIRPLAY_SCREEN_CAPTURE = 2006
        val FALLBACK_BACKGROUND: Int = Color.rgb(247, 248, 250)
        val FALLBACK_TEXT: Int = Color.rgb(26, 28, 32)
        val FALLBACK_MUTED: Int = Color.rgb(91, 95, 103)
        val FALLBACK_OUTLINE: Int = Color.rgb(219, 223, 230)
        val FALLBACK_PRIMARY: Int = Color.rgb(23, 104, 201)
        val FALLBACK_QUIET: Int = Color.rgb(236, 239, 244)
        val FALLBACK_SUCCESS: Int = Color.rgb(24, 120, 54)
        val FALLBACK_WARNING: Int = Color.rgb(166, 94, 0)
        val FALLBACK_ERROR: Int = Color.rgb(180, 32, 32)
    }

}
