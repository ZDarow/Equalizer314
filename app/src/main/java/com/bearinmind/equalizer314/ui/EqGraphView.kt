package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.SpectrumAnalyzer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Custom view for displaying and interacting with parametric equalizer.
 *
 * Декомпозирован:
 * - [EqGraphTheme] — Paint-объекты + светлая тема
 * - [EqGraphGridRenderer] — сетка, подписи, координаты
 * - [EqGraphMbcMath] — LR4 crossover + compressor/expander math
 * - [EqGraphMbcRenderer] — отрисовка + touch MBC-полос
 * - [EqGraphTouchHandler] — drag, double-tap, long-press EQ-точек
 */
class EqGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ── Delegated components ────────────────────────────────────
    val theme = EqGraphTheme(resources)
    private val gridRenderer = EqGraphGridRenderer(theme)
    private val mbcRenderer = EqGraphMbcRenderer(theme, this)
    private val touchHandler = EqGraphTouchHandler(this)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // Wire MBC renderer callbacks
        mbcRenderer.onCrossoverChanged = { index, freq ->
            onMbcCrossoverChanged?.invoke(index, freq)
        }
        mbcRenderer.onBandGainChanged = { index, gain ->
            onMbcBandGainChanged?.invoke(index, gain)
        }
        mbcRenderer.onBandSelected = { index ->
            onMbcBandSelected?.invoke(index)
        }
        mbcRenderer.onBandGainReset = { index ->
            onMbcBandGainReset?.invoke(index)
        }

        // Wire touch handler callbacks
        touchHandler.onLongPress = { onLongPressListener?.invoke() }
        touchHandler.onBandChanged = { index, freq, gain ->
            onBandChangedListener?.invoke(index, freq, gain)
        }
        touchHandler.onBandSelected = { index ->
            onBandSelectedListener?.invoke(index)
        }
        touchHandler.onBandDragEnd = { onBandDragEndListener?.invoke() }

        // Light theme init for paints
        // (all initialization happens in EqGraphTheme constructor)
    }

    // ── EQ state ────────────────────────────────────────────────
    private var parametricEq: ParametricEqualizer? = null
    private val bandPoints = mutableListOf<BandPoint>()
    private var activebandIndex: Int? = null

    var eqUiMode: EqUiMode = EqUiMode.PARAMETRIC
        set(value) {
            field = value
            invalidate()
        }

    // ── Visibility flags ────────────────────────────────────────
    var showEqCurve = true
    var showSaturationCurve = true
    var verticalPadding = 80f
    var showCurveFill = false
    var showBandCurves = false
        set(value) {
            field = value
            invalidate()
        }
    var readOnlyPoints = false
    var showBandPoints = true

    // ── Spectrum state ──────────────────────────────────────────
    private var spectrumAnalyzer: SpectrumAnalyzer? = null
    private var spectrumData: FloatArray? = null
    private var spectrumEnabled = true
    private var cachedSpectrumHash = 0
    private var cachedNormalizedSpectrum: FloatArray? = null
    private val spectrumPath = Path()
    private val spectrumFillPath = Path()

    var spectrumRenderer: com.bearinmind.equalizer314.audio.SpectrumAnalyzerRenderer? = null

    // ── MBC properties ──────────────────────────────────────────
    var mbcCrossovers: FloatArray? = null
    var mbcBandColors: IntArray? = null
    var mbcBandGains: FloatArray? = null
    var mbcBandPostGains: FloatArray? = null
    var mbcBandThresholds: FloatArray? = null
    var mbcBandRatios: FloatArray? = null
    var mbcBandKnees: FloatArray? = null
    var mbcBandGateThresholds: FloatArray? = null
    var mbcBandExpanderRatios: FloatArray? = null
    var mbcSelectedBand: Int
        get() = mbcRenderer.selectedBand
        set(value) { mbcRenderer.selectedBand = value }
    var onMbcCrossoverChanged: ((crossoverIndex: Int, frequency: Float) -> Unit)? = null
    var onMbcBandGainChanged: ((bandIndex: Int, gain: Float) -> Unit)? = null
    var onMbcBandSelected: ((bandIndex: Int) -> Unit)? = null
    var onMbcBandGainReset: ((bandIndex: Int) -> Unit)? = null

    // ── Range ───────────────────────────────────────────────────
    private val graphMinFreq = 10f
    private val graphMaxFreq = 20000f
    var minGain = -20f
    var maxGain = 20f

    // ── Callbacks ───────────────────────────────────────────────
    var onBandChangedListener: ((bandIndex: Int, frequency: Float, gain: Float) -> Unit)? = null
    var onBandSelectedListener: ((bandIndex: Int?) -> Unit)? = null
    var onBandDragEndListener: (() -> Unit)? = null
    var onLongPressListener: (() -> Unit)? = null

    // ── Slot labels & colors ────────────────────────────────────
    private var bandSlotLabels: List<Int>? = null
    private var bandColorMap: Map<Int, Int>? = null

    fun setBandSlotLabels(slots: List<Int>) {
        bandSlotLabels = slots
        invalidate()
    }

    fun setBandColors(colors: Map<Int, Int>) {
        bandColorMap = colors
        invalidate()
    }

    private fun getBandLabel(index: Int): String {
        val slots = bandSlotLabels
        return if (slots != null && index < slots.size) (slots[index] + 1).toString()
        else (index + 1).toString()
    }

    // ── BandPoint data class ────────────────────────────────────
    data class BandPoint(
        var bandIndex: Int,
        var frequency: Float,
        var gain: Float,
        var x: Float = 0f,
        var y: Float = 0f,
    )

    // ── Public API ──────────────────────────────────────────────
    fun setParametricEqualizer(eq: ParametricEqualizer) {
        parametricEq = eq
        bandPoints.clear()
        val bands = eq.getAllBands()
        for (i in bands.indices) {
            bandPoints.add(BandPoint(i, bands[i].frequency, bands[i].gain))
        }
        activebandIndex = if (bandPoints.isNotEmpty()) 0 else null
        touchHandler.bandPoints = bandPoints
        touchHandler.parametricEq = eq
        invalidate()
    }

    fun setSpectrumAnalyzer(analyzer: SpectrumAnalyzer) {
        spectrumAnalyzer = analyzer
    }

    fun setSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
    }

    fun isSpectrumEnabled(): Boolean = spectrumEnabled

    var spectrumDataForDrawing: FloatArray?
        get() = spectrumData
        set(value) {
            spectrumData = value
            invalidate()
        }

    fun updateBandLevels() {
        parametricEq?.let { eq ->
            val bands = eq.getAllBands()
            for (i in bandPoints.indices) {
                if (i < bands.size) {
                    bandPoints[i].frequency = bands[i].frequency
                    bandPoints[i].gain = bands[i].gain
                }
            }
            // Update touch handler references
            touchHandler.bandPoints = bandPoints
            invalidate()
        }
    }

    fun setFilterType(bandIndex: Int, filterType: BiquadFilter.FilterType) {
        val point = bandPoints.getOrNull(bandIndex) ?: return
        parametricEq?.updateBand(bandIndex, point.frequency, point.gain, filterType)
        invalidate()
    }

    fun setQ(bandIndex: Int, q: Double) {
        val point = bandPoints.getOrNull(bandIndex) ?: return
        parametricEq?.getBand(bandIndex)?.let {
            parametricEq?.updateBand(bandIndex, it.frequency, it.gain, it.filterType, q)
        }
        invalidate()
    }

    fun getActiveBandIndex(): Int? = activebandIndex

    fun setActiveBand(index: Int) {
        activebandIndex = index
        touchHandler.activeBandIndex = index
        invalidate()
    }

    fun clearActiveBand() {
        activebandIndex = null
        touchHandler.activeBandIndex = null
        invalidate()
    }

    // ── onDraw ──────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (bandPoints.isEmpty()) {
            val text = context.getString(R.string.msg_eq_not_initialized)
            canvas.drawText(text, width / 2f - theme.textPaint.measureText(text) / 2f, height / 2f, theme.textPaint)
            return
        }

        val vPad = verticalPadding
        val graphWidth = width.toFloat()
        val graphHeight = height - 2 * vPad

        canvas.drawColor(if (isLightTheme()) 0xFFE4E4E4.toInt() else 0xFF1E1E1E.toInt())

        // ── Grid (behind everything) ────────────────────────────
        gridRenderer.drawGrid(canvas, vPad, graphWidth, graphHeight, minGain, maxGain, width.toFloat(), height.toFloat())

        // ── MBC (behind spectrum & EQ) ──────────────────────────
        val crossovers = mbcCrossovers
        val gains = mbcBandGains
        if (crossovers != null && gains != null) {
            mbcRenderer.draw(canvas, vPad, graphWidth, graphHeight, crossovers, gains, mbcBandColors)
        }

        // ── Spectrum ────────────────────────────────────────────
        if (spectrumEnabled) {
            drawSpectrum(canvas, vPad, graphWidth, graphHeight)
        }

        // ── Sync band points with ParametricEqualizer ───────────
        parametricEq?.let { eq ->
            val bands = eq.getAllBands()
            for (i in bandPoints.indices) {
                if (i < bands.size) {
                    if (touchHandler.isDragging && i == activebandIndex) continue
                    bandPoints[i].frequency = bands[i].frequency
                    bandPoints[i].gain = bands[i].gain
                }
            }
        }

        // ── Calculate point positions ───────────────────────────
        calculatePointPositions(vPad, graphWidth, graphHeight)

        // ── EQ curve ────────────────────────────────────────────
        if (showEqCurve) drawCurve(canvas, vPad, graphWidth, graphHeight)

        // ── Points ──────────────────────────────────────────────
        if (showBandPoints) {
            drawPoints(canvas)
            activebandIndex?.let { index ->
                if (index < bandPoints.size) {
                    drawActivePointLabel(canvas, bandPoints[index])
                }
            }
        }

        // ── Grid labels (on top) ────────────────────────────────
        gridRenderer.drawGridLabels(canvas, vPad, graphWidth, graphHeight, minGain, maxGain)
    }

    // ── onTouchEvent ────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnlyPoints) return false

        // Sync touch handler state
        touchHandler.bandPoints = bandPoints
        touchHandler.parametricEq = parametricEq
        touchHandler.activeBandIndex = activebandIndex
        touchHandler.readOnlyPoints = readOnlyPoints
        touchHandler.showBandPoints = showBandPoints

        val crossovers = mbcCrossovers
        val gains = mbcBandGains
        if (crossovers != null && gains != null) {
            return mbcRenderer.handleTouch(event, crossovers, gains)
        }

        if (!showBandPoints) return super.onTouchEvent(event)

        val handled = touchHandler.handleTouchEvent(event)
        // Sync activeBandIndex back from touch handler
        activebandIndex = touchHandler.activeBandIndex
        return handled
    }

    // ── onMeasure ───────────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minWidth = 600
        val minHeight = 400
        val w = resolveSize(minWidth, widthMeasureSpec)
        val h = resolveSize(minHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Spectrum drawing ────────────────────────────────────────
    private fun drawSpectrum(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val renderer = spectrumRenderer
        if (renderer != null) {
            val eq = parametricEq
            val displayWidth = graphWidth.toInt().coerceAtLeast(1)
            val logMin = log10(20f)
            val logMax = log10(20000f)
            val logRange = logMax - logMin
            val crossovers = mbcCrossovers
            val gains = mbcBandGains

            val eqResponse: FloatArray? = if ((eq != null && eq.getBandCount() > 0) || (crossovers != null && gains != null)) {
                FloatArray(displayWidth) { x ->
                    val logFreqMid = logMin + logRange * (x + 0.5f) / displayWidth
                    val freq = 10f.pow(logFreqMid)
                    var db = 0f
                    if (eq != null && eq.getBandCount() > 0) {
                        db += eq.getFrequencyResponse(freq)
                    }
                    db
                }
            } else null
            renderer.draw(canvas, 0f, 0f, graphWidth, height.toFloat(), dbMin = -60f, dbMax = 0f, eqResponseDb = eqResponse)
            return
        }

        val spectrum = spectrumData ?: return
        val analyzer = spectrumAnalyzer ?: return
        if (spectrum.isEmpty()) return

        spectrumPath.reset()
        spectrumFillPath.reset()

        val spectrumHash = spectrum.contentHashCode()
        val normalizedSpectrum = if (spectrumHash == cachedSpectrumHash && cachedNormalizedSpectrum != null) {
            cachedNormalizedSpectrum!!
        } else {
            val smoothed = analyzer.smoothSpectrum(spectrum, windowSize = 3)
            val normalized = analyzer.normalizeSpectrum(smoothed, minDb = -90f, maxDb = 0f)
            cachedNormalizedSpectrum = normalized
            cachedSpectrumHash = spectrumHash
            normalized
        }

        var pathStarted = false
        var lastX = 0f
        val bottomY = vPad + graphHeight

        fun getMagnitudeAtFreq(targetFreq: Float): Float {
            val sampleRate = 44100f
            val fftLen = 2048
            val binWidth = sampleRate / fftLen
            val binIndexFloat = targetFreq / binWidth
            val lowerBin = binIndexFloat.toInt().coerceIn(0, spectrum.size - 1)
            val upperBin = (lowerBin + 1).coerceIn(0, spectrum.size - 1)
            val magnitude: Float = if (lowerBin == upperBin || upperBin >= spectrum.size) {
                normalizedSpectrum[lowerBin]
            } else {
                val lowerFreq = lowerBin * binWidth
                val upperFreq = upperBin * binWidth
                val ratio = (targetFreq - lowerFreq) / (upperFreq - lowerFreq)
                normalizedSpectrum[lowerBin] + ratio * (normalizedSpectrum[upperBin] - normalizedSpectrum[lowerBin])
            }
            return parametricEq?.let { eq ->
                val eqResponse = eq.getFrequencyResponse(targetFreq)
                val spectrumDb = -90f + magnitude * 90f
                val adjustedDb = spectrumDb + eqResponse
                ((adjustedDb + 90f) / 90f).coerceIn(0f, 1f)
            } ?: magnitude
        }

        val leftEdgeX = 0f
        val leftEdgeMag = getMagnitudeAtFreq(graphMinFreq)
        val leftEdgeY = vPad + graphHeight * (1f - leftEdgeMag)
        spectrumPath.moveTo(leftEdgeX, leftEdgeY)
        spectrumFillPath.moveTo(leftEdgeX, bottomY)
        spectrumFillPath.lineTo(leftEdgeX, leftEdgeY)
        pathStarted = true
        lastX = leftEdgeX

        val numBins = spectrum.size
        val hasEQ = parametricEq != null
        val eq = parametricEq
        var i = 0
        while (i < numBins) {
            val freq = analyzer.getBinFrequency(i)
            if (freq < graphMinFreq) { i++; continue }
            if (freq > graphMaxFreq) break
            val x = gridRenderer.freqToX(freq, graphWidth)
            var magnitude = normalizedSpectrum[i]
            if (hasEQ && eq != null) {
                val eqResponse = eq.getFrequencyResponse(freq)
                val spectrumDb = -90f + magnitude * 90f
                val adjustedDb = spectrumDb + eqResponse
                magnitude = ((adjustedDb + 90f) / 90f).coerceIn(0f, 1f)
            }
            val y = vPad + graphHeight * (1f - magnitude)
            spectrumPath.lineTo(x, y)
            spectrumFillPath.lineTo(x, y)
            lastX = x
            val skipAmount = when {
                freq < 500 -> 1
                freq < 2000 -> 2
                else -> 3
            }
            i += skipAmount
        }

        val rightEdgeX = graphWidth
        val rightEdgeMag = getMagnitudeAtFreq(graphMaxFreq)
        val rightEdgeY = vPad + graphHeight * (1f - rightEdgeMag)
        spectrumPath.lineTo(rightEdgeX, rightEdgeY)
        spectrumFillPath.lineTo(rightEdgeX, rightEdgeY)
        lastX = rightEdgeX

        if (pathStarted) {
            spectrumFillPath.lineTo(lastX, bottomY)
            spectrumFillPath.close()
            val gradient = LinearGradient(
                0f, vPad, 0f, bottomY,
                intArrayOf(0x80888888.toInt(), 0x40444444.toInt()),
                null, Shader.TileMode.CLAMP
            )
            theme.spectrumFillPaint.shader = gradient
            canvas.drawPath(spectrumFillPath, theme.spectrumFillPaint)
            canvas.drawPath(spectrumPath, theme.spectrumLinePaint)
        }
    }

    // ── Point positions ─────────────────────────────────────────
    private fun calculatePointPositions(vPad: Float, graphWidth: Float, graphHeight: Float) {
        for (point in bandPoints) {
            point.x = gridRenderer.freqToX(point.frequency, graphWidth).coerceIn(23f, graphWidth - 23f)
            val filterType = parametricEq?.getBand(point.bandIndex)?.filterType
            if (filterType == BiquadFilter.FilterType.LOW_PASS || filterType == BiquadFilter.FilterType.HIGH_PASS) {
                val q = (parametricEq?.getBand(point.bandIndex)?.q ?: 0.707).toFloat()
                val qNormalized = ((q - 0.1f) / (12f - 0.1f)).coerceIn(0f, 1f)
                point.y = vPad + graphHeight * (1f - qNormalized)
            } else {
                val gainNormalized = (point.gain - minGain) / (maxGain - minGain)
                point.y = vPad + graphHeight * (1f - gainNormalized.coerceIn(0f, 1f))
            }
        }
    }

    // ── Curve drawing ───────────────────────────────────────────
    private fun drawCurve(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        val eq = parametricEq ?: return
        if (bandPoints.isEmpty()) return

        val path = Path()
        val saturatedPath = Path()
        val numSamples = 220
        var pathStarted = false
        var showSaturated = false
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)

        for (i in 0 until numSamples) {
            val x = graphWidth * i / (numSamples - 1)
            val logFreq = logMin + (x / graphWidth) * (logMax - logMin)
            val freq = 10f.pow(logFreq)
            val responsedB = eq.getFrequencyResponse(freq)
            val saturatedDb = eq.getFrequencyResponseWithSaturation(freq)
            if (responsedB.isNaN() || responsedB.isInfinite()) continue
            val gainNormalized = (responsedB - minGain) / (maxGain - minGain)
            val y = vPad + graphHeight * (1f - gainNormalized)
            val satGainNormalized = (saturatedDb - minGain) / (maxGain - minGain)
            val satY = if (saturatedDb.isNaN() || saturatedDb.isInfinite()) y
                       else vPad + graphHeight * (1f - satGainNormalized)
            if (abs(responsedB - saturatedDb) > 0.5f) showSaturated = true
            if (!pathStarted) {
                path.moveTo(x, y)
                saturatedPath.moveTo(x, satY)
                pathStarted = true
            } else {
                path.lineTo(x, y)
                saturatedPath.lineTo(x, satY)
            }
        }

        // Per-band curves
        if (showBandCurves && mbcCrossovers == null) {
            val zeroY = vPad + graphHeight * (1f - (0f - minGain) / (maxGain - minGain))
            val density = resources.displayMetrics.density
            for (b in 0 until eq.getBandCount()) {
                if (eq.getBand(b)?.enabled != true) continue
                val bandPath = Path()
                var started = false
                var maxAbsDb = 0f
                for (j in 0 until numSamples) {
                    val x = graphWidth * j / (numSamples - 1)
                    val lf = logMin + (x / graphWidth) * (logMax - logMin)
                    val f = 10f.pow(lf)
                    val db = eq.getBandFrequencyResponse(b, f)
                    if (db.isNaN() || db.isInfinite()) continue
                    if (abs(db) > maxAbsDb) maxAbsDb = abs(db)
                    val yB = vPad + graphHeight * (1f - (db - minGain) / (maxGain - minGain))
                    if (!started) { bandPath.moveTo(x, yB); started = true }
                    else bandPath.lineTo(x, yB)
                }
                if (!started || maxAbsDb < 0.1f) continue
                val baseColor = getBandColor(b) ?: 0xFF888888.toInt()
                val fillPath = Path(bandPath).apply {
                    lineTo(graphWidth, zeroY)
                    lineTo(0f, zeroY)
                    close()
                }
                canvas.drawPath(fillPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (baseColor and 0x00FFFFFF) or 0x26000000
                    style = Paint.Style.FILL
                })
                canvas.drawPath(bandPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (baseColor and 0x00FFFFFF) or 0x55000000
                    style = Paint.Style.STROKE
                    strokeWidth = 1.2f * density
                })
            }
            canvas.drawLine(0f, zeroY, graphWidth, zeroY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLightTheme()) 0xFFADADAD.toInt() else 0xFF555555.toInt()
                strokeWidth = 1f * density
                style = Paint.Style.STROKE
            })
        }

        // Fill between curve and 0dB
        if (showCurveFill && pathStarted) {
            val zeroY = vPad + graphHeight * (1f - (0f - minGain) / (maxGain - minGain))
            val fillPath = Path(path)
            fillPath.lineTo(graphWidth, zeroY)
            fillPath.lineTo(0f, zeroY)
            fillPath.close()
            canvas.drawPath(fillPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x30AAAAAA.toInt()
                style = Paint.Style.FILL
            })
        }

        // Main curve
        if (mbcCrossovers != null) {
            val dottedPaint = Paint(theme.curvePaint).apply {
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                alpha = 50
            }
            canvas.drawPath(path, dottedPaint)
        } else {
            canvas.drawPath(path, theme.curvePaint)
        }
        if (showSaturated && showSaturationCurve) {
            canvas.drawPath(saturatedPath, theme.saturatedCurvePaint)
        }
    }

    // ── Graphic bars ────────────────────────────────────────────
    private fun drawGraphicBars(canvas: Canvas, vPad: Float, graphWidth: Float, graphHeight: Float) {
        if (bandPoints.isEmpty()) return
        val zeroDbNorm = (0f - minGain) / (maxGain - minGain)
        val zeroDbY = vPad + graphHeight * (1f - zeroDbNorm)
        val connectPath = Path()
        val sortedPoints = bandPoints.sortedBy { it.frequency }
        for ((idx, point) in sortedPoints.withIndex()) {
            canvas.drawLine(point.x, zeroDbY, point.x, point.y, theme.graphicBarPaint)
            if (idx == 0) connectPath.moveTo(point.x, point.y)
            else connectPath.lineTo(point.x, point.y)
        }
        canvas.drawPath(connectPath, theme.graphicConnectLinePaint)
    }

    // ── Points ──────────────────────────────────────────────────
    private fun drawPoints(canvas: Canvas) {
        if (readOnlyPoints) {
            val smallRadius = 6f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLightTheme()) 0xFF363636.toInt() else 0xFFCCCCCC.toInt()
                style = Paint.Style.FILL
            }
            for (point in bandPoints) {
                canvas.drawCircle(point.x, point.y, smallRadius, dotPaint)
            }
            return
        }

        for (i in bandPoints.indices) {
            val point = bandPoints[i]
            val isActive = i == activebandIndex
            val bandEnabled = parametricEq?.getBand(i)?.enabled != false
            val customColor = getBandColor(i)
            if (!bandEnabled && eqUiMode == EqUiMode.TABLE) continue

            canvas.drawCircle(point.x, point.y, 20f, theme.pointBgPaint)
            val bandNumber = getBandLabel(i)

            if (!bandEnabled) {
                canvas.drawCircle(point.x, point.y, 20f, theme.disabledPointPaint)
                val textY = point.y + (theme.disabledPointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, theme.disabledPointNumberPaint)
            } else if (isActive) {
                if (customColor != null) {
                    theme.coloredFillPaint.color = customColor
                    canvas.drawCircle(point.x, point.y, 20f, theme.coloredFillPaint)
                    theme.coloredRingPaint.color = customColor
                    theme.coloredRingPaint.strokeWidth = 3f
                    canvas.drawCircle(point.x, point.y, 20f, theme.coloredRingPaint)
                } else {
                    canvas.drawCircle(point.x, point.y, 20f, theme.activePointFillPaint)
                    canvas.drawCircle(point.x, point.y, 20f, theme.activePointRingPaint)
                }
                val textY = point.y + (theme.activePointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY,
                    if (customColor != null) theme.coloredNumberPaint else theme.activePointNumberPaint)
            } else {
                if (customColor != null) {
                    theme.coloredRingPaint.color = customColor
                    theme.coloredRingPaint.strokeWidth = 2f
                    canvas.drawCircle(point.x, point.y, 20f, theme.coloredRingPaint)
                } else {
                    canvas.drawCircle(point.x, point.y, 20f, theme.pointRingPaint)
                }
                val textY = point.y + (theme.pointNumberPaint.textSize / 3)
                canvas.drawText(bandNumber, point.x, textY, theme.pointNumberPaint)
            }
        }
    }

    // ── Active point label ──────────────────────────────────────
    private fun drawActivePointLabel(canvas: Canvas, point: BandPoint) {
        val currentFilterType = parametricEq?.getBand(point.bandIndex)?.filterType?.name ?: "BELL"
        val actualGain = parametricEq?.getBand(point.bandIndex)?.gain ?: point.gain
        val label = "Band ${getBandLabel(point.bandIndex)}: ${gridRenderer.formatFrequency(point.frequency.toInt())} | " +
            String.format(Locale.US, "%.1f dB", actualGain) + " | $currentFilterType"
        val labelWidth = theme.labelPaint.measureText(label)
        val padH = 14f
        val padV = 8f
        val cornerRadius = 12f * resources.displayMetrics.density
        val labelX = (width - labelWidth) / 2f
        val labelY = 30f
        val rect = android.graphics.RectF(labelX - padH, labelY - 24f, labelX + labelWidth + padH, labelY + padV)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, theme.labelBgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, theme.labelStrokePaint)
        canvas.drawText(label, labelX, labelY, theme.labelPaint)
    }

    // ── Band color ──────────────────────────────────────────────
    private fun getBandColor(index: Int): Int? {
        val slots = bandSlotLabels ?: return null
        val colors = bandColorMap ?: return null
        if (index >= slots.size) return null
        return colors[slots[index]]
    }

    // ── Theme helpers ───────────────────────────────────────────
    private fun isLightTheme(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}
