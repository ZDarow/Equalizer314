package com.bearinmind.equalizer314.measurement

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.bearinmind.equalizer314.autoeq.AutoEqProfile
import com.bearinmind.equalizer314.autoeq.EqFitter
import com.bearinmind.equalizer314.autoeq.FreqResponse
import com.bearinmind.equalizer314.autoeq.FreqResponseParser
import com.bearinmind.equalizer314.dsp.FFT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Движок замера АЧХ.
 *
 * Алгоритм:
 * 1. Генерируется розовый шум (10 сек, 48 кГц)
 * 2. Шум проигрывается через динамик/наушники
 * 3. Одновременно записывается с микрофона
 * 4. Transfer function вычисляется через Welch's averaged periodogram:
 *    H(f) = sqrt(avg(|FFT(recorded)|²) / avg(|FFT(original)|²))
 * 5. Сглаживание 1/3 октавы
 * 6. Интерполяция на логарифмическую сетку частот
 * 7. EqFitter.computeCorrection() для подбора EQ-фильтров
 */
class FreqMeasurementEngine {

    companion object {
        private const val TAG = "FreqMeasurement"
        private const val SAMPLE_RATE = 48000
        private const val FFT_SIZE = 8192
        private const val HOP_SIZE = FFT_SIZE / 2 // 50% overlap
        private const val MEASUREMENT_DURATION_MS = 10000
        private const val SMOOTHING_OCTAVE_FRACTION = 3 // 1/3 октавы
    }

    /** Состояние замера */
    enum class State { IDLE, MEASURING, PROCESSING, DONE, ERROR }

    /** Результат замера */
    data class MeasurementResult(
        val measuredFreq: FloatArray,           // частоты (log-spaced)
        val measuredLevel: FloatArray,          // измеренная АЧХ (dB)
        val correctionProfile: AutoEqProfile,   // подобранные EQ-фильтры
        val totalSamples: Int                   // количество обработанных сэмплов
    )

    private val audioCapture = AudioCapture()

