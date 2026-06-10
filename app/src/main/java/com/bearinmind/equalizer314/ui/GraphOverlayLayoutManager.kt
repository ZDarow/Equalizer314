package com.bearinmind.equalizer314.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.bearinmind.equalizer314.R

/**
 * Manages positioning of toolbar/overlay buttons on top of the EQ graph.
 * The buttons overlay the graph at specific positions relative to the
 * 10 kHz grid line and the graph's right edge.
 *
 * Call [layoutButtons] inside `eqGraphView.post {}` so the graph view
 * has its final width.
 */
class GraphOverlayLayoutManager {

    private var density: Float = 0f
    private var viewWidth: Int = 0
    private var gapPx: Int = 0
    private var vPadPx: Int = 80
    private var btnTop: Int = 0
    private var btnHeight: Int = 0
    private var specWidth: Int = 0
    private var specLeft: Int = 0
    private var editLeftPx: Int = 0
    private var resetLeftPx: Int = 0
    private var row2Top: Int = 0

    // Cached for [repositionChannelBadge]
    private var lastAltRouteLeft: Int = 0
    private var lastSpecWidth: Int = 0
    private var lastBtnTop: Int = 0

    /**
     * Compute button geometry from the graph view's current width.
     * Must be called from a `post {}` runnable after the graph is laid out.
     */
    fun layoutButtons(
        graphView: View,
        vararg buttons: Pair<Int, LayoutPosition>, // (viewId, position)
    ) {
        density = graphView.resources.displayMetrics.density
        viewWidth = graphView.width
        if (viewWidth <= 0) return

        gapPx = (2 * density).toInt()
        val gridLine10k = (viewWidth * 3.0 / 3.301).toInt()
        btnTop = gapPx
        btnHeight = vPadPx - 2 * gapPx
        specWidth = (viewWidth - gapPx) - (gridLine10k + gapPx)
        specLeft = gridLine10k + gapPx
        editLeftPx = (specLeft - gapPx - specWidth).coerceAtLeast(gapPx)
        resetLeftPx = (specLeft - 2 * (gapPx + specWidth)).coerceAtLeast(gapPx)
        row2Top = btnTop + btnHeight + gapPx

        lastAltRouteLeft = specLeft // will be overridden if altRoute is positioned
        lastSpecWidth = specWidth
        lastBtnTop = btnTop

        for ((viewId, position) in buttons) {
            val view = graphView.rootView.findViewById<View>(viewId) ?: continue
            val (left, top) = positionFor(position)
            reposition(view, left, top)
        }
    }

    /** Reposition a single view using cached geometry. */
    fun reposition(view: View, leftMargin: Int, topMargin: Int = btnTop) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = specWidth
        lp.height = btnHeight
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = leftMargin
        lp.topMargin = topMargin
        view.layoutParams = lp
        view.minimumWidth = 0
        view.minimumHeight = 0
        view.setPadding(0, 0, 0, 0)
    }

    /** Position the L/R badge in the top-right corner of the split icon. */
    fun repositionChannelBadge(badge: TextView, altRouteLeft: Int? = null) {
        if (density <= 0f) return
        val aLeft = altRouteLeft ?: lastAltRouteLeft
        badge.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val lp = badge.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = aLeft + lastSpecWidth - badge.measuredWidth - (6 * density).toInt()
        lp.topMargin = lastBtnTop + (5 * density).toInt()
        badge.layoutParams = lp
    }

    /** Default button layout: row 1 = band points, save, alt-route, settings,
     *  viz; row 2 = undo, redo, L, R; edit at the top triggers a popout.
     *  Row positions are determined by the [positionFor] mapping. */
    enum class LayoutPosition {
        /** Top-left corner (band points toggle). */
        BAND_POINTS,
        /** Row 1, after band points (save preset). */
        SAVE_PRESET,
        /** Row 1, after save (alt-route / split icon). */
        ALT_ROUTE,
        /** Row 1, after alt-route (settings gear popout). */
        SETTINGS_GEAR,
        /** Row 1, far right (visualizer toggle, below 10 kHz line). */
        VISUALIZER,
        /** Row 1, left of visualizer (edit button). */
        EDIT,
        /** Row 1, left of edit (reset button, hidden by default). */
        RESET,
        /** Row 2, below reset (undo). */
        UNDO,
        /** Row 2, below edit (redo). */
        REDO,
        /** Row 2, below alt-route (left channel). */
        CHANNEL_L,
        /** Row 2, below settings-gear (right channel). */
        CHANNEL_R,
    }

    private fun positionFor(pos: LayoutPosition): Pair<Int, Int> = when (pos) {
        LayoutPosition.BAND_POINTS -> gapPx to btnTop
        LayoutPosition.SAVE_PRESET -> (gapPx + specWidth + gapPx) to btnTop
        LayoutPosition.ALT_ROUTE -> {
            val left = gapPx + 2 * (specWidth + gapPx)
            lastAltRouteLeft = left
            left to btnTop
        }
        LayoutPosition.SETTINGS_GEAR -> (lastAltRouteLeft + specWidth + gapPx) to btnTop
        LayoutPosition.VISUALIZER -> specLeft to btnTop
        LayoutPosition.EDIT -> editLeftPx to btnTop
        LayoutPosition.RESET -> resetLeftPx to btnTop
        LayoutPosition.UNDO -> resetLeftPx to row2Top
        LayoutPosition.REDO -> editLeftPx to row2Top
        LayoutPosition.CHANNEL_L -> lastAltRouteLeft to row2Top
        LayoutPosition.CHANNEL_R -> (lastAltRouteLeft + specWidth + gapPx) to row2Top
    }
}
