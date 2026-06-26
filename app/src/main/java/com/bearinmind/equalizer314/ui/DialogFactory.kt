package com.bearinmind.equalizer314.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.bearinmind.equalizer314.R as AppR
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton

/**
 * Factory for constructing the custom-styled dialogs used throughout the app.
 *
 * All dialogs share the same visual DNA: a [LinearLayout] containing a title,
 * optional content, a divider, and a two-button action row. This class
 * eliminates the repeated ~60-line dialog builders in [MainActivity].
 */
object DialogFactory {

    /**
     * Build a two-button confirmation dialog and return the [android.app.AlertDialog].
     *
     * @param context  Activity context (for theme resolution).
     * @param title    Dialog title text.
     * @param message  Body text shown below the title.
     * @param confirmLabel  Text for the confirm (right) button.
     * @param cancelLabel   Text for the cancel (left) button.
     * @param onConfirm  Called when the user presses the confirm button.
     *                   Dismisses the dialog automatically afterwards.
     */
    fun confirmation(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String = context.getString(AppR.string.action_cancel),
        onConfirm: () -> Unit,
    ): android.app.AlertDialog {
        val density = context.resources.displayMetrics.density
        val root = verticalLayout(context, density)

        root.addView(titleView(context, title, density))
        root.addView(bodyView(context, message, density))
        root.addView(divider(context, density))

        val (cancelBtn, confirmBtn) = buttonRow(context, confirmLabel, cancelLabel, density)
        root.addView(rowLayout(context, cancelBtn, confirmBtn))

        return buildDialog(context, root, cancelBtn, confirmBtn, onConfirm)
    }

    /**
     * Build a dialog with a text input field.
     *
     * @param context    Activity context.
     * @param title      Dialog title.
     * @param hint       Input field hint text.
     * @param initialText  Pre-filled text (or empty).
     * @param confirmLabel  Text for the confirm button.
     * @param onConfirm  Called with the entered text. Dismisses automatically.
     */
    fun input(
        context: Context,
        title: String,
        hint: String,
        initialText: String = "",
        confirmLabel: String = context.getString(AppR.string.action_ok),
        cancelLabel: String = context.getString(AppR.string.action_cancel),
        onConfirm: (text: String) -> Unit,
    ): android.app.AlertDialog {
        val density = context.resources.displayMetrics.density
        val root = verticalLayout(context, density)

        root.addView(titleView(context, title, density))

        val input = EditText(context).apply {
            setText(initialText)
            setHint(hint)
            setTextColor(0xFFE2E2E2.toInt())
            setHintTextColor(0xFF888888.toInt())
            background = null
            textSize = 16f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        root.addView(input)
        root.addView(divider(context, density))

        val (cancelBtn, confirmBtn) = buttonRow(context, confirmLabel, cancelLabel, density)
        root.addView(rowLayout(context, cancelBtn, confirmBtn))

        val dialog = buildDialog(context, root, cancelBtn, confirmBtn) {
            onConfirm(input.text.toString().trim())
        }
        return dialog
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun verticalLayout(ctx: Context, density: Float) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
    }

    private fun rowLayout(ctx: Context, vararg buttons: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        for (b in buttons) addView(b)
    }

    private fun titleView(ctx: Context, text: String, density: Float) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE2E2E2.toInt())
        textSize = 20f
        setPadding(0, 0, 0, (12 * density).toInt())
    }

    private fun bodyView(ctx: Context, text: String, density: Float) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 14f
        setPadding(0, 0, 0, (16 * density).toInt())
    }

    private fun divider(ctx: Context, density: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()).apply {
            bottomMargin = (12 * density).toInt()
        }
        setBackgroundColor(0xFF444444.toInt())
    }

    private fun outlinedButton(
        ctx: Context,
        text: String,
        textColor: Int,
        density: Float,
        weight: Float = 1f,
    ) = MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        cornerRadius = (12 * density).toInt()
        setTextColor(textColor)
        strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
        strokeWidth = (1 * density).toInt()
        setBackgroundColor(0x00000000)
        insetTop = 0; insetBottom = 0
    }

    private fun buttonRow(
        ctx: Context,
        confirmLabel: String,
        cancelLabel: String,
        density: Float,
    ): Pair<MaterialButton, MaterialButton> {
        val cancelBtn = outlinedButton(ctx, cancelLabel, 0xFFEF9A9A.toInt(), density).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = (3 * density).toInt()
            }
        }
        val confirmBtn = outlinedButton(ctx, confirmLabel, 0xFFDDDDDD.toInt(), density).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = (3 * density).toInt()
            }
        }
        return cancelBtn to confirmBtn
    }

    private fun buildDialog(
        context: Context,
        root: LinearLayout,
        cancelBtn: MaterialButton,
        confirmBtn: MaterialButton,
        onConfirm: () -> Unit,
    ): android.app.AlertDialog {
        val dialog = android.app.AlertDialog.Builder(context, AppR.style.Theme_Equalizer314_Dialog)
            .setView(root)
            .create()
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }
        return dialog
    }
}
