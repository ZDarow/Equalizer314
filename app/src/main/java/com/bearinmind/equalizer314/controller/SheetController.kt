package com.bearinmind.equalizer314.controller

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Управляет отображением bottom sheet с выбором пресетов.
 *
 * Принимает [contract] для доступа к состоянию и ресурсам.
 * Bottom sheet показывается при долгом нажатии на график EQ.
 */
class SheetController(
    private val contract: MainActivityContract,
) {
    private val eqViewModel get() = contract.eqViewModel
    private val context get() = contract.context
    private val resources get() = contract.context.resources
    private val eqGraphView get() = contract.eqGraphView
    private val bandToggleManager get() = contract.bandToggleManager

    /**
     * Показать bottom sheet с предустановленными пресетами.
     * Вызывается при long-press на графике EQ.
     */
    fun showPresetsBottomSheet() {
        val density = resources.displayMetrics.density
        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val bottomSheet = BottomSheetDialog(context)
        val sheetLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (24 * density).toInt()
            )
        }
        for (presetName in presets) {
            val item = TextView(context).apply {
                text = presetName
                textSize = 16f
                setTextColor(0xFFE2E2E2.toInt())
                setPadding(
                    (16 * density).toInt(), (14 * density).toInt(),
                    (16 * density).toInt(), (14 * density).toInt()
                )
                setOnClickListener {
                    eqViewModel.loadPreset(presetName, eqGraphView)
                    bandToggleManager.updateIcons()
                    bottomSheet.dismiss()
                }
            }
            sheetLayout.addView(item)
        }
        bottomSheet.setContentView(sheetLayout)
        bottomSheet.show()
    }
}
