package com.bearinmind.equalizer314.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.ui.EqGraphView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lifecycle-aware ViewModel wrapping [EqStateManager] with reactive [StateFlow]
 * for key UI state.
 *
 * Migrate incrementally — add new StateFlow properties as needed while the
 * existing [stateManager] field remains accessible for direct imperative use.
 */
class EqViewModel(application: Application) : AndroidViewModel(application) {

    val eqPrefs: EqPreferencesManager = EqPreferencesManager(application)
    val stateManager: EqStateManager = EqStateManager(application, eqPrefs)

    // ---- Reactive state ----

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentEqUiMode = MutableStateFlow(EqUiMode.PARAMETRIC)
    val currentEqUiMode: StateFlow<EqUiMode> = _currentEqUiMode.asStateFlow()

    private val _selectedBandIndex = MutableStateFlow<Int?>(null)
    val selectedBandIndex: StateFlow<Int?> = _selectedBandIndex.asStateFlow()

    private val _preampGainDb = MutableStateFlow(0f)
    val preampGainDb: StateFlow<Float> = _preampGainDb.asStateFlow()

    private val _autoGainEnabled = MutableStateFlow(false)
    val autoGainEnabled: StateFlow<Boolean> = _autoGainEnabled.asStateFlow()

    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqUiMode = MutableStateFlow(EqUiMode.PARAMETRIC)
    val eqUiMode: StateFlow<EqUiMode> = _eqUiMode.asStateFlow()

    // Mirror state manager → StateFlow every time we sync
    private fun syncFromStateManager() {
        _isProcessing.value = stateManager.isProcessing
        _currentEqUiMode.value = stateManager.currentEqUiMode
        _selectedBandIndex.value = stateManager.selectedBandIndex
        _preampGainDb.value = stateManager.preampGainDb
        _autoGainEnabled.value = stateManager.autoGainEnabled
        _eqEnabled.value = stateManager.parametricEq.isEnabled
    }

    /** Initialise from persisted state and wire callbacks. */
    fun init(graphView: EqGraphView) {
        stateManager.initEq(graphView)
        syncFromStateManager()

        // Replace raw callbacks with StateFlow updates
        stateManager.onProcessingChanged = { processing ->
            _isProcessing.value = processing
        }
    }

    /** Wrapper around [EqStateManager.setActiveChannel] that syncs state. */
    fun setActiveChannel(channel: EqStateManager.ActiveChannel) {
        stateManager.setActiveChannel(channel)
        syncFromStateManager()
    }

    /** Wrapper around [EqStateManager.setChannelSideEqEnabled] that syncs state. */
    fun setChannelSideEqEnabled(enabled: Boolean) {
        stateManager.setChannelSideEqEnabled(enabled)
        syncFromStateManager()
    }

    /** Wrapper that syncs preamp gain and emits StateFlow update. */
    fun setPreampGain(gain: Float) {
        stateManager.preampGainDb = gain
        _preampGainDb.value = gain
    }

    /** Wrapper that syncs auto-gain and emits StateFlow update. */
    fun setAutoGainEnabled(enabled: Boolean) {
        stateManager.autoGainEnabled = enabled
        _autoGainEnabled.value = enabled
    }

    /** Update the EQ enabled state from the graph or toggle. */
    fun setEqEnabled(enabled: Boolean) {
        stateManager.parametricEq.isEnabled = enabled
        _eqEnabled.value = enabled
        if (stateManager.isProcessing) {
            stateManager.eqService?.setEqEnabled(enabled)
        }
    }

    /** Switch UI mode and sync. */
    fun setEqUiMode(mode: EqUiMode) {
        stateManager.currentEqUiMode = mode
        _eqUiMode.value = mode
    }

    /** Select a band index and sync. */
    fun selectBand(index: Int) {
        stateManager.selectedBandIndex = index
        _selectedBandIndex.value = index
    }

    override fun onCleared() {
        super.onCleared()
        stateManager.saveState()
    }

    /** Convenience access to the active EQ. */
    val parametricEq: ParametricEqualizer
        get() = stateManager.parametricEq
}
