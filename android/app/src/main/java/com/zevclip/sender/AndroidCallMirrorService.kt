package com.zevclip.sender

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

class AndroidCallMirrorService : Service() {
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipCallMirror")
    }

    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var currentCallId: String? = null
    private var currentCallerName: String? = null
    private var currentCallerNumber: String? = null
    private var currentCallDirection: String? = null
    private var callAnsweredAtMillis: Long = 0L
    private var didMuteRinger = false
    private var previousRingerMode: Int? = null
    private var previousInterruptionFilter: Int? = null
    private var isIgnoringOutgoingCall = false
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startListening()
            ACTION_STOP -> {
                stopListening("Call mirroring stopped.")
                stopSelf(startId)
            }
            else -> Log.w(TAG, "Ignoring unknown call mirror action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening("Call mirroring stopped.")
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun startListening() {
        activeService = this

        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            setStatus("Call mirroring needs Phone permission.")
            return
        }

        if (legacyListener != null) {
            return
        }

        val manager = getSystemService(TelephonyManager::class.java)
        telephonyManager = manager

        try {
            @Suppress("DEPRECATION")
            val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : PhoneStateListener(mainExecutor) {
                    @Deprecated("Deprecated by Android; kept because this callback can include caller number.")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
            } else {
                object : PhoneStateListener() {
                    @Deprecated("Deprecated by Android; kept for API 26-30.")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)

            setStatus("Call mirroring connected.")
            Log.i(TAG, "Call mirror listener connected")
        } catch (error: SecurityException) {
            setStatus("Android denied Phone permission for call mirroring.")
            Log.w(TAG, "Android denied call-state listener", error)
        } catch (error: RuntimeException) {
            setStatus("Could not start call mirroring: ${error.message ?: "unknown error"}")
            Log.w(TAG, "Could not start call mirror listener", error)
        }
    }

    private fun stopListening(status: String) {
        val manager = telephonyManager

        try {
            @Suppress("DEPRECATION")
            legacyListener?.let { manager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not stop call mirror listener", error)
        }

        legacyListener = null
        telephonyManager = null
        currentCallId = null
        currentCallerName = null
        currentCallerNumber = null
        currentCallDirection = null
        callAnsweredAtMillis = 0L
        unmuteRingerIfNeeded()
        isIgnoringOutgoingCall = false
        lastCallState = TelephonyManager.CALL_STATE_IDLE
        setStatus(status)
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        if (!ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }
        if (state == lastCallState && phoneNumber.isNullOrBlank()) {
            return
        }

        val previousState = lastCallState
        lastCallState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val callId = UUID.randomUUID().toString()
                val callerInfo = resolveCallerInfo(phoneNumber)
                currentCallId = callId
                currentCallerName = callerInfo.name
                currentCallerNumber = callerInfo.number
                currentCallDirection = DIRECTION_INCOMING
                sendCallEvent(
                    AndroidCallMirrorPayload(
                        event = EVENT_RINGING,
                        callId = callId,
                        title = callerInfo.title,
                        body = callerInfo.body,
                        callerName = callerInfo.name,
                        callerNumber = callerInfo.number,
                        direction = DIRECTION_INCOMING,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (currentCallDirection == null || currentCallId == null) {
                    isIgnoringOutgoingCall = true
                    setStatus("Ignoring outgoing Android call.")
                    return
                }
                val direction = currentCallDirection ?: DIRECTION_INCOMING
                val callId = currentCallId ?: return
                callAnsweredAtMillis = System.currentTimeMillis()
                unmuteRingerIfNeeded()
                sendCallEvent(
                    AndroidCallMirrorPayload(
                        event = EVENT_ANSWERED,
                        callId = callId,
                        title = currentCallerName ?: currentCallerNumber ?: if (direction == DIRECTION_INCOMING) {
                            "Android call answered"
                        } else {
                            "Outgoing Android call"
                        },
                        body = if (direction == DIRECTION_INCOMING) {
                            "The call is active on your phone."
                        } else {
                            "Outgoing call active on your phone."
                        },
                        callerName = currentCallerName,
                        callerNumber = currentCallerNumber,
                        direction = direction,
                        timestampMillis = callAnsweredAtMillis
                    )
                )
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (isIgnoringOutgoingCall) {
                    isIgnoringOutgoingCall = false
                    currentCallId = null
                    currentCallerName = null
                    currentCallerNumber = null
                    currentCallDirection = null
                    callAnsweredAtMillis = 0L
                    setStatus("Ignored outgoing Android call.")
                    return
                }
                if (previousState != TelephonyManager.CALL_STATE_IDLE) {
                    val callId = currentCallId ?: UUID.randomUUID().toString()
                    sendCallEvent(
                        AndroidCallMirrorPayload(
                            event = EVENT_ENDED,
                            callId = callId,
                            title = currentCallerName ?: currentCallerNumber ?: "Android call ended",
                            body = "The call is no longer active.",
                            callerName = currentCallerName,
                            callerNumber = currentCallerNumber,
                            direction = currentCallDirection ?: DIRECTION_UNKNOWN,
                            timestampMillis = System.currentTimeMillis()
                        )
                    )
                }
                currentCallId = null
                currentCallerName = null
                currentCallerNumber = null
                currentCallDirection = null
                callAnsweredAtMillis = 0L
                unmuteRingerIfNeeded()
            }
        }
    }

    private fun sendCallEvent(payload: AndroidCallMirrorPayload) {
        networkExecutor.execute {
            val result = AndroidCallMirrorSender.sendSavedEndpoint(this, payload)
            val status = when (result) {
                is SendResult.Success -> {
                    when (payload.event) {
                        EVENT_RINGING -> "Mirrored incoming Android call."
                        EVENT_ANSWERED -> "Mirrored answered Android call."
                        EVENT_ENDED -> "Mirrored ended Android call."
                        else -> "Mirrored Android call."
                    }
                }
                is SendResult.Failure -> "Call mirror failed: ${result.message}"
            }
            setStatus(status)
            Log.i(TAG, status)
        }
    }

    private fun handleCallAction(action: String, callId: String?): AndroidCallActionResult {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in SUPPORTED_ACTIONS) {
            return AndroidCallActionResult(false, "Unsupported call action.")
        }

        if (normalizedAction != ACTION_SILENCE && !hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return AndroidCallActionResult(false, "Android Phone permission to answer/manage calls is not granted.")
        }

        if (normalizedAction == ACTION_ACCEPT && lastCallState != TelephonyManager.CALL_STATE_RINGING) {
            return AndroidCallActionResult(false, "There is no ringing Android call to accept.")
        }
        if (normalizedAction == ACTION_SILENCE && lastCallState != TelephonyManager.CALL_STATE_RINGING) {
            return AndroidCallActionResult(false, "There is no ringing Android call to silence.")
        }
        if (
            normalizedAction == ACTION_REJECT &&
            lastCallState != TelephonyManager.CALL_STATE_RINGING &&
            lastCallState != TelephonyManager.CALL_STATE_OFFHOOK
        ) {
            return AndroidCallActionResult(false, "There is no active Android call to end.")
        }
        val activeCallId = currentCallId
        if (!callId.isNullOrBlank() && activeCallId != null && callId != activeCallId) {
            return AndroidCallActionResult(false, "That Android call is no longer active.")
        }

        val telecomManager = getSystemService(TelecomManager::class.java)
            ?: return AndroidCallActionResult(false, "Android Telecom service is unavailable.")

        return try {
            when (normalizedAction) {
                ACTION_ACCEPT -> {
                    @Suppress("DEPRECATION")
                    telecomManager.acceptRingingCall()
                    AndroidCallActionResult(true, "Android call accepted.")
                }
                ACTION_REJECT -> {
                    @Suppress("DEPRECATION")
                    val ended = telecomManager.endCall()
                    if (ended) {
                        AndroidCallActionResult(true, "Android call rejected.")
                    } else {
                        AndroidCallActionResult(false, "Android did not reject the call.")
                    }
                }
                ACTION_SILENCE -> {
                    silenceRingerAndVibration(telecomManager)
                }
                else -> AndroidCallActionResult(false, "Unsupported call action.")
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Android denied call action $normalizedAction", error)
            AndroidCallActionResult(false, "Android denied the call action permission.")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Android call action $normalizedAction failed", error)
            AndroidCallActionResult(false, "Android call action failed: ${error.message ?: "unknown error"}")
        }
    }

    private fun resolveCallerInfo(phoneNumber: String?): CallerInfo {
        val number = phoneNumber?.trim()?.takeUnless { it.isBlank() }
        val name = number?.let { lookupContactName(it) }
        val title = name ?: number ?: "Incoming Android call"
        val body = when {
            name != null && number != null -> number
            number != null -> "Your phone is ringing."
            else -> "Your phone is ringing. Grant Call Log permission to show the number."
        }
        return CallerInfo(name = name, number = number, title = title, body = body)
    }

    private fun lookupContactName(number: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return null
        }

        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeUnless { it.isBlank() }
                } else {
                    null
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not look up caller contact", error)
            null
        }
    }

    private fun silenceRingerAndVibration(
        telecomManager: TelecomManager
    ): AndroidCallActionResult {
        var telecomSilenced = false
        try {
            telecomManager.silenceRinger()
            telecomSilenced = true
        } catch (error: SecurityException) {
            Log.w(TAG, "Android denied Telecom silenceRinger", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Telecom silenceRinger failed", error)
        }

        val audioManager = getSystemService(AudioManager::class.java)
            ?: return if (telecomSilenced) {
                AndroidCallActionResult(true, "Android call ringer silenced.")
            } else {
                AndroidCallActionResult(false, "Android Audio service is unavailable.")
            }

        var streamMuted = false
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            streamMuted = true
            didMuteRinger = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not mute ring stream", error)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val canSetSilentMode = notificationManager?.isNotificationPolicyAccessGranted == true
        if (!canSetSilentMode) {
            return if (telecomSilenced || streamMuted) {
                AndroidCallActionResult(
                    false,
                    "Sound muted, but vibration needs Do Not Disturb access for ZevLink."
                )
            } else {
                AndroidCallActionResult(
                    false,
                    "Enable Do Not Disturb access for ZevLink to silence vibration."
                )
            }
        }

        return try {
            if (previousRingerMode == null) {
                previousRingerMode = audioManager.ringerMode
            }
            if (previousInterruptionFilter == null) {
                previousInterruptionFilter = notificationManager.currentInterruptionFilter
            }
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            didMuteRinger = true
            AndroidCallActionResult(true, "Android call sound and vibration silenced.")
        } catch (error: SecurityException) {
            Log.w(TAG, "Android denied silent ringer mode", error)
            AndroidCallActionResult(
                false,
                "Android denied silent mode. Enable Do Not Disturb access for ZevLink."
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not silence ringer and vibration", error)
            AndroidCallActionResult(
                false,
                "Android could not silence vibration: ${error.message ?: "ringer mode failed"}"
            )
        }
    }

    private fun unmuteRingerIfNeeded() {
        if (!didMuteRinger && previousRingerMode == null) {
            return
        }
        didMuteRinger = false
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val notificationManager = getSystemService(NotificationManager::class.java)
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            previousRingerMode?.let { savedMode ->
                audioManager.ringerMode = savedMode
            }
            if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                previousInterruptionFilter?.let { savedFilter ->
                    notificationManager.setInterruptionFilter(savedFilter)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not restore ringer mode", error)
        } finally {
            previousRingerMode = null
            previousInterruptionFilter = null
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun setStatus(status: String) {
        ZevClipPreferences.setCallMirrorStatus(this, status)
    }

    private data class CallerInfo(
        val name: String?,
        val number: String?,
        val title: String,
        val body: String
    )

    companion object {
        private const val TAG = "ZevClipCallMirror"
        private const val ACTION_START = "com.zevclip.sender.START_CALL_MIRROR"
        private const val ACTION_STOP = "com.zevclip.sender.STOP_CALL_MIRROR"
        private const val EVENT_RINGING = "ringing"
        private const val EVENT_ANSWERED = "answered"
        private const val EVENT_ENDED = "ended"
        private const val DIRECTION_INCOMING = "incoming"
        private const val DIRECTION_UNKNOWN = "unknown"
        private const val ACTION_ACCEPT = "accept"
        private const val ACTION_REJECT = "reject"
        private const val ACTION_SILENCE = "silence"
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_ACCEPT,
            ACTION_REJECT,
            ACTION_SILENCE
        )

        @Volatile
        private var activeService: AndroidCallMirrorService? = null

        fun start(context: Context) {
            context.startService(
                Intent(context, AndroidCallMirrorService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AndroidCallMirrorService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun performCallAction(action: String, callId: String?): AndroidCallActionResult {
            val service = activeService
                ?: return AndroidCallActionResult(false, "Android call mirror is not connected.")
            return service.handleCallAction(action, callId)
        }
    }
}
