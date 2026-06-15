package com.zevclip.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

class OTPMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.filterNotNull()
            .orEmpty()
        if (messages.isEmpty()) {
            return
        }

        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
            .trim()
        val sender = messages.firstNotNullOfOrNull { it.displayOriginatingAddress?.trim() }
            ?.takeUnless { it.isBlank() }
            ?: "SMS"
        val otpCode = OTPMessageDetector.detectCode(body) ?: return

        val appContext = context.applicationContext
        val packageName = Telephony.Sms.getDefaultSmsPackage(appContext) ?: "android.sms"
        val now = System.currentTimeMillis()
        val recentNotification = AndroidNotificationMirrorService.recentSMSNotificationKey(packageName)
        val payload = AndroidNotificationMirrorPayload(
            event = "posted",
            appName = "Messages",
            packageName = packageName,
            appIconPngBase64 = AndroidPackageIconLoader.appIconPngBase64ForPackage(appContext, packageName),
            title = sender,
            body = body,
            subtext = "OTP code: $otpCode",
            actions = emptyList(),
            notificationKey = recentNotification?.first
                ?: pendingOTPKey(sender = sender, otpCode = otpCode, receivedAtMillis = now),
            postedAtMillis = recentNotification?.second ?: now
        )

        if (recentNotification == null) {
            rememberPendingOTP(packageName, payload)
        }
        val pendingResult = goAsync()
        thread(name = "ZevClipOTPMirror") {
            try {
                val result = AndroidNotificationMirrorSender.sendSavedEndpoint(appContext, payload)
                val status = when (result) {
                    is SendResult.Success -> "Mirrored SMS OTP notification."
                    is SendResult.Failure -> "SMS OTP mirror failed: ${result.message}"
                }
                ZevClipPreferences.setLastNotificationMirrorStatus(appContext, status)
                Log.i(TAG, status)
            } catch (error: Exception) {
                val status = "SMS OTP mirror failed: ${error.message ?: error.javaClass.simpleName}"
                ZevClipPreferences.setLastNotificationMirrorStatus(appContext, status)
                Log.w(TAG, status, error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private data class PendingOTP(
        val payload: AndroidNotificationMirrorPayload,
        val recordedAtMillis: Long
    )

    companion object {
        private const val TAG = "ZevClipOTPReceiver"
        private const val PENDING_OTP_WINDOW_MS = 12_000L
        private val pendingOTPsByPackage = mutableMapOf<String, PendingOTP>()

        private fun pendingOTPKey(sender: String, otpCode: String, receivedAtMillis: Long): String {
            return "sms-otp-$receivedAtMillis-${sender.hashCode()}-${otpCode.hashCode()}"
        }

        private fun rememberPendingOTP(packageName: String, payload: AndroidNotificationMirrorPayload) {
            synchronized(pendingOTPsByPackage) {
                pendingOTPsByPackage[packageName] = PendingOTP(
                    payload = payload,
                    recordedAtMillis = System.currentTimeMillis()
                )
            }
        }

        fun flushPendingOTPForPackage(
            context: Context,
            packageName: String,
            notificationKey: String,
            postedAtMillis: Long
        ) {
            val pendingOTP = synchronized(pendingOTPsByPackage) {
                val pending = pendingOTPsByPackage[packageName] ?: return@synchronized null
                if (System.currentTimeMillis() - pending.recordedAtMillis > PENDING_OTP_WINDOW_MS) {
                    pendingOTPsByPackage.remove(packageName)
                    null
                } else {
                    pendingOTPsByPackage.remove(packageName)
                    pending
                }
            } ?: return

            val payload = pendingOTP.payload.copy(
                notificationKey = notificationKey,
                postedAtMillis = postedAtMillis
            )
            val appContext = context.applicationContext
            thread(name = "ZevClipOTPKeyRefresh") {
                try {
                    AndroidNotificationMirrorSender.sendSavedEndpoint(appContext, payload)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not refresh SMS OTP notification key", error)
                }
            }
        }
    }
}

object OTPMessageDetector {
    private val otpKeywords = listOf(
        "otp",
        "one time",
        "one-time",
        "verification",
        "verify",
        "code",
        "passcode",
        "security code",
        "login code",
        "authentication"
    )
    private val codeRegex = Regex("""(?<!\d)(\d(?:[ -]?\d){3,7})(?!\d)""")

    fun detectCode(message: String): String? {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) {
            return null
        }

        val lowercasedMessage = normalizedMessage.lowercase()
        if (otpKeywords.none { lowercasedMessage.contains(it) }) {
            return null
        }

        return codeRegex.findAll(normalizedMessage)
            .map { match -> match.groupValues[1].filter(Char::isDigit) }
            .firstOrNull { code -> code.length in 4..8 }
    }
}
