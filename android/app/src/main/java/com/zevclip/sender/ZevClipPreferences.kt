package com.zevclip.sender

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object ZevClipPreferences {
    const val DEFAULT_PORT = "9876"
    const val KEY_IP_ADDRESS = "mac_ip_address"
    const val KEY_PORT = "port"
    const val KEY_PAIRING_TOKEN = "pairing_token"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_LAST_AUTO_STATUS = "last_auto_status"
    const val KEY_LAST_TILE_STATUS = "last_tile_status"
    const val KEY_DISCOVERY_STATUS = "discovery_status"
    const val KEY_ACCESSIBILITY_SERVICE_BOUND = "accessibility_service_bound"
    const val KEY_LAST_ACCESSIBILITY_SERVICE_EVENT = "last_accessibility_service_event"
    const val KEY_LAST_SERVICE_CONNECTED_AT = "last_service_connected_at"
    const val KEY_LAST_AUTO_SEND_AT = "last_auto_send_at"
    const val KEY_ANDROID_RECEIVER_RUNNING = "android_receiver_running"
    const val KEY_ANDROID_RECEIVER_STATUS = "android_receiver_status"
    const val KEY_ANDROID_RECEIVER_ADVERTISING = "android_receiver_advertising"
    const val KEY_ANDROID_RECEIVER_LAST_RECEIVED_AT = "android_receiver_last_received_at"
    const val KEY_ANDROID_RECEIVER_LAST_RECEIVED_STATUS = "android_receiver_last_received_status"
    const val KEY_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"
    const val KEY_NOTIFICATION_MIRROR_CONNECTED = "notification_mirror_connected"
    const val KEY_NOTIFICATION_MIRROR_STATUS = "notification_mirror_status"
    const val KEY_CALL_MIRROR_STATUS = "call_mirror_status"
    const val KEY_MAC_BATTERY_PERCENTAGE = "mac_battery_percentage"
    const val KEY_MAC_BATTERY_CHARGING = "mac_battery_charging"
    const val KEY_MAC_BATTERY_AVAILABLE = "mac_battery_available"
    const val KEY_MAC_BATTERY_LAST_SEEN_AT = "mac_battery_last_seen_at"
    const val KEY_EXPERIMENTAL_AIRPLAY_ENABLED = "experimental_airplay_enabled"
    const val KEY_AIRPLAY_TEST_STATUS = "airplay_test_status"
    const val KEY_AIRPLAY_PASSCODE = "airplay_passcode"
    const val KEY_AIRPLAY_SCREEN_CODE = "airplay_screen_code"
    const val KEY_AIRPLAY_STREAMING = "airplay_streaming"
    const val KEY_AIRPLAY_SCREEN_MIRRORING = "airplay_screen_mirroring"
    const val KEY_AIRPLAY_BROADCAST_STATUS = "airplay_broadcast_status"
    const val KEY_AIRPLAY_BROADCAST_STREAMING = "airplay_broadcast_streaming"

    private const val KEY_ANDROID_DEVICE_ID = "android_device_id"
    private const val KEY_LAST_TILE_SUBTITLE = "last_tile_subtitle"
    private const val KEY_AIRPLAY_BROADCAST_PASSWORD_PREFIX = "airplay_broadcast_password_"
    private const val KEY_AIRPLAY_BROADCAST_DELAY_PREFIX = "airplay_broadcast_delay_"
    private const val KEY_AIRPLAY_BROADCAST_APPROVED_PREFIX = "airplay_broadcast_approved_"

    private const val PREFERENCES_NAME = "zevclip_preferences"
    private const val LEGACY_ACTIVITY_PREFERENCES_NAME = "MainActivity"
    private const val KEY_MIGRATION_COMPLETE = "shared_preferences_migration_complete"

    fun preferences(context: Context): SharedPreferences {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        migrateLegacyActivityPreferences(context, preferences)
        return preferences
    }

    fun saveEndpoint(context: Context, ipAddress: String, port: String) {
        preferences(context).edit()
            .putString(KEY_IP_ADDRESS, NetworkInputValidator.normalizeHost(ipAddress))
            .putString(KEY_PORT, port.trim())
            .apply()
    }

    fun endpoint(context: Context): Endpoint? {
        val preferences = preferences(context)
        val ipAddress = NetworkInputValidator.normalizeHost(
            preferences.getString(KEY_IP_ADDRESS, "").orEmpty()
        )
        val port = NetworkInputValidator.parsePort(
            preferences.getString(KEY_PORT, DEFAULT_PORT).orEmpty().trim()
        )

        if (!NetworkInputValidator.validateHost(ipAddress) || port == null) {
            return null
        }

        return Endpoint(ipAddress, port)
    }

    fun pairingToken(context: Context): String {
        return preferences(context).getString(KEY_PAIRING_TOKEN, "").orEmpty().trim()
    }

    fun savePairingToken(context: Context, token: String) {
        preferences(context).edit()
            .putString(KEY_PAIRING_TOKEN, token.trim())
            .apply()
    }

    fun deviceId(context: Context): String {
        return preferences(context).getString(KEY_DEVICE_ID, "").orEmpty().trim()
    }

    fun saveDeviceId(context: Context, deviceId: String?) {
        val normalizedDeviceId = deviceId.orEmpty().trim()
        if (normalizedDeviceId.isEmpty()) {
            return
        }

        preferences(context).edit()
            .putString(KEY_DEVICE_ID, normalizedDeviceId)
            .apply()
    }

    fun androidDeviceId(context: Context): String {
        val preferences = preferences(context)
        val existingDeviceId = preferences.getString(KEY_ANDROID_DEVICE_ID, "").orEmpty().trim()
        if (existingDeviceId.isNotEmpty()) {
            return existingDeviceId
        }

        val newDeviceId = UUID.randomUUID().toString()
        preferences.edit()
            .putString(KEY_ANDROID_DEVICE_ID, newDeviceId)
            .apply()
        return newDeviceId
    }

    fun lastAutoStatus(context: Context): String {
        return preferences(context).getString(
            KEY_LAST_AUTO_STATUS,
            "No automatic send attempt yet."
        ).orEmpty()
    }

    fun setLastAutoStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_LAST_AUTO_STATUS, status)
            .apply()
    }

    fun isAccessibilityServiceBound(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ACCESSIBILITY_SERVICE_BOUND, false)
    }

    fun lastAccessibilityServiceEvent(context: Context): String {
        return preferences(context).getString(
            KEY_LAST_ACCESSIBILITY_SERVICE_EVENT,
            "No service lifecycle event yet."
        ).orEmpty()
    }

    fun setAccessibilityServiceState(
        context: Context,
        bound: Boolean,
        event: String,
        connectedAtMillis: Long? = null
    ) {
        val editor = preferences(context).edit()
            .putBoolean(KEY_ACCESSIBILITY_SERVICE_BOUND, bound)
            .putString(KEY_LAST_ACCESSIBILITY_SERVICE_EVENT, event)

        if (connectedAtMillis != null) {
            editor.putLong(KEY_LAST_SERVICE_CONNECTED_AT, connectedAtMillis)
        }

        editor.apply()
    }

    fun lastServiceConnectedAt(context: Context): Long {
        return preferences(context).getLong(KEY_LAST_SERVICE_CONNECTED_AT, 0L)
    }

    fun lastAutoSendAt(context: Context): Long {
        return preferences(context).getLong(KEY_LAST_AUTO_SEND_AT, 0L)
    }

    fun setLastAutoSendAt(context: Context, timestampMillis: Long) {
        preferences(context).edit()
            .putLong(KEY_LAST_AUTO_SEND_AT, timestampMillis)
            .apply()
    }

    fun lastTileStatus(context: Context): String {
        return preferences(context).getString(
            KEY_LAST_TILE_STATUS,
            "No Quick Settings sync attempt yet."
        ).orEmpty()
    }

    fun setLastTileStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_LAST_TILE_STATUS, status)
            .apply()
    }

    fun lastTileSubtitle(context: Context): String {
        return preferences(context).getString(KEY_LAST_TILE_SUBTITLE, "Ready").orEmpty()
    }

    fun setLastTileResult(context: Context, subtitle: String, status: String) {
        preferences(context).edit()
            .putString(KEY_LAST_TILE_SUBTITLE, subtitle)
            .putString(KEY_LAST_TILE_STATUS, status)
            .apply()
    }

    fun isClipboardSyncEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_CLIPBOARD_SYNC_ENABLED, true)
    }

    fun setClipboardSyncEnabled(context: Context, isEnabled: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_CLIPBOARD_SYNC_ENABLED, isEnabled)
            .apply()
    }

    fun isExperimentalAirPlayEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_EXPERIMENTAL_AIRPLAY_ENABLED, false)
    }

    fun setExperimentalAirPlayEnabled(context: Context, isEnabled: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_EXPERIMENTAL_AIRPLAY_ENABLED, isEnabled)
            .apply()
    }

    fun airPlayTestStatus(context: Context): String {
        return preferences(context).getString(
            KEY_AIRPLAY_TEST_STATUS,
            "AirPlay is ready."
        ).orEmpty()
    }

    fun setAirPlayTestStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_AIRPLAY_TEST_STATUS, status)
            .apply()
    }

    fun airPlayPasscode(context: Context): String {
        return preferences(context).getString(KEY_AIRPLAY_PASSCODE, "").orEmpty().trim()
    }

    fun setAirPlayPasscode(context: Context, passcode: String) {
        preferences(context).edit()
            .putString(KEY_AIRPLAY_PASSCODE, passcode.trim())
            .apply()
    }

    fun airPlayScreenCode(context: Context): String {
        return preferences(context).getString(KEY_AIRPLAY_SCREEN_CODE, "").orEmpty().trim()
    }

    fun setAirPlayScreenCode(context: Context, code: String) {
        preferences(context).edit()
            .putString(KEY_AIRPLAY_SCREEN_CODE, code.trim())
            .apply()
    }

    fun isAirPlayStreaming(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_AIRPLAY_STREAMING, false)
    }

    fun setAirPlayStreaming(context: Context, isStreaming: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_AIRPLAY_STREAMING, isStreaming)
            .apply()
    }

    fun isAirPlayScreenMirroring(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_AIRPLAY_SCREEN_MIRRORING, false)
    }

    fun setAirPlayScreenMirroring(context: Context, isMirroring: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_AIRPLAY_SCREEN_MIRRORING, isMirroring)
            .apply()
    }

    fun airPlayBroadcastStatus(context: Context): String {
        return preferences(context).getString(
            KEY_AIRPLAY_BROADCAST_STATUS,
            "No AirPlay broadcast yet."
        ).orEmpty()
    }

    fun setAirPlayBroadcastStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_AIRPLAY_BROADCAST_STATUS, status)
            .apply()
    }

    fun isAirPlayBroadcastStreaming(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_AIRPLAY_BROADCAST_STREAMING, false)
    }

    fun setAirPlayBroadcastStreaming(context: Context, isStreaming: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_AIRPLAY_BROADCAST_STREAMING, isStreaming)
            .apply()
    }

    fun airPlayBroadcastPassword(context: Context, receiverKey: String): String {
        return preferences(context)
            .getString(KEY_AIRPLAY_BROADCAST_PASSWORD_PREFIX + receiverKey, "")
            .orEmpty()
            .trim()
    }

    fun setAirPlayBroadcastPassword(context: Context, receiverKey: String, password: String) {
        preferences(context).edit()
            .putString(KEY_AIRPLAY_BROADCAST_PASSWORD_PREFIX + receiverKey, password.trim())
            .apply()
    }

    fun airPlayBroadcastDelayMs(context: Context, receiverKey: String): Int {
        return preferences(context)
            .getInt(KEY_AIRPLAY_BROADCAST_DELAY_PREFIX + receiverKey, 0)
            .coerceIn(-1_000, 1_000)
    }

    fun setAirPlayBroadcastDelayMs(context: Context, receiverKey: String, delayMs: Int) {
        preferences(context).edit()
            .putInt(KEY_AIRPLAY_BROADCAST_DELAY_PREFIX + receiverKey, delayMs.coerceIn(-1_000, 1_000))
            .apply()
    }

    fun isAirPlayBroadcastReceiverApproved(context: Context, receiverKey: String): Boolean {
        return preferences(context)
            .getBoolean(KEY_AIRPLAY_BROADCAST_APPROVED_PREFIX + receiverKey, false)
    }

    fun setAirPlayBroadcastReceiverApproved(context: Context, receiverKey: String, isApproved: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_AIRPLAY_BROADCAST_APPROVED_PREFIX + receiverKey, isApproved)
            .apply()
    }

    fun isNotificationMirrorConnected(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_NOTIFICATION_MIRROR_CONNECTED, false)
    }

    fun notificationMirrorStatus(context: Context): String {
        return preferences(context).getString(
            KEY_NOTIFICATION_MIRROR_STATUS,
            "Notification mirroring is waiting for permission."
        ).orEmpty()
    }

    fun setNotificationMirrorState(
        context: Context,
        isConnected: Boolean,
        status: String
    ) {
        preferences(context).edit()
            .putBoolean(KEY_NOTIFICATION_MIRROR_CONNECTED, isConnected)
            .putString(KEY_NOTIFICATION_MIRROR_STATUS, status)
            .apply()
    }

    fun setLastNotificationMirrorStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_NOTIFICATION_MIRROR_STATUS, status)
            .apply()
    }

    fun callMirrorStatus(context: Context): String {
        return preferences(context).getString(
            KEY_CALL_MIRROR_STATUS,
            "Call mirroring is waiting for Phone permission."
        ).orEmpty()
    }

    fun setCallMirrorStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_CALL_MIRROR_STATUS, status)
            .apply()
    }

    fun macBatteryStatus(context: Context): MacBatteryStatus {
        val preferences = preferences(context)
        val percentage = preferences.getInt(KEY_MAC_BATTERY_PERCENTAGE, -1)
            .takeIf { it in 0..100 }

        return MacBatteryStatus(
            percentage = percentage,
            isCharging = preferences.getBoolean(KEY_MAC_BATTERY_CHARGING, false),
            isAvailable = preferences.getBoolean(KEY_MAC_BATTERY_AVAILABLE, false),
            lastSeenAtMillis = preferences.getLong(KEY_MAC_BATTERY_LAST_SEEN_AT, 0L)
        )
    }

    fun setMacBatteryStatus(
        context: Context,
        percentage: Int?,
        isCharging: Boolean?,
        isAvailable: Boolean,
        seenAtMillis: Long = System.currentTimeMillis()
    ) {
        val editor = preferences(context).edit()
            .putBoolean(KEY_MAC_BATTERY_AVAILABLE, isAvailable)
            .putLong(KEY_MAC_BATTERY_LAST_SEEN_AT, seenAtMillis)

        if (percentage != null) {
            editor.putInt(KEY_MAC_BATTERY_PERCENTAGE, percentage)
        } else {
            editor.remove(KEY_MAC_BATTERY_PERCENTAGE)
        }

        if (isCharging != null) {
            editor.putBoolean(KEY_MAC_BATTERY_CHARGING, isCharging)
        }

        editor.apply()
    }

    fun discoveryStatus(context: Context): String {
        return preferences(context).getString(
            KEY_DISCOVERY_STATUS,
            "No discovery attempt yet."
        ).orEmpty()
    }

    fun setDiscoveryStatus(context: Context, status: String) {
        preferences(context).edit()
            .putString(KEY_DISCOVERY_STATUS, status)
            .apply()
    }

    fun isAndroidReceiverRunning(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ANDROID_RECEIVER_RUNNING, false)
    }

    fun isAndroidReceiverAdvertising(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ANDROID_RECEIVER_ADVERTISING, false)
    }

    fun androidReceiverStatus(context: Context): String {
        return preferences(context).getString(
            KEY_ANDROID_RECEIVER_STATUS,
            "Android receiver is stopped."
        ).orEmpty()
    }

    fun androidReceiverLastReceivedAt(context: Context): Long {
        return preferences(context).getLong(KEY_ANDROID_RECEIVER_LAST_RECEIVED_AT, 0L)
    }

    fun androidReceiverLastReceivedStatus(context: Context): String {
        return preferences(context).getString(
            KEY_ANDROID_RECEIVER_LAST_RECEIVED_STATUS,
            "No Mac clipboard received yet."
        ).orEmpty()
    }

    fun setAndroidReceiverState(
        context: Context,
        isRunning: Boolean,
        status: String
    ) {
        preferences(context).edit()
            .putBoolean(KEY_ANDROID_RECEIVER_RUNNING, isRunning)
            .putString(KEY_ANDROID_RECEIVER_STATUS, status)
            .apply()
    }

    fun setAndroidReceiverAdvertising(
        context: Context,
        isAdvertising: Boolean,
        status: String? = null
    ) {
        val editor = preferences(context).edit()
            .putBoolean(KEY_ANDROID_RECEIVER_ADVERTISING, isAdvertising)

        if (status != null) {
            editor.putString(KEY_ANDROID_RECEIVER_STATUS, status)
        }

        editor.apply()
    }

    fun setAndroidReceiverLastReceived(
        context: Context,
        timestampMillis: Long,
        status: String
    ) {
        preferences(context).edit()
            .putLong(KEY_ANDROID_RECEIVER_LAST_RECEIVED_AT, timestampMillis)
            .putString(KEY_ANDROID_RECEIVER_LAST_RECEIVED_STATUS, status)
            .apply()
    }

    private fun migrateLegacyActivityPreferences(
        context: Context,
        preferences: SharedPreferences
    ) {
        if (preferences.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
            return
        }

        val legacyPreferences = context.getSharedPreferences(
            LEGACY_ACTIVITY_PREFERENCES_NAME,
            Activity.MODE_PRIVATE
        )

        preferences.edit()
            .putString(
                KEY_IP_ADDRESS,
                legacyPreferences.getString(KEY_IP_ADDRESS, "").orEmpty()
            )
            .putString(
                KEY_PORT,
                legacyPreferences.getString(KEY_PORT, DEFAULT_PORT).orEmpty()
            )
            .putString(
                KEY_PAIRING_TOKEN,
                legacyPreferences.getString(KEY_PAIRING_TOKEN, "").orEmpty()
            )
            .putBoolean(KEY_MIGRATION_COMPLETE, true)
            .apply()
    }
}

data class Endpoint(
    val ipAddress: String,
    val port: Int
)

data class MacBatteryStatus(
    val percentage: Int?,
    val isCharging: Boolean,
    val isAvailable: Boolean,
    val lastSeenAtMillis: Long
)
