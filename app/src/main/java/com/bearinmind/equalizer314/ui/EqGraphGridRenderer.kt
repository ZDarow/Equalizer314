package com.bearinmind.equalizer314.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.log10
import kotlin.math.pow

/**
 * Отрисовка сетки и подписей для [EqGraphView].
 *
 * Содержит только stateless rendering-методы и координатные преобразования.
 * Не имеет состояния — все параметры передаются через аргументы.
 */
class EqGraphGridRenderer(
    private val theme: EqGraphTheme,
) {
    private val graphMinFreq = 10f
    private val graphMaxFreq = 20000f

    // ── Grid drawing ────────────────────────────────────────────
    fun drawGrid(
        canvas: Canvas,
        vPad: Float,
        graphWidth: Float,
        graphHeight: Float,
        minGain: Float,
        maxGain: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        val dbSteps = 4
        for (i in 0..dbSteps) {
            val y = vPad + (graphHeight * i / dbSteps)
            val db = maxGain - (maxGain - minGain) * i / dbSteps
            val dbLabel = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            val labelWidth = theme.textPaint.measureText(dbLabel)
            val lineStartX = 10f + labelWidth + 6f
            canvas.drawLine(lineStartX, y, viewWidth, y, theme.gridPaint)
        }

        val freqMarkers = listOf(100f, 1000f, 10000f)
        for (freq in freqMarkers) {
            if (freq >= graphMinFreq && freq <= graphMaxFreq) {
                val x = freqToX(freq, graphWidth)
                canvas.drawLine(x, 0f, x, viewHeight, theme.gridPaint)
            }
        }

        // Graph border
        canvas.drawLine(0f, 0f, 0f, viewHeight, theme.gridPaint)
        canvas.drawLine(graphWidth, 0f, graphWidth, viewHeight, theme.gridPaint)
        val topLabel = "+${maxGain.toInt()}"
        val topLabelEnd = 10f + theme.textPaint.measureText(topLabel) + 6f
        canvas.drawLine(topLabelEnd, vPad, graphWidth, vPad, theme.gridPaint)
        canvas.drawLine(0f, vPad + graphHeight, graphWidth, vPad + graphHeight, theme.gridPaint)
    }

    // ── Labels ──────────────────────────────────────────────────
    fun drawGridLabels(
        canvas: Canvas,
        vPad: Float,
        graphWidth: Float,
        graphHeight: Float,
        minGain: Float,
        maxGain: Float,
    ) {
        val labelPaint = Paint(theme.textPaint)

        val dbSteps = 4
        for (i in 0..dbSteps) {
            val y = vPad + (graphHeight * i / dbSteps)
            val db = maxGain - (maxGain - minGain) * i / dbSteps
            val dbLabel = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            val textY = if (i == 0) {
                val bounds = Rect()
                labelPaint.getTextBounds(dbLabel, 0, dbLabel.length, bounds)
                y - bounds.top.toFloat() - 3f
            } else y + 8f
            canvas.drawText(dbLabel, 10f, textY, labelPaint)
        }

        val freqMarkers = listOf(100f, 1000f, 10000f)
        for (freq in freqMarkers) {
            if (freq >= graphMinFreq && freq <= graphMaxFreq) {
                val x = freqToX(freq, graphWidth)
                val freqLabel = when {
                    freq >= 1000f -> "${(freq / 1000).toInt()}k"
                    else -> "${freq.toInt()}"
                }
                val labelWidth = labelPaint.measureText(freqLabel)
                canvas.drawText(freqLabel, x - labelWidth / 2f, vPad + graphHeight + 30f, labelPaint)
            }
        }
    }

    // ── Coordinate conversions ──────────────────────────────────
    fun freqToX(freq: Float, graphWidth: Float): Float {
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = log10(freq)
        return graphWidth * (logFreq - logMin) / (logMax - logMin)
    }

    fun xToFreq(x: Float, graphWidth: Float): Float {
        val normalizedX = (x / graphWidth).coerceIn(0f, 1f)
        val logMin = log10(graphMinFreq)
        val logMax = log10(graphMaxFreq)
        val logFreq = logMin + normalizedX * (logMax - logMin)
        return 10f.pow(logFreq).coerceIn(graphMinFreq, graphMaxFreq)
    }

    fun yToGain(y: Float, vPad: Float, graphHeight: Float, minGain: Float, maxGain: Float): Float {
        val normalizedY = ((y - vPad) / graphHeight).coerceIn(0f, 1f)
        return maxGain - normalizedY * (maxGain - minGain)
    }

    fun formatFrequency(hz: Int): String {
        return when {
            hz >= 1000 -> {
                val kHz = hz / 1000.0
                if (kHz >= 10) "${kHz.toInt()}k"
                else if (kHz % 1.0 == 0.0) "${kHz.toInt()}k"
                else java.lang.String.format(java.util.Locale.US, "%.1fk", kHz)
            }
            else -> "$hz"
        }
    }
}
