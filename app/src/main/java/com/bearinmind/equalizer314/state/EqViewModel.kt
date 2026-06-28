package com.bearinmind.equalizer314.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.ui.EqGraphView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AndroidViewModel that bridges [EqStateManager] and [EqPreferencesManager] to Compose/UI
 * via [StateFlow] properties. Writes go through setter methods that update state, persist
 * preferences, and sync reactive flows in a single call.
 */
class EqViewModel(application: Application) : AndroidViewModel(application) {

    val eqPrefs: EqPreferencesManager = EqPreferencesManager(application)
    val stateManager: EqStateManager = EqStateManager(application, eqPrefs)

    // ---- Reactive state flows ----

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

    private val _parametricEq = MutableStateFlow(stateManager.parametricEq)
    val parametricEq: StateFlow<ParametricEqualizer> = _parametricEq.asStateFlow()

    private val _activeChannel = MutableStateFlow(EqStateManager.ActiveChannel.BOTH)
    val activeChannel: StateFlow<EqStateManager.ActiveChannel> = _activeChannel.asStateFlow()

    private val _limiterEnabled = MutableStateFlow(true)
    val limiterEnabled: StateFlow<Boolean> = _limiterEnabled.asStateFlow()

    private val _limiterAttackMs = MutableStateFlow(1f)
    val limiterAttackMs: StateFlow<Float> = _limiterAttackMs.asStateFlow()

    private val _limiterReleaseMs = MutableStateFlow(60f)
    val limiterReleaseMs: StateFlow<Float> = _limiterReleaseMs.asStateFlow()

    private val _limiterRatio = MutableStateFlow(10f)
    val limiterRatio: StateFlow<Float> = _limiterRatio.asStateFlow()

    private val _limiterThresholdDb = MutableStateFlow(-2f)
    val limiterThresholdDb: StateFlow<Float> = _limiterThresholdDb.asStateFlow()

    private val _limiterPostGainDb = MutableStateFlow(0f)
    val limiterPostGainDb: StateFlow<Float> = _limiterPostGainDb.asStateFlow()

    private val _channelBalancePercent = MutableStateFlow(0)
    val channelBalancePercent: StateFlow<Int> = _channelBalancePercent.asStateFlow()

    private val _leftChannelGainDb = MutableStateFlow(0f)
    val leftChannelGainDb: StateFlow<Float> = _leftChannelGainDb.asStateFlow()

    private val _rightChannelGainDb = MutableStateFlow(0f)
    val rightChannelGainDb: StateFlow<Float> = _rightChannelGainDb.asStateFlow()

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    private val _eqService = MutableStateFlow<EqService?>(null)
    val eqService: StateFlow<EqService?> = _eqService.asStateFlow()

    private val _bandSlots = MutableStateFlow<List<Int>>(emptyList())
    val bandSlots: StateFlow<List<Int>> = _bandSlots.asStateFlow()

    private val _bandColors = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val bandColors: StateFlow<Map<Int, Int>> = _bandColors.asStateFlow()

    private val _displayToBandIndex = MutableStateFlow<List<Int>>(emptyList())
    val displayToBandIndex: StateFlow<List<Int>> = _displayToBandIndex.asStateFlow()

    // ---- Sync from stateManager ----

    private fun syncFromStateManager() {
        _isProcessing.value = stateManager.isProcessing
        _currentEqUiMode.value = stateManager.currentEqUiMode
        _selectedBandIndex.value = stateManager.selectedBandIndex
        _preampGainDb.value = stateManager.preampGainDb
        _autoGainEnabled.value = stateManager.autoGainEnabled
        _eqEnabled.value = stateManager.parametricEq.isEnabled
        _parametricEq.value = stateManager.parametricEq
        _activeChannel.value = stateManager.activeChannel
        _limiterEnabled.value = stateManager.limiterEnabled
        _limiterAttackMs.value = stateManager.limiterAttackMs
        _limiterReleaseMs.value = stateManager.limiterReleaseMs
        _limiterRatio.value = stateManager.limiterRatio
        _limiterThresholdDb.value = stateManager.limiterThresholdDb
        _limiterPostGainDb.value = stateManager.limiterPostGainDb
        _channelBalancePercent.value = stateManager.channelBalancePercent
        _leftChannelGainDb.value = stateManager.leftChannelGainDb
        _rightChannelGainDb.value = stateManager.rightChannelGainDb
        _serviceBound.value = stateManager.serviceBound
        _eqService.value = stateManager.eqService
        _bandSlots.value = stateManager.bandSlots.toList()
        _bandColors.value = stateManager.bandColors.toMap()
        _displayToBandIndex.value = stateManager.displayToBandIndex
    }

