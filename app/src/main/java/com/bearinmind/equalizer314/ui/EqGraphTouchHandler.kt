package com.bearinmind.equalizer314.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.ui.EqGraphView.BandPoint
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Обработчик касаний для [EqGraphView].
 *
 * Управляет drag'ом точек EQ, double-tap reset, long-press,
 * определением активной полосы. Не имеет состояния визуализации —
 * все изменения применяются через коллбэки и [view.invalidate].
 */
class EqGraphTouchHandler(
    private val view: View,
) {
    // ── Double-tap detection ────────────────────────────────────
    private var lastTapTime = 0L
    private var lastTapBandIndex: Int? = null
    private val doubleTapTimeout = 300L
    private var justResetBand = false

    // ── Drag threshold ──────────────────────────────────────────
    private var touchStartX = 0f
    private var touchStartY = 0f
    var isDragging = false
    private val dragThreshold = 8f

    // ── Long-press ──────────────────────────────────────────────
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val longPressTimeout = 500L

    // ── Callbacks ───────────────────────────────────────────────
    var onLongPress: (() -> Unit)? = null
    var onBandChanged: ((bandIndex: Int, frequency: Float, gain: Float) -> Unit)? = null
    var onBandSelected: ((bandIndex: Int?) -> Unit)? = null
    var onBandDragEnd: (() -> Unit)? = null

    // ── View data (set externally) ──────────────────────────────
    var bandPoints: List<BandPoint> = emptyList()
    var parametricEq: ParametricEqualizer? = null
    var activeBandIndex: Int? = null
    var readOnlyPoints: Boolean = false
    var showBandPoints: Boolean = true

    private val graphMinFreq = 10f
    private val graphMaxFreq = 20000f
    private val minGain = -20f
    private val maxGain = 20f

    // Default log-spaced frequencies for double-tap reset
    private val defaultFrequencies: List<Float> by lazy {
        ParametricEqualizer.logSpacedFrequencies(16).toList()
    }

    /**
     * Основной обработчик касаний. Возвращает true, если событие обработано.
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (readOnlyPoints) return false
        if (!showBandPoints) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                view.parent?.requestDisallowInterceptTouchEvent(true)

                cancelLongPressTimer()
                longPressRunnable = Runnable {
                    activeBandIndex = null
                    isDragging = false
                    onLongPress?.invoke()
                }
                longPressHandler.postDelayed(longPressRunnable!!, longPressTimeout)

                val tapped = findClosestPoint(event.x, event.y)
                activeBandIndex = tapped
                if (tapped != null) {
                    val currentTime = android.os.SystemClock.elapsedRealtime()
                    if (tapped == lastTapBandIndex && currentTime - lastTapTime < doubleTapTimeout) {
                        cancelLongPressTimer()
                        resetBandToZero(tapped)
                        justResetBand = true
                        lastTapTime = 0L
                        lastTapBandIndex = null
                        view.invalidate()
                        return true
                    }
                    lastTapTime = currentTime
                    lastTapBandIndex = tapped
                    isDragging = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    onBandSelected?.invoke(tapped)
                    view.invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (justResetBand) return true

                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (distance > dragThreshold) {
                    cancelLongPressTimer()
                }

                activeBandIndex?.let { index ->
                    if (!isDragging) {
                        if (distance < dragThreshold) return true
                        isDragging = true
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    updatePointPosition(event.x, event.y, index)
                    view.invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                view.parent?.requestDisallowInterceptTouchEvent(false)
                justResetBand = false
                val wasDragging = isDragging
                isDragging = false
                view.invalidate()
                if (wasDragging) onBandDragEnd?.invoke()
            }
        }
        return false
    }

    /**
     * Ищет ближайшую точку к координатам (x, y) в пределах 100px.
     */
    private fun findClosestPoint(x: Float, y: Float): Int? {
        var closestIndex: Int? = null
        var minDistance = Float.MAX_VALUE
        for (i in bandPoints.indices) {
            val point = bandPoints[i]
            val distance = hypot(x - point.x, y - point.y)
            if (distance < minDistance && distance < 100f) {
                minDistance = distance
                closestIndex = i
            }
        }
        return closestIndex
    }

    /**
     * Обновляет позицию точки при drag'е. Для LP/HP Y меняет Q, для остальных — gain.
     */
    private fun updatePointPosition(x: Float, y: Float, index: Int) {
        val vPad = 80f
        val graphWidth = view.width.toFloat()
        val graphHeight = view.height - 2 * vPad
        val point = bandPoints[index]

        val newFreq = xToFreq(x, graphWidth)
        point.frequency = newFreq

        val currentFilterType = parametricEq?.getBand(index)?.filterType ?: BiquadFilter.FilterType.BELL
        val isLpHp = currentFilterType == BiquadFilter.FilterType.LOW_PASS ||
            currentFilterType == BiquadFilter.FilterType.HIGH_PASS

        if (isLpHp) {
            val normalizedY = ((y - vPad) / graphHeight).coerceIn(0f, 1f)
            val newQ = (0.1 + (1f - normalizedY) * (12.0 - 0.1)).coerceIn(0.1, 12.0)
            parametricEq?.updateBand(index, newFreq, 0f, currentFilterType, newQ)
        } else {
            val newGain = yToGain(y, vPad, graphHeight)
            point.gain = newGain
            val currentQ = parametricEq?.getBand(index)?.q ?: 0.707
            parametricEq?.updateBand(index, newFreq, newGain, currentFilterType, currentQ)
        }
        onBandChanged?.invoke(index, newFreq, point.gain)
    }

    /**
     * Сбрасывает полосу на частоту по умолчанию и gain=0.
     */
    private fun resetBandToZero(bandIndex: Int) {
        val point = bandPoints[bandIndex]
        val defaultFreq = if (bandIndex < defaultFrequencies.size) defaultFrequencies[bandIndex] else point.frequency
        point.frequency = defaultFreq
        point.gain = 0f
        parametricEq?.updateBand(bandIndex, defaultFreq, 0f, BiquadFilter.FilterType.BELL, 0.707)
        onBandChanged?.invoke(bandIndex, defaultFreq, 0f)
        onBandSelected?.invoke(bandIndex)
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    fun cancelAll() {
        cancelLongPressTimer()
        isDragging = false
        justResetBand = false
    }

    // ── Coordinate helpers ──────────────────────────────────────
    private fun xToFreq(x: Float, graphWidth: Float): Float {
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

    private fun hypot(a: Float, b: Float): Float {
        return sqrt((a * a + b * b).toDouble()).toFloat()
    }
}
