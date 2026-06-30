package com.bearinmind.equalizer314.measurement

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Генератор тестовых сигналов для замера АЧХ и калибровки.
 *
 * ## Типы шумов (спектральная плотность)
 *
 * | Тип | Наклон | Характер |
 * |-----|--------|----------|
 * | Белый (white) | 0 dB/окт | Равномерный — все частоты одинаковой энергии |
 * | Розовый (pink) | −3 dB/окт | Естественный — энергия убывает с частотой |
 * | Коричневый (brown) | −6 dB/окт | Глубокий бас, как рокот океана |
 * | Синий (blue) | +3 dB/окт | Акцент на ВЧ, шипящий |
 * | Фиолетовый (violet) | +6 dB/окт | Резкий, свистящий ВЧ |
 * | Серый (grey) | psychoacoustic | Субъективно плоский (Equal-loudness contour) |
 *
 * ## Типы измерительных сигналов
 *
 * | Сигнал | Описание |
 * |--------|----------|
 * | Лог. свип | Экспоненциальный sine sweep — точный FR, разделение гармоник |
 * | Линейный свип | Линейный sine sweep — равномерное время на октаву |
 * | Ступенчатый тон | Дискретные частоты — максимум энергии на каждой |
 * | MLS | Псевдослучайная бинарная последовательность — помехоустойчивый |
 * | Multi-tone | Сумма синусов — быстрое измерение, 8 фиксированных частот |
 * | Импульс (Dirac) | Единичный отсчёт — измерение импульсной характеристики |
 */
object TestSignalGenerator {

    private const val DEFAULT_SAMPLE_RATE = 48000
    private val rng = Random(42) // фиксированное семя для воспроизводимости

    // ========================================================================
    // ТИПЫ СИГНАЛОВ
    // ========================================================================

    /** Типы тестовых сигналов, доступные для выбора. */
    enum class SignalType(
        val label: String,
        val description: String,
        val defaultDurationSec: Float
    ) {
        PINK_NOISE("Розовый шум", "1/f, Paul Kellet, 7 octaves", 10f),
        PINK_NOISE_FFT("Розовый шум (FFT)", "1/f, спектральное формирование", 8f),
        WHITE_NOISE("Белый шум", "Равномерный спектр", 5f),
        BROWN_NOISE("Коричневый шум", "1/f², глубокий бас", 10f),
        BLUE_NOISE("Синий шум", "+3 dB/окт, ВЧ акцент", 5f),
        VIOLET_NOISE("Фиолетовый шум", "+6 dB/окт, свистящий", 5f),
        GREY_NOISE("Серый шум", "Психоакустически плоский", 8f),
        LOG_SWEEP("Лог. свип", "20 Гц – 20 кГц, экспоненциальный", 8f),
        LINEAR_SWEEP("Линейный свип", "20 Гц – 20 кГц, линейный", 8f),
        STEPPED_TONE("Ступенчатый тон", "12 дискретных частот, 500 мс каждая", 6f),
        MULTI_TONE("Multi-tone", "8 синусов одновременно", 4f),
        MLS("MLS", "Maximum Length Sequence, max помехоуст.", 6f),
        IMPULSE("Импульс (Dirac)", "Единичный отсчёт для IR", 2f),
    }

    // ========================================================================
    // ШУМЫ: цветные
    // ========================================================================