    /** Pull all state from [stateManager] into the reactive [StateFlow] properties. */
    fun syncAll() {
        syncFromStateManager()
    }

    // ---- Initialisation ----

    /** Initialise the EQ graph and restore persisted state. Must be called once
     *  after the ViewModel is created with the live [EqGraphView] reference. */
    fun init(graphView: EqGraphView) {
        stateManager.initEq(graphView)
        syncFromStateManager()

        stateManager.onProcessingChanged = { processing ->
            _isProcessing.value = processing
        }

        stateManager.onServiceConnected = {
            syncFromStateManager()
        }
    }

    // ---- Setter wrappers (state + StateFlow + prefs persistence) ----

    fun setActiveChannel(channel: EqStateManager.ActiveChannel) {
        stateManager.setActiveChannel(channel)
        syncFromStateManager()
    }

    fun setChannelSideEqEnabled(enabled: Boolean) {
        stateManager.setChannelSideEqEnabled(enabled)
        syncFromStateManager()
    }

    fun setPreampGain(gain: Float) {
        stateManager.preampGainDb = gain
        _preampGainDb.value = gain
        eqPrefs.savePreampGain(gain)
    }

    fun setAutoGainEnabled(enabled: Boolean) {
        stateManager.autoGainEnabled = enabled
        _autoGainEnabled.value = enabled
        eqPrefs.saveAutoGainEnabled(enabled)
    }

    /** Toggle the EQ on/off. When processing is active the change is pushed to
     *  the audio service immediately. */
    fun setEqEnabled(enabled: Boolean) {
        stateManager.parametricEq.isEnabled = enabled
        _eqEnabled.value = enabled
        if (stateManager.isProcessing) {
            stateManager.eqService?.setEqEnabled(enabled)
        }
    }

    /** Switch the EQ editing mode and persist the choice. */
    fun setEqUiMode(mode: EqUiMode) {
        stateManager.currentEqUiMode = mode
        _eqUiMode.value = mode
        stateManager.saveState()
    }

    fun selectBand(index: Int) {
        stateManager.selectedBandIndex = index
        _selectedBandIndex.value = index
    }

    // ---- Limiter setters ----

    fun setLimiterEnabled(enabled: Boolean) {
        stateManager.limiterEnabled = enabled
        _limiterEnabled.value = enabled
        eqPrefs.saveLimiterEnabled(enabled)
    }

    fun setLimiterAttackMs(ms: Float) {
        stateManager.limiterAttackMs = ms
        _limiterAttackMs.value = ms
        eqPrefs.saveLimiterAttack(ms)
    }

    fun setLimiterReleaseMs(ms: Float) {
        stateManager.limiterReleaseMs = ms
        _limiterReleaseMs.value = ms
        eqPrefs.saveLimiterRelease(ms)
    }

    fun setLimiterRatio(ratio: Float) {
        stateManager.limiterRatio = ratio
        _limiterRatio.value = ratio
        eqPrefs.saveLimiterRatio(ratio)
    }

    fun setLimiterThresholdDb(db: Float) {
        stateManager.limiterThresholdDb = db
        _limiterThresholdDb.value = db
        eqPrefs.saveLimiterThreshold(db)
    }

    fun setLimiterPostGainDb(db: Float) {
        stateManager.limiterPostGainDb = db
        _limiterPostGainDb.value = db
        eqPrefs.saveLimiterPostGain(db)
    }

    // ---- Channel balance / gain setters ----

    fun setChannelBalancePercent(pct: Int) {
        stateManager.channelBalancePercent = pct
        _channelBalancePercent.value = pct
        eqPrefs.saveChannelBalancePercent(pct)
    }

