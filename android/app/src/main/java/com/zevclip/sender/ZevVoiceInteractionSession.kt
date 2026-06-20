package com.zevclip.sender

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class ZevVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val audioHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var recognizerCueMutedByUs = false
    private val restoreRecognizerAudio = Runnable {
        if (!recognizerCueMutedByUs) return@Runnable
        setRecognizerCueMuted(false)
        recognizerCueMutedByUs = false
        Log.i(TAG, "Recognizer cue output restored")
    }
    private var recognizer: SpeechRecognizer? = null
    private var visible = false
    private var listening = false
    private var speechStarted = false
    private var waitingForFinalResult = false
    private var lastSpeechActivityAt = 0L
    private var lastHeardText: String? = null
    private lateinit var sheet: View
    private lateinit var ambientOutline: AssistantAmbientOutlineView
    private lateinit var indicator: ListeningIndicatorView
    private lateinit var retryButton: TextView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private var assistedUrl: String? = null
    private var pendingConfirmationAction: String? = null
    private var pendingConfirmationUntil = 0L
    private val noSpeechTimeout = Runnable {
        if (!visible || !listening || speechStarted) return@Runnable
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        scheduleRecognizerAudioRestore()
        showFailure(context.getString(R.string.zev_assistant_did_not_hear))
    }
    private val resultTimeout = Runnable {
        if (!visible || (!listening && !waitingForFinalResult)) return@Runnable
        val fallback = lastHeardText?.takeIf { it.isNotBlank() }
        if (fallback != null) {
            Log.i(TAG, "Executing fallback command after recognizer result timeout: $fallback")
            execute(fallback)
        } else {
            listening = false
            waitingForFinalResult = false
            scheduleRecognizerAudioRestore()
            showFailure(context.getString(R.string.zev_assistant_did_not_hear))
        }
    }
    private val speechSilenceTimeout = object : Runnable {
        override fun run() {
            if (!visible || !listening || !speechStarted || waitingForFinalResult) return
            val idleMs = SystemClock.uptimeMillis() - lastSpeechActivityAt
            if (idleMs >= SPEECH_SILENCE_TIMEOUT_MILLIS) {
                Log.i(TAG, "Speech idle for ${idleMs}ms; stopping recognizer for final result")
                stopRecognizerForFinalResult()
            } else {
                handler.postDelayed(this, SPEECH_SILENCE_TIMEOUT_MILLIS - idleMs)
            }
        }
    }
    private val maxSpeechTimeout = Runnable {
        if (!visible || !listening || !speechStarted || waitingForFinalResult) return@Runnable
        Log.i(TAG, "Max speech duration reached; stopping recognizer for final result")
        stopRecognizerForFinalResult()
    }

    override fun onCreate() {
        super.onCreate()
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM
                windowAnimations = 0
            }
        }
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        ambientOutline = AssistantAmbientOutlineView(context).apply { visibility = View.GONE }
        root.addView(ambientOutline, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            elevation = dp(14).toFloat()
            background = roundedDrawable(SURFACE, 30, OUTLINE, 1)
            alpha = 0f
            scaleX = 0.975f
            scaleY = 0.975f
            translationY = dp(18).toFloat()

            addView(View(context).apply {
                background = roundedDrawable(HANDLE, 4)
            }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(FrameLayout(context).apply {
                    indicator = ListeningIndicatorView(context).apply {
                        contentDescription = context.getString(R.string.zev_assistant_listening)
                    }
                    addView(indicator, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(64), dp(50)))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(TextView(context).apply {
                        text = context.getString(R.string.zev_assistant_title)
                        textSize = 21f
                        setTextColor(TEXT)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    statusText = TextView(context).apply {
                        text = context.getString(R.string.zev_assistant_listening)
                        textSize = 15f
                        setTextColor(PRIMARY)
                        setPadding(0, dp(2), 0, 0)
                    }
                    addView(statusText)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                retryButton = TextView(context).apply {
                    text = context.getString(R.string.zev_assistant_retry)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    background = roundedDrawable(PRIMARY, 18)
                    setOnClickListener { startListening() }
                }
                addView(retryButton, LinearLayout.LayoutParams(dp(64), dp(36)).apply {
                    leftMargin = dp(10)
                    gravity = Gravity.TOP
                })
            }, matchWidth())

            transcriptText = TextView(context).apply {
                text = ""
                textSize = 17f
                setTextColor(MUTED)
                maxLines = 2
                setPadding(0, dp(10), 0, dp(10))
                visibility = View.GONE
            }
            addView(transcriptText, matchWidth())

            addView(controlLabel(R.string.zev_assistant_mac_controls), matchWidth())
            addView(iconRow(
                control(AssistantIcon.LOCK, R.string.remote_lock) { runRemoteAction("lock", R.string.remote_lock) },
                control(AssistantIcon.SLEEP, R.string.remote_sleep) { runRemoteAction("sleep", R.string.remote_sleep) },
                control(AssistantIcon.RESTART, R.string.remote_restart) { confirmRemoteAction("restart", R.string.remote_restart) }
            ), matchWidth(topMargin = CONTROL_GAP_DP))

            addView(iconRow(
                control(AssistantIcon.POWER, R.string.remote_shutdown) { confirmRemoteAction("shutdown", R.string.remote_shutdown) },
                control(AssistantIcon.LOGOUT, R.string.remote_logout) { confirmRemoteAction("logout", R.string.remote_logout) },
                control(AssistantIcon.LINK, R.string.remote_open_url) { openSelectedUrlOnMac() }
            ), matchWidth(topMargin = CONTROL_GAP_DP))

            addView(iconRow(
                control(AssistantIcon.MUTE, R.string.remote_mute) { runRemoteAction("toggleMute", R.string.remote_mute) },
                control(AssistantIcon.VOLUME_DOWN, R.string.remote_volume_down) { runRemoteAction("volumeDown", R.string.remote_volume_down) },
                control(AssistantIcon.VOLUME_UP, R.string.remote_volume_up) { runRemoteAction("volumeUp", R.string.remote_volume_up) }
            ), matchWidth(topMargin = CONTROL_GAP_DP))

            addView(iconRow(
                control(AssistantIcon.PREVIOUS, R.string.remote_previous) { runRemoteAction("previousTrack", R.string.remote_previous) },
                control(AssistantIcon.PLAY_PAUSE, R.string.remote_play_pause) { runRemoteAction("playPause", R.string.remote_play_pause) },
                control(AssistantIcon.NEXT, R.string.remote_next) { runRemoteAction("nextTrack", R.string.remote_next) }
            ), matchWidth(topMargin = CONTROL_GAP_DP))

            addView(controlLabel(R.string.zev_assistant_airplay_controls), matchWidth(topMargin = SECTION_GAP_DP))
            addView(iconRow(
                control(AssistantIcon.SCREEN, R.string.start_screen_mirror) {
                    toggleAirPlayScreenMirror()
                },
                control(AssistantIcon.AUDIO, R.string.start_airplay_audio) {
                    toggleAirPlayAudio()
                },
                control(AssistantIcon.BROADCAST, R.string.airplay_broadcast_audio) {
                    launchAirPlay(MainActivity.ACTION_OPEN_AIRPLAY_BROADCAST)
                }
            ), matchWidth(topMargin = CONTROL_GAP_DP))
        }
        ambientOutline.track(sheet)

        root.addView(sheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(18)
        })
        return root
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::sheet.isInitialized || sheet.width == 0) return
        val location = IntArray(2)
        sheet.getLocationInWindow(location)
        outInsets.contentInsets.top = location[1]
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        visible = true
        prepareAssistantEntrance()
        sheet.post {
            if (!visible) return@post
            ambientOutline.start()
            sheet.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ASSISTANT_ENTER_ANIMATION_MILLIS)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
        val initialCommand = args?.getString(ZevVoiceInteractionService.EXTRA_INITIAL_COMMAND).orEmpty()
        if (initialCommand.isNotBlank()) {
            transcriptText.visibility = View.VISIBLE
            transcriptText.text = initialCommand
            handler.post { execute(initialCommand) }
        } else {
            handler.post { startListening() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        assistedUrl = findSelectedUrl(structure)
            ?: extractHttpUrl(content?.webUri?.toString())
    }

    override fun onHide() {
        visible = false
        val wasListening = listening
        listening = false
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        lastHeardText = null
        assistedUrl = null
        pendingConfirmationAction = null
        indicator.setActive(false)
        sheet.animate().cancel()
        ambientOutline.pause()
        handler.removeCallbacksAndMessages(null)
        if (wasListening) recognizer?.cancel()
        scheduleRecognizerAudioRestore()
        super.onHide()
    }

    override fun onDestroy() {
        visible = false
        listening = false
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        lastHeardText = null
        handler.removeCallbacksAndMessages(null)
        if (::ambientOutline.isInitialized) {
            ambientOutline.release()
        }
        recognizer?.destroy()
        recognizer = null
        scheduleRecognizerAudioRestore()
        super.onDestroy()
    }

    private fun prepareAssistantEntrance() {
        sheet.animate().cancel()
        sheet.alpha = 0f
        sheet.scaleX = 0.975f
        sheet.scaleY = 0.975f
        sheet.translationY = dp(18).toFloat()
        ambientOutline.pause()
    }

    private fun startListening() {
        if (!visible) return
        statusText.text = context.getString(R.string.zev_assistant_listening)
        statusText.setTextColor(PRIMARY)
        transcriptText.text = ""
        transcriptText.visibility = View.GONE
        transcriptText.setTextColor(MUTED)
        retryButton.visibility = View.GONE
        indicator.visibility = View.VISIBLE
        indicator.setState(PRIMARY, true)
        listening = true
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        handler.postDelayed(noSpeechTimeout, NO_SPEECH_TIMEOUT_MILLIS)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            showFailure(context.getString(R.string.zev_assistant_speech_unavailable))
            return
        }
        if (recognizer == null) {
            recognizer = createRecognizer().also { it.setRecognitionListener(this) }
        }
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MILLIS)
        })
    }

    private fun createRecognizer(): SpeechRecognizer {
        Log.i(TAG, "Using default system speech recognizer")
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun suppressRecognizerAudio() {
        audioHandler.removeCallbacks(restoreRecognizerAudio)
        if (recognizerCueMutedByUs || audioManager.isStreamMute(RECOGNIZER_CUE_STREAM)) return
        setRecognizerCueMuted(true)
        recognizerCueMutedByUs = true
        Log.i(TAG, "Recognizer cue output suppressed: ${audioManager.isStreamMute(RECOGNIZER_CUE_STREAM)}")
    }

    @Suppress("DEPRECATION")
    private fun setRecognizerCueMuted(muted: Boolean) {
        audioManager.setStreamMute(RECOGNIZER_CUE_STREAM, muted)
    }

    private fun scheduleRecognizerAudioRestore() {
        audioHandler.removeCallbacks(restoreRecognizerAudio)
        if (recognizerCueMutedByUs) {
            audioHandler.postDelayed(restoreRecognizerAudio, RECOGNIZER_END_CUE_MILLIS)
        }
    }

    private fun execute(text: String) {
        listening = false
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        scheduleRecognizerAudioRestore()
        indicator.setState(WORKING, true)
        statusText.text = context.getString(R.string.zev_assistant_working)
        statusText.setTextColor(WORKING)
        transcriptText.visibility = View.VISIBLE
        transcriptText.text = text
        transcriptText.setTextColor(TEXT)
        when (val result = ZevAssistantActionExecutor(context).execute(ZevAssistantCommandParser.parse(text))) {
            is ZevAssistantExecutionResult.Started -> {
                indicator.setState(SUCCESS, false)
                statusText.text = result.message
                statusText.setTextColor(SUCCESS)
                handler.postDelayed({ finish() }, 700L)
            }
            is ZevAssistantExecutionResult.Failed -> showFailure(result.message, replaceTranscript = false)
        }
    }

    private fun stopListeningForControl() {
        if (!listening) return
        listening = false
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        recognizer?.cancel()
        scheduleRecognizerAudioRestore()
        indicator.setActive(false)
    }

    private fun confirmRemoteAction(action: String, labelRes: Int) {
        stopListeningForControl()
        val now = SystemClock.uptimeMillis()
        if (pendingConfirmationAction == action && now < pendingConfirmationUntil) {
            pendingConfirmationAction = null
            runRemoteAction(action, labelRes)
            return
        }
        pendingConfirmationAction = action
        pendingConfirmationUntil = now + CONFIRMATION_TIMEOUT_MILLIS
        indicator.setState(ERROR, false)
        statusText.text = context.getString(R.string.zev_assistant_tap_again, context.getString(labelRes))
        statusText.setTextColor(ERROR)
    }

    private fun runRemoteAction(
        action: String,
        labelRes: Int,
        url: String? = null,
        finishOnSuccess: Boolean = false
    ) {
        stopListeningForControl()
        pendingConfirmationAction = null
        retryButton.visibility = View.GONE
        indicator.setState(WORKING, true)
        statusText.text = context.getString(R.string.remote_sending, context.getString(labelRes))
        statusText.setTextColor(WORKING)
        thread(name = "ZevAssistantMacRemote", isDaemon = true) {
            val result = MacRemoteSender.sendSavedEndpoint(context, action, url)
            handler.post {
                if (!visible) return@post
                when (result) {
                    is SendResult.Success -> {
                        indicator.setState(SUCCESS, false)
                        statusText.text = result.message
                        statusText.setTextColor(SUCCESS)
                        if (finishOnSuccess) finish()
                    }
                    is SendResult.Failure -> showFailure(result.message, replaceTranscript = false)
                }
            }
        }
    }

    private fun openSelectedUrlOnMac() {
        val url = assistedUrl ?: clipboardUrl()
        if (url == null) {
            stopListeningForControl()
            showFailure(context.getString(R.string.zev_assistant_select_url), replaceTranscript = false)
            return
        }
        runRemoteAction("openURL", R.string.remote_open_url, url, finishOnSuccess = true)
    }

    private fun findSelectedUrl(structure: AssistStructure?): String? {
        if (structure == null) return null
        repeat(structure.windowNodeCount) { windowIndex ->
            findSelectedUrl(structure.getWindowNodeAt(windowIndex).rootViewNode)?.let { return it }
        }
        return null
    }

    private fun findSelectedUrl(node: AssistStructure.ViewNode): String? {
        val text = node.text
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (text != null && start >= 0 && end > start && end <= text.length) {
            extractHttpUrl(text.subSequence(start, end).toString())?.let { return it }
        }
        repeat(node.childCount) { childIndex ->
            findSelectedUrl(node.getChildAt(childIndex))?.let { return it }
        }
        return null
    }

    private fun clipboardUrl(): String? {
        return runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            extractHttpUrl(clip.getItemAt(0).coerceToText(context)?.toString())
        }.getOrNull()
    }

    private fun extractHttpUrl(value: String?): String? {
        val candidate = HTTP_URL.find(value.orEmpty())?.value
            ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            ?: return null
        val uri = android.net.Uri.parse(candidate)
        return candidate.takeIf {
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        }
    }

    private fun launchAirPlay(action: String) {
        stopListeningForControl()
        startAssistantActivitySafely(Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun toggleAirPlayAudio() {
        stopListeningForControl()
        if (ZevClipPreferences.isAirPlayStreaming(context)) {
            AirPlayAudioCaptureService.stop(context)
            ZevClipPreferences.setAirPlayTestStatus(
                context,
                context.getString(R.string.airplay_streaming_stopped)
            )
            ZevClipStatusNotification.update(context)
        } else {
            startAssistantActivitySafely(Intent(context, AirPlayCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
        }
        finish()
    }

    private fun toggleAirPlayScreenMirror() {
        stopListeningForControl()
        if (ZevClipPreferences.isAirPlayScreenMirroring(context)) {
            AirPlayScreenMirrorService.stop(context)
            ZevClipPreferences.setAirPlayTestStatus(
                context,
                context.getString(R.string.airplay_screen_mirror_stopped)
            )
            ZevClipStatusNotification.update(context)
        } else {
            startAssistantActivitySafely(Intent(context, AirPlayScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
        }
        finish()
    }

    private fun startAssistantActivitySafely(intent: Intent) {
        runCatching {
            startVoiceActivity(intent)
        }.onFailure { error ->
            Log.w(TAG, "Voice activity launch failed; falling back to application context", error)
            context.startActivity(intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun controlLabel(stringRes: Int) = TextView(context).apply {
        text = context.getString(stringRes)
        textSize = 12f
        setTextColor(MUTED)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun iconRow(vararg controls: ImageButton) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        require(controls.size == 3)
        controls.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, dp(CONTROL_HEIGHT_DP), 1f).apply {
                if (index > 0) leftMargin = dp(CONTROL_GAP_DP)
            })
        }
    }

    private fun control(icon: AssistantIcon, labelRes: Int, onClick: () -> Unit) = ImageButton(context).apply {
        contentDescription = context.getString(labelRes)
        setImageDrawable(AssistantIconDrawable(icon, PRIMARY, dp(23)))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = roundedDrawable(CHIP, CONTROL_CORNER_RADIUS_DP, OUTLINE, 1)
        setOnClickListener { onClick() }
    }

    private fun showFailure(message: String, replaceTranscript: Boolean = true) {
        if (!visible) return
        scheduleRecognizerAudioRestore()
        listening = false
        speechStarted = false
        waitingForFinalResult = false
        lastSpeechActivityAt = 0L
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        indicator.setState(ERROR, false)
        indicator.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(ERROR)
        if (replaceTranscript) {
            transcriptText.text = ""
            transcriptText.visibility = View.GONE
            transcriptText.setTextColor(MUTED)
        }
    }

    override fun onResults(results: Bundle?) {
        if (!listening && !waitingForFinalResult) return
        listening = false
        waitingForFinalResult = false
        scheduleRecognizerAudioRestore()
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (text.isNullOrBlank()) {
            showFailure(context.getString(R.string.zev_assistant_did_not_hear))
        }
        else {
            Log.i(TAG, "Recognized command: $text")
            execute(text)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
            markSpeechActivity()
            lastHeardText = it
            transcriptText.visibility = View.VISIBLE
            transcriptText.text = it
            transcriptText.setTextColor(TEXT)
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        indicator.setLevel(rmsdB)
        if (listening && speechStarted && !waitingForFinalResult && rmsdB >= ACTIVE_SPEECH_RMS_DB) {
            markSpeechActivity()
        }
    }
    override fun onReadyForSpeech(params: Bundle?) = suppressRecognizerAudio()
    override fun onBeginningOfSpeech() {
        speechStarted = true
        markSpeechActivity()
        handler.removeCallbacks(noSpeechTimeout)
        handler.postDelayed(maxSpeechTimeout, MAX_SPEECH_INPUT_MILLIS)
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (listening) {
            waitingForFinalResult = true
            handler.removeCallbacks(speechSilenceTimeout)
            handler.removeCallbacks(maxSpeechTimeout)
            handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MILLIS)
        }
    }
    override fun onError(error: Int) {
        if (!listening && !waitingForFinalResult) return
        listening = false
        waitingForFinalResult = false
        scheduleRecognizerAudioRestore()
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        val fallback = lastHeardText?.takeIf { it.isNotBlank() }
        if (speechStarted && fallback != null && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            Log.i(TAG, "Executing fallback command after recognizer error $error: $fallback")
            execute(fallback)
        } else {
            Log.w(TAG, "Speech recognizer failed with error $error")
            showFailure(context.getString(R.string.zev_assistant_did_not_hear))
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun markSpeechActivity() {
        lastSpeechActivityAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(speechSilenceTimeout)
        handler.postDelayed(speechSilenceTimeout, SPEECH_SILENCE_TIMEOUT_MILLIS)
    }

    private fun stopRecognizerForFinalResult() {
        if (!listening || waitingForFinalResult) return
        waitingForFinalResult = true
        handler.removeCallbacks(speechSilenceTimeout)
        handler.removeCallbacks(maxSpeechTimeout)
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        recognizer?.stopListening()
        handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MILLIS)
    }

    private fun roundedDrawable(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null && strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = dp(topMargin) }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class ListeningIndicatorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var active = true
        private var level = 0f
        private var accent = PRIMARY

        fun setLevel(rms: Float) {
            level = ((rms + 2f) / 12f).coerceIn(0f, 1f)
        }

        fun setActive(value: Boolean) {
            active = value
            invalidate()
        }

        fun setState(color: Int, animated: Boolean) {
            accent = color
            active = animated
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = blend(Color.WHITE, accent, 0.13f)
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) * 0.48f, paint)
            val time = SystemClock.uptimeMillis() / 150f
            val barWidth = width * 0.07f
            val gap = width * 0.075f
            val centerX = width / 2f
            repeat(4) { index ->
                val phase = kotlin.math.sin(time + index * 0.9f).toFloat()
                val motion = if (active) (phase + 1f) * 0.5f else 0.1f
                val barHeight = height * (0.16f + 0.22f * motion + 0.12f * level)
                val left = centerX + (index - 1.5f) * (barWidth + gap) - barWidth / 2f
                paint.color = accent
                canvas.drawRoundRect(
                    left,
                    height / 2f - barHeight / 2f,
                    left + barWidth,
                    height / 2f + barHeight / 2f,
                    barWidth,
                    barWidth,
                    paint
                )
            }
            if (active) postInvalidateDelayed(32L)
        }

        private fun blend(base: Int, overlay: Int, amount: Float): Int = Color.rgb(
            (Color.red(base) * (1f - amount) + Color.red(overlay) * amount).toInt(),
            (Color.green(base) * (1f - amount) + Color.green(overlay) * amount).toInt(),
            (Color.blue(base) * (1f - amount) + Color.blue(overlay) * amount).toInt()
        )
    }

    private class AssistantAmbientOutlineView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val density = context.resources.displayMetrics.density
        private var startedAt = 0L
        private var running = false
        private var targetView: View? = null
        private var cachedGlow: Bitmap? = null
        private var cachedWidth = 0
        private var cachedHeight = 0
        private val cachedRect = RectF()

        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun track(view: View) {
            targetView = view
        }

        fun start() {
            startedAt = SystemClock.uptimeMillis()
            running = true
            visibility = VISIBLE
            invalidate()
        }

        fun pause() {
            running = false
            visibility = GONE
            paint.shader = null
            paint.maskFilter = null
        }

        fun release() {
            pause()
            cachedGlow?.recycle()
            cachedGlow = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val target = targetView ?: return
            if (!running || width <= 0 || height <= 0 || target.width <= 0 || target.height <= 0) {
                if (running) postInvalidateOnAnimation()
                return
            }

            val elapsed = SystemClock.uptimeMillis() - startedAt
            val appear = easeOut((elapsed / OUTLINE_APPEAR_MILLIS.toFloat()).coerceIn(0f, 1f))
            val pulse = 0.84f + 0.16f * kotlin.math.sin(elapsed / 260f).toFloat()
            val intensity = appear * pulse

            val bleedInset = 1.5f * density
            val radius = 30f * density
            rect.set(
                target.left + bleedInset,
                target.top + bleedInset,
                target.right - bleedInset,
                target.bottom - bleedInset
            )

            val glow = cachedGlowFor(width, height, rect, radius)
            paint.shader = null
            paint.maskFilter = null
            paint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
            canvas.drawBitmap(glow, 0f, 0f, paint)

            postInvalidateDelayed(66L)
        }

        private fun cachedGlowFor(width: Int, height: Int, bounds: RectF, radius: Float): Bitmap {
            cachedGlow?.let {
                if (!it.isRecycled && cachedWidth == width && cachedHeight == height && cachedRect == bounds) {
                    return it
                }
            }
            cachedGlow?.recycle()
            cachedWidth = width
            cachedHeight = height
            cachedRect.set(bounds)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val glowCanvas = Canvas(bitmap)
            paint.shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                intArrayOf(
                    Color.rgb(18, 188, 255),
                    Color.rgb(45, 110, 255),
                    Color.rgb(150, 72, 255),
                    Color.rgb(238, 62, 203),
                    Color.rgb(18, 188, 255)
                ),
                floatArrayOf(0f, 0.24f, 0.52f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
            drawGlowStroke(glowCanvas, bounds, radius, 34f * density, 82, 22f * density)
            drawGlowStroke(glowCanvas, bounds, radius, 20f * density, 126, 11f * density)
            drawGlowStroke(glowCanvas, bounds, radius, 8f * density, 196, 3.5f * density)
            drawGlowStroke(glowCanvas, bounds, radius, 3f * density, 235, 0f)
            cachedGlow = bitmap
            return bitmap
        }

        private fun drawGlowStroke(
            canvas: Canvas,
            bounds: RectF,
            radius: Float,
            strokeWidth: Float,
            alpha: Int,
            blur: Float
        ) {
            paint.strokeWidth = strokeWidth
            paint.alpha = alpha
            paint.maskFilter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }

        private fun easeOut(value: Float): Float {
            val inverse = 1f - value
            return 1f - inverse * inverse * inverse
        }

        private fun easeIn(value: Float): Float {
            return value * value
        }

        companion object {
            private const val OUTLINE_APPEAR_MILLIS = 260L
        }
    }

    private enum class AssistantIcon {
        LOCK, SLEEP, RESTART, POWER, LOGOUT, MUTE, VOLUME_DOWN, VOLUME_UP,
        PREVIOUS, PLAY_PAUSE, NEXT, LINK, SCREEN, AUDIO, BROADCAST
    }

    private class AssistantIconDrawable(
        private val icon: AssistantIcon,
        color: Int,
        private val intrinsicSize: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(paint).apply { style = Paint.Style.FILL }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            val scale = size / 24f
            canvas.save()
            canvas.translate(b.exactCenterX() - 12f * scale, b.exactCenterY() - 12f * scale)
            canvas.scale(scale, scale)
            paint.strokeWidth = 2.1f
            when (icon) {
                AssistantIcon.LOCK -> {
                    canvas.drawRoundRect(RectF(5f, 10f, 19f, 21f), 2f, 2f, paint)
                    canvas.drawArc(RectF(8f, 3f, 16f, 14f), 180f, 180f, false, paint)
                    canvas.drawCircle(12f, 15f, 1.25f, fill)
                    canvas.drawLine(12f, 16f, 12f, 18f, paint)
                }
                AssistantIcon.SLEEP -> {
                    canvas.drawLine(5f, 6f, 15f, 6f, paint)
                    canvas.drawLine(15f, 6f, 5f, 16f, paint)
                    canvas.drawLine(5f, 16f, 15f, 16f, paint)
                    canvas.drawLine(15f, 13f, 20f, 13f, paint)
                    canvas.drawLine(20f, 13f, 15f, 19f, paint)
                    canvas.drawLine(15f, 19f, 20f, 19f, paint)
                }
                AssistantIcon.RESTART -> {
                    canvas.drawArc(RectF(4f, 4f, 20f, 20f), -50f, 300f, false, paint)
                    path.reset(); path.moveTo(17f, 3f); path.lineTo(20.5f, 8f); path.lineTo(14.5f, 7.5f); canvas.drawPath(path, paint)
                }
                AssistantIcon.POWER -> {
                    canvas.drawLine(12f, 3f, 12f, 12f, paint)
                    canvas.drawArc(RectF(4f, 5f, 20f, 21f), -48f, 276f, false, paint)
                }
                AssistantIcon.LOGOUT -> {
                    canvas.drawPath(Path().apply { moveTo(10f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(10f, 19f) }, paint)
                    canvas.drawLine(9f, 12f, 20f, 12f, paint)
                    canvas.drawPath(Path().apply { moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f) }, paint)
                }
                AssistantIcon.MUTE, AssistantIcon.VOLUME_DOWN, AssistantIcon.VOLUME_UP -> {
                    speaker(canvas)
                    when (icon) {
                        AssistantIcon.MUTE -> { canvas.drawLine(17f, 9f, 22f, 15f, paint); canvas.drawLine(22f, 9f, 17f, 15f, paint) }
                        AssistantIcon.VOLUME_DOWN -> canvas.drawLine(17f, 12f, 22f, 12f, paint)
                        AssistantIcon.VOLUME_UP -> { canvas.drawLine(17f, 12f, 22f, 12f, paint); canvas.drawLine(19.5f, 9.5f, 19.5f, 14.5f, paint) }
                        else -> Unit
                    }
                }
                AssistantIcon.PREVIOUS -> mediaSkip(canvas, false)
                AssistantIcon.NEXT -> mediaSkip(canvas, true)
                AssistantIcon.PLAY_PAUSE -> {
                    path.reset(); path.moveTo(4f, 5f); path.lineTo(4f, 19f); path.lineTo(13f, 12f); path.close(); canvas.drawPath(path, fill)
                    canvas.drawRoundRect(RectF(16f, 5f, 18.5f, 19f), 1f, 1f, fill)
                    canvas.drawRoundRect(RectF(21f, 5f, 23.5f, 19f), 1f, 1f, fill)
                }
                AssistantIcon.LINK -> {
                    canvas.drawArc(RectF(2f, 7f, 13f, 17f), 45f, 270f, false, paint)
                    canvas.drawArc(RectF(11f, 7f, 22f, 17f), 225f, 270f, false, paint)
                    canvas.drawLine(8f, 12f, 16f, 12f, paint)
                }
                AssistantIcon.SCREEN -> {
                    canvas.drawRoundRect(RectF(3f, 4f, 21f, 17f), 2f, 2f, paint)
                    canvas.drawLine(9f, 21f, 15f, 21f, paint); canvas.drawLine(12f, 17f, 12f, 21f, paint)
                }
                AssistantIcon.AUDIO -> {
                    canvas.drawLine(14f, 5f, 14f, 17f, paint); canvas.drawLine(14f, 5f, 20f, 4f, paint)
                    canvas.drawCircle(10f, 18f, 4f, fill)
                    canvas.drawArc(RectF(3f, 5f, 11f, 13f), -65f, 130f, false, paint)
                }
                AssistantIcon.BROADCAST -> {
                    canvas.drawCircle(12f, 12f, 2f, fill)
                    canvas.drawArc(RectF(6f, 6f, 18f, 18f), -55f, 110f, false, paint)
                    canvas.drawArc(RectF(2f, 2f, 22f, 22f), -55f, 110f, false, paint)
                    canvas.drawArc(RectF(6f, 6f, 18f, 18f), 125f, 110f, false, paint)
                    canvas.drawArc(RectF(2f, 2f, 22f, 22f), 125f, 110f, false, paint)
                }
            }
            canvas.restore()
        }

        private fun speaker(canvas: Canvas) {
            path.reset(); path.moveTo(3f, 9f); path.lineTo(8f, 9f); path.lineTo(13f, 5f)
            path.lineTo(13f, 19f); path.lineTo(8f, 15f); path.lineTo(3f, 15f); path.close(); canvas.drawPath(path, fill)
        }

        private fun mediaSkip(canvas: Canvas, next: Boolean) {
            val direction = if (next) 1f else -1f
            val center = 12f
            path.reset(); path.moveTo(center - 6f * direction, 5f); path.lineTo(center + 4f * direction, 12f)
            path.lineTo(center - 6f * direction, 19f); path.close(); canvas.drawPath(path, fill)
            canvas.drawLine(center + 7f * direction, 5f, center + 7f * direction, 19f, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; fill.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; fill.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android framework")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = intrinsicSize
        override fun getIntrinsicHeight() = intrinsicSize
    }

    companion object {
        private const val TAG = "ZevVoiceAssistant"
        private val SURFACE = Color.rgb(253, 253, 255)
        private val TEXT = Color.rgb(28, 29, 33)
        private val MUTED = Color.rgb(91, 95, 103)
        private val PRIMARY = Color.rgb(47, 100, 220)
        private val WORKING = Color.rgb(113, 72, 190)
        private val SUCCESS = Color.rgb(30, 126, 68)
        private val ERROR = Color.rgb(180, 42, 42)
        private val OUTLINE = Color.rgb(226, 228, 234)
        private val HANDLE = Color.rgb(205, 208, 215)
        private val CHIP = Color.rgb(241, 243, 248)
        private const val CONTROL_HEIGHT_DP = 58
        private const val CONTROL_CORNER_RADIUS_DP = 18
        private const val CONTROL_GAP_DP = 12
        private const val SECTION_GAP_DP = 14
        private const val RECOGNIZER_CUE_STREAM = AudioManager.STREAM_NOTIFICATION
        private const val RECOGNIZER_END_CUE_MILLIS = 1_200L
        private const val CONFIRMATION_TIMEOUT_MILLIS = 5_000L
        private val HTTP_URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        private const val ASSISTANT_ENTER_ANIMATION_MILLIS = 210L
        private const val NO_SPEECH_TIMEOUT_MILLIS = 10_000L
        private const val RESULT_TIMEOUT_MILLIS = 2_500L
        private const val SPEECH_SILENCE_TIMEOUT_MILLIS = 1_200L
        private const val MAX_SPEECH_INPUT_MILLIS = 8_000L
        private const val END_OF_SPEECH_SILENCE_MILLIS = 900L
        private const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 1_200L
        private const val ACTIVE_SPEECH_RMS_DB = 0.8f
    }
}
