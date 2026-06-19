package com.zevclip.sender

import java.util.Locale

sealed interface ZevAssistantCommand {
    data class Call(val target: String) : ZevAssistantCommand
    data class SendMessage(val recipient: String, val body: String) : ZevAssistantCommand
    data class SendWhatsAppMessage(val recipient: String, val body: String) : ZevAssistantCommand
    data class MediaControl(val action: MediaAction) : ZevAssistantCommand
    data class PlayMedia(val query: String?, val appName: String?) : ZevAssistantCommand
    data class MacRemote(val action: MacRemoteAction) : ZevAssistantCommand
    data class OpenApp(val appName: String) : ZevAssistantCommand
    data class SearchWeb(val query: String) : ZevAssistantCommand
    data class Navigate(val destination: String) : ZevAssistantCommand
    data class SetTimer(val seconds: Int) : ZevAssistantCommand
    data class SetAlarm(val hour: Int, val minute: Int) : ZevAssistantCommand
    data class DismissAlarm(val hour: Int? = null, val minute: Int? = null, val isPm: Boolean? = null) : ZevAssistantCommand
    object ShowAlarms : ZevAssistantCommand
    data class Unknown(val original: String) : ZevAssistantCommand
}

enum class MediaAction {
    Play,
    Pause,
    Next
}

enum class MacRemoteAction(val remoteAction: String, val spokenLabel: String) {
    Lock("lock", "Lock"),
    SleepDisplay("sleepDisplay", "Display off"),
    Sleep("sleep", "Sleep"),
    Restart("restart", "Restart"),
    Shutdown("shutdown", "Shutdown"),
    Logout("logout", "Log out"),
    Mute("toggleMute", "Mute"),
    VolumeDown("volumeDown", "Volume down"),
    VolumeUp("volumeUp", "Volume up"),
    Previous("previousTrack", "Previous"),
    PlayPause("playPause", "Play pause"),
    Next("nextTrack", "Next")
}

