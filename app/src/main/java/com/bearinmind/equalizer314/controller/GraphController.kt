package com.bearinmind.equalizer314.controller

import android.annotation.SuppressLint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.bearinmind.equalizer314.R
import android.view.ViewGroup
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.ui.FilterRole
import com.bearinmind.equalizer314.ui.TableEqController
import com.bearinmind.equalizer314.ui.buildFilterButtonText
import com.bearinmind.equalizer314.ui.filterTypeFamily
import com.bearinmind.equalizer314.ui.formatHzValue
import com.bearinmind.equalizer314.ui.hzToSlider
import com.bearinmind.equalizer314.ui.isPeakFamily
import com.bearinmind.equalizer314.ui.oneOrderVariant
import com.bearinmind.equalizer314.ui.peakButtonLabel
import com.bearinmind.equalizer314.ui.sliderToHz
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.abs

/**
 * Управляет EQ-графом и панелью параметров полос (фильтры, слайдеры, цвета).
 *
 * Принимает [contract] для доступа к общему состоянию и ресурсам,
 * а также конкретные view-ссылки для управления UI.
 */
@Suppress("LongParameterList", "UnusedPrivateProperty", "LoopWithTooManyJumpStatements", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // Сохранены для будущего использования
class GraphController(
    private val contract: MainActivityContract,
    private val filterTypeGroup: LinearLayout,
    private val colorSwatchRow: LinearLayout,
    @Suppress("UnusedPrivateProperty")
    private val bandInputGroup: View,
    private val bandHzSlider: Slider,
    private val bandDbSlider: Slider,
    private val qSlider: Slider,
    private val bandHzInput: com.google.android.material.textfield.TextInputEditText,
    private val bandDbInput: com.google.android.material.textfield.TextInputEditText,
    private val bandQInput: com.google.android.material.textfield.TextInputEditText,
    @Suppress("UnusedPrivateProperty")
    private val bandQInputLayout: com.google.android.material.textfield.TextInputLayout,
    @Suppress("UnusedPrivateProperty")
    private val graphicController: com.bearinmind.equalizer314.ui.GraphicEqController,
    @Suppress("UnusedPrivateProperty")
    private val tableController: TableEqController,
    private val bandToggleManager: com.bearinmind.equalizer314.ui.BandToggleManager,
) {
    private val eqViewModel get() = contract.eqViewModel
    private val stateManager get() = contract.stateManager
    private val eqPrefs get() = contract.eqPrefs
    private val eqGraphView get() = contract.eqGraphView
    private val resources get() = contract.context.resources
    private val isLightUi get() = contract.isLightUi

    /** Флаг для предотвращения рекурсивного обновления полей ввода */
    private var isUpdatingInputs = false

    // ---- цвета кнопок графа (темизированные) ----
    @Suppress("UnusedPrivateProperty")
    private val graphBtnLitBg: Int get() = if (isLightUi) 0xFFADADAD.toInt() else 0xFF555555.toInt()
    @Suppress("UnusedPrivateProperty")
    private val graphBtnLitStroke: Int get() = if (isLightUi) 0xFF7A7A7A.toInt() else 0xFF888888.toInt()
    @Suppress("UnusedPrivateProperty")
    private val graphBtnLitContent: Int get() = if (isLightUi) 0xFF252525.toInt() else 0xFFDDDDDD.toInt()
    @Suppress("UnusedPrivateProperty")
    private val graphBtnDimStroke: Int get() = if (isLightUi) 0xFFBEBEBE.toInt() else 0xFF444444.toInt()
    @Suppress("UnusedPrivateProperty")
    private val graphBtnDimContent: Int get() = if (isLightUi) 0xFF555555.toInt() else 0xFF888888.toInt()

    /**
     * Настроить все подписки: слайдеры полос, поля ввода, фильтры, цвета.
     * Вызывается из [setupListeners].
     */
    fun setup() {
        setupBandSliderListeners()
        setupBandInputListeners()
        setupFilterTypeButtons()
        setupColorSwatches()
    }

    // ========================================================================
    // Band Parameter Sliders + Text Inputs
    // ========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBandSliderListeners() {
        bandHzSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            val hz = sliderToHz(value)
            isUpdatingInputs = true
            bandHzInput.setText(formatHzValue(hz))
            isUpdatingInputs = false
            applyBandHz(hz)
        }

        bandDbSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandDbInput.setText(String.format(Locale.US, "%.1f", value))
            isUpdatingInputs = false
            applyBandDb(value)
        }

        qSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandQInput.setText(String.format(Locale.US, "%.2f", value))
            isUpdatingInputs = false
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addOnChangeListener
            eqGraphView.setQ(bandIndex, value.toDouble())
            eqViewModel.pushEqUpdate()
        }

        // Double-tap to reset sliders to default values
        addDoubleTapReset(bandHzSlider) {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            val defaults = stateManager.allDefaultFrequencies
            val slot = eqViewModel.bandSlots.value.getOrElse(bandIndex) { bandIndex }
            val defaultHz = if (slot < defaults.size) defaults[slot] else 1000f
            bandHzSlider.value = hzToSlider(defaultHz)
            bandHzInput.setText(formatHzValue(defaultHz))
            applyBandHz(defaultHz)
        }

        addDoubleTapReset(bandDbSlider) {
            bandDbSlider.value = 0f
            bandDbInput.setText("0.0")
            applyBandDb(0f)
        }

        addDoubleTapReset(qSlider) {
            val defaultQ = 0.71f
            qSlider.value = defaultQ
            bandQInput.setText(String.format(Locale.US, "%.2f", defaultQ))
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            eqGraphView.setQ(bandIndex, defaultQ.toDouble())
            eqViewModel.pushEqUpdate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addDoubleTapReset(slider: Slider, onReset: () -> Unit) {
        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { v, event ->
            if (consumeUntilUp) {
                if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL
                ) {
                    consumeUntilUp = false
                }
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastTapTime < 300) {
                    isUpdatingInputs = true
                    onReset()
                    isUpdatingInputs = false
                    eqGraphView.invalidate()
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            false
        }
    }

    private fun setupBandInputListeners() {
        val applyOnAction = android.view.inputmethod.EditorInfo.IME_ACTION_DONE

        bandHzInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandHzFromInput(); true } else false
        }
        bandHzInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandHzFromInput()
        }

        bandDbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandDbFromInput(); true } else false
        }
        bandDbInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandDbFromInput()
        }

        bandQInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandQFromInput(); true } else false
        }
        bandQInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandQFromInput()
        }
    }

    private fun applyBandHzFromInput() {
        if (isUpdatingInputs) return
        val hz = bandHzInput.text.toString().toFloatOrNull() ?: return
        val clamped = hz.coerceIn(10f, 20000f)
        isUpdatingInputs = true
        bandHzSlider.value = hzToSlider(clamped)
        isUpdatingInputs = false
        applyBandHz(clamped)
    }

    private fun applyBandDbFromInput() {
        if (isUpdatingInputs) return
        val db = bandDbInput.text.toString().toFloatOrNull() ?: return
        val clamped = db.coerceIn(-20f, 20f)
        isUpdatingInputs = true
        bandDbSlider.value = clamped
        isUpdatingInputs = false
        applyBandDb(clamped)
    }

    @Suppress("ReturnCount")
    private fun applyBandQFromInput() {
        if (isUpdatingInputs) return
        val q = bandQInput.text.toString().toDoubleOrNull() ?: return
        val clamped = q.coerceIn(0.1, 12.0)
        isUpdatingInputs = true
        qSlider.value = clamped.toFloat()
        isUpdatingInputs = false
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        eqGraphView.setQ(bandIndex, clamped)
        eqViewModel.pushEqUpdate()
    }

    private fun applyBandHz(hz: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        eqViewModel.parametricEq.value.updateBand(
            bandIndex, hz, band.gain, band.filterType, band.q
        )
        eqGraphView.updateBandLevels()
        eqViewModel.pushEqUpdate()
    }

    private fun applyBandDb(db: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val ftNow = band.filterType
        val gainless = ftNow == BiquadFilter.FilterType.LOW_PASS ||
            ftNow == BiquadFilter.FilterType.HIGH_PASS ||
            ftNow == BiquadFilter.FilterType.LOW_PASS_1 ||
            ftNow == BiquadFilter.FilterType.HIGH_PASS_1 ||
            ftNow == BiquadFilter.FilterType.BAND_PASS ||
            ftNow == BiquadFilter.FilterType.NOTCH ||
            ftNow == BiquadFilter.FilterType.ALL_PASS
        val effectiveDb = if (gainless) 0f else db
        eqViewModel.parametricEq.value.updateBand(
            bandIndex, band.frequency, effectiveDb, band.filterType, band.q
        )
        eqGraphView.updateBandLevels()
        eqViewModel.pushEqUpdate()
    }

    /**
     * Обновить поля ввода и слайдеры для указанной полосы.
     * Вызывается при выборе полосы или обновлении параметров.
     */
    fun updateBandInputs(bandIndex: Int?) {
        isUpdatingInputs = true
        val idx = bandIndex ?: eqViewModel.selectedBandIndex.value ?: 0
        val band = eqViewModel.parametricEq.value.getBand(idx)
        if (band != null) {
            bandHzInput.setText(formatHzValue(band.frequency))
            bandDbInput.setText(String.format(Locale.US, "%.1f", band.gain))
            bandQInput.setText(String.format(Locale.US, "%.2f", band.q))
            bandHzSlider.value = hzToSlider(band.frequency)
            bandDbSlider.value = band.gain.coerceIn(-20f, 20f)
            qSlider.value = band.q.toFloat().coerceIn(0.1f, 12f)

            // dB slider/input disabled for gainless filter types
            val ft = band.filterType
            val gainless = ft == BiquadFilter.FilterType.LOW_PASS ||
                ft == BiquadFilter.FilterType.HIGH_PASS ||
                ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                ft == BiquadFilter.FilterType.HIGH_PASS_1 ||
                ft == BiquadFilter.FilterType.BAND_PASS ||
                ft == BiquadFilter.FilterType.NOTCH ||
                ft == BiquadFilter.FilterType.ALL_PASS
            bandDbSlider.isEnabled = !gainless
            bandDbInput.isEnabled = !gainless
            bandDbSlider.alpha = if (gainless) 0.3f else 1f
            bandDbInput.alpha = if (gainless) 0.3f else 1f

            // Q slider/input disabled for 1st-order variants
            val is1st = ft == BiquadFilter.FilterType.LOW_SHELF_1 ||
                ft == BiquadFilter.FilterType.HIGH_SHELF_1 ||
                ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                ft == BiquadFilter.FilterType.HIGH_PASS_1
            qSlider.isEnabled = !is1st
            bandQInput.isEnabled = !is1st
            qSlider.alpha = if (is1st) 0.3f else 1f
            bandQInput.alpha = if (is1st) 0.3f else 1f
        } else {
            bandHzInput.setText("1000")
            bandDbInput.setText("0.0")
            bandQInput.setText("0.71")
            bandHzSlider.value = 500f
            bandDbSlider.value = 0f
            qSlider.value = 0.71f
            bandDbSlider.isEnabled = true
            bandDbInput.isEnabled = true
            bandDbSlider.alpha = 1f
            bandDbInput.alpha = 1f
            qSlider.isEnabled = true
            bandQInput.isEnabled = true
            qSlider.alpha = 1f
            bandQInput.alpha = 1f
        }
        updateColorSwatches(bandIndex)
        isUpdatingInputs = false
    }

    // ========================================================================
    // Color Swatches
    // ========================================================================

    @Suppress("LoopWithTooManyJumpStatements")
    private fun setupColorSwatches() {
        colorSwatchRow.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (22 * density).toInt()

        for (color in TableEqController.BAND_COLORS) {
            val isNone = color == 0xFF333333.toInt()
            val wrapper = FrameLayout(contract.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val swatch: View = if (isNone) {
                TextView(contract.context).apply {
                    text = "\u2014"
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF404040.toInt())
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                View(contract.context).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            }
            swatch.setOnClickListener {
                val bandIndex = eqViewModel.selectedBandIndex.value ?: return@setOnClickListener
                val slotIdx = if (bandIndex < eqViewModel.bandSlots.value.size) {
                    eqViewModel.bandSlots.value[bandIndex]
                } else return@setOnClickListener
                if (isNone) {
                    eqViewModel.bandColorsMap.remove(slotIdx)
                } else {
                    eqViewModel.bandColorsMap[slotIdx] = color
                }
                eqViewModel.saveState()
                eqGraphView.setBandColors(eqViewModel.bandColors.value)
                eqGraphView.invalidate()
                bandToggleManager.updateSelection(eqViewModel.selectedBandIndex.value)
                updateColorSwatches(bandIndex)
            }
            wrapper.addView(swatch)
            colorSwatchRow.addView(wrapper)
        }
    }

    private fun updateColorSwatches(bandIndex: Int?) {
        val idx = bandIndex ?: eqViewModel.selectedBandIndex.value ?: return
        val slotIdx = if (idx < eqViewModel.bandSlots.value.size) {
            eqViewModel.bandSlots.value[idx]
        } else -1
        val currentColor = if (slotIdx >= 0) eqViewModel.bandColors.value[slotIdx] else null
        val density = resources.displayMetrics.density

        for (i in 0 until colorSwatchRow.childCount) {
            val wrapper = colorSwatchRow.getChildAt(i) as? FrameLayout ?: continue
            val swatch = wrapper.getChildAt(0) ?: continue
            val bg = swatch.background as? android.graphics.drawable.GradientDrawable ?: continue

            val swatchColor = TableEqController.BAND_COLORS[i]
            val isNone = swatchColor == 0xFF333333.toInt()
            val isSelected = if (isNone) currentColor == null else currentColor == swatchColor

            if (isSelected) {
                bg.setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            } else {
                bg.setStroke((1 * density).toInt(), 0xFF666666.toInt())
            }
        }
    }

    // ========================================================================
    // Filter Type Buttons
    // ========================================================================

    private fun setupFilterTypeButtons() {
        filterTypeGroup.removeAllViews()

        // PEAK — first tap applies BELL; second tap opens dropdown for PK/BP/NO
        val peakBtn = buildFilterTypeButton(
            label = contract.getString(R.string.filter_peak),
            defaultSubtitle = "",
            weightedWidth = true,
        )
        peakBtn.setOnClickListener { anchor ->
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            val band = eqViewModel.parametricEq.value.getBand(bandIndex)
            val inFamily = band != null && band.enabled && isPeakFamily(band.filterType)
            if (inFamily) {
                showPeakPopup(anchor, bandIndex)
            } else {
                applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.BELL)
            }
        }
        filterTypeGroup.addView(peakBtn)

        // Shelves & passes — each has a 2-tap 12 dB / 6 dB slope popup
        val shelfPassTypes = listOf(
            contract.getString(R.string.filter_low_shelf) to BiquadFilter.FilterType.LOW_SHELF,
            contract.getString(R.string.filter_high_shelf) to BiquadFilter.FilterType.HIGH_SHELF,
            contract.getString(R.string.filter_low_pass) to BiquadFilter.FilterType.LOW_PASS,
            contract.getString(R.string.filter_high_pass) to BiquadFilter.FilterType.HIGH_PASS,
        )
        for ((label, type) in shelfPassTypes) {
            val btn = buildFilterTypeButton(
                label = label,
                defaultSubtitle = contract.getString(R.string.msg_12db),
                weightedWidth = true,
            )
            btn.setOnClickListener { anchor ->
                val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
                val band = eqViewModel.parametricEq.value.getBand(bandIndex)
                val alreadyActive = band != null &&
                    band.enabled &&
                    filterTypeFamily(band.filterType) == type
                if (alreadyActive) {
                    showSlopePopup(anchor, bandIndex, type)
                } else {
                    applyFilterTypeToBand(bandIndex, type)
                }
            }
            filterTypeGroup.addView(btn)
        }

        // BYPASS — tied 1:1 with ALL_PASS
        val bypassBtn = buildFilterTypeButton(
            label = contract.getString(R.string.filter_bypass),
            defaultSubtitle = "",
            weightedWidth = true,
        )
        bypassBtn.setOnClickListener {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.ALL_PASS)
        }
        filterTypeGroup.addView(bypassBtn)
    }

    /**
     * Обновить подсветку кнопок фильтров для указанной полосы.
     */
    @Suppress("LoopWithTooManyJumpStatements", "NestedBlockDepth")
    fun updateFilterTypeButtons(bandIndex: Int?) {
        if (bandIndex == null) return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val currentType = band.filterType
        val currentFamily = filterTypeFamily(currentType)
        val currentIs1st = currentType == BiquadFilter.FilterType.LOW_SHELF_1 ||
            currentType == BiquadFilter.FilterType.HIGH_SHELF_1 ||
            currentType == BiquadFilter.FilterType.LOW_PASS_1 ||
            currentType == BiquadFilter.FilterType.HIGH_PASS_1
        val bandEnabled = band.enabled

        data class Entry(val btn: MaterialButton, val label: String, val role: FilterRole)
        val roles = listOf(
            FilterRole.PEAK, FilterRole.SHELF_PASS, FilterRole.SHELF_PASS,
            FilterRole.SHELF_PASS, FilterRole.SHELF_PASS, FilterRole.BYPASS
        )
        val labels = listOf(
            contract.getString(R.string.filter_peak),
            contract.getString(R.string.filter_low_shelf),
            contract.getString(R.string.filter_high_shelf),
            contract.getString(R.string.filter_low_pass),
            contract.getString(R.string.filter_high_pass),
            contract.getString(R.string.filter_bypass),
        )
        val typesForHighlight = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS,
            BiquadFilter.FilterType.BELL, // unused for BYPASS
        )
        val entries = mutableListOf<Entry>()
        for (i in 0 until filterTypeGroup.childCount) {
            val btn = filterTypeGroup.getChildAt(i) as? MaterialButton ?: continue
            if (i >= roles.size) break
            entries += Entry(btn, labels[i], roles[i])
        }

        val inPeakFam = isPeakFamily(currentType)
        val isAllPass = currentType == BiquadFilter.FilterType.ALL_PASS

        for ((i, e) in entries.withIndex()) {
            val buttonType = typesForHighlight[i]
            val sameFamily = filterTypeFamily(buttonType) == currentFamily
            val isActive = when (e.role) {
                FilterRole.PEAK -> bandEnabled && inPeakFam
                FilterRole.SHELF_PASS -> bandEnabled && sameFamily
                FilterRole.BYPASS -> !bandEnabled || isAllPass
            }

            if (isActive) {
                e.btn.setBackgroundColor(contract.getColor(R.color.filter_active))
                e.btn.setTextColor(contract.getColor(R.color.filter_active_text))
            } else {
                e.btn.setBackgroundColor(0x00000000)
                e.btn.setTextColor(contract.getColor(R.color.filter_inactive_text))
                e.btn.strokeColor = android.content.res.ColorStateList.valueOf(
                    contract.getColor(R.color.filter_outline)
                )
            }

            val primary: String = when (e.role) {
                FilterRole.PEAK -> peakButtonLabel(
                    currentType, bandEnabled, contract.context
                )
                else -> e.label
            }
            val subtitle: String = when (e.role) {
                FilterRole.PEAK -> ""
                FilterRole.SHELF_PASS -> when {
                    sameFamily -> if (currentIs1st) "6 dB" else contract.getString(R.string.msg_12db)
                    else -> contract.getString(R.string.msg_12db)
                }
                FilterRole.BYPASS -> ""
            }
            e.btn.text = buildFilterButtonText(primary, subtitle)
        }
    }

    /**
     * Применить тип фильтра к полосе и обновить всё зависимое UI.
     */
    fun applyFilterTypeToBand(bandIndex: Int, newType: BiquadFilter.FilterType) {
        eqViewModel.parametricEq.value.setBandEnabled(bandIndex, true)
        eqGraphView.setFilterType(bandIndex, newType)
        updateFilterTypeButtons(bandIndex)
        updateBandInputs(bandIndex)
        bandToggleManager.updateIcons()
        bandToggleManager.updateSelection(bandIndex)
        eqViewModel.pushEqUpdate()
        eqViewModel.eqPrefs.saveState(
            eqViewModel.parametricEq.value,
            eqViewModel.bandSlots.value
        )
        eqViewModel.persistLeftRightIfCse()
    }

    private fun buildFilterTypeButton(
        label: String,
        defaultSubtitle: String,
        weightedWidth: Boolean,
        fixedWidthPx: Int = 0,
    ): MaterialButton {
        val density = resources.displayMetrics.density
        return MaterialButton(
            contract.context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            icon = null
            text = buildFilterButtonText(label, defaultSubtitle)
            textSize = 11f
            cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius)
            isSingleLine = false
            maxLines = 2
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            layoutParams = if (weightedWidth) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(fixedWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            }.apply { setMargins(3, 0, 3, 0) }
            val vertPad = (6 * density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0
            insetBottom = 0
            minimumHeight = (42 * density).toInt()
            minHeight = (42 * density).toInt()
        }
    }

    private fun showPeakPopup(anchor: View, bandIndex: Int) {
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val current = band.filterType
        val density = resources.displayMetrics.density
        val cornerRadius =
            resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = contract.getColor(R.color.filter_outline)
        val activeBg = contract.getColor(R.color.filter_active)
        val activeTx = contract.getColor(R.color.filter_active_text)
        val inactiveTx = contract.getColor(R.color.filter_inactive_text)
        val ctx = contract.context
        val bgColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = PopupWindow(
            container,
            anchor.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, type: BiquadFilter.FilterType) {
            val isActive = current == type
            val item = TextView(ctx).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    applyFilterTypeToBand(bandIndex, type)
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
            }
            container.addView(item)
        }

        fun addDivider() {
            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt(),
                )
                setBackgroundColor(outlineColor)
            }
            container.addView(divider)
        }

        addItem("PK", BiquadFilter.FilterType.BELL)
        addDivider()
        addItem("BP", BiquadFilter.FilterType.BAND_PASS)
        addDivider()
        addItem("NO", BiquadFilter.FilterType.NOTCH)

        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }

    private fun showSlopePopup(
        anchor: View,
        bandIndex: Int,
        family2nd: BiquadFilter.FilterType,
    ) {
        val family1st = oneOrderVariant(family2nd) ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val currentIs1st = band.filterType == family1st
        val density = resources.displayMetrics.density
        val cornerRadius =
            resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = contract.getColor(R.color.filter_outline)
        val activeBg = contract.getColor(R.color.filter_active)
        val activeTx = contract.getColor(R.color.filter_active_text)
        val inactiveTx = contract.getColor(R.color.filter_inactive_text)
        val ctx = contract.context
        val bgColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = PopupWindow(
            container,
            anchor.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, isActive: Boolean, onTap: () -> Unit) {
            val item = TextView(ctx).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    onTap()
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
                setBackgroundResource(0)
                if (isActive) setBackgroundColor(activeBg)
            }
            container.addView(item)
        }

        addItem(contract.getString(R.string.msg_12db), !currentIs1st) {
            applyFilterTypeToBand(bandIndex, family2nd)
        }
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt(),
            )
            setBackgroundColor(outlineColor)
        }
        container.addView(divider)
        addItem("6 dB", currentIs1st) {
            applyFilterTypeToBand(bandIndex, family1st)
        }

        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }
}
