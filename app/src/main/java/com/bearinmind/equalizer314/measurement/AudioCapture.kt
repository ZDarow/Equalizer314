package com.bearinmind.equalizer314.measurement

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над AudioRecord для захвата звука с микрофона.
 *
 * Использует:
 * - sampleRate = 48000 Гц (стандарт для Android)
 * - MONO, 16-bit PCM
 * - Буфер 4096 сэмплов
 */
class AudioCapture {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULT = 2
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordedSamples = mutableListOf<Short>()

    /**
     * Начинает запись с микрофона.
     *
     * @param durationMs максимальная длительность записи в мс (0 = без лимита)
     * @return true если запись успешно начата
     */
    fun start(durationMs: Int = 15000): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Уже записываем")
            return false
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            BUFFER_SIZE_MULT * 4096
        )

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет разрешения на запись", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Неверные параметры AudioRecord", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Не удалось инициализировать AudioRecord")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        recordedSamples.clear()
        isRecording.set(true)
        audioRecord?.startRecording()

        // Читаем в фоновом потоке
        Thread {
            val buffer = ShortArray(bufferSize / 2) // ShortArray = bufferSize / 2 байт
            val maxSamples = if (durationMs > 0) SAMPLE_RATE * durationMs / 1000 else Int.MAX_VALUE

            while (isRecording.get() && recordedSamples.size < maxSamples) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(recordedSamples) {
                        val remaining = maxSamples - recordedSamples.size
                        val toAdd = minOf(read, remaining)
                        recordedSamples.addAll(buffer.take(toAdd))
                    }
                } else if (read < 0) {
                    Log.w(TAG, "Ошибка чтения AudioRecord: $read")
                    break
                }
            }
            stop()
        }.apply {
            name = "AudioCapture-Reader"
            start()
        }

        Log.d(TAG, "Запись начата: bufferSize=$bufferSize, duration=$durationMs ms")
        return true
    }

    /**
     * Останавливает запись.
     */
    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord уже остановлен", e)
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Запись остановлена: ${recordedSamples.size} сэмплов")
    }

    /**
     * Возвращает записанные сэмплы как FloatArray [-1.0, 1.0].
     */
    fun getSamples(): FloatArray {
        val shorts: List<Short>
        synchronized(recordedSamples) {
            shorts = recordedSamples.toList()
        }
        return FloatArray(shorts.size) { i ->
            shorts[i].toFloat() / Short.MAX_VALUE.toFloat()
        }
    }

    /**
     * Возвращает количество записанных сэмплов.
     */
    fun getSampleCount(): Int {
        synchronized(recordedSamples) {
            return recordedSamples.size
        }
    }

    /**
     * Проверяет, идёт ли запись.
     */
    fun isActive(): Boolean = isRecording.get()

    /**
     * Освобождает ресурсы.
     */
    fun release() {
        stop()
        synchronized(recordedSamples) {
            recordedSamples.clear()
        }
    }
}
