package com.bearinmind.equalizer314

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bearinmind.equalizer314.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

abstract class EqBaseActivity : AppCompatActivity() {

    /** Guard against recursive onChange. Subclass must set it. */
    protected var isUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // ── Dialogs ──────────────────────────────────────────────────────

    /** Строит кастомный диалог с тёмной темой и Material-кнопками. */
    private fun buildDialog(
        titleText: String,
        messageText: String?,
        positiveText: String,
        negativeText: String,
        positiveColor: Int = 0xFFEF9A9A.toInt(),
        onPositive: (AlertDialog) -> Unit,
    ): AlertDialog {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = titleText
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (12 * d).toInt())
        })
        if (messageText != null) {
            root.addView(TextView(this).apply {
                text = messageText
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
                setPadding(0, 0, 0, (16 * d).toInt())
            })
        }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { bottomMargin = (12 * d).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btnRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = positiveText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * d).toInt()
            }
            cornerRadius = (12 * d).toInt()
            setTextColor(positiveColor)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * d).toInt()
            setBackgroundColor(0x00000000)
            insetTop = 0; insetBottom = 0
        })
        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = negativeText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * d).toInt()
            }
            cornerRadius = (12 * d).toInt()
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * d).toInt()
            insetTop = 0; insetBottom = 0
        }
        btnRow.addView(cancelBtn)
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        btnRow.getChildAt(0).setOnClickListener { onPositive(dialog) }
        return dialog
    }

    /** Диалог подтверждения сброса (Reset). */
    protected fun showResetDialog(
        title: String = getString(R.string.action_reset),
        message: String = getString(R.string.dialog_reset_all_values),
        onReset: () -> Unit,
    ) {
        buildDialog(
            titleText = title,
            messageText = message,
            positiveText = getString(R.string.action_reset),
            negativeText = getString(R.string.action_cancel),
            positiveColor = 0xFFEF9A9A.toInt(),
        ) { dialog ->
            isUpdating = true
            onReset()
            isUpdating = false
            dialog.dismiss()
        }.show()
    }

    /** Диалог подтверждения удаления (Delete). */
    protected fun showDeleteDialog(
        itemName: String,
        title: String = getString(R.string.action_delete),
        onDelete: () -> Unit,
    ) {
        buildDialog(
            titleText = title,
            messageText = getString(R.string.dialog_delete_item, itemName),
            positiveText = getString(R.string.action_delete),
            negativeText = getString(R.string.action_cancel),
            positiveColor = 0xFFEF9A9A.toInt(),
        ) { dialog ->
            onDelete()
            dialog.dismiss()
        }.show()
    }

    /** Диалог сохранения с текстовым полем. */
    protected fun showSaveDialog(
        defaultName: String,
        title: String = getString(R.string.dialog_save_custom_preset),
        hint: String = defaultName,
        onSave: (name: String) -> Unit,
    ) {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (12 * d).toInt())
        })
        val input = EditText(this).apply {
            this.hint = hint
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = null
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            isSingleLine = true
        }
        val inputBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (16 * d).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x00000000)
                setStroke((1 * d).toInt(), 0xFF555555.toInt())
                cornerRadius = 12 * d
            }
            addView(input)
        }
        root.addView(inputBox)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { bottomMargin = (12 * d).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        }
        root.addView(divider)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.action_cancel)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * d).toInt()
            }
            cornerRadius = (12 * d).toInt()
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x00000000)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * d).toInt()
            insetTop = 0; insetBottom = 0
        }
        val saveBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.action_save)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * d).toInt()
            }
            cornerRadius = (12 * d).toInt()
            setTextColor(0xFFA5D6A7.toInt())
            setBackgroundColor(0x00000000)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
            strokeWidth = (1 * d).toInt()
            insetTop = 0; insetBottom = 0
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val name = input.text.toString().trim().ifEmpty { defaultName }
            onSave(name)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ── Toast ────────────────────────────────────────────────────────

    protected fun toastDsp(on: Boolean) {
        Toast.makeText(this,
            if (on) getString(R.string.msg_dsp_start) else getString(R.string.msg_dsp_stop),
            Toast.LENGTH_SHORT).show()
    }

    // ── Double-tap reset ─────────────────────────────────────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    protected fun addDoubleTapReset(slider: Slider, onReset: () -> Unit) {
        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { _, event ->
            if (consumeUntilUp) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
                    consumeUntilUp = false
                return@setOnTouchListener true
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    isUpdating = true
                    onReset()
                    isUpdating = false
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            false
        }
    }
}
