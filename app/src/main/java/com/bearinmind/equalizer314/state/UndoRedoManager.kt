package com.bearinmind.equalizer314.state

import com.bearinmind.equalizer314.dsp.EqSerializer
import com.bearinmind.equalizer314.ui.BandToggleManager
import com.bearinmind.equalizer314.ui.EqGraphView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages EQ state history for undo/redo operations.
 *
 * Captures the full [ParametricEqualizer] band state as a JSON string
 * on each [saveState] call and navigates the history on [undo]/[redo].
 * Works with a mutable EQ instance so the history stores only the
 * band configuration (not the object reference).
 *
 * Thread safety: designed for single-thread (UI thread) access.
 *
 * @param eqViewModel Source of the live [ParametricEqualizer] state.
 * @param eqGraphView The graph view to update when restoring a state.
 * @param bandToggleManager Manages toggle visibility after state changes.
 */
class UndoRedoManager(
    private val eqViewModel: EqViewModel,
    private val eqGraphView: EqGraphView,
    private val bandToggleManager: BandToggleManager,
) {
    private val history = mutableListOf<String>()
    private var index = -1

    /** Whether [undo] can navigate backward. */
    val canUndo: Boolean get() = index > 0

    /** Whether [redo] can navigate forward. */
    val canRedo: Boolean get() = index < history.size - 1

    /** Save the current EQ state, trimming any "future" states. */
    fun saveState() {
        val eq = eqViewModel.parametricEq.value
        val json = EqSerializer.eqToPresetJson(eq, eqViewModel.preampGainDb.value)
        // Trim future states if we're not at the end
        while (history.size > index + 1) {
            history.removeAt(history.size - 1)
        }
        history.add(json)
        index = history.size - 1
    }

    /** Restore a previous EQ state from history. */
    fun undo() {
        if (!canUndo) return
        index--
        restoreAt(index)
    }

    /** Restore a more recent EQ state from history. */
    fun redo() {
        if (!canRedo) return
        index++
        restoreAt(index)
    }

    /** Reset history — called when the EQ is fully replaced (preset load, reset). */
    fun reset() {
        history.clear()
        index = -1
        saveState()
    }

    // ── internal ────────────────────────────────────────────────────────

    private fun restoreAt(idx: Int) {
        val jsonStr = history[idx]
        val eq = eqViewModel.parametricEq.value
        val obj = JSONObject(jsonStr)
        val bandsArr = obj.optJSONArray("bands") ?: return
        val preamp = obj.optDouble("preamp", 0.0).toFloat()

        EqSerializer.loadBandsTo(eq, bandsArr)
        eqGraphView.setParametricEqualizer(eq)
        eqViewModel.eqPrefs.saveState(eq)
        eqViewModel.persistLeftRightIfCse()
        eqViewModel.initBandSlots()
        bandToggleManager.setupToggles()

        if (preamp != 0f) {
            eqViewModel.setPreampGain(preamp)
        }

        if (eqViewModel.isProcessing.value) {
            eqViewModel.eqService.value?.let { svc ->
                svc.dynamicsManager.stop()
                svc.dynamicsManager.run {
                    requestedBandCount = eqViewModel.eqPrefs.getDpBandCount()
                    start(eq)
                }
            }
        }
    }
}
