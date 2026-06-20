package com.zevclip.sender

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object AirPlayScreenCodeDialog {
    fun show(
        activity: Activity,
        promptStarted: Boolean,
        onCancel: () -> Unit,
        onMissingCode: () -> Unit,
        onCode: (String) -> Unit
    ) {
        val colors = activity.resolvePalette()
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(22), activity.dp(24), activity.dp(22))
            background = activity.roundedDrawable(colors.surface, 30)
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            translationY = activity.dp(22).toFloat()
        }

        content.addView(activity.dialogText(
            activity.getString(R.string.airplay_screen_code_dialog_title),
            27f,
            colors.text
        ).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, activity.dp(6))
        }, activity.matchWidth())

        content.addView(activity.dialogText(
            activity.getString(
                if (promptStarted) {
                    R.string.airplay_screen_code_dialog_message
                } else {
                    R.string.airplay_screen_code_dialog_message_retry
                }
            ),
            16f,
            colors.muted
        ).apply {
            setLineSpacing(0f, 1.08f)
        }, activity.matchWidth())

        val errorText = activity.dialogText("", 13f, colors.error).apply {
            visibility = View.GONE
            setPadding(0, activity.dp(8), 0, 0)
        }

        val codeBoxes = mutableListOf<EditText>()
        val codeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) { index ->
            val box = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = if (index == 3) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(1))
                textSize = 24f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.text)
                setHintTextColor(colors.muted)
                background = activity.roundedDrawable(colors.inputSurface, 18, colors.outline, 1)
                setSelectAllOnFocus(true)
            }
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    errorText.visibility = View.GONE
                    codeBoxes.forEach {
                        it.background = activity.roundedDrawable(colors.inputSurface, 18, colors.outline, 1)
                    }
                    if ((s?.length ?: 0) == 1 && index < 3) {
                        codeBoxes[index + 1].requestFocus()
                    }
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isEmpty() &&
                    index > 0
                ) {
                    codeBoxes[index - 1].requestFocus()
                    codeBoxes[index - 1].setSelection(codeBoxes[index - 1].text.length)
                    true
                } else {
                    false
                }
            }
            codeBoxes.add(box)
            codeRow.addView(
                box,
                LinearLayout.LayoutParams(activity.dp(54), activity.dp(58)).apply {
                    if (index > 0) {
                        leftMargin = activity.dp(8)
                    }
                }
            )
        }
        content.addView(codeRow, activity.matchWidth(topMargin = 18))
        content.addView(errorText, activity.matchWidth())

        var submitted = false
        var handledDismiss = false
        val buttonRow = activity.horizontalButtons(
            activity.dialogButton(activity.getString(android.R.string.cancel), colors.quiet, colors.text) {
                handledDismiss = true
                dialog.dismiss()
                onCancel()
            },
            activity.dialogButton(activity.getString(R.string.airplay_screen_code_start), colors.primary, colors.onPrimary) {
                val code = codeBoxes.joinToString("") { it.text.toString().trim() }
                if (code.length != 4) {
                    errorText.text = activity.getString(R.string.airplay_screen_code_missing)
                    errorText.visibility = View.VISIBLE
                    codeBoxes.forEach {
                        it.background = activity.roundedDrawable(colors.inputSurface, 18, colors.error, 1)
                    }
                    codeRow.animate()
                        .translationX(activity.dp(5).toFloat())
                        .setDuration(70)
                        .withEndAction {
                            codeRow.animate()
                                .translationX(0f)
                                .setDuration(90)
                                .start()
                        }
                        .start()
                    (codeBoxes.firstOrNull { it.text.isBlank() } ?: codeBoxes.first()).requestFocus()
                    onMissingCode()
                    return@dialogButton
                }
                submitted = true
                dialog.dismiss()
                onCode(code)
            }
        )
        content.addView(buttonRow, activity.matchWidth(topMargin = 20))

        codeBoxes.last().setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                (buttonRow.getChildAt(1) as Button).performClick()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            if (!submitted && !handledDismiss) {
                onCancel()
            }
        }

        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            attributes = attributes.apply {
                dimAmount = 0.58f
            }
            setLayout(activity.resources.displayMetrics.widthPixels - activity.dp(42), WindowManager.LayoutParams.WRAP_CONTENT)
        }

        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
        codeRow.postDelayed({
            codeRow.animate()
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(150)
                .withEndAction {
                    codeRow.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(170)
                        .start()
                }
                .start()
        }, 260)
        codeBoxes.first().post {
            codeBoxes.first().requestFocus()
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(codeBoxes.first(), InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun Activity.dialogText(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
        }
    }

    private fun Activity.dialogButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            translationZ = 0f
            minHeight = dp(54)
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER
            background = roundedDrawable(backgroundColor, 18)
            setOnClickListener { onClick() }
        }
    }

    private fun Activity.horizontalButtons(first: Button, second: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            })
        }
    }

    private fun Activity.roundedDrawable(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun Activity.matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun Activity.resolvePalette(): DialogPalette {
        val fallbackPrimary = resolveThemeColor(android.R.attr.colorAccent, FALLBACK_PRIMARY)
        val primary = systemColor("system_accent1_600", fallbackPrimary)
        val surface = systemColor("system_neutral1_0", Color.WHITE)
        val inputSurface = systemColor("system_neutral2_50", blend(surface, primary, 0.05f))
        return DialogPalette(
            surface = surface,
            inputSurface = inputSurface,
            text = systemColor("system_neutral1_900", FALLBACK_TEXT),
            muted = systemColor("system_neutral2_700", FALLBACK_MUTED),
            outline = systemColor("system_neutral2_200", FALLBACK_OUTLINE),
            primary = primary,
            onPrimary = Color.WHITE,
            quiet = systemColor("system_neutral2_100", FALLBACK_QUIET),
            error = FALLBACK_ERROR
        )
    }

    private fun Activity.resolveThemeColor(attribute: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attribute, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }

    private fun Activity.systemColor(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return fallback
        }

        val resourceId = resources.getIdentifier(name, "color", "android")
        return if (resourceId != 0) {
            getColor(resourceId)
        } else {
            fallback
        }
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(base) * inverse + Color.red(overlay) * amount).toInt(),
            (Color.green(base) * inverse + Color.green(overlay) * amount).toInt(),
            (Color.blue(base) * inverse + Color.blue(overlay) * amount).toInt()
        )
    }

    private fun Activity.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class DialogPalette(
        val surface: Int,
        val inputSurface: Int,
        val text: Int,
        val muted: Int,
        val outline: Int,
        val primary: Int,
        val onPrimary: Int,
        val quiet: Int,
        val error: Int
    )

    private val FALLBACK_TEXT: Int = Color.rgb(26, 28, 32)
    private val FALLBACK_MUTED: Int = Color.rgb(91, 95, 103)
    private val FALLBACK_OUTLINE: Int = Color.rgb(219, 223, 230)
    private val FALLBACK_PRIMARY: Int = Color.rgb(23, 104, 201)
    private val FALLBACK_QUIET: Int = Color.rgb(236, 239, 244)
    private val FALLBACK_ERROR: Int = Color.rgb(180, 32, 32)
}
