package com.bearinmind.equalizer314.state

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for [EqViewModel] — validates reactive StateFlow
 * wrappers around [EqStateManager].
 *
 * Note: [EqViewModel.init] requires a real [EqGraphView] which is a
 * heavyweight custom view. The state-mutating methods tested here
 * do NOT require init() to be called first — they operate on the
 * underlying [stateManager] directly, and [syncFromStateManager] is
 * called where needed.
 */
@RunWith(RobolectricTestRunner::class)
class EqViewModelTest {

    private lateinit var viewModel: EqViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = EqViewModel(app)
    }

    @Test
    fun `initial preamp gain is 0`() {
        assertEquals(0f, viewModel.preampGainDb.value, 0.001f)
    }

    @Test
    fun `setPreampGain updates both state and flow`() {
        viewModel.setPreampGain(-3.5f)
        assertEquals(-3.5f, viewModel.stateManager.preampGainDb, 0.001f)
        assertEquals(-3.5f, viewModel.preampGainDb.value, 0.001f)
    }

    @Test
    fun `setAutoGainEnabled updates both state and flow`() {
        assertFalse(viewModel.autoGainEnabled.value)
        viewModel.setAutoGainEnabled(true)
        assertTrue(viewModel.stateManager.autoGainEnabled)
        assertTrue(viewModel.autoGainEnabled.value)
    }

    @Test
    fun `selectBand updates both state and flow`() {
        viewModel.selectBand(2)
        assertEquals(2, viewModel.stateManager.selectedBandIndex)
        assertEquals(2, viewModel.selectedBandIndex.value)
    }

    @Test
    fun `setEqUiMode updates both state and flow`() {
        viewModel.setEqUiMode(com.bearinmind.equalizer314.EqUiMode.GRAPHIC)
        assertEquals(
            com.bearinmind.equalizer314.EqUiMode.GRAPHIC,
            viewModel.stateManager.currentEqUiMode,
        )
        assertEquals(
            com.bearinmind.equalizer314.EqUiMode.GRAPHIC,
            viewModel.currentEqUiMode.value,
        )
    }

    @Test
    fun `isProcessing starts false`() {
        assertFalse(viewModel.isProcessing.value)
    }

    @Test
    fun `eqEnabled starts true`() {
        assertTrue(viewModel.eqEnabled.value)
    }

    @Test
    fun `currentEqUiMode starts PARAMETRIC`() {
        assertEquals(
            com.bearinmind.equalizer314.EqUiMode.PARAMETRIC,
            viewModel.currentEqUiMode.value,
        )
    }

    @Test
    fun `stateManager is accessible for imperative use`() {
        assertNotNull(viewModel.stateManager)
        assertNotNull(viewModel.stateManager.eqPrefs)
    }

    @Test
    fun `parametricEq delegates to stateManager`() {
        assertSame(viewModel.stateManager.parametricEq, viewModel.parametricEq.value)
    }

    @Test
    fun `setEqEnabled updates flow`() {
        viewModel.setEqEnabled(false)
        assertFalse(viewModel.eqEnabled.value)
        assertFalse(viewModel.stateManager.parametricEq.isEnabled)
    }

    @Test
    fun `setEqEnabled true`() {
        viewModel.setEqEnabled(true)
        assertTrue(viewModel.eqEnabled.value)
    }

    @Test
    fun `setChannelSideEqEnabled switches in-memory state`() {
        viewModel.setChannelSideEqEnabled(true)
        assertEquals(EqStateManager.ActiveChannel.LEFT, viewModel.stateManager.activeChannel)
    }

    @Test
    fun `setChannelSideEqEnabled off switches to BOTH`() {
        viewModel.setChannelSideEqEnabled(true)   // enable first
        viewModel.setChannelSideEqEnabled(false)  // disable
        assertEquals(EqStateManager.ActiveChannel.BOTH, viewModel.stateManager.activeChannel)
    }

    @Test
    fun `setActiveChannel when CSE is off is no-op`() {
        viewModel.setActiveChannel(EqStateManager.ActiveChannel.LEFT)
        // Without CSE enabled, active channel stays BOTH
        assertEquals(EqStateManager.ActiveChannel.BOTH, viewModel.stateManager.activeChannel)
    }

    @Test
    fun `setPreampGain updates state manager in memory`() {
        viewModel.setPreampGain(2.0f)
        assertEquals(2.0f, viewModel.stateManager.preampGainDb, 0.001f)
    }

    @Test
    fun `saveState persists preampGain to SharedPreferences`() {
        viewModel.setPreampGain(2.0f)
        viewModel.stateManager.saveState()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = app.getSharedPreferences("eq_settings", android.content.Context.MODE_PRIVATE)
        assertEquals(2.0f, prefs.getFloat("preampGain", 0f), 0.001f)
    }
}
