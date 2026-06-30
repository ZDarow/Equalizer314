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
        @Suppress("UnusedPrivateProperty")
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
            @Suppress("TooGenericExceptionCaught")
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
        val correctionLevels = computeCorrectionLevels(result)

        resultCurveContainer.post {
            val w = resultCurveContainer.width.coerceAtLeast(1)
            val h = resultCurveContainer.height.coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            val bgColor = MaterialColors.getColor(
                resultCurveContainer,
                com.google.android.material.R.attr.colorSurfaceVariant
            )
            canvas.drawColor(bgColor)

            val padLeft = 40f; val padRight = 16f
            val padTop = 16f; val padBottom = 24f
            val plotW = w - padLeft - padRight
            val plotH = h - padTop - padBottom
            val dbMin = -24f; val dbMax = 24f

            val transformer = GraphTransformer(padLeft, padRight, padTop, padBottom,
                plotW, plotH, dbMin, dbMax)

            drawGrid(canvas, w, h, transformer)
            drawLineSeries(canvas, transformer,
                result.measuredFreq, result.measuredLevel,
                Color.argb(180, 255, 100, 100))
            drawLineSeries(canvas, transformer,
                result.measuredFreq, correctionLevels,
                Color.argb(200, 80, 200, 255))
            drawZeroLine(canvas, transformer)
            drawLegend(canvas, transformer)

            resultCurveContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            resultCurveContainer.background =
                android.graphics.drawable.BitmapDrawable(resources, bmp)
        }
    }

    /** Вычисляет суммарный отклик всех фильтров коррекции на логарифмической сетке. */
    private fun computeCorrectionLevels(
        result: FreqMeasurementEngine.MeasurementResult
    ): FloatArray {
        val profile = result.correctionProfile
        val eq = ParametricEqualizer(48000)
        for (filter in profile.filters) {
            eq.addBand(
                filter.frequency, filter.gain,
                com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.valueOf(filter.filterType),
                filter.q.toDouble()
            )
        }
        return FloatArray(result.measuredFreq.size) { i ->
            eq.getFrequencyResponse(result.measuredFreq[i])
        }
    }

    /**
     * Функции преобразования freq↔x и db↔y для графика АЧХ.
     */
    @Suppress("LongParameterList")
    class GraphTransformer(
        val padLeft: Float,
        val padRight: Float,
        val padTop: Float,
        val padBottom: Float,
        val plotW: Float,
        val plotH: Float,
        val dbMin: Float,
        val dbMax: Float
    ) {
        private val dbRange = dbMax - dbMin
        private val logMin = kotlin.math.log10(20f)
        private val logMax = kotlin.math.log10(20000f)

        fun freqToX(freq: Float): Float {
            val logF = kotlin.math.log10(freq.coerceIn(20f, 20000f))
            return padLeft + ((logF - logMin) / (logMax - logMin)) * plotW
        }

        fun dbToY(db: Float): Float {
            return padTop + ((dbMax - db.coerceIn(dbMin, dbMax)) / dbRange) * plotH
        }

        fun width(): Float = plotW + padLeft + padRight
        fun height(): Float = plotH + padTop + padBottom
    }

    /** Рисует сетку графика: горизонтальные линии (dB) и вертикальные (частоты). */
    private fun drawGrid(
        canvas: Canvas,
        w: Int, h: Int,
        t: GraphTransformer
    ) {
        val gridPaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255); strokeWidth = 1f
        }
        val textPaint = Paint().apply {
            color = Color.argb(120, 255, 255, 255); textSize = 24f; isAntiAlias = true
        }
        // Горизонтальные линии каждые 6 dB
        for (db in t.dbMin.toInt()..t.dbMax.toInt() step 6) {
            val y = t.dbToY(db.toFloat())
            canvas.drawLine(t.padLeft, y, w - t.padRight, y, gridPaint)
            canvas.drawText("$db", 2f, y + 8f, textPaint)
        }
        // Вертикальные линии (частоты)
        val markerFreqs = floatArrayOf(
            20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f
        )
        for (f in markerFreqs) {
            val x = t.freqToX(f)
            canvas.drawLine(x, t.padTop, x, h - t.padBottom, gridPaint)
            val label = if (f >= 1000f) "${(f / 1000f).toInt()}k" else f.toInt().toString()
            canvas.drawText(label, x - 12f, h - 4f, textPaint)
        }
    }

    /** Рисует одну ломаную линию на графике. */
    private fun drawLineSeries(
        canvas: Canvas,
        t: GraphTransformer,
        freqs: FloatArray,
        levels: FloatArray,
        color: Int
    ) {
        val paint = Paint().apply {
            this.color = color; strokeWidth = 3f
            style = Paint.Style.STROKE; isAntiAlias = true
        }
        val path = Path()
        var first = true
        for (i in freqs.indices) {
            val x = t.freqToX(freqs[i])
            val y = t.dbToY(levels[i])
            if (first) { path.moveTo(x, y); first = false }
            else { path.lineTo(x, y) }
        }
        canvas.drawPath(path, paint)
    }

    /** Рисует нулевую линию (0 dB). */
    private fun drawZeroLine(canvas: Canvas, t: GraphTransformer) {
        val paint = Paint().apply {
            color = Color.argb(100, 255, 255, 255); strokeWidth = 1f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        canvas.drawLine(t.padLeft, t.dbToY(0f),
            t.padLeft + t.plotW, t.dbToY(0f), paint)
    }

    /** Рисует легенду в правом верхнем углу. */
    private fun drawLegend(canvas: Canvas, t: GraphTransformer) {
        canvas.drawText("— Измеренная АЧХ", t.width() - 200f, t.padTop + 24f, Paint().apply {
            color = Color.argb(180, 255, 100, 100); textSize = 22f; isAntiAlias = true
        })
        canvas.drawText("— Коррекция", t.width() - 200f, t.padTop + 50f, Paint().apply {
            color = Color.argb(200, 80, 200, 255); textSize = 22f; isAntiAlias = true
        })
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
