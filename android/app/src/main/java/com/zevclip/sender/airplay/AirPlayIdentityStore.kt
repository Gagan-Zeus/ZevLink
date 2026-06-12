package com.zevclip.sender.airplay

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.zevclip.sender.ZevClipPreferences
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object AirPlayIdentityStore {
    private const val PREF_NAME = "zevclip_airplay_identity"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PRIVATE_KEY = "private_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SENDER_NAME = "sender_name"

    fun getOrCreate(context: Context): AirPlayIdentity {
        return read(context) ?: AirPlayIdentity.generate(
            deviceId = readOrCreateDeviceId(context),
            senderName = resolvedSenderName(context)
        ).also { write(context, it) }
    }

    fun read(context: Context): AirPlayIdentity? {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pairingId = preferences.getString(KEY_PAIRING_ID, null) ?: return null
        val privateKeyBase64 = preferences.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privateKey = runCatching {
            Base64.getDecoder().decode(privateKeyBase64)
        }.getOrNull() ?: return null

        val storedDeviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val deviceId = storedDeviceId ?: readOrCreateDeviceId(appContext)
        val senderName = resolvedSenderName(appContext)
        if (storedDeviceId == null || preferences.getString(KEY_SENDER_NAME, null) != senderName) {
            preferences.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_SENDER_NAME, senderName)
                .apply()
        }

        return runCatching {
            AirPlayIdentity.fromPrivateKey(
                pairingId = pairingId,
                privateKey = privateKey,
                deviceId = deviceId,
                senderName = senderName
            )
        }.getOrNull()
    }

    fun write(context: Context, identity: AirPlayIdentity) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRING_ID, identity.pairingId)
            .putString(KEY_PRIVATE_KEY, Base64.getEncoder().encodeToString(identity.privateKey))
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_SENDER_NAME, identity.senderName)
            .apply()
    }

    fun reset(context: Context): AirPlayIdentity {
        val identity = AirPlayIdentity.generate(
            deviceId = readOrCreateDeviceId(context),
            senderName = resolvedSenderName(context)
        )
        write(context, identity)
        return identity
    }

    private fun readOrCreateDeviceId(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val stableSeed = ZevClipPreferences.androidDeviceId(context.applicationContext)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableSeed.toByteArray(Charsets.UTF_8))
            .copyOf(6)
        digest[0] = ((digest[0].toInt() or 0x02) and 0xFE).toByte()
        val deviceId = digest.joinToString(":") { byte ->
            "%02X".format(Locale.US, byte.toInt() and 0xFF)
        }
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    private fun resolvedSenderName(context: Context): String {
        val resolver = context.applicationContext.contentResolver
        val candidates = listOf(
            runCatching { Settings.Global.getString(resolver, "device_name") }.getOrNull(),
            runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull(),
            Build.MODEL,
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        return candidates
            .asSequence()
            .map { it.orEmpty().trim() }
            .map { name -> name.replace(Regex("\\s+"), " ") }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_SENDER_NAME_LENGTH)
            ?: "Android"
    }

    private const val MAX_SENDER_NAME_LENGTH = 64
}
