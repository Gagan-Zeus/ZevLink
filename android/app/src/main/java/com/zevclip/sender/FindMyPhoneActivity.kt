package com.zevclip.sender

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FindMyPhoneActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var colors: OverlayPalette
    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS) closeOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colors = resolveDynamicPalette()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        setContentView(buildContent())
        enterImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, IntentFilter(ACTION_DISMISS), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stoppedReceiver, IntentFilter(ACTION_DISMISS))
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stoppedReceiver) }
        super.onStop()
        restoreOverlayIfRinging()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        restoreOverlayIfRinging()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Deprecated("The ringing screen stays open until Find My Phone stops.")
    override fun onBackPressed() = Unit

    private fun closeOverlay() {
        handler.removeCallbacksAndMessages(null)
        moveTaskToBack(true)
        finishAndRemoveTask()
    }

    private fun restoreOverlayIfRinging() {
        if (!FindMyPhoneRinger.isRinging) return
        handler.postDelayed(
            {
                if (FindMyPhoneRinger.isRinging) {
                    show(applicationContext)
                }
            },
            RESTORE_DELAY_MS
        )
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun buildContent(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(colors.background)

            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(20))
                background = roundedDrawable(colors.primaryContainer, 28)

                addView(TextView(context).apply {
                    text = getString(R.string.find_phone_notification_title)
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.text)
                }, matchWidth())

                addView(TextView(context).apply {
                    text = getString(R.string.find_phone_notification_text)
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.primary)
                    setPadding(0, dp(10), 0, dp(16))
                }, matchWidth())

                addView(Button(context).apply {
                    text = getString(R.string.find_phone_notification_stop)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.onPrimary)
                    isAllCaps = false
                    stateListAnimator = null
                    elevation = 0f
                    minHeight = dp(54)
                    minWidth = 0
                    gravity = Gravity.CENTER
                    background = roundedDrawable(colors.primary, 18)
                    setOnClickListener {
                        startService(AndroidClipboardReceiverService.stopFindMyPhoneIntent(context))
                    }
                }, matchWidth())
            }, matchWidth())

        }
    }

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun resolveDynamicPalette(): OverlayPalette {
        val fallbackPrimary = resolveThemeColor(android.R.attr.colorAccent, FALLBACK_PRIMARY)
        val primary = systemColor("system_accent1_600", fallbackPrimary)
        val background = systemColor("system_neutral1_10", FALLBACK_BACKGROUND)
        return OverlayPalette(
            background = background,
            text = systemColor("system_neutral1_900", FALLBACK_TEXT),
            primary = primary,
            onPrimary = Color.WHITE,
            primaryContainer = systemColor(
                "system_accent1_50",
                blend(FALLBACK_BACKGROUND, primary, 0.10f)
            )
        )
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attribute, typedValue, true)) typedValue.data else fallback
    }

    private fun systemColor(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        val resourceId = resources.getIdentifier(name, "color", "android")
        return if (resourceId != 0) getColor(resourceId) else fallback
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(base) * inverse + Color.red(overlay) * amount).toInt(),
            (Color.green(base) * inverse + Color.green(overlay) * amount).toInt(),
            (Color.blue(base) * inverse + Color.blue(overlay) * amount).toInt()
        )
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class OverlayPalette(
        val background: Int,
        val text: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int
    )

    companion object {
        private const val ACTION_DISMISS = "com.zevclip.sender.action.DISMISS_FIND_MY_PHONE"
        private const val RESTORE_DELAY_MS = 150L
        private const val FALLBACK_BACKGROUND = 0xFFF7F8FA.toInt()
        private const val FALLBACK_TEXT = 0xFF1A1C20.toInt()
        private const val FALLBACK_PRIMARY = 0xFF1768C9.toInt()

        fun intent(context: Context): Intent {
            return Intent(context, FindMyPhoneActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        fun show(context: Context) {
            runCatching { context.startActivity(intent(context)) }
        }

        fun dismiss(context: Context) {
            context.sendBroadcast(Intent(ACTION_DISMISS).setPackage(context.packageName))
        }
    }
}
