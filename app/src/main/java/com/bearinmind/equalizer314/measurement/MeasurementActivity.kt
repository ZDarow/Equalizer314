package com.bearinmind.equalizer314.measurement

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.autoeq.AutoEqFilter
import com.bearinmind.equalizer314.autoeq.AutoEqProfile
import com.bearinmind.equalizer314.autoeq.FreqResponse
import com.bearinmind.equalizer314.autoeq.FreqResponseParser
import com.bearinmind.equalizer314.dsp.EqSerializer
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.PresetManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Активность для замера АЧХ, расчёта коррекции и создания пресета EQ.
 *
 * Workflow:
 * 1. Запрос разрешения RECORD_AUDIO
 * 2. Нажатие «Начать замер» → проигрывание тестового сигнала + запись
 * 3. Обработка → вычисление transfer function → подбор EQ
 * 4. Отображение результата (измеренная АЧХ + кривая коррекции)
 * 5. Сохранение пресета через PresetManager
 */
class MeasurementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MeasurementActivity"
        private const val DEFAULT_NUM_BANDS = 10
    }

    // UI
    private lateinit var startButton: Button
    private lateinit var backButton: ImageButton
    private lateinit var savePresetButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultInfo: TextView
    private lateinit var resultLabel: TextView
    private lateinit var resultCurveContainer: View
    private lateinit var statusSection: View
    private lateinit var refCurveContainer: View
    private lateinit var instructions: TextView
    private lateinit var signalSpinner: Spinner

    // Состояние
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var measurementJob: Job? = null
    private var lastResult: FreqMeasurementEngine.MeasurementResult? = null

    // Зависимости
    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var presetManager: PresetManager
    private val engine = FreqMeasurementEngine()

    // Запрос разрешения
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMeasurement()
        } else {
            Toast.makeText(
                this,
                getString(R.string.measurement_no_record_permission),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freq_measurement)

        eqPrefs = EqPreferencesManager(this)
        presetManager = PresetManager(
            getSharedPreferences("custom_presets", MODE_PRIVATE)
        )

        initViews()
    }

    private fun initViews() {
        startButton = findViewById(R.id.startMeasurementButton)
        backButton = findViewById(R.id.measurementBackButton)
        savePresetButton = findViewById(R.id.savePresetButton)
        statusText = findViewById(R.id.measurementStatusText)
        progressBar = findViewById(R.id.measurementProgress)
        resultInfo = findViewById(R.id.resultInfo)
        resultLabel = findViewById(R.id.resultLabel)
        resultCurveContainer = findViewById(R.id.resultCurveContainer)
        statusSection = findViewById(R.id.statusSection)
        refCurveContainer = findViewById(R.id.refCurveContainer)
        instructions = findViewById(R.id.measurementInstructions)

        // Инициализация выбора типа сигнала
        signalSpinner = findViewById(R.id.signalTypeSpinner)
        val signalTypes = TestSignalGenerator.SignalType.entries.toTypedArray()
        val signalLabels = signalTypes.map { it.label }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, signalLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        signalSpinner.adapter = adapter
        // По умолчанию — розовый шум
        val defaultIdx = signalTypes.indexOfFirst { it == TestSignalGenerator.SignalType.PINK_NOISE }
        if (defaultIdx >= 0) signalSpinner.setSelection(defaultIdx)

        startButton.setOnClickListener { requestRecordPermission() }
        backButton.setOnClickListener { finish() }
        savePresetButton.setOnClickListener { showSaveDialog() }
    }

    private fun requestRecordPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> {
                startMeasurement()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(
                    this,
                    getString(R.string.measurement_permission_rationale),
                    Toast.LENGTH_LONG
                ).show()
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startMeasurement() {
        if (measurementJob?.isActive == true) {
            Toast.makeText(this, getString(R.string.measurement_in_progress), Toast.LENGTH_SHORT).show()
            return
        }

        // Показываем статус
        startButton.isEnabled = false
        statusSection.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.measurement_playing)
        resultLabel.visibility = View.GONE
        resultCurveContainer.visibility = View.GONE
        resultInfo.visibility = View.GONE
        savePresetButton.visibility = View.GONE
        instructions.visibility = View.GONE

        // Определяем выбранный тип сигнала
        val selectedType = TestSignalGenerator.SignalType.entries[
            signalSpinner.selectedItemPosition.coerceIn(
                0,
                TestSignalGenerator.SignalType.entries.size - 1
            )
        ]
        statusText.text = getString(R.string.measurement_playing) + " (${selectedType.label})"

        measurementJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.runMeasurement(
                        signalType = selectedType,
                        numBands = DEFAULT_NUM_BANDS,
                        onProgress = { progress ->
                            launch(Dispatchers.Main) {
                                progressBar.progress = (progress * 100).toInt()
                                statusText.text = when {
                                    progress < 0.1f -> getString(R.string.measurement_preparing)
                                    progress < 0.75f -> getString(R.string.measurement_playing)
                                    progress < 0.9f -> getString(R.string.measurement_processing)
                                    progress < 1f -> getString(R.string.measurement_calculating_eq)
                                    else -> getString(R.string.measurement_done)
                                }
                            }
                        }
                    )
                }
                showResult(result)
            } catch (e: SecurityException) {
                statusText.text = getString(R.string.measurement_error)
                Toast.makeText(this@MeasurementActivity, e.message, Toast.LENGTH_LONG).show()
                resetUI()
            } catch (e: Exception) {
                statusText.text = getString(R.string.measurement_error)
                Toast.makeText(
                    this@MeasurementActivity,
                    "${getString(R.string.measurement_error)}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                resetUI()
            }
        }
    }

    private fun showResult(result: FreqMeasurementEngine.MeasurementResult) {
        lastResult = result
        val profile = result.correctionProfile

        // Скрываем статус, показываем результат
        statusSection.visibility = View.GONE
        resultLabel.visibility = View.VISIBLE
        resultCurveContainer.visibility = View.VISIBLE
        resultInfo.visibility = View.VISIBLE
        savePresetButton.visibility = View.VISIBLE
        startButton.isEnabled = true
        startButton.text = getString(R.string.measurement_retry)

        // Рисуем графики на resultCurveContainer
        drawResultCurves(result)

        // Информация о результате
        val numFilters = profile.filters.size
        val totalGain = profile.filters.sumOf {
            abs(it.gain).toDouble()
        }
        resultInfo.text = getString(
            R.string.measurement_result_info,
            numFilters,
            "%.1f".format(totalGain),
            result.totalSamples
        )
    }

    /**
     * Рисует измеренную АЧХ и кривую коррекции на Canvas.
     */
    private fun drawResultCurves(result: FreqMeasurementEngine.MeasurementResult) {
        val profile = result.correctionProfile

        // Вычисляем АЧХ коррекции (суммарный отклик всех фильтров)
        val eq = ParametricEqualizer(48000)
        for (filter in profile.filters) {
            eq.addBand(
                filter.frequency,
                filter.gain,
                com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(filter.filterType),
                filter.q.toDouble()
            )
        }

        val correctionLevels = FloatArray(result.measuredFreq.size) { i ->
            eq.getFrequencyResponse(result.measuredFreq[i])
        }

        // Рисуем на resultCurveContainer
        resultCurveContainer.post {
            val w = resultCurveContainer.width.coerceAtLeast(1)
            val h = resultCurveContainer.height.coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bgColor = MaterialColors.getColor(resultCurveContainer, com.google.android.material.R.attr.colorSurfaceVariant)
            canvas.drawColor(bgColor)

            // Границы графика (dB)
            val dbMin = -24f
            val dbMax = 24f
            val dbRange = dbMax - dbMin

            // Padding
            val padLeft = 40f
            val padRight = 16f
            val padTop = 16f
            val padBottom = 24f
            val plotW = w - padLeft - padRight
            val plotH = h - padTop - padBottom

            // Функция преобразования
            fun freqToX(freq: Float): Float {
                val logMin = kotlin.math.log10(20f)
                val logMax = kotlin.math.log10(20000f)
                val logF = kotlin.math.log10(freq.coerceIn(20f, 20000f))
                return padLeft + ((logF - logMin) / (logMax - logMin)) * plotW
            }

            fun dbToY(db: Float): Float {
                return padTop + ((dbMax - db.coerceIn(dbMin, dbMax)) / dbRange) * plotH
            }

            // Сетка
            val gridPaint = Paint().apply {
                color = Color.argb(40, 255, 255, 255)
                strokeWidth = 1f
            }
            val textPaint = Paint().apply {
                color = Color.argb(120, 255, 255, 255)
                textSize = 24f
                isAntiAlias = true
            }

            // Горизонтальные линии (каждые 6 dB)
            for (db in dbMin.toInt()..dbMax.toInt() step 6) {
                val y = dbToY(db.toFloat())
                canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
                canvas.drawText("$db", 2f, y + 8f, textPaint)
            }

            // Вертикальные линии (частоты)
            val markerFreqs = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
            for (f in markerFreqs) {
                val x = freqToX(f)
                canvas.drawLine(x, padTop, x, h - padBottom, gridPaint)
                val label = when {
                    f >= 1000f -> "${(f / 1000f).toInt()}k"
                    else -> f.toInt().toString()
                }
                canvas.drawText(label, x - 12f, h - 4f, textPaint)
            }

            // Измеренная АЧХ (красная)
            val measuredPaint = Paint().apply {
                color = Color.argb(180, 255, 100, 100)
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val measuredPath = Path()
            var firstMeasured = true
            for (i in result.measuredFreq.indices) {
                val x = freqToX(result.measuredFreq[i])
                val y = dbToY(result.measuredLevel[i])
                if (firstMeasured) {
                    measuredPath.moveTo(x, y)
                    firstMeasured = false
                } else {
                    measuredPath.lineTo(x, y)
                }
            }
            canvas.drawPath(measuredPath, measuredPaint)

            // Кривая коррекции (зелёная/голубая)
            val correctionPaint = Paint().apply {
                color = Color.argb(200, 80, 200, 255)
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val correctionPath = Path()
            var firstCorrection = true
            for (i in result.measuredFreq.indices) {
                val x = freqToX(result.measuredFreq[i])
                val y = dbToY(correctionLevels[i])
                if (firstCorrection) {
                    correctionPath.moveTo(x, y)
                    firstCorrection = false
                } else {
                    correctionPath.lineTo(x, y)
                }
            }
            canvas.drawPath(correctionPath, correctionPaint)

            // Нулевая линия
            val zeroPaint = Paint().apply {
                color = Color.argb(100, 255, 255, 255)
                strokeWidth = 1f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
            }
            canvas.drawLine(padLeft, dbToY(0f), w - padRight, dbToY(0f), zeroPaint)

            // Легенда
            val legendPaint = Paint().apply {
                textSize = 22f
                isAntiAlias = true
            }
            canvas.drawText("— Измеренная АЧХ", w - 200f, padTop + 24f, Paint().apply {
                color = Color.argb(180, 255, 100, 100)
                textSize = 22f
                isAntiAlias = true
            })
            canvas.drawText("— Коррекция", w - 200f, padTop + 50f, Paint().apply {
                color = Color.argb(200, 80, 200, 255)
                textSize = 22f
                isAntiAlias = true
            })

            resultCurveContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            resultCurveContainer.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
        }
    }

    /**
     * Показывает диалог сохранения пресета.
     */
    private fun showSaveDialog() {
        val result = lastResult ?: return
        val profile = result.correctionProfile

        // Создаём JSON пресета
        val eq = ParametricEqualizer(48000)
        for (filter in profile.filters) {
            eq.addBand(
                filter.frequency,
                filter.gain,
                com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(filter.filterType),
                filter.q.toDouble()
            )
        }
        val bandsJson = EqSerializer.bandsToJson(eq)
        val presetJson = EqSerializer.presetToJson(
            profile.preampDb,
            bandsJson
        )

        // Используем диалог ввода имени
        val dialog = android.app.AlertDialog.Builder(this)
        val input = TextInputEditText(this).apply {
            setText(presetManager.nextCustomName("AutoEQ"))
            setSelectAllOnFocus(true)
        }
        val inputLayout = TextInputLayout(this).apply {
            addView(input)
            hint = getString(R.string.measurement_preset_name_hint)
            setStartIconDrawable(R.drawable.ic_save)
        }

        dialog.setTitle(getString(R.string.measurement_save_preset))
            .setView(inputLayout)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val name = input.text?.toString()?.trim() ?: return@setPositiveButton
                if (name.isNotEmpty()) {
                    presetManager.save(name, presetJson)
                    eqPrefs.savePresetName(name)
                    Toast.makeText(this,
                        getString(R.string.measurement_saved, name),
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun resetUI() {
        startButton.isEnabled = true
        startButton.text = getString(R.string.measurement_start)
        statusSection.visibility = View.GONE
        instructions.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        scope.cancel()
        engine.release()
        super.onDestroy()
    }
}
