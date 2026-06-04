package com.zevclip.sender

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences

object ZevClipPreferences {
    const val DEFAULT_PORT = "9876"
    const val KEY_IP_ADDRESS = "mac_ip_address"
    const val KEY_PORT = "port"
    const val KEY_LAST_AUTO_STATUS = "last_auto_status"
    const val KEY_LAST_AUTO_SENT_HASH = "last_auto_sent_hash"

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
            .putString(KEY_IP_ADDRESS, ipAddress.trim())
            .putString(KEY_PORT, port.trim())
            .apply()
    }

    fun endpoint(context: Context): Endpoint? {
        val preferences = preferences(context)
        val ipAddress = preferences.getString(KEY_IP_ADDRESS, "").orEmpty().trim()
        val port = NetworkInputValidator.parsePort(
            preferences.getString(KEY_PORT, DEFAULT_PORT).orEmpty().trim()
        )

        if (!NetworkInputValidator.validateIPv4(ipAddress) || port == null) {
            return null
        }

        return Endpoint(ipAddress, port)
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

    fun lastAutoSentHash(context: Context): String? {
        return preferences(context).getString(KEY_LAST_AUTO_SENT_HASH, null)
    }

    fun setLastAutoSentHash(context: Context, hash: String) {
        preferences(context).edit()
            .putString(KEY_LAST_AUTO_SENT_HASH, hash)
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
            .putBoolean(KEY_MIGRATION_COMPLETE, true)
            .apply()
    }
}

data class Endpoint(
    val ipAddress: String,
    val port: Int
)

