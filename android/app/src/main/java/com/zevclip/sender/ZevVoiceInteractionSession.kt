package com.zevclip.sender

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class ZevVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var visible = false
    private var listening = false
    private var speechStarted = false
    private var lastHeardText: String? = null
    private lateinit var sheet: View
    private lateinit var indicator: ListeningIndicatorView
    private lateinit var retryButton: TextView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private val noSpeechTimeout = Runnable {
        if (!visible || !listening || speechStarted) return@Runnable
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        showFailure(context.getString(R.string.zev_assistant_did_not_hear))
    }
    private val resultTimeout = Runnable {
        if (!visible || !listening) return@Runnable
        listening = false
        showFailure(context.getString(R.string.zev_assistant_did_not_hear))
    }

    override fun onCreate() {
        super.onCreate()
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM
                windowAnimations = R.style.Animation_ZevAssistantSheet
            }
        }
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            elevation = dp(14).toFloat()
            background = roundedDrawable(SURFACE, 30, OUTLINE, 1)

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
                text = context.getString(R.string.zev_assistant_command_hint)
                textSize = 17f
                setTextColor(MUTED)
                maxLines = 2
                setPadding(0, dp(16), 0, dp(14))
            }
            addView(transcriptText, matchWidth())

            addView(TextView(context).apply {
                text = context.getString(R.string.zev_assistant_capabilities)
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedDrawable(CHIP, 16)
            }, matchWidth())
        }

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
        val initialCommand = args?.getString(ZevVoiceInteractionService.EXTRA_INITIAL_COMMAND).orEmpty()
        if (initialCommand.isNotBlank()) {
            transcriptText.text = initialCommand
            handler.post { execute(initialCommand) }
        } else {
            handler.post { startListening() }
        }
    }

    override fun onHide() {
        visible = false
        listening = false
        speechStarted = false
        lastHeardText = null
        indicator.setActive(false)
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        super.onHide()
    }

    override fun onDestroy() {
        visible = false
        listening = false
        speechStarted = false
        lastHeardText = null
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun startListening() {
        if (!visible) return
        statusText.text = context.getString(R.string.zev_assistant_listening)
        statusText.setTextColor(PRIMARY)
        transcriptText.text = context.getString(R.string.zev_assistant_command_hint)
        transcriptText.setTextColor(MUTED)
        retryButton.visibility = View.GONE
        indicator.visibility = View.VISIBLE
        indicator.setState(PRIMARY, true)
        listening = true
        speechStarted = false
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MAX_SPEECH_INPUT_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MILLIS)
        })
    }

    private fun createRecognizer(): SpeechRecognizer {
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun execute(text: String) {
        listening = false
        speechStarted = false
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        indicator.setState(WORKING, true)
        statusText.text = context.getString(R.string.zev_assistant_working)
        statusText.setTextColor(WORKING)
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

    private fun showFailure(message: String, replaceTranscript: Boolean = true) {
        if (!visible) return
        listening = false
        speechStarted = false
        lastHeardText = null
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        indicator.setState(ERROR, false)
        indicator.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(ERROR)
        if (replaceTranscript) {
            transcriptText.text = context.getString(R.string.zev_assistant_retry_hint)
            transcriptText.setTextColor(MUTED)
        }
    }

    override fun onResults(results: Bundle?) {
        if (!listening) return
        listening = false
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (text.isNullOrBlank()) {
            showFailure(context.getString(R.string.zev_assistant_did_not_hear))
        }
        else execute(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
            lastHeardText = it
            transcriptText.text = it
            transcriptText.setTextColor(TEXT)
        }
    }

    override fun onRmsChanged(rmsdB: Float) = indicator.setLevel(rmsdB)
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() {
        speechStarted = true
        handler.removeCallbacks(noSpeechTimeout)
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (listening) handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MILLIS)
    }
    override fun onError(error: Int) {
        if (!listening) return
        listening = false
        handler.removeCallbacks(noSpeechTimeout)
        handler.removeCallbacks(resultTimeout)
        val fallback = lastHeardText?.takeIf { it.isNotBlank() }
        if (speechStarted && fallback != null && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            execute(fallback)
        } else {
            showFailure(context.getString(R.string.zev_assistant_did_not_hear))
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun roundedDrawable(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null && strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = topMargin }

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

    companion object {
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
        private const val NO_SPEECH_TIMEOUT_MILLIS = 10_000L
        private const val RESULT_TIMEOUT_MILLIS = 8_000L
        private const val MAX_SPEECH_INPUT_MILLIS = 60_000L
        private const val END_OF_SPEECH_SILENCE_MILLIS = 2_200L
        private const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 3_200L
    }
}