    /**
     * Генерирует сигнал по указанному типу.
     * Единая точка входа для всех типов.
     */
    fun generate(
        type: SignalType = SignalType.PINK_NOISE,
        durationSec: Float = type.defaultDurationSec,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        freqStart: Float = 20f,
        freqEnd: Float = 20000f
    ): FloatArray = when (type) {
        SignalType.PINK_NOISE -> generatePinkNoise(durationSec, sampleRate)
        SignalType.PINK_NOISE_FFT -> generatePinkNoiseFFT(durationSec, sampleRate)
        SignalType.WHITE_NOISE -> generateWhiteNoise(durationSec, sampleRate)
        SignalType.BROWN_NOISE -> generateBrownNoise(durationSec, sampleRate)
        SignalType.BLUE_NOISE -> generateBlueNoise(durationSec, sampleRate)
        SignalType.VIOLET_NOISE -> generateVioletNoise(durationSec, sampleRate)
        SignalType.GREY_NOISE -> generateGreyNoise(durationSec, sampleRate)
        SignalType.LOG_SWEEP -> generateSweep(durationSec, sampleRate, freqStart, freqEnd)
        SignalType.LINEAR_SWEEP -> generateLinearSweep(durationSec, sampleRate, freqStart, freqEnd)
        SignalType.STEPPED_TONE -> generateSteppedTone(durationSec, sampleRate, freqStart, freqEnd)
        SignalType.MULTI_TONE -> generateMultiTone(durationSec, sampleRate)
        SignalType.MLS -> generateMLS(durationSec, sampleRate)
        SignalType.IMPULSE -> generateImpulse(durationSec, sampleRate)
    }

    // ------------------------------------------------------------------
    // Розовый шум (Paul Kellet, 7-каскадный)
    // ------------------------------------------------------------------
    fun generatePinkNoise(
        durationSec: Float = 10f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples)
        val b = FloatArray(7) { 0f }

