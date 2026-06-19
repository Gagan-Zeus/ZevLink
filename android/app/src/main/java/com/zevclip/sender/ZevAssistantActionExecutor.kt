package com.zevclip.sender

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.util.Locale
import kotlin.concurrent.thread

sealed interface ZevAssistantExecutionResult {
    data class Started(val message: String) : ZevAssistantExecutionResult
    data class Failed(val message: String) : ZevAssistantExecutionResult
}

class ZevAssistantActionExecutor(private val context: Context) {
    fun execute(command: ZevAssistantCommand): ZevAssistantExecutionResult = when (command) {
        is ZevAssistantCommand.Call -> call(command.target)
        is ZevAssistantCommand.SendMessage -> sendMessage(command.recipient, command.body)
        is ZevAssistantCommand.SendWhatsAppMessage -> sendWhatsAppMessage(command.recipient, command.body)
        is ZevAssistantCommand.MediaControl -> mediaControl(command.action)
        is ZevAssistantCommand.PlayMedia -> playMedia(command.query, command.appName)
        is ZevAssistantCommand.MacRemote -> macRemote(command.action)
        is ZevAssistantCommand.OpenApp -> openApp(command.appName)
        is ZevAssistantCommand.SearchWeb -> start(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, command.query),
            "Searching for ${command.query}"
        )
        is ZevAssistantCommand.Navigate -> start(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(command.destination)}")),
            "Opening directions to ${command.destination}"
        )
        is ZevAssistantCommand.SetTimer -> start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, context.getString(R.string.zev_assistant_timer_label))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Timer started"
        )
        is ZevAssistantCommand.SetAlarm -> start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, context.getString(R.string.zev_assistant_alarm_label))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            String.format(Locale.getDefault(), "Alarm set for %02d:%02d", command.hour, command.minute)
        )
        is ZevAssistantCommand.DismissAlarm -> dismissAlarm(command)
        is ZevAssistantCommand.ShowAlarms -> start(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            "Showing alarms"
        )
        is ZevAssistantCommand.Unknown -> ZevAssistantExecutionResult.Failed(
            context.getString(R.string.zev_assistant_command_not_understood)
        )
    }

    private fun dismissAlarm(command: ZevAssistantCommand.DismissAlarm): ZevAssistantExecutionResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        if (command.hour != null) {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, command.minute ?: 0)
            if (command.isPm != null) {
                intent.putExtra(AlarmClock.EXTRA_IS_PM, command.isPm)
            }
        } else {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        return start(intent, "Cancelling alarm")
    }

    private fun macRemote(action: MacRemoteAction): ZevAssistantExecutionResult {
        val appContext = context.applicationContext
        if (ZevClipPreferences.endpoint(appContext) == null ||
            ZevClipPreferences.pairingToken(appContext).isBlank()
        ) {
            return ZevAssistantExecutionResult.Failed(context.getString(R.string.remote_pairing_needed))
        }

        thread(name = "ZevAssistantMacRemote") {
            when (val result = MacRemoteSender.sendSavedEndpoint(appContext, action.remoteAction)) {
                is SendResult.Success -> Log.i(TAG, "Assistant Mac remote ${action.remoteAction} succeeded: ${result.message}")
                is SendResult.Failure -> Log.w(TAG, "Assistant Mac remote ${action.remoteAction} failed: ${result.message}")
            }
        }

        return ZevAssistantExecutionResult.Started("Sending ${action.spokenLabel} to Mac")
    }

    private fun mediaControl(action: MediaAction): ZevAssistantExecutionResult {
        val keyCode = when (action) {
            MediaAction.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        val message = when (action) {
            MediaAction.Play -> "Resuming music"
            MediaAction.Pause -> "Pausing music"
            MediaAction.Next -> "Skipping to next song"
        }
        return ZevAssistantExecutionResult.Started(message)
    }

    private fun playMedia(query: String?, appName: String?): ZevAssistantExecutionResult {
        if (query == null && appName != null) {
            return openApp(appName)
        }

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query.orEmpty())
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)

        if (appName != null) {
            val match = findLaunchableApp(appName)
                ?: return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_app_not_found, appName))
            return start(intent.setPackage(match.first), "Playing ${query.orEmpty()} in ${match.second}")
        }

        return start(Intent.createChooser(intent, "Play with"), "Choose an app to play ${query.orEmpty()}")
    }

    private fun sendWhatsAppMessage(recipient: String, body: String): ZevAssistantExecutionResult {
        val number = phoneAddress(recipient) ?: findPhoneNumberIfPermitted(recipient)
        val intents = if (number != null) {
            val digits = number.filter(Char::isDigit)
            WHATSAPP_PACKAGES.map { packageName ->
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
                ).setPackage(packageName)
            }
        } else {
            WHATSAPP_PACKAGES.map { packageName ->
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .setPackage(packageName)
                    .putExtra(Intent.EXTRA_TEXT, body)
            }
        }
        return startFirst(intents, "Opening WhatsApp for $recipient")
    }

    private fun sendMessage(recipient: String, body: String): ZevAssistantExecutionResult {
        val address = phoneAddress(recipient)
            ?: findPhoneNumberIfPermitted(recipient)
            ?: recipient
        return start(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(address)}"))
                .putExtra("sms_body", body)
                .putExtra(Intent.EXTRA_TEXT, body),
            "Opening Messages for $recipient"
        )
    }

    private fun call(target: String): ZevAssistantExecutionResult {
        val number = phoneAddress(target)
            ?: run {
                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_contacts_needed))
                }
                findPhoneNumber(target)
                    ?: return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_contact_not_found, target))
            }
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_phone_permission_needed))
        }
        return start(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")),
            "Calling $target"
        )
    }

    private fun phoneAddress(target: String): String? {
        return target.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            .takeIf { candidate -> candidate.count(Char::isDigit) >= 3 }
    }

    private fun findPhoneNumberIfPermitted(name: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return findPhoneNumber(name)
    }

    private fun findPhoneNumber(name: String): String? {
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            columns,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(columns[0])
            val numberIndex = cursor.getColumnIndexOrThrow(columns[1])
            var first: String? = null
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex)
                if (first == null) first = number
                if (cursor.getString(nameIndex).equals(name, ignoreCase = true)) return number
            }
            return first
        }
        return null
    }

    private fun openApp(requestedName: String): ZevAssistantExecutionResult {
        if (requestedName.equals("settings", ignoreCase = true)) {
            return start(Intent(Settings.ACTION_SETTINGS), "Opening Settings")
        }
        val match = findLaunchableApp(requestedName)
            ?: return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_app_not_found, requestedName))
        val intent = context.packageManager.getLaunchIntentForPackage(match.first)
            ?: return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_app_not_found, requestedName))
        return start(intent, "Opening ${match.second}")
    }

    private fun findLaunchableApp(requestedName: String): Pair<String, String>? {
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcherQuery, 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
            .sortedBy { (_, label) -> if (label.equals(requestedName, true)) 0 else 1 }
            .firstOrNull { (_, label) -> label.contains(requestedName, ignoreCase = true) }
    }

    private fun start(intent: Intent, message: String): ZevAssistantExecutionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ZevAssistantExecutionResult.Started(message)
        } catch (_: Exception) {
            ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_action_unavailable))
        }
    }

    private fun startFirst(intents: List<Intent>, message: String): ZevAssistantExecutionResult {
        for (intent in intents) {
            val result = start(intent, message)
            if (result is ZevAssistantExecutionResult.Started) return result
        }
        return ZevAssistantExecutionResult.Failed(context.getString(R.string.zev_assistant_action_unavailable))
    }

    private companion object {
        private const val TAG = "ZevAssistantActionExecutor"
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