    /**
     * Выполняет полный цикл замера: проигрывание + запись + анализ + подбор EQ.
     *
     * @param signalType  тип тестового сигнала (по умолчанию розовый шум)
     * @param targetFreq  целевая АЧХ (null = плоская)
     * @param numBands    количество полос EQ для подбора
     * @param onProgress  callback прогресса (0.0 .. 1.0)
     */
    suspend fun runMeasurement(
        signalType: TestSignalGenerator.SignalType = TestSignalGenerator.SignalType.PINK_NOISE,
        targetFreq: FreqResponse? = null,
        numBands: Int = 10,
        onProgress: (Float) -> Unit = {}
    ): MeasurementResult = withContext(Dispatchers.IO) {
        onProgress(0f)
        Log.d(TAG, "Начало замера АЧХ, сигнал: ${signalType.label}")

        // 1. Генерация тестового сигнала
        val testSignal = TestSignalGenerator.generate(
            type = signalType,
            durationSec = MEASUREMENT_DURATION_MS / 1000f,
            sampleRate = SAMPLE_RATE
        )
        Log.d(TAG, "Сгенерирован тестовый сигнал '${signalType.label}': ${testSignal.size} сэмплов")
        onProgress(0.05f)

        // 2. Запуск записи
        if (!audioCapture.start(MEASUREMENT_DURATION_MS + 2000)) {
            throw SecurityException("Не удалось запустить запись с микрофона. Проверьте разрешение RECORD_AUDIO.")
        }
        onProgress(0.1f)

        // 3. Проигрывание тестового сигнала
        val audioTrack = createAudioTrack()
        try {
            audioTrack.play()

            // Пишем сигнал блоками
            val chunkSize = 2048
            var offset = 0
            var samplesWritten = 0
            while (offset < testSignal.size) {
                val chunk = minOf(chunkSize, testSignal.size - offset)
                val buffer = ShortArray(chunk) { i ->
                    (testSignal[offset + i] * Short.MAX_VALUE.toFloat()).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
                audioTrack.write(buffer, 0, chunk)
                offset += chunk
                samplesWritten += chunk

                // Прогресс: 10% – 70% (проигрывание)
                val progress = 0.1f + 0.6f * (samplesWritten.toFloat() / testSignal.size)
                onProgress(progress)
            }
        } finally {
            audioTrack.stop()
            audioTrack.release()
        }

        // 4. Остановка записи и сбор данных
        audioCapture.stop()
        val recordedSamples = audioCapture.getSamples()
        Log.d(TAG, "Записано сэмплов: ${recordedSamples.size}")
        onProgress(0.75f)

        // 5. Вычисление transfer function
        val tf = computeTransferFunction(testSignal, recordedSamples)
        onProgress(0.85f)

        // 6. Сглаживание 1/3 октавы
        val smoothed = octaveSmooth(tf.first, tf.second, SMOOTHING_OCTAVE_FRACTION)
        onProgress(0.9f)

        // 7. Интерполяция на логарифмическую сетку (128 точек)
        val logFreqs = FreqResponseParser.logSpace(128, 20f, 20000f)
        val interpolated = FreqResponseParser.interpolateAt(
            FreqResponse(smoothed.first, smoothed.second), logFreqs
        )
        onProgress(0.93f)

        // 8. Подбор EQ-фильтров
        val measurement = FreqResponse(logFreqs, interpolated)
        val target = targetFreq ?: FreqResponse(
            logFreqs,
            FloatArray(logFreqs.size) { 0f }
        )

        val profile = EqFitter.computeCorrection(measurement, target, numBands)
        Log.d(TAG, "Подобрано фильтров: ${profile.filters.size}")
        onProgress(1f)

        MeasurementResult(
            measuredFreq = logFreqs,
            measuredLevel = interpolated,
            correctionProfile = profile,
            totalSamples = recordedSamples.size
        )
    }

    /**
     * Создаёт AudioTrack для воспроизведения тестового сигнала.
     */
    private fun createAudioTrack(): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(FFT_SIZE * 2) // 2 байта на сэмпл (16-bit)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /**
     * Вычисляет transfer function методом Welch's averaged periodogram.
     *
     * @param original  исходный тестовый сигнал
     * @param recorded  записанный с микрофона сигнал
     * @return пара (частоты, уровни в dB)
     */
    private fun computeTransferFunction(
        original: FloatArray,
        recorded: FloatArray
    ): Pair<FloatArray, FloatArray> {
        val fft = FFT(FFT_SIZE)

        // Суммы периодограмм (линейная шкала)
        val origPowerSum = DoubleArray(FFT_SIZE / 2) { 0.0 }
        val recPowerSum = DoubleArray(FFT_SIZE / 2) { 0.0 }
        var segments = 0

        // Hann window
        val window = FloatArray(FFT_SIZE) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
        }

        // Выравнивание: обрезаем до меньшей длины
        val len = minOf(original.size, recorded.size)
        val maxStart = len - FFT_SIZE

        if (maxStart <= 0) {
            Log.w(TAG, "Слишком мало сэмплов для FFT: original=${original.size}, recorded=${recorded.size}")
            val freqs = FloatArray(FFT_SIZE / 2) { i ->
                i.toFloat() * SAMPLE_RATE / FFT_SIZE
            }
            return freqs to FloatArray(FFT_SIZE / 2) { -96f }
        }

        var start = 0
        while (start + FFT_SIZE <= len) {
            val origFrame = FloatArray(FFT_SIZE) { i ->
                original[start + i] * window[i]
            }
            val recFrame = FloatArray(FFT_SIZE) { i ->
                recorded[start + i] * window[i]
            }

            val origSpec = fft.computePowerSpectrum(
                fft.applyWindow(origFrame) as DoubleArray?
                    ?: DoubleArray(FFT_SIZE) { origFrame[it].toDouble() }
            )
            val recSpec = fft.computePowerSpectrum(
                fft.applyWindow(recFrame) as DoubleArray?
                    ?: DoubleArray(FFT_SIZE) { recFrame[it].toDouble() }
            )

            // Накапливаем — используем только первую половину (положительные частоты)
            for (i in 0 until FFT_SIZE / 2) {
                origPowerSum[i] += origSpec[i]
                recPowerSum[i] += recSpec[i]
            }
            segments++
            start += HOP_SIZE
        }

        // Усреднение и вычисление H(f)
        val freqs = FloatArray(FFT_SIZE / 2) { i ->
            i.toFloat() * SAMPLE_RATE / FFT_SIZE
        }
        val magnitudeDb = FloatArray(FFT_SIZE / 2)

        for (i in 0 until FFT_SIZE / 2) {
            val avgOrig = origPowerSum[i] / segments
            val avgRec = recPowerSum[i] / segments

            if (avgOrig > 1e-15 && avgRec > 1e-15) {
                val h = sqrt(avgRec / avgOrig)
                magnitudeDb[i] = (20.0 * kotlin.math.log10(h)).toFloat()
            } else {
                magnitudeDb[i] = -96f
            }
        }

        Log.d(TAG, "Transfer function: $segments сегментов, ${freqs.size} частотных бинов")
        return freqs to magnitudeDb
    }

    /**
     * Сглаживание АЧХ по октавным полосам.
     *
     * @param freqs     массив частот
     * @param levels    массив уровней (dB)
     * @param fraction  знаменатель октавы (3 = 1/3 октавы, 1 = 1 октава)
     * @return сглаженная пара (частоты, уровни)
     */
    private fun octaveSmooth(
        freqs: FloatArray,
        levels: FloatArray,
        fraction: Int = 3
    ): Pair<FloatArray, FloatArray> {
        val smoothed = levels.copyOf()

        for (i in freqs.indices) {
            if (freqs[i] <= 0f) continue

            val fc = freqs[i]
            val fLow = fc / 2.0.pow(1.0 / (2 * fraction))
            val fHigh = fc * 2.0.pow(1.0 / (2 * fraction))

            var sum = 0.0
            var count = 0
            for (j in freqs.indices) {
                if (freqs[j] in fLow..fHigh) {
                    sum += levels[j]
                    count++
                }
            }
            if (count > 0) {
                smoothed[i] = (sum / count).toFloat()
            }
        }

        return freqs to smoothed
    }

    /**
     * Освобождает ресурсы движка.
     */
    fun release() {
        audioCapture.release()
    }
}
