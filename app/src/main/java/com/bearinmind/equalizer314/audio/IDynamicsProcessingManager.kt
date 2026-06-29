package com.bearinmind.equalizer314.audio

import com.bearinmind.equalizer314.dsp.ParametricEqualizer

/**
 * Интерфейс обёртки над Android [android.media.audiofx.DynamicsProcessing].
 *
 * 11 методов — необходимый минимум для DSP (lifecycle, EQ, MBC, limiter,
 * channel). Разделение на мелкие интерфейсы нецелесообразно: все операции
 * завязаны на один объект DynamicsProcessing.
 */
@Suppress("TooManyFunctions")
interface IDynamicsProcessingManager {

    // ---- Lifecycle ----

    /** Активен ли DSP в данный момент. */
    val isActive: Boolean

    /** Запустить DSP с заданным эквалайзером. */
    fun start(eq: ParametricEqualizer)

    /** Остановить DSP и освободить ресурсы. */
    fun stop()

    /** Пересоздать DSP на текущем аудиовыходе.
     *  Используется при смене маршрута (BT ↔ speaker). */
    fun reattachActive(): Boolean

    // ---- EQ update ----

    /** Обновить EQ из одного эквалайзера (для обоих каналов). */
    fun updateFromEqualizer(eq: ParametricEqualizer)

    /** Обновить EQ из пары (левый, правый) для per-channel режима. */
    fun updateFromEqualizers(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer)

    /** Включить/выключить DSP без полного stop/start. */
    fun setEnabled(enabled: Boolean)

    // ---- MBC ----

    /** Применить параметры MBC к DSP. */
    fun applyMbcBands(bands: List<DynamicsProcessingManager.MbcBandParams>, crossovers: FloatArray)

    // ---- Limiter ----

    /** Применить текущие параметры лимитера. */
    fun pushLimiterUpdate()

    // ---- Channel settings ----

    /** Применить настройки каналов (баланс, per-channel gain). */
    fun updateChannelSettings()

    // ---- Watchdog ----

    /** Проверить, не потерян ли контроль над аудиосессией. */
    fun hasLostControl(): Boolean

    /** Проверить, истёк ли cooldown на reclaim. */
    fun reclaimCooldownElapsed(): Boolean

    // ---- Properties (конфигурация перед start) ----

    var preampGainDb: Float
    var autoGainEnabled: Boolean
    var channelBalancePercent: Int
    var leftChannelGainDb: Float
    var rightChannelGainDb: Float

    var limiterEnabled: Boolean
    var limiterAttackMs: Float
    var limiterReleaseMs: Float
    var limiterRatio: Float
    var limiterThresholdDb: Float
    var limiterPostGainDb: Float

    var mbcEnabled: Boolean
    var mbcBandCount: Int
}
