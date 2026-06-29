package com.bearinmind.equalizer314.ui

import android.content.res.Resources
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Все Paint-объекты для [EqGraphView] с поддержкой светлой темы.
 *
 * Создаётся один раз в конструкторе View. [applyLightTheme] вызывается
 * при загрузке ресурсов, если активна светлая тема. Каждый Paint
 * доступен как публичное val для использования в методах отрисовки.
 *
 * Порядок инициализации: все Paint-декларации выполняются в порядке
 * объявления свойств, затем [applyLightTheme] перекрашивает их для
 * светлой темы (если ресурсы сообщают [UI_MODE_NIGHT_NO]).
 */
class EqGraphTheme(resources: Resources) {

    // ── MBC paints ──────────────────────────────────────────────
    val mbcCrossoverLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAABBBBBB.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    val mbcCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    val mbcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    val mbcDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    val mbcDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    val mbcTriTouchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14AAAAAA.toInt()
        style = Paint.Style.FILL
    }

    // ── Grid paints ─────────────────────────────────────────────
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    // ── Curve paints ────────────────────────────────────────────
    val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // ── Point paints ────────────────────────────────────────────
    val pointBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        style = Paint.Style.FILL
    }

    val pointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    val activePointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    val activePointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBBBBBB.toInt()
        style = Paint.Style.FILL
    }

    val disabledPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    val disabledPointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val pointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val activePointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Text paints ─────────────────────────────────────────────
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 24f
    }

    val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 22f
    }

    // ── Saturation paint ────────────────────────────────────────
    val saturatedCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF9800.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // ── Spectrum paints ─────────────────────────────────────────
    val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Graphic mode paints ─────────────────────────────────────
    val graphicBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88AAAAAA.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    val graphicConnectLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // ── Label paints ────────────────────────────────────────────
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 20f
    }

    val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
        style = Paint.Style.FILL
    }

    val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // ── Colored ring/fill/number paints ─────────────────────────
    val coloredRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    val coloredFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val coloredNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt()
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Light theme init ────────────────────────────────────────
    init {
        if (isLightTheme(resources)) {
            applyLightTheme()
        }
    }

    /**
     * Перекрашивает все Paint-объекты для светлой темы.
     * Вызывается из [init], если активна светлая тема.
     */
    private fun applyLightTheme() {
        gridPaint.color = 0xFFCFCFCF.toInt()
        curvePaint.color = 0xFF585858.toInt()
        pointBgPaint.color = 0xFFE4E4E4.toInt()
        pointRingPaint.color = 0xFF585858.toInt()
        activePointRingPaint.color = 0xFF363636.toInt()
        activePointFillPaint.color = 0xFF474747.toInt()
        activePointNumberPaint.color = 0xFFFFFFFF.toInt()
        disabledPointPaint.color = 0xFFADADAD.toInt()
        disabledPointNumberPaint.color = 0xFF8B8B8B.toInt()
        pointNumberPaint.color = 0xFF111111.toInt()
        textPaint.color = 0xFF7A7A7A.toInt()
        titleTextPaint.color = 0xFF111111.toInt()
        spectrumLinePaint.color = 0xFF7A7A7A.toInt()
        mbcCurvePaint.color = 0xFF252525.toInt()
        mbcFillPaint.color = 0x18000000
        mbcDotPaint.color = 0xFF111111.toInt()
        mbcDotRingPaint.color = 0xFF585858.toInt()
        mbcCrossoverLinePaint.color = 0xAA474747.toInt()
        mbcTriTouchPaint.color = 0x14585858
        graphicBarPaint.color = 0x88585858.toInt()
        graphicConnectLinePaint.color = 0xFF363636.toInt()
        labelPaint.color = 0xFF585858.toInt()
        labelBgPaint.color = 0xFFE6E6E6.toInt()
        labelStrokePaint.color = 0xFFBEBEBE.toInt()
    }

    companion object {
        /**
         * Определяет, активна ли светлая тема по флагу [UI_MODE_NIGHT_NO].
         */
        private fun isLightTheme(resources: Resources): Boolean =
            (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