    fun setLeftChannelGainDb(db: Float) {
        stateManager.leftChannelGainDb = db
        _leftChannelGainDb.value = db
        eqPrefs.saveLeftChannelGainDb(db)
    }

    fun setRightChannelGainDb(db: Float) {
        stateManager.rightChannelGainDb = db
        _rightChannelGainDb.value = db
        eqPrefs.saveRightChannelGainDb(db)
    }

    // ---- Wrapper methods for EqStateManager imperative operations ----

    /** Push the current EQ band state + preamp to the DynamicsProcessor immediately. */
    fun pushEqUpdate() {
        stateManager.pushEqUpdate()
    }

    /** Coalesce rapid EQ updates into at most one write per ~16 ms frame. */
    fun pushEqUpdateThrottled() {
        stateManager.pushEqUpdateThrottled()
    }

    /** Cancel any pending throttled update and push the current state immediately. */
    fun flushEqUpdate() {
        stateManager.flushEqUpdate()
    }

    /** Replace the in-memory EQ state from a parsed preset's band specs.
     *  Handles both shared ("both") and per-channel (CSE) layouts. */
    fun applyPresetEqs(
        cseEnabled: Boolean,
        bothBands: List<EqStateManager.BandSpec>,
        leftBands: List<EqStateManager.BandSpec>,
        rightBands: List<EqStateManager.BandSpec>,
    ) {
        stateManager.applyPresetEqs(cseEnabled, bothBands, leftBands, rightBands)
        syncFromStateManager()
    }

    /** Persist all current EQ state (bands, slots, preamp, limiter, etc.) to preferences. */
    fun saveState() {
        stateManager.saveState()
    }

    /** Load a named preset into the current EQ and refresh the graph. */
    fun loadPreset(name: String, graphView: EqGraphView) {
        stateManager.loadPreset(name, graphView)
        syncFromStateManager()
    }

    fun initBandSlots() {
        stateManager.initBandSlots()
        _bandSlots.value = stateManager.bandSlots.toList()
    }

    /** Bind the audio service, sync all DSP params, and start DynamicsProcessing. */
    fun doStartEq(animatePower: (Boolean) -> Unit) {
        stateManager.doStartEq(animatePower)
        syncFromStateManager()
    }

    /** Unbind the service and stop DynamicsProcessing. */
    fun stopProcessing(animatePower: (Boolean) -> Unit) {
        stateManager.stopProcessing(animatePower)
        syncFromStateManager()
    }

    /** Return the EQ pair to apply to left/right channels — identical shared EQs
     *  in BOTH mode, or independent L/R EQs in CSE mode. */
    fun getChannelEqs(): Pair<ParametricEqualizer, ParametricEqualizer> =
        stateManager.getChannelEqs()

    /** Save left/right EQ bands separately when Channel Side EQ is active. */
    fun persistLeftRightIfCse() {
        stateManager.persistLeftRightIfCse()
    }

    /** Initiate EQ processing: check permissions, request notification access,
     *  start the service, and bind to it. Falls through to [doStartEq] when bound. */
    fun startProcessing(doStartEq: () -> Unit, animatePower: (Boolean) -> Unit) {
        stateManager.startProcessing(doStartEq, animatePower)
    }

    fun pushChannelSettingsUpdate() {
        stateManager.pushChannelSettingsUpdate()
    }

    fun pushLimiterUpdate() {
        stateManager.pushLimiterUpdate()
    }

    fun getAutoGainOffset(): Float = stateManager.getAutoGainOffset()

    fun getFilterIconRes(filterType: BiquadFilter.FilterType): Int =
        stateManager.getFilterIconRes(filterType)

    fun getFilterIconForBand(index: Int): Int? =
        stateManager.getFilterIconForBand(index)

    // ---- Direct mutable collection access ----

    val bandSlotsList: MutableList<Int>
        get() = stateManager.bandSlots

    val bandColorsMap: MutableMap<Int, Int>
        get() = stateManager.bandColors

    override fun onCleared() {
        super.onCleared()
        stateManager.saveState()
    }
}
