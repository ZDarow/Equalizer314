package com.bearinmind.equalizer314.ui

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.bearinmind.equalizer314.ui.EqGraphMbcMath.mbcGainAtFreq
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Отрисовка MBC (Multiband Compressor) полос на [EqGraphView].
 *
 * Использует [EqGraphTheme] для доступа к Paint-объектам и
 * [EqGraphMbcMath] для crossover/compressor math.
 * Состояние: текущая перетаскиваемая полоса и crossover.
 */
class EqGraphMbcRenderer(
    private val theme: EqGraphTheme,
    private val view: View,
) {
    // ── Drag state ──────────────────────────────────────────────
    var draggingCrossover: Int = -1
    var draggingBand: Int = -1
    var selectedBand: Int = 0

    // ── Halo animation ──────────────────────────────────────────
    var haloAlpha = 0f
    var haloType = 0  // 1=postGain
    var haloBand = -1
    private var haloAnimator: android.animation.ValueAnimator? = null

    // ── Callbacks ───────────────────────────────────────────────
    var onCrossoverChanged: ((crossoverIndex: Int, frequency: Float) -> Unit)? = null
    var onBandGainChanged: ((bandIndex: Int, gain: Float) -> Unit)? = null
    var onBandSelected: ((bandIndex: Int) -> Unit)? = null
    var onBandGainReset: ((bandIndex: Int) -> Unit)? = null

    private val graphMinFreq = 10f
    private val graphMaxFreq = 20000f
    private val minGain = -20f
    private val maxGain = 20f

    /**
     * Основной метод отрисовки MBC-полос.
     * [crossovers], [gains], [postGains], [thresholds], [ratios],
     * [knees], [gateThresholds], [expanderRatios], [bandColors] —
     * всё из [EqGraphView].
     */
    fun draw(
        canvas: Canvas,
        vPad: Float,
        graphWidth: Float,
        graphHeight: Float,
        crossovers: FloatArray,
        gains: FloatArray,
        bandColors: IntArray?,
    ) {
        val bandCount = crossovers.size + 1
        if (gains.size < bandCount) return

        // --- Draw MBC frequency response curve ---
        val numSamples = (graphWidth / 2f).toInt().coerceAtLeast(100)
        val curvePath = Path()
        for (s in 0..numSamples) {
            val x = graphWidth * s.toFloat() / numSamples
            val freq = freqToXInverse(x, graphWidth)
            val gainDb = mbcGainAtFreq(freq, crossovers, gains).coerceIn(minGain, maxGain)
            val y = vPad + graphHeight * (1f - (gainDb - minGain) / (maxGain - minGain))
            if (s == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        canvas.drawPath(curvePath, theme.mbcCurvePaint)

        // --- Draw crossover lines + drag ripple ---
        for (i in crossovers.indices) {
            val x = freqToX(crossovers[i], graphWidth)
            canvas.drawLine(x, vPad, x, vPad + graphHeight, theme.mbcCrossoverLinePaint)
            if (i == draggingCrossover) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isLightTheme()) 0xFF474747.toInt() else 0xFFBBBBBB.toInt()
                    alpha = 40; style = Paint.Style.FILL
                }
                val glowPad = 24f
                val cornerR = 10f
                val glowRect = android.graphics.RectF(x - glowPad, vPad, x + glowPad, vPad + graphHeight)
                canvas.drawRoundRect(glowRect, cornerR, cornerR, glowPaint)
            }
        }

        // --- Draw draggable triangles ---
        val triPath = Path()
        for (i in 0 until bandCount) {
            val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
            val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
            val centerFreq = 10f.pow((log10(leftFreq) + log10(rightFreq)) / 2f)
            val dotX = freqToX(centerFreq, graphWidth)
            val dotY = vPad + graphHeight * (1f - (gains[i] - minGain) / (maxGain - minGain))

            val isSelected = (i == selectedBand)
            val isDraggingGain = (draggingBand == i)
            val r = 28f
            val cornerRadius = 8f
            val bandColor = if (bandColors != null && i < bandColors.size && bandColors[i] != 0) bandColors[i] else null

            // PostGain ▼
            triPath.reset()
            triPath.moveTo(dotX, dotY)
            triPath.lineTo(dotX - r * 0.866f, dotY - r * 1.5f)
            triPath.lineTo(dotX + r * 0.866f, dotY - r * 1.5f)
            triPath.close()

            val roundedBgPaint = Paint(theme.pointBgPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
            canvas.drawPath(triPath, roundedBgPaint)

            val showGainHalo = isDraggingGain || (haloAlpha > 0f && haloType == 1 && haloBand == i && draggingBand < 0)
            if (showGainHalo) {
                val triCenterY = dotY - r * 1.0f
                val haloDp = 24f * view.resources.displayMetrics.density
                theme.mbcTriTouchPaint.alpha = (haloAlpha * 0x38).toInt()
                canvas.drawCircle(dotX, triCenterY, haloDp, theme.mbcTriTouchPaint)
            }

            if (bandColor != null) {
                if (isSelected) {
                    val colorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = bandColor; style = Paint.Style.FILL; alpha = 200
                        pathEffect = CornerPathEffect(cornerRadius)
                    }
                    canvas.drawPath(triPath, colorFillPaint)
                }
                val colorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bandColor; style = Paint.Style.STROKE; strokeWidth = if (isSelected) 3f else 2f
                    pathEffect = CornerPathEffect(cornerRadius)
                }
                canvas.drawPath(triPath, colorRingPaint)
            } else if (isSelected) {
                val fillPaint = Paint(theme.activePointFillPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                val ringPaint = Paint(theme.activePointRingPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                canvas.drawPath(triPath, fillPaint)
                canvas.drawPath(triPath, ringPaint)
            } else {
                val ringPaint = Paint(theme.pointRingPaint).apply { pathEffect = CornerPathEffect(cornerRadius) }
                canvas.drawPath(triPath, ringPaint)
            }
        }
    }

    // ── Animation ───────────────────────────────────────────────
    fun animateHaloIn() {
        haloAnimator?.cancel()
        haloAnimator = android.animation.ValueAnimator.ofFloat(haloAlpha, 1f).apply {
            duration = 100
            addUpdateListener { haloAlpha = it.animatedValue as Float; view.invalidate() }
            start()
        }
    }

    fun animateHaloOut() {
        haloAnimator?.cancel()
        haloAnimator = android.animation.ValueAnimator.ofFloat(haloAlpha, 0f).apply {
            duration = 200
            addUpdateListener { haloAlpha = it.animatedValue as Float; view.invalidate() }
            start()
        }
    }

    // ── Touch handling ──────────────────────────────────────────
    fun handleTouch(event: android.view.MotionEvent, crossovers: FloatArray, gains: FloatArray): Boolean {
        val graphWidth = view.width.toFloat()
        val vPad = 80f
        val graphHeight = view.height - 2 * vPad
        val bandCount = crossovers.size + 1
        val hitRadius = 50f

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                draggingCrossover = -1
                draggingBand = -1

                var bestDist = hitRadius
                var bestBand = -1

                for (i in 0 until bandCount) {
                    val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
                    val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
                    val centerFreq = 10f.pow((log10(leftFreq) + log10(rightFreq)) / 2f)
                    val dotX = freqToX(centerFreq, graphWidth)

                    val dotY = vPad + graphHeight * (1f - (gains[i] - minGain) / (maxGain - minGain))
                    val triCenterY = dotY - 28f * 0.75f
                    val distGain = kotlin.math.sqrt(
                        ((event.x - dotX) * (event.x - dotX) + (event.y - triCenterY) * (event.y - triCenterY)).toDouble()
                    ).toFloat()
                    if (distGain < bestDist) {
                        bestDist = distGain; bestBand = i
                    }
                }

                if (bestBand >= 0) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    // Double-tap detection with external state (lastTapTime/lastTapBand — stored externally)
                    if (bestBand == lastMbcTapBand && now - lastMbcTapTime < 300L) {
                        gains[bestBand] = 0f
                        onBandGainChanged?.invoke(bestBand, 0f)
                        onBandGainReset?.invoke(bestBand)
                        lastMbcTapTime = 0L
                        lastMbcTapBand = -1
                        view.invalidate()
                        return true
                    }
                    lastMbcTapTime = now
                    lastMbcTapBand = bestBand

                    draggingBand = bestBand
                    selectedBand = bestBand
                    haloType = 1
                    haloBand = bestBand
                    onBandSelected?.invoke(bestBand)
                    animateHaloIn()
                    view.invalidate()
                    return true
                }

                // Check for crossover line touch
                for (i in crossovers.indices) {
                    val lineX = freqToX(crossovers[i], graphWidth)
                    if (abs(event.x - lineX) < hitRadius) {
                        draggingCrossover = i
                        return true
                    }
                }

                // Tap on band region
                val tappedFreq = freqToXInverse(event.x, graphWidth)
                for (i in 0 until bandCount) {
                    val leftFreq = if (i == 0) graphMinFreq else crossovers[i - 1]
                    val rightFreq = if (i == bandCount - 1) graphMaxFreq else crossovers[i]
                    if (tappedFreq in leftFreq..rightFreq) {
                        selectedBand = i
                        onBandSelected?.invoke(i)
                        view.invalidate()
                        return true
                    }
                }
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (draggingBand >= 0) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val newGain = yToGain(event.y, vPad, graphHeight).coerceIn(minGain, maxGain)
                    gains[draggingBand] = newGain
                    onBandGainChanged?.invoke(draggingBand, newGain)
                    view.invalidate()
                    return true
                }
                if (draggingCrossover >= 0) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val newFreq = freqToXInverse(event.x, graphWidth)
                    val minFreq = if (draggingCrossover == 0) 30f
                                  else crossovers[draggingCrossover - 1] * 1.2f
                    val maxFreq = if (draggingCrossover == crossovers.size - 1) 18000f
                                  else crossovers[draggingCrossover + 1] / 1.2f
                    crossovers[draggingCrossover] = newFreq.coerceIn(minFreq, maxFreq)
                    onCrossoverChanged?.invoke(draggingCrossover, crossovers[draggingCrossover])
                    view.invalidate()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (draggingBand >= 0) animateHaloOut()
                draggingCrossover = -1
                draggingBand = -1
            }
        }
        return true
    }

    // ── Double-tap state (shared with EqGraphTouchHandler) ──────
    private var lastMbcTapTime = 0L
    private var lastMbcTapBand = -1

    // ── Coordinate helpers ──────────────────────────────────────
    private fun freqToX(freq: Float, graphWidth: Float): Float {
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = log10(freq)
        return graphWidth * (logFreq - logMin) / (logMax - logMin)
    }

    private fun freqToXInverse(x: Float, graphWidth: Float): Float {
        val normalizedX = (x / graphWidth).coerceIn(0f, 1f)
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = logMin + normalizedX * (logMax - logMin)
        return 10f.pow(logFreq).coerceIn(graphMinFreq, graphMaxFreq)
    }

    private fun yToGain(y: Float, vPad: Float, graphHeight: Float): Float {
        val normalizedY = ((y - vPad) / graphHeight).coerceIn(0f, 1f)
        return maxGain - normalizedY * (maxGain - minGain)
    }

    private fun isLightTheme(): Boolean =
        (view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}