        for (i in 0 until numSamples) {
            val white = rng.nextFloat() * 2f - 1f
            b[0] = 0.99886f * b[0] + white * 0.0555179f
            b[1] = 0.99332f * b[1] + white * 0.0750759f
            b[2] = 0.96900f * b[2] + white * 0.1538520f
            b[3] = 0.86650f * b[3] + white * 0.3104856f
            b[4] = 0.55000f * b[4] + white * 0.5329522f
            b[5] = -0.7616f * b[5] - white * 0.0168980f
            val pink = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + white * 0.5362f) * 0.11f
            b[6] = white * 0.115926f
            samples[i] = pink.coerceIn(-1f, 1f)
        }
        return samples
    }

    // ------------------------------------------------------------------
    // Розовый шум (FFT-метод — спектральное формирование белого шума)
    // Более точный pink noise, но требует FFT и больше памяти.
    // ------------------------------------------------------------------
    fun generatePinkNoiseFFT(
        durationSec: Float = 8f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        // Генерируем белый шум
        val white = FloatArray(numSamples) { rng.nextFloat() * 2f - 1f }
        // Применяем формирующий фильтр 1/f через БПФ
        val fftSize = 1
        var n = 1
        while (n < numSamples) n *= 2

        // Используем перегрузку FFT с действительными и мнимыми массивами
        val re = FloatArray(n) { if (it < numSamples) white[it] else 0f }
        val im = FloatArray(n) { 0f }

        // In-place Cooley-Tukey FFT
        fftInPlace(re, im)

        // Применяем спад 1/√f (для амплитудного спектра, чтобы получить 1/f по мощности)
        for (i in 1 until n / 2) {
            val f = i.toFloat() / n * sampleRate
            val scale = if (f > 0f) 1f / sqrt(f) else 1f
            re[i] *= scale
            im[i] *= scale
            // Зеркальная половина
            re[n - i] *= scale
            im[n - i] *= scale
        }
        re[0] = 0f // DC = 0

        // Обратное БПФ
        ifftInPlace(re, im)

        // Нормализация
        val maxVal = re.take(numSamples).maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val norm = 0.95f / maxVal
        return FloatArray(numSamples) { (re[it] * norm).coerceIn(-1f, 1f) }
    }

    // ------------------------------------------------------------------
    // Белый шум
    // ------------------------------------------------------------------
    fun generateWhiteNoise(
        durationSec: Float = 5f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        return FloatArray(numSamples) { (rng.nextFloat() * 2f - 1f) }
    }

    // ------------------------------------------------------------------
    // Коричневый (Brownian / Red) шум — 1/f²
    // Интегрированный белый шум.
    // ------------------------------------------------------------------
    fun generateBrownNoise(
        durationSec: Float = 10f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples)
        var last = 0f
        for (i in 0 until numSamples) {
            val white = rng.nextFloat() * 2f - 1f
            last += white * 0.02f
            // Anti-windup: не даём уйти в бесконечность
            last = last.coerceIn(-1f, 1f)
            samples[i] = last
        }
        // Нормализация к [-0.95, 0.95]
        val maxVal = samples.maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val norm = 0.95f / maxVal
        for (i in samples.indices) samples[i] = (samples[i] * norm).coerceIn(-1f, 1f)
        return samples
    }

    // ------------------------------------------------------------------
    // Синий шум — +3 dB/окт
    // Разность между соседними отсчётами белого шума.
    // ------------------------------------------------------------------
    fun generateBlueNoise(
        durationSec: Float = 5f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val white = generateWhiteNoise(durationSec, sampleRate)
        val samples = FloatArray(numSamples)
        for (i in 1 until numSamples) {
            samples[i] = (white[i] - white[i - 1]) * 0.5f
        }
        samples[0] = white[0] * 0.5f
        // Нормализация
        val maxVal = samples.maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val norm = 0.95f / maxVal
        for (i in samples.indices) samples[i] = (samples[i] * norm).coerceIn(-1f, 1f)
        return samples
    }

    // ------------------------------------------------------------------
    // Фиолетовый шум — +6 dB/окт
    // Вторая разность белого шума.
    // ------------------------------------------------------------------
    fun generateVioletNoise(
        durationSec: Float = 5f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val white = generateWhiteNoise(durationSec, sampleRate)
        val samples = FloatArray(numSamples)
        for (i in 2 until numSamples) {
            samples[i] = (white[i] - 2f * white[i - 1] + white[i - 2]) * 0.25f
        }
        // Нормализация
        val maxVal = samples.maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val norm = 0.95f / maxVal
        for (i in samples.indices) samples[i] = (samples[i] * norm).coerceIn(-1f, 1f)
        return samples
    }

    // ------------------------------------------------------------------
    // Серый шум — психоакустически плоский
    // Pink noise + коррекция на equal-loudness contour (ISO 226)
    // Аппроксимация: pink + gentle shelf. Для 48кГц.
    // ------------------------------------------------------------------
    fun generateGreyNoise(
        durationSec: Float = 8f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val pink = generatePinkNoiseFFT(durationSec, sampleRate)

        // Коррекция equal-loudness: аппроксимируем inverse A-weighting
        // через фильтр низких частот. Простая shelf-коррекция.
        // Grey noise по Moorer: pink noise + high-frequency boost
        val numSamples = pink.size
        val samples = FloatArray(numSamples)
        var lp1 = 0f
        var lp2 = 0f
        val a = 0.9f // полюс фильтра
        val boost = 2.5f // подъём ВЧ
        for (i in 0 until numSamples) {
            lp1 = a * lp1 + (1f - a) * pink[i]
            lp2 = a * lp2 + (1f - a) * lp1
            // Разность = ВЧ-составляющая; pink + boost * (original - lp)
            val hf = pink[i] - lp2
            samples[i] = (pink[i] + boost * hf).coerceIn(-1f, 1f)
        }

        val maxVal = samples.maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val norm = 0.95f / maxVal
        for (i in samples.indices) samples[i] = (samples[i] * norm).coerceIn(-1f, 1f)
        return samples
    }

    // ========================================================================
    // ИЗМЕРИТЕЛЬНЫЕ СИГНАЛЫ
    // ========================================================================

    // ------------------------------------------------------------------
    // Логарифмический sine sweep
    // ------------------------------------------------------------------
    fun generateSweep(
        durationSec: Float = 8f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        freqStart: Float = 20f,
        freqEnd: Float = 20000f
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples)
        val L = durationSec / ln((freqEnd / freqStart).toDouble())

        val fadeLen = (0.02f * sampleRate).toInt() // 20 ms fade in/out
        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val phase = 2.0 * PI * freqStart * L * (exp(t / L) - 1.0)
            samples[i] = sin(phase).toFloat()
            // Fade in/out
            when {
                i < fadeLen -> samples[i] *= (1f - cos(PI.toFloat() * i / fadeLen)) * 0.5f
                i > numSamples - fadeLen -> {
                    val j = numSamples - i
                    samples[i] *= (1f - cos(PI.toFloat() * j / fadeLen)) * 0.5f
                }
            }
        }
        return samples
    }

    // ------------------------------------------------------------------
    // Линейный sine sweep
    // ------------------------------------------------------------------
    fun generateLinearSweep(
        durationSec: Float = 8f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        freqStart: Float = 20f,
        freqEnd: Float = 20000f
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples)
        val fadeLen = (0.02f * sampleRate).toInt()

        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val f = freqStart + (freqEnd - freqStart) * (t / durationSec)
            val phase = 2.0 * PI * (freqStart * t + (freqEnd - freqStart) * t * t / (2f * durationSec))
            samples[i] = sin(phase).toFloat()
            when {
                i < fadeLen -> samples[i] *= (1f - cos(PI.toFloat() * i / fadeLen)) * 0.5f
                i > numSamples - fadeLen -> {
                    val j = numSamples - i
                    samples[i] *= (1f - cos(PI.toFloat() * j / fadeLen)) * 0.5f
                }
            }
        }
        return samples
    }

    // ------------------------------------------------------------------
    // Ступенчатый тон — дискретные частоты, каждая длится N секунд
    // ------------------------------------------------------------------
    fun generateSteppedTone(
        durationSec: Float = 6f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        freqStart: Float = 20f,
        freqEnd: Float = 20000f
    ): FloatArray {
        // 12 частот на октаву (полутона) от 20 Гц до 20 кГц
        val freqs = generateLogFrequencies(12, freqStart, freqEnd)
        val stepLen = (durationSec / freqs.size * sampleRate).toInt().coerceAtLeast(sampleRate / 10)
        val totalLen = stepLen * freqs.size
        val samples = FloatArray(totalLen)
        val fadeLen = (stepLen * 0.02f).toInt().coerceAtLeast(4)

        for (si in freqs.indices) {
            val offset = si * stepLen
            for (i in 0 until stepLen) {
                val t = i.toFloat() / sampleRate
                samples[offset + i] = sin(2.0 * PI * freqs[si] * t).toFloat()
                // Fade in/out для каждого шага
                when {
                    i < fadeLen -> {
                        val gain = (1f - cos(PI.toFloat() * i / fadeLen)) * 0.5f
                        samples[offset + i] *= gain
                    }
                    i > stepLen - fadeLen -> {
                        val j = stepLen - i
                        val gain = (1f - cos(PI.toFloat() * j / fadeLen)) * 0.5f
                        samples[offset + i] *= gain
                    }
                }
            }
        }
        return samples
    }

    // ------------------------------------------------------------------
    // Multi-tone — сумма синусов на 8 ключевых частотах
    // ------------------------------------------------------------------
    fun generateMultiTone(
        durationSec: Float = 4f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        // 8 ключевых частот (1/3 октавы)
        val freqs = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples)
        val fadeLen = (0.02f * sampleRate).toInt()

        // Каждая частота со случайной фазой для снижения crest factor
        val phases = FloatArray(freqs.size) { rng.nextFloat() * 2f * PI.toFloat() }
        val nFreqs = freqs.size.toFloat()

        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            var sum = 0f
            for (fi in freqs.indices) {
                sum += sin(2.0 * PI * freqs[fi] * t + phases[fi]).toFloat()
            }
            samples[i] = sum / nFreqs // нормализация амплитуды
            // Fade
            when {
                i < fadeLen -> samples[i] *= (1f - cos(PI.toFloat() * i / fadeLen)) * 0.5f
                i > numSamples - fadeLen -> {
                    val j = numSamples - i
                    samples[i] *= (1f - cos(PI.toFloat() * j / fadeLen)) * 0.5f
                }
            }
        }
        return samples
    }

    // ------------------------------------------------------------------
    // MLS (Maximum Length Sequence) — псевдослучайная бинарная последовательность
    // Лучшая помехоустойчивость, хороша для noisy environments.
    // ------------------------------------------------------------------
    fun generateMLS(
        durationSec: Float = 6f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()

        // LFSR: x^15 + x^14 + 1 (период 32767)
        val mlsPeriod = 32767
        val mls = ShortArray(mlsPeriod)
        var reg = 1 // seed
        for (i in 0 until mlsPeriod) {
            mls[i] = if ((reg and 0x1) == 1) 1 else -1
            val bit = ((reg shr 14) xor (reg shr 13)) and 0x1
            reg = ((reg shl 1) or bit) and 0x7FFF
        }

        // Повторяем MLS до заполнения буфера
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            samples[i] = mls[i % mlsPeriod].toFloat() * 0.9f
        }

        val fadeLen = (0.02f * sampleRate).toInt()
        for (i in 0 until fadeLen) {
            val gain = (1f - cos(PI.toFloat() * i / fadeLen)) * 0.5f
            samples[i] *= gain
            samples[numSamples - 1 - i] *= gain
        }
        return samples
    }

    // ------------------------------------------------------------------
    // Импульс (Dirac delta) — единичный отсчёт
    // ------------------------------------------------------------------
    fun generateImpulse(
        durationSec: Float = 2f,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(numSamples) { 0f }
        samples[numSamples / 4] = 0.95f // импульс на 25% длительности
        return samples
    }

    // ========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // ========================================================================

    /**
     * Генерирует логарифмическую сетку частот.
     * @param n           количество точек
     * @param minHz       минимальная частота
     * @param maxHz       максимальная частота
     */
    fun generateLogFrequencies(
        n: Int = 12,
        minHz: Float = 20f,
        maxHz: Float = 20000f
    ): FloatArray {
        val logMin = ln(minHz.toDouble())
        val logMax = ln(maxHz.toDouble())
        return FloatArray(n) { i ->
            exp(logMin + i * (logMax - logMin) / (n - 1)).toFloat()
        }
    }

    /**
     * Создаёт плоскую (flat) целевую кривую АЧХ.
     */
    fun generateFlatTarget(
        n: Int = 128,
        minHz: Float = 20f,
        maxHz: Float = 20000f
    ): Pair<FloatArray, FloatArray> {
        val freqs = generateLogFrequencies(n, minHz, maxHz)
        val levels = FloatArray(n) { 0f }
        return freqs to levels
    }

    // ========================================================================
    // УТИЛИТЫ FFT
    // ========================================================================

    /**
     * In-place radix-2 Cooley-Tukey FFT.
     * Размер n обязан быть степенью двойки.
     */
    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var bits = 0
        while (1 shl bits < n) bits++

        // Bit-reversal permutation
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Cooley-Tukey radix-2
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val wRe = cos(2.0 * PI / len).toFloat()
            val wIm = -sin(2.0 * PI / len).toFloat()
            for (i in 0 until n step len) {
                var wr = 1f
                var wi = 0f
                for (j in 0 until halfLen) {
                    val k = i + j + halfLen
                    val tRe = wr * re[k] - wi * im[k]
                    val tIm = wr * im[k] + wi * re[k]
                    re[k] = re[i + j] - tRe
                    im[k] = im[i + j] - tIm
                    re[i + j] += tRe
                    im[i + j] += tIm
                    // Rotate
                    val newWr = wr * wRe - wi * wIm
                    wi = wr * wIm + wi * wRe
                    wr = newWr
                }
            }
            len *= 2
        }
    }

    /**
     * In-place обратное БПФ.
     */
    private fun ifftInPlace(re: FloatArray, im: FloatArray) {
        // Обратить знак мнимой части
        for (i in im.indices) im[i] = -im[i]
        fftInPlace(re, im)
        // Нормализация
        val n = re.size.toFloat()
        for (i in re.indices) {
            re[i] /= n
            im[i] = -im[i] / n
        }
    }
}