object ZevAssistantCommandParser {
    private val call = Regex("^(?:please\\s+)?(?:call|dial|phone)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val whatsappToRecipient = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?:a|an)?\\s*(?:whatsapp|whats\\s+app|wa)(?:\\s+(?:text|message|msg))?\\s+to\\s+(.+?)\\s+(?:(?:saying|that\\s+says|that|with|as)\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val whatsappRecipientFirst = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?!(?:a|an|the)\\s+(?:whatsapp|whats\\s+app|wa)\\b)(.+?)\\s+(?:a|an)\\s*(?:whatsapp|whats\\s+app|wa)(?:\\s+(?:text|message|msg))?\\s+(?:(?:saying|that\\s+says|that|with|as)\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val directWhatsAppRecipientFirst = Regex("^(?:please\\s+)?(?:whatsapp|whats\\s+app|wa)\\s+(.+?)\\s+(?:saying|that\\s+says|that|with|as)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val directWhatsAppWithoutConnector = Regex("^(?:please\\s+)?(?:whatsapp|whats\\s+app|wa)\\s+(\\S+)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val messageBodyToRecipientOnWhatsApp = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?:the\\s+)?(?:(?:text|message|msg)\\s+)?(.+?)\\s+to\\s+(.+?)\\s+(?:on|in|through|via|using)\\s+(?:the\\s+)?(?:whatsapp|whats\\s+app|wa)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val tellRecipientOnWhatsApp = Regex("^(?:please\\s+)?tell\\s+(?!me\\b)(.+?)\\s+(?:on|in|through|via|using)\\s+(?:the\\s+)?(?:whatsapp|whats\\s+app|wa)(?:\\s+app)?\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val messageToRecipient = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?:a|an)?\\s*(?:text|message|sms)\\s+to\\s+(.+?)\\s+(?:(?:saying|that\\s+says|that|with|as)\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val messageRecipientFirst = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?!(?:a|an|the)\\s+(?:text|message|sms)\\b)(.+?)\\s+(?:a|an)?\\s*(?:text|message|sms)\\s+(?:(?:saying|that\\s+says|that|with|as)\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val directMessageRecipientFirst = Regex("^(?:please\\s+)?(?:text|message|sms)\\s+(.+?)\\s+(?:saying|that\\s+says|that|with|as)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val directMessageWithoutConnector = Regex("^(?:please\\s+)?(?:text|message|sms)\\s+(\\S+)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val messageBodyToRecipient = Regex("^(?:please\\s+)?(?:send|shoot)\\s+(?:the\\s+)?(?:(?:text|message|sms)\\s+)?(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val tellRecipient = Regex("^(?:please\\s+)?tell\\s+(?!me\\b)(.+?)\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val open = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val search = Regex("^(?:please\\s+)?(?:search(?:\\s+the\\s+web)?(?:\\s+for)?|google)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val navigate = Regex("^(?:please\\s+)?(?:navigate|directions|drive|go)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val mediaControl = Regex("^(?:please\\s+)?(?:(?:play|start|resume|continue)\\s+(?:the\\s+)?(?:music|song|track|audio|playback)|(?:pause|stop)\\s+(?:the\\s+)?(?:music|song|track|audio|playback)|(?:next|skip)(?:\\s+(?:song|track|music))?|skip\\s+to\\s+(?:the\\s+)?next(?:\\s+(?:song|track))?)$", RegexOption.IGNORE_CASE)
    private val playMedia = Regex("^(?:please\\s+)?(?:play|put\\s+on|listen\\s+to|start\\s+playing)\\s+(.+?)(?:\\s+(?:on|in|through|using|with)\\s+(.+?)(?:\\s+app)?)?$", RegexOption.IGNORE_CASE)
    private val unsupportedStopwatch = Regex("\\bstop\\s*watch\\b", RegexOption.IGNORE_CASE)
    private val macContext = Regex("\\b(?:mac|macbook|computer|laptop|desktop|zev\\s*link|zevlink|remote)\\b", RegexOption.IGNORE_CASE)
    private val timer = Regex("^(?:please\\s+)?(?:set|start)\\s+(?:a\\s+)?timer(?:\\s+for)?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val setAlarm = Regex("^(?:please\\s+)?(?:(?:set|create|add|schedule)\\s+(?:an?\\s+)?alarm(?:\\s+(?:for|at|to))?|(?:wake|get)\\s+me\\s+up(?:\\s+(?:at|for))?)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val showAlarms = Regex("^(?:please\\s+)?(?:show|open|display|list|view)\\s+(?:my\\s+)?alarms?$", RegexOption.IGNORE_CASE)
    private val dismissAlarm = Regex("^(?:please\\s+)?(?:cancel|dismiss|delete|remove|turn\\s+off|switch\\s+off|disable|clear|stop)\\s+(?:(?:my|the|next|upcoming)\\s+)?alarms?(?:\\s+(?:for|at)\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val clockTime = Regex("^(\\d{1,2})(?:(?::|\\s+)(\\d{1,2}))?\\s*(a\\s*m|p\\s*m|a\\.m\\.?|p\\.m\\.?|am|pm)?$", RegexOption.IGNORE_CASE)
    private val durationPart = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)", RegexOption.IGNORE_CASE)

    fun parse(input: String): ZevAssistantCommand {
        val normalized = input.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return ZevAssistantCommand.Unknown(input)
        if (unsupportedStopwatch.containsMatchIn(normalized)) return ZevAssistantCommand.Unknown(input.trim())

        parseMacRemote(normalized)?.let { return it }
        parseWhatsAppMessage(normalized)?.let { return it }
        parseMessage(normalized)?.let { return it }
        parseMedia(normalized)?.let { return it }
        parseAlarm(normalized)?.let { return it }
        call.matchEntire(normalized)?.groupValues?.get(1)?.trim()?.let { return ZevAssistantCommand.Call(it) }
        timer.matchEntire(normalized)?.groupValues?.get(1)?.let { value ->
            parseDuration(value)?.let { return ZevAssistantCommand.SetTimer(it) }
        }
        navigate.matchEntire(normalized)?.groupValues?.get(1)?.trim()?.let { return ZevAssistantCommand.Navigate(it) }
        search.matchEntire(normalized)?.groupValues?.get(1)?.trim()?.let { return ZevAssistantCommand.SearchWeb(it) }
        open.matchEntire(normalized)?.groupValues?.get(1)?.trim()?.let { return ZevAssistantCommand.OpenApp(it) }
        return ZevAssistantCommand.Unknown(input.trim())
    }

    private fun parseMacRemote(input: String): ZevAssistantCommand.MacRemote? {
        val lower = input.lowercase(Locale.US)
        val compact = lower
            .replace(Regex("\\b(?:please|can you|could you|would you|hey zev|zev)\\b"), " ")
            .replace(Regex("\\b(?:my|the|this|that|a)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val hasMacContext = macContext.containsMatchIn(lower)

        fun remote(action: MacRemoteAction) = ZevAssistantCommand.MacRemote(action)
        fun containsAny(vararg patterns: String): Boolean {
            return patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(lower) }
        }
        fun matchesAny(vararg patterns: String): Boolean {
            return patterns.any { Regex(it, RegexOption.IGNORE_CASE).matches(compact) }
        }

        if (matchesAny(
                "(?:turn|switch|put) off (?:mac |computer |laptop |desktop )?(?:display|screen)",
                "(?:mac |computer |laptop |desktop )?(?:display|screen) off",
                "(?:sleep|turn off) (?:mac |computer |laptop |desktop )?(?:display|screen)"
            )
        ) return remote(MacRemoteAction.SleepDisplay)

        if (matchesAny("(?:lock|secure)(?: (?:mac|macbook|computer|laptop|desktop))?", "(?:mac|macbook|computer|laptop|desktop) lock")) {
            return remote(MacRemoteAction.Lock)
        }
        if (matchesAny("(?:sleep|put to sleep)(?: (?:mac|macbook|computer|laptop|desktop))?", "(?:put )?(?:mac|macbook|computer|laptop|desktop) to sleep")) {
            return remote(MacRemoteAction.Sleep)
        }
        if (matchesAny("(?:restart|reboot)(?: (?:mac|macbook|computer|laptop|desktop))?", "(?:restart|reboot) (?:the )?(?:mac|macbook|computer|laptop|desktop)")) {
            return remote(MacRemoteAction.Restart)
        }
        if (matchesAny("(?:shutdown|shut down|power off|turn off)(?: (?:mac|macbook|computer|laptop|desktop))?", "(?:shutdown|shut down|power off|turn off) (?:the )?(?:mac|macbook|computer|laptop|desktop)")) {
            return remote(MacRemoteAction.Shutdown)
        }
        if (matchesAny("(?:log out|logout|sign out|sign off)(?: (?:of|from))?(?: (?:mac|macbook|computer|laptop|desktop))?", "(?:log|sign) (?:the )?(?:mac|macbook|computer|laptop|desktop) out")) {
            return remote(MacRemoteAction.Logout)
        }

        if (matchesAny("(?:mute|unmute)(?: (?:mac|macbook|computer|laptop|desktop|audio|sound|volume))?") ||
            (hasMacContext && containsAny("\\b(?:mute|unmute|toggle mute)\\b"))
        ) return remote(MacRemoteAction.Mute)

        if (matchesAny(
                "(?:vol|volume) (?:-|minus|down|lower|decrease)",
                "(?:mac|macbook|computer|laptop|desktop) (?:vol|volume) (?:-|minus|down|lower|decrease)",
                "(?:lower|decrease|turn down|volume down)(?: (?:mac|computer|laptop|desktop|volume))?"
            ) ||
            (hasMacContext && containsAny("\\b(?:volume down|vol down|lower volume|decrease volume|turn down volume)\\b"))
        ) return remote(MacRemoteAction.VolumeDown)

        if (matchesAny(
                "(?:vol|volume) (?:\\+|plus|up|raise|increase)",
                "(?:mac|macbook|computer|laptop|desktop) (?:vol|volume) (?:\\+|plus|up|raise|increase)",
                "(?:raise|increase|turn up|volume up)(?: (?:mac|computer|laptop|desktop|volume))?"
            ) ||
            (hasMacContext && containsAny("\\b(?:volume up|vol up|raise volume|increase volume|turn up volume)\\b"))
        ) return remote(MacRemoteAction.VolumeUp)

        if (matchesAny("(?:previous|prev|back)") ||
            (hasMacContext && containsAny("\\b(?:previous|prev|back|skip back|last track|last song)\\b"))
        ) return remote(MacRemoteAction.Previous)

        if (matchesAny("(?:play pause|play/pause|pause play|toggle playback)") ||
            (hasMacContext && containsAny("\\b(?:play pause|play/pause|pause play|toggle playback|pause music|play music|resume music)\\b"))
        ) return remote(MacRemoteAction.PlayPause)

        if (matchesAny("(?:next|skip)") ||
            (hasMacContext && containsAny("\\b(?:next|skip|skip forward|next track|next song)\\b"))
        ) return remote(MacRemoteAction.Next)

        return null
    }

    private fun parseAlarm(input: String): ZevAssistantCommand? {
        if (showAlarms.matches(input)) return ZevAssistantCommand.ShowAlarms
        dismissAlarm.matchEntire(input)?.let { match ->
            val value = match.groupValues.getOrNull(1).orEmpty()
            if (value.isBlank()) return ZevAssistantCommand.DismissAlarm()
            parseClockTime(value)?.let { time ->
                return ZevAssistantCommand.DismissAlarm(time.hour, time.minute, time.isPm)
            }
        }
        setAlarm.matchEntire(input)?.groupValues?.get(1)?.let { value ->
            parseClockTime(value)?.let { time -> return ZevAssistantCommand.SetAlarm(time.hour, time.minute) }
        }
        return null
    }

    private fun parseMedia(input: String): ZevAssistantCommand? {
        mediaControl.matchEntire(input)?.let {
            val lower = input.lowercase(Locale.US)
            return when {
                lower.startsWith("pause") || lower.startsWith("please pause") || lower.startsWith("stop") || lower.startsWith("please stop") -> {
                    ZevAssistantCommand.MediaControl(MediaAction.Pause)
                }
                lower.startsWith("next") || lower.startsWith("please next") || lower.startsWith("skip") || lower.startsWith("please skip") -> {
                    ZevAssistantCommand.MediaControl(MediaAction.Next)
                }
                else -> ZevAssistantCommand.MediaControl(MediaAction.Play)
            }
        }

        playMedia.matchEntire(input)?.let { match ->
            val query = cleanMediaQuery(match.groupValues[1])
            val appName = cleanMessagePart(match.groupValues[2]).ifBlank { null }
            if (query.lowercase(Locale.US) in genericMediaTargets) {
                return if (appName == null) {
                    ZevAssistantCommand.MediaControl(MediaAction.Play)
                } else {
                    ZevAssistantCommand.PlayMedia(query = null, appName = appName)
                }
            }
            if (query.isBlank()) return null
            return ZevAssistantCommand.PlayMedia(query = query, appName = appName)
        }
        return null
    }

    private fun cleanMediaQuery(value: String): String {
        return cleanMessagePart(value)
            .replace(Regex("^(?:the\\s+)?(?:song|track|music)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun parseWhatsAppMessage(input: String): ZevAssistantCommand.SendWhatsAppMessage? {
        whatsappToRecipient.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[1], match.groupValues[2])
        }
        whatsappRecipientFirst.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[1], match.groupValues[2])
        }
        directWhatsAppRecipientFirst.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[1], match.groupValues[2])
        }
        tellRecipientOnWhatsApp.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[1], match.groupValues[2])
        }
        messageBodyToRecipientOnWhatsApp.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[2], match.groupValues[1])
        }
        directWhatsAppWithoutConnector.matchEntire(input)?.let { match ->
            return whatsappCommand(match.groupValues[1], match.groupValues[2])
        }
        return null
    }

    private fun parseMessage(input: String): ZevAssistantCommand.SendMessage? {
        messageToRecipient.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[1], match.groupValues[2])
        }
        messageRecipientFirst.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[1], match.groupValues[2])
        }
        directMessageRecipientFirst.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[1], match.groupValues[2])
        }
        tellRecipient.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[1], match.groupValues[2])
        }
        messageBodyToRecipient.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[2], match.groupValues[1])
        }
        directMessageWithoutConnector.matchEntire(input)?.let { match ->
            return messageCommand(match.groupValues[1], match.groupValues[2])
        }
        return null
    }

    private fun whatsappCommand(recipient: String, body: String): ZevAssistantCommand.SendWhatsAppMessage? {
        val cleanRecipient = stripMessagingAppSuffix(cleanMessagePart(recipient).removePrefix("to ").trim())
        val cleanBody = cleanMessagePart(body)
        if (cleanRecipient.isBlank() || cleanBody.isBlank()) return null
        if (cleanRecipient.lowercase(Locale.US) in emptyRecipients) return null
        if (cleanBody.lowercase(Locale.US) in emptyMessageBodies) return null
        return ZevAssistantCommand.SendWhatsAppMessage(cleanRecipient, cleanBody)
    }

    private fun messageCommand(recipient: String, body: String): ZevAssistantCommand.SendMessage? {
        val cleanRecipient = stripMessagingAppSuffix(cleanMessagePart(recipient).removePrefix("to ").trim())
        val cleanBody = cleanMessagePart(body)
        if (cleanRecipient.isBlank() || cleanBody.isBlank()) return null
        if (cleanRecipient.lowercase(Locale.US) in emptyRecipients) return null
        if (cleanBody.lowercase(Locale.US) in emptyMessageBodies) return null
        return ZevAssistantCommand.SendMessage(cleanRecipient, cleanBody)
    }

    private fun stripMessagingAppSuffix(value: String): String {
        return value.replace(
            Regex("\\s+(?:in|on|through|via|using)\\s+(?:the\\s+)?(?:messages?|sms|text|whatsapp|whats\\s+app|wa)\\s*(?:app)?$", RegexOption.IGNORE_CASE),
            ""
        ).trim()
    }

    private fun cleanMessagePart(value: String): String {
        return value
            .trim()
            .trim('"', '\'', ' ', ':', ',', '.', '!', '?')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val emptyMessageBodies = setOf(
        "message",
        "a message",
        "the message",
        "text",
        "a text",
        "the text",
        "sms",
        "an sms",
        "the sms",
        "whatsapp",
        "a whatsapp",
        "the whatsapp",
        "whats app",
        "a whats app",
        "the whats app",
        "wa"
    )
    private val emptyRecipients = setOf("message", "text", "sms", "whatsapp", "whats app", "wa")
    private val genericMediaTargets = setOf("music", "the music", "song", "the song", "songs", "tracks", "audio", "playback")

    private fun parseDuration(value: String): Int? {
        val matches = durationPart.findAll(value).toList()
        if (matches.isEmpty()) return value.trim().toIntOrNull()?.takeIf { it > 0 }?.times(60)
        val unmatched = durationPart.replace(value, "")
            .replace(Regex("[ ,+and]+", RegexOption.IGNORE_CASE), "")
        if (unmatched.isNotBlank()) return null

        val total = matches.sumOf { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            when (match.groupValues[2].lowercase(Locale.US).first()) {
                'h' -> amount * 3600
                'm' -> amount * 60
                else -> amount
            }
        }
        return total.takeIf { it in 1..86_400 }
    }

    private fun parseClockTime(value: String): ClockTime? {
        val cleaned = cleanMessagePart(value)
            .replace(Regex("\\bo['’]?clock\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\ba\\s*\\.\\s*m\\s*\\.?\\b", RegexOption.IGNORE_CASE), "am")
            .replace(Regex("\\bp\\s*\\.\\s*m\\s*\\.?\\b", RegexOption.IGNORE_CASE), "pm")
            .replace(Regex("\\bin\\s+the\\s+(morning|evening|afternoon|night)\\b", RegexOption.IGNORE_CASE)) { match ->
                when (match.groupValues[1].lowercase(Locale.US)) {
                    "morning" -> "am"
                    else -> "pm"
                }
            }
            .replace(Regex("\\s+"), " ")
            .trim()

        val match = clockTime.matchEntire(cleaned)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
            val suffix = match.groupValues[3].replace(" ", "").replace(".", "").lowercase(Locale.US)
            return normalizeClockTime(hour, minute, suffix)
        }

        return parseWordClockTime(cleaned)
    }

    private fun normalizeClockTime(rawHour: Int, minute: Int, suffix: String): ClockTime? {
        var hour = rawHour
        if (minute !in 0..59) return null
        var isPm: Boolean? = null
        if (suffix.isNotBlank()) {
            if (hour !in 1..12) return null
            isPm = suffix == "pm"
            if (suffix == "pm" && hour != 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
        } else if (hour !in 0..23) {
            return null
        }
        return ClockTime(hour, minute, isPm)
    }

    private fun parseWordClockTime(value: String): ClockTime? {
        val words = value.lowercase(Locale.US).replace("-", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val suffix = when {
            words.takeLast(2) == listOf("a", "m") -> "am"
            words.takeLast(2) == listOf("p", "m") -> "pm"
            words.last() in setOf("am", "pm") -> words.last()
            else -> ""
        }
        val timeWords = when {
            words.takeLast(2) == listOf("a", "m") || words.takeLast(2) == listOf("p", "m") -> words.dropLast(2)
            suffix.isNotBlank() -> words.dropLast(1)
            else -> words
        }.filter { it != "at" && it != "for" }
        if (timeWords.isEmpty()) return null

        val hour = numberWords[timeWords.first()] ?: return null
        val minute = when {
            timeWords.size == 1 -> 0
            timeWords.drop(1).joinToString(" ") == "o five" -> 5
            timeWords.drop(1).joinToString(" ") == "oh five" -> 5
            else -> parseMinuteWords(timeWords.drop(1)) ?: return null
        }
        return normalizeClockTime(hour, minute, suffix)
    }

    private fun parseMinuteWords(words: List<String>): Int? {
        if (words.isEmpty()) return 0
        val phrase = words.joinToString(" ")
        minuteWords[phrase]?.let { return it }
        if (words.size == 1) return numberWords[words.first()]
        val tens = minuteTens[words.first()] ?: return null
        val ones = numberWords[words[1]] ?: return null
        return (tens + ones).takeIf { it in 0..59 }
    }

    private data class ClockTime(val hour: Int, val minute: Int, val isPm: Boolean?)

    private val numberWords = mapOf(
        "zero" to 0,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19
    )
    private val minuteTens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val minuteWords = numberWords + minuteTens + mapOf("oh" to 0, "o" to 0)
}
