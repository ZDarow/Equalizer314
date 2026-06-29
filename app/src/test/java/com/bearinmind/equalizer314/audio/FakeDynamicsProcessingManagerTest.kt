package com.bearinmind.equalizer314.audio

import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Демонстрация тестирования с [IDynamicsProcessingManager].
 *
 * Fake-реализация не требует Android-устройства и позволяет проверить
 * бизнес-логику без эмулятора. Рекомендуется расширять по мере
 * добавления логики в DSP-слой.
 */
class FakeDynamicsProcessingManagerTest {

    private val fake = FakeDpm()

    @Test
    fun `start activates DPM`() {
        fake.start(ParametricEqualizer())
        assertTrue(fake.isActive)
    }

    @Test
    fun `stop deactivates DPM`() {
        fake.start(ParametricEqualizer())
        fake.stop()
        assertFalse(fake.isActive)
    }

    @Test
    fun `start stores the last EQ`() {
        val eq = ParametricEqualizer()
        // ParametricEqualizer init создаёт 4 дефолтных полосы, добавляем ещё одну = 5
        val defaultCount = eq.getBandCount()
        eq.addBand(1000f, 3f, com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL)
        fake.start(eq)
        assertEquals(defaultCount + 1, fake.lastStartedEq?.getBandCount())
    }

    @Test
    fun `preamp gain is set`() {
        fake.preampGainDb = -5f
        assertEquals(-5f, fake.preampGainDb, 0.001f)
    }

    @Test
    fun `updateFromEqualizer does nothing when inactive`() {
        val eq = ParametricEqualizer()
        fake.updateFromEqualizer(eq)
        // Нет исключения — корректное no-op поведение
    }

    @Test
    fun `hasLostControl returns false by default`() {
        assertFalse(fake.hasLostControl())
    }

    @Test
    fun `reattachActive returns false when inactive`() {
        assertFalse(fake.reattachActive())
    }
}

/**
 * Простая fake-реализация [IDynamicsProcessingManager] для unit-тестов.
 */
class FakeDpm : IDynamicsProcessingManager {

    override var isActive: Boolean = false
        private set
    private var _lastStartedEq: ParametricEqualizer? = null
    val lastStartedEq: ParametricEqualizer? get() = _lastStartedEq

    override var preampGainDb: Float = 0f
    override var autoGainEnabled: Boolean = false
    override var channelBalancePercent: Int = 0
    override var leftChannelGainDb: Float = 0f
    override var rightChannelGainDb: Float = 0f

    override var limiterEnabled: Boolean = true
    override var limiterAttackMs: Float = 1f
    override var limiterReleaseMs: Float = 60f
    override var limiterRatio: Float = 10f
    override var limiterThresholdDb: Float = -2f
    override var limiterPostGainDb: Float = 0f

    override var mbcEnabled: Boolean = false
    override var mbcBandCount: Int = 3

    override fun start(eq: ParametricEqualizer) {
        _lastStartedEq = eq
        isActive = true
    }

    override fun stop() {
        isActive = false
    }

    override fun reattachActive(): Boolean = false

    override fun updateFromEqualizer(eq: ParametricEqualizer) {
        if (!isActive) return
        _lastStartedEq = eq
    }

    override fun updateFromEqualizers(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer) {
        if (!isActive) return
        _lastStartedEq = leftEq
    }

    override fun setEnabled(enabled: Boolean) { /* no-op: fake не управляет реальным DSP */ }

    override fun applyMbcBands(
        bands: List<DynamicsProcessingManager.MbcBandParams>,
        crossovers: FloatArray,
    ) { /* no-op: fake не управляет DSP */ }

    override fun pushLimiterUpdate() { /* no-op */ }

    override fun updateChannelSettings() { /* no-op */ }

    override fun hasLostControl(): Boolean = false

    override fun reclaimCooldownElapsed(): Boolean = false
}
