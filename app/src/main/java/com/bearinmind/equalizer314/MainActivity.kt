package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.state.EqViewModel
import com.bearinmind.equalizer314.ui.BandToggleManager
import com.bearinmind.equalizer314.ui.EqGraphView
import com.bearinmind.equalizer314.ui.GraphicEqController
import com.bearinmind.equalizer314.ui.GraphOverlayLayoutManager
import com.bearinmind.equalizer314.ui.SimpleEqController
import com.bearinmind.equalizer314.ui.TableEqController
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Locale
import kotlin.math.pow
import kotlinx.coroutines.launch

class  MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Equalizer314"
    }

    // ViewModel — lifecycle-aware wrapper around EqStateManager
    private val eqViewModel: EqViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[EqViewModel::class.java]
    }

    private var pendingExportText: String? = null
    // Once-per-process gate so we don't fire the POST_NOTIFICATIONS
    // dialog every time the user taps the power button. Combined with
    // not returning early from startProcessing(), this prevents the
    // recursive freeze when notifications are OS-disabled.
    private var notificationPermissionRequested = false
    private val presetExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val text = pendingExportText ?: return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                android.widget.Toast.makeText(this, getString(R.string.msg_exported_success), android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, getString(R.string.msg_export_failed, e.message), android.widget.Toast.LENGTH_SHORT).show()
            }
            pendingExportText = null
        }
    }

    internal fun launchPresetExport(text: String, fileName: String) {
        pendingExportText = text
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TITLE, fileName)
        }
        presetExportLauncher.launch(intent)
    }

    // UI controllers
    private lateinit var graphicController: GraphicEqController
    private lateinit var tableController: TableEqController
    private lateinit var simpleEqController: SimpleEqController
    private lateinit var bandToggleManager: BandToggleManager
    private lateinit var undoRedoManager: com.bearinmind.equalizer314.state.UndoRedoManager
    private lateinit var presetManager: com.bearinmind.equalizer314.state.PresetManager
    private val graphOverlayLayoutManager = GraphOverlayLayoutManager()
    private val visualizerHelper = com.bearinmind.equalizer314.audio.VisualizerHelper()

    private fun reloadEqFromPrefs() {
        eqViewModel.syncAll()
        eqViewModel.setPreampGain(eqViewModel.eqPrefs.getPreampGain())
        preampSlider.value = eqViewModel.preampGainDb.value.coerceIn(-12f, 12f)
        preampText.setText(String.format(Locale.US, "%.1f", eqViewModel.preampGainDb.value))
        eqGraphView.updateBandLevels()
        bandToggleManager.setupToggles()
        eqViewModel.pushEqUpdate()
        val preset = eqViewModel.eqPrefs.getPresetName()
        presetDropdown.setText(preset, false)
        updateAutoEqStatus()
        // Re-apply current mode visibility (toggles may have been rebuilt)
        if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) {
            bandToggleGroup.visibility = View.GONE
            bandToggleGroup2.visibility = View.GONE
            tableController.buildTable()
        }
    }

    private val autoEqLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) handlePresetReturn()
    }

    private val targetCurveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) handlePresetReturn()
    }

    /** Common refresh for AutoEQ / TargetCurve preset returns. The launched
     *  activity persists the new bands and (possibly) flips Channel Side EQ
     *  off; we still need to rebind the graph and audio to the new active EQ
     *  reference so the UI doesn't keep showing a stale leftEq/rightEq view. */
    private fun handlePresetReturn() {
        // Reset stale band selection — if CSE was on, selectedBandIndex may
        // point past the end of the new bothEq.
        eqViewModel.selectBand(0)
        reloadEqFromPrefs()
        rebindActiveEq()
        if (eqViewModel.isProcessing.value) {
            val (lEq, rEq) = eqViewModel.getChannelEqs()
            eqViewModel.eqService.value?.let { svc ->
                svc.dynamicsManager.stop()
                svc.dynamicsManager.run { requestedBandCount = eqViewModel.eqPrefs.getDpBandCount(); start(eqViewModel.parametricEq.value) }
                svc.updateEqPerChannel(lEq, rEq)
            }
        }
    }

    @Suppress("UnusedPrivateProperty") // Reserved for future APO import button
    private val apoImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val profile = com.bearinmind.equalizer314.autoeq.AutoEqParser.parse(text)
            if (profile == null || profile.filters.isEmpty()) {
                android.widget.Toast.makeText(this, getString(R.string.msg_parse_apo_failed), android.widget.Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            fun toBandSpecs(filters: List<com.bearinmind.equalizer314.autoeq.AutoEqFilter>):
                    List<com.bearinmind.equalizer314.state.EqStateManager.BandSpec> =
                filters.map {
                    com.bearinmind.equalizer314.state.EqStateManager.BandSpec(
                        frequency = it.frequency,
                        gain = it.gain,
                        q = it.q.toDouble(),
                        filterType = com.bearinmind.equalizer314.autoeq.apoTokenToFilterType(it.filterType),
                    )
                }

            eqViewModel.eqPrefs.savePreampGain(profile.preampDb)
            eqViewModel.eqPrefs.savePresetName(getString(R.string.msg_apo_import))
            eqViewModel.eqPrefs.saveAutoEqName("")
            eqViewModel.eqPrefs.saveAutoEqSource("")

            if (profile.perChannel) {
                // Fork into L/R editors and flip Channel Side EQ on.
                val leftSpecs = toBandSpecs(profile.leftFilters)
                val rightSpecs = toBandSpecs(profile.rightFilters)
                // bothBands falls back to the combined flat list in case the
                // user later turns CSE off.
                val bothSpecs = toBandSpecs(profile.filters)
                eqViewModel.applyPresetEqs(
                    cseEnabled = true,
                    bothBands = bothSpecs,
                    leftBands = leftSpecs,
                    rightBands = rightSpecs,
                )
                // Persist L's bands as the main "bands" state + both L and R
                // under their own prefs keys so the divergence survives a
                // process restart.
                eqViewModel.eqPrefs.saveState(eqViewModel.parametricEq.value, (0 until eqViewModel.parametricEq.value.getBandCount()).toList())
                eqViewModel.persistLeftRightIfCse()
                if (eqViewModel.isProcessing.value) {
                    val (lEq, rEq) = eqViewModel.getChannelEqs()
                    eqViewModel.eqService.value?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.run { requestedBandCount = eqViewModel.eqPrefs.getDpBandCount(); start(eqViewModel.parametricEq.value) }
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                reloadEqFromPrefs()
                refreshChannelPopoutDim()
                android.widget.Toast.makeText(
                    this,
                    "Applied L:${profile.leftFilters.size} R:${profile.rightFilters.size} filters",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                // Flat single-channel preset — CSE off, single EQ.
                // Reset stale band selection — CSE-on may have left
                // selectedBandIndex pointing past the end of the new bothEq.
                eqViewModel.selectBand(0)
                val specs = toBandSpecs(profile.filters)
                eqViewModel.applyPresetEqs(
                    cseEnabled = false,
                    bothBands = specs,
                    leftBands = specs,
                    rightBands = specs,
                )
                eqViewModel.eqPrefs.saveState(eqViewModel.parametricEq.value, (0 until eqViewModel.parametricEq.value.getBandCount()).toList())
                reloadEqFromPrefs()
                // Force a full active-EQ rebind so the graph / band toggles /
                // input widgets retarget bothEq when CSE was previously on.
                // Without this the UI keeps pointing at the old leftEq until
                // the user manually flips the CSE switch.
                rebindActiveEq()
                // If DP is running, it was bound to leftEq/rightEq — push the
                // new bothEq to both channels so audio matches the displayed
                // EQ immediately instead of after the next interaction.
                if (eqViewModel.isProcessing.value) {
                    val (lEq, rEq) = eqViewModel.getChannelEqs()
                    eqViewModel.eqService.value?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.run { requestedBandCount = eqViewModel.eqPrefs.getDpBandCount(); start(eqViewModel.parametricEq.value) }
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                android.widget.Toast.makeText(this, getString(R.string.msg_applied_filters, profile.filters.size), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.msg_error, e.message), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Views
    private lateinit var eqGraphView: EqGraphView
    private lateinit var eqToggleButton: MaterialButton
    private lateinit var presetDropdown: MaterialAutoCompleteTextView
    private lateinit var filterTypeGroup: LinearLayout
    private lateinit var qSlider: Slider
    private lateinit var qValueText: TextView
    private lateinit var qControlGroup: View
    private lateinit var powerButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var modeDescriptionText: TextView
    private lateinit var bandToggleGroup: LinearLayout
    private lateinit var bandToggleGroup2: LinearLayout
    private lateinit var triangleIndicator: View
    private lateinit var bandInputGroup: View
    private lateinit var pageEq: View
    private lateinit var pageSettings: View
    private lateinit var navSettingsButton: ImageButton
    private lateinit var navPresetsButton: ImageButton
    private lateinit var powerFab: android.widget.ImageButton
    private lateinit var dpBandCountGroup: View
    private lateinit var dpBandCountSlider: Slider
    private lateinit var dpBandCountText: EditText
    private lateinit var bandHzSlider: Slider
    private lateinit var bandDbSlider: Slider
    private lateinit var bandHzInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandDbInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandQInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var bandQInputLayout: com.google.android.material.textfield.TextInputLayout
    private var isUpdatingInputs = false

    // Settings views
    private lateinit var preampSlider: Slider
    private lateinit var preampText: EditText
    private lateinit var autoGainSwitch: MaterialSwitch
    private lateinit var autoGainOffsetText: TextView
    // Old inline limiter controls removed — now in LimiterActivity

    // EQ UI mode
    private lateinit var modeParametricBtn: MaterialButton
    private lateinit var modeGraphicBtn: MaterialButton
    private lateinit var modeTableBtn: MaterialButton
    private lateinit var parametricControlsCard: View
    private lateinit var hzControlRow: View
    private lateinit var tableEqCard: View
    private lateinit var tableEqRowContainer: LinearLayout
    private lateinit var graphicScrollView: HorizontalScrollView
    private lateinit var graphicSlidersContainer: LinearLayout
    private lateinit var colorSwatchRow: LinearLayout
    private lateinit var simpleEqContainer: LinearLayout
    private lateinit var eqControlsContainer: LinearLayout
    private lateinit var graphCardView: View
    private lateinit var modeSelectorGroup: LinearLayout

    // Hz slider uses logarithmic mapping: slider 0–1000 → 10–20000 Hz
    private val hzLogMin = kotlin.math.log10(10f)
    private val hzLogMax = kotlin.math.log10(20000f)

    // Listen for EQ stopped from notification "Turn Off" button
    private val eqStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            eqViewModel.stateManager.isProcessing = false
            eqViewModel.stateManager.eqService = null
            if (eqViewModel.serviceBound.value) {
                try { unbindService(eqViewModel.stateManager.serviceConnection) } catch (_: Exception) {}
                eqViewModel.stateManager.serviceBound = false
            }
            animatePowerFab(false)
            showPowerSnackbar(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // On fresh app launch, reset power state — user must explicitly turn on
        if (savedInstanceState == null) {
            eqViewModel.eqPrefs.savePowerState(false)
            eqViewModel.stateManager.pendingStartEq = false
            // Also re-lock the Experimental settings on every fresh launch
            eqViewModel.eqPrefs.saveExperimentalUnlocked(false)
            // Force Experimental DSP options to safe defaults (currently disabled
            // in the UI; see ExperimentalActivity). Overwrite anything a user may
            // have saved in a previous build.
            eqViewModel.eqPrefs.saveDpBandCount(128)
            eqViewModel.eqPrefs.saveAutoGainEnabled(false)
        }

        initViews()
        initControllers()
        initEQ()
        syncPreampUI()
        setupListeners()

        ContextCompat.registerReceiver(
            this,
            eqStoppedReceiver,
            IntentFilter(EqService.ACTION_EQ_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val savedMode = try { EqUiMode.valueOf(eqViewModel.eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
        val effectiveMode = if (eqViewModel.eqPrefs.getSimpleEqEnabled()) EqUiMode.SIMPLE else savedMode
        switchEqUiMode(effectiveMode)
        // Ensure rows are properly ordered after views are laid out
        pageEq.post { reorderToggleRows(animate = false) }
    }

    private fun initViews() {
        eqGraphView = findViewById(R.id.eqGraphView)
        eqToggleButton = findViewById(R.id.eqToggleButton)
        presetDropdown = findViewById(R.id.presetSpinner)
        filterTypeGroup = findViewById(R.id.filterTypeGroup)
        qSlider = findViewById(R.id.qSlider)
        qValueText = findViewById(R.id.qValueText)
        qControlGroup = findViewById(R.id.qControlGroup)
        powerButton = findViewById(R.id.powerButton)
        statusText = findViewById(R.id.statusText)
        modeDescriptionText = findViewById(R.id.modeDescriptionText)
        bandToggleGroup = findViewById(R.id.bandToggleGroup)
        bandToggleGroup2 = findViewById(R.id.bandToggleGroup2)
        triangleIndicator = findViewById<View>(R.id.triangleIndicator).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_triangle_up)
        }
        bandInputGroup = findViewById(R.id.bandInputGroup)
        pageEq = findViewById(R.id.pageEq)
        pageSettings = findViewById(R.id.pageSettings)
        navSettingsButton = findViewById(R.id.navSettingsButton)
        navPresetsButton = findViewById(R.id.navPresetsButton)
        powerFab = findViewById(R.id.powerFab)
        dpBandCountGroup = findViewById(R.id.dpBandCountGroup)
        dpBandCountSlider = findViewById(R.id.dpBandCountSlider)
        dpBandCountText = findViewById(R.id.dpBandCountText)
        preampSlider = findViewById(R.id.preampSliderBar)
        preampText = findViewById(R.id.preampTextBar)
        autoGainSwitch = findViewById(R.id.autoGainSwitch)
        autoGainOffsetText = findViewById(R.id.autoGainOffsetText)
        bandHzSlider = findViewById(R.id.bandHzSlider)
        bandDbSlider = findViewById(R.id.bandDbSlider)
        bandHzInput = findViewById(R.id.bandHzInput)
        bandDbInput = findViewById(R.id.bandDbInput)
        bandQInput = findViewById(R.id.bandQInput)
        bandQInputLayout = findViewById(R.id.bandQInputLayout)
        modeParametricBtn = findViewById(R.id.modeParametricBtn)
        modeGraphicBtn = findViewById(R.id.modeGraphicBtn)
        modeTableBtn = findViewById(R.id.modeTableBtn)
        parametricControlsCard = findViewById(R.id.parametricControlsCard)
        hzControlRow = findViewById(R.id.hzControlRow)
        tableEqCard = findViewById(R.id.tableEqCard)
        tableEqRowContainer = findViewById(R.id.tableEqRowContainer)
        graphicScrollView = findViewById(R.id.graphicScrollView)
        graphicSlidersContainer = findViewById(R.id.graphicSlidersContainer)
        colorSwatchRow = findViewById(R.id.colorSwatchRow)
        simpleEqContainer = findViewById(R.id.simpleEqContainer)
        eqControlsContainer = findViewById(R.id.eqControlsContainer)
        modeSelectorGroup = findViewById(R.id.modeSelectorGroup)
        graphCardView = (eqGraphView.parent as View).parent as View // FrameLayout → MaterialCardView

        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presets)
        presetDropdown.setAdapter(adapter)
        presetDropdown.setText("Flat", false)

        val savedBandCount = eqViewModel.eqPrefs.getDpBandCount().coerceIn(128, 1024)
        ParametricToDpConverter.setNumBands(savedBandCount)
        dpBandCountSlider.value = savedBandCount.toFloat()
        dpBandCountText.setText(savedBandCount.toString())

        eqGraphView.showSaturationCurve = false

        updateBottomBarHighlight(isEqPage = true)
    }

    /** Called after initEQ() to sync preamp UI with restored state */
    private fun syncPreampUI() {
        preampSlider.value = eqViewModel.preampGainDb.value.coerceIn(-12f, 12f)
        preampText.setText(String.format(Locale.US, "%.1f", eqViewModel.preampGainDb.value))
        autoGainSwitch.isChecked = eqViewModel.autoGainEnabled.value
        updateAutoGainOffsetText()
    }

    private fun initControllers() {
        val onEqChanged = {
            eqViewModel.pushEqUpdate()
        }

        val onBandCountChanged = {
            onEqChanged()
            bandToggleManager.updateIcons()
            setupFilterTypeButtons()
            eqViewModel.selectedBandIndex.value?.let { updateFilterTypeButtons(it) }
            if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) tableController.buildTable()
            if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
            reorderToggleRows()
        }

        val onBandSelected = { bandIndex: Int? ->
            updateFilterTypeButtons(bandIndex)
            updateBandInputs(bandIndex)
            // In graphic mode, rebuild sliders if we switched to a different page
            if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC && graphicController.updatePageForBand(bandIndex)) {
                graphicController.buildSliders(graphicController.targetCardHeight)
            }
            reorderToggleRows()
        }

        graphicController = GraphicEqController(
            this, graphicSlidersContainer, eqGraphView, eqViewModel.stateManager,
            onEqChanged, onBandCountChanged
        )
        graphicController.onColorChanged = {
            bandToggleManager.updateSelection(eqViewModel.selectedBandIndex.value)
        }

        tableController = TableEqController(
            this, tableEqRowContainer, eqGraphView, eqViewModel.stateManager, onEqChanged
        )

        simpleEqController = SimpleEqController(
            this, simpleEqContainer, eqViewModel.stateManager, eqViewModel.eqPrefs, onEqChanged
        )

        bandToggleManager = BandToggleManager(
            this, bandToggleGroup, bandToggleGroup2, triangleIndicator, eqGraphView, eqViewModel.stateManager,
            onEqChanged, onBandCountChanged, onBandSelected
        )

        // Wire state manager callbacks
        eqViewModel.stateManager.onProcessingChanged = { active ->
            animatePowerFab(active)
        }
        eqViewModel.stateManager.onServiceConnected = {
            doStartEq()
        }
    }

    private fun initEQ() {
        eqViewModel.init(eqGraphView)

        val savedPreset = eqViewModel.eqPrefs.getPresetName()
        presetDropdown.setText(savedPreset, false)

        updateEqToggleUI()
        eqGraphView.updateBandLevels()
        bandToggleManager.setupToggles()

        // Always have a band selected in parametric mode
        if (eqViewModel.currentEqUiMode.value == EqUiMode.PARAMETRIC && eqViewModel.parametricEq.value.getBandCount() > 0) {
            val defaultBand = eqViewModel.selectedBandIndex.value ?: 0
            eqViewModel.selectBand(defaultBand)
            eqGraphView.setActiveBand(defaultBand)
            updateFilterTypeButtons(defaultBand)
            updateBandInputs(defaultBand)
        }
    }

    private fun setupListeners() {
        // Navigation
        navSettingsButton.setOnClickListener {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        }
        navPresetsButton.setOnClickListener {
            pageEq.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
            updateBottomBarHighlight(isEqPage = true)
        }
        val navMbcBtn = findViewById<ImageButton>(R.id.navMbcButton)
        val navLimiterBtn = findViewById<ImageButton>(R.id.navLimiterButton)
        navMbcBtn.setOnClickListener {
            startActivity(Intent(this, MbcActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        navLimiterBtn.setOnClickListener {
            startActivity(Intent(this, LimiterActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        powerFab.setOnClickListener {
            if (eqViewModel.isProcessing.value) stopProcessing() else startProcessing()
        }

        // Visualizer toggle + Edit + Reset + Undo/Redo + Band points toggle + Save preset
        val vizToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.visualizerToggle)
        val editBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.editButton)
        val resetBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.resetButton)
        val undoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.undoButton)
        val redoBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.redoButton)
        val bandPtsBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.bandPointsToggle)
        val saveBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.savePresetButton)
        val altRouteBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.altRouteButton)
        val settingsGearBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.settingsGearButton)
        val channelLBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelLButton)
        val channelRBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelRButton)
        val gapPx = (2 * resources.displayMetrics.density).toInt()
        val vizDensity = resources.displayMetrics.density
        eqGraphView.post {
            graphOverlayLayoutManager.layoutButtons(
                graphView = eqGraphView,
                R.id.visualizerToggle to GraphOverlayLayoutManager.LayoutPosition.VISUALIZER,
                R.id.editButton to GraphOverlayLayoutManager.LayoutPosition.EDIT,
                R.id.resetButton to GraphOverlayLayoutManager.LayoutPosition.RESET,
                R.id.undoButton to GraphOverlayLayoutManager.LayoutPosition.UNDO,
                R.id.redoButton to GraphOverlayLayoutManager.LayoutPosition.REDO,
                R.id.bandPointsToggle to GraphOverlayLayoutManager.LayoutPosition.BAND_POINTS,
                R.id.savePresetButton to GraphOverlayLayoutManager.LayoutPosition.SAVE_PRESET,
                R.id.altRouteButton to GraphOverlayLayoutManager.LayoutPosition.ALT_ROUTE,
                R.id.settingsGearButton to GraphOverlayLayoutManager.LayoutPosition.SETTINGS_GEAR,
                R.id.channelLButton to GraphOverlayLayoutManager.LayoutPosition.CHANNEL_L,
                R.id.channelRButton to GraphOverlayLayoutManager.LayoutPosition.CHANNEL_R,
            )
            // Mini L/R badge overlaid on the top-right of altRouteButton.
            val badge = findViewById<android.widget.TextView>(R.id.altRouteChannelBadge)
            graphOverlayLayoutManager.repositionChannelBadge(badge)
            badge.translationZ = 16f * resources.displayMetrics.density
            badge.bringToFront()
            refreshChannelPopoutDim()
        }

        // Settings gear starts hidden; the split icon pops it in alongside
        // L / R with the same overshoot animation used by undo / redo / reset.
        settingsGearBtn.visibility = View.GONE

        var channelPopoutOpen = false
        altRouteBtn.setOnClickListener {
            channelPopoutOpen = !channelPopoutOpen
            if (channelPopoutOpen) {
                val offsetY = -(altRouteBtn.height.toFloat() + gapPx)
                // L and R are dimmed when Channel Side EQ is off, bright when on.
                val lrAlpha = if (eqViewModel.eqPrefs.getChannelSideEqEnabled()) 1.0f else 0.4f
                // Paint pressed/outlined styles first so the buttons fade in
                // already reflecting the active channel.
                paintChannelButtonStyles()
                listOf(channelLBtn, channelRBtn, settingsGearBtn).forEach { v ->
                    v.visibility = View.VISIBLE
                    v.alpha = 0f; v.scaleX = 0.3f; v.scaleY = 0.3f; v.translationY = offsetY
                }
                channelLBtn.animate().alpha(lrAlpha).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                channelRBtn.animate().alpha(lrAlpha).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                settingsGearBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                altRouteBtn.setBackgroundColor(0xFF555555.toInt())
                altRouteBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                altRouteBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                val offsetY = -(altRouteBtn.height.toFloat() + gapPx)
                settingsGearBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { settingsGearBtn.visibility = View.GONE; settingsGearBtn.translationY = 0f }.start()
                channelRBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(40).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { channelRBtn.visibility = View.GONE; channelRBtn.translationY = 0f }.start()
                channelLBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(80).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { channelLBtn.visibility = View.GONE; channelLBtn.translationY = 0f }.start()
                altRouteBtn.setBackgroundColor(0x00000000)
                altRouteBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                altRouteBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }

        // L / R buttons switch which channel the main graph is editing.
        // Only effective while Channel Side EQ is enabled; dimmed and no-op
        // otherwise. Tapping the currently-active channel is a no-op too.
        channelLBtn.setOnClickListener {
            if (!eqViewModel.eqPrefs.getChannelSideEqEnabled()) return@setOnClickListener
            if (eqViewModel.activeChannel.value == EqStateManager.ActiveChannel.LEFT) return@setOnClickListener
            eqViewModel.setActiveChannel(EqStateManager.ActiveChannel.LEFT)
            rebindActiveEq()
        }
        channelRBtn.setOnClickListener {
            if (!eqViewModel.eqPrefs.getChannelSideEqEnabled()) return@setOnClickListener
            if (eqViewModel.activeChannel.value == EqStateManager.ActiveChannel.RIGHT) return@setOnClickListener
            eqViewModel.setActiveChannel(EqStateManager.ActiveChannel.RIGHT)
            rebindActiveEq()
        }

        // Settings gear: navigate to the Settings page.
        settingsGearBtn.setOnClickListener {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        }
        // Save preset button — toggle between controls and preset picker
        val eqControlsContainerLocal = eqControlsContainer as android.view.View
        val presetPickerScroll = findViewById<android.widget.ScrollView>(R.id.presetPickerScroll)
        val presetPickerContainer = findViewById<android.widget.LinearLayout>(R.id.presetPickerContainer)
        var presetPickerOpen = false

        fun populatePresetPicker() {
            presetPickerContainer.removeAllViews()
            val presetNames = presetManager.names.sorted()

            val density = resources.displayMetrics.density

            // "+" button at top — exact copy of the band add button style, full width
            val saveCurrentBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (4 * density).toInt())
                }
                cornerRadius = (12 * density).toInt()
                textSize = 11f
                val vertPad = (6 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                insetTop = 0; insetBottom = 0
                minWidth = 0; minimumWidth = 0
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0x00000000)
                setTextColor(0xFF888888.toInt())
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
            }
            saveCurrentBtn.setOnClickListener {
                val prefix = getString(R.string.msg_custom_preset_prefix, 1)
                    .trimEnd('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
                val defaultName = presetManager.nextCustomName(prefix)
                com.bearinmind.equalizer314.ui.DialogFactory.input(
                    this,
                    title = getString(R.string.dialog_save_custom_preset),
                    hint = defaultName,
                    initialText = defaultName,
                    onConfirm = { name ->
                        if (name.isEmpty()) return@input
                        eqViewModel.eqPrefs.saveState(eqViewModel.parametricEq.value)
                        eqViewModel.persistLeftRightIfCse()
                        val cseOn = eqViewModel.eqPrefs.getChannelSideEqEnabled()
                        val bandsJson = com.bearinmind.equalizer314.dsp.EqSerializer.bandsToJson(
                            eqViewModel.parametricEq.value
                        )
                        val json = if (cseOn) {
                            val (lEq, rEq) = eqViewModel.getChannelEqs()
                            com.bearinmind.equalizer314.dsp.EqSerializer.presetToJson(
                                preampDb = eqViewModel.preampGainDb.value,
                                bands = bandsJson,
                                channelSideEqEnabled = true,
                                leftBands = com.bearinmind.equalizer314.dsp.EqSerializer.bandsToJson(lEq),
                                rightBands = com.bearinmind.equalizer314.dsp.EqSerializer.bandsToJson(rEq),
                            )
                        } else {
                            com.bearinmind.equalizer314.dsp.EqSerializer.eqToPresetJson(
                                eqViewModel.parametricEq.value,
                                eqViewModel.preampGainDb.value,
                            )
                        }
                        presetManager.save(name, json)
                        populatePresetPicker()
                        android.widget.Toast.makeText(this, getString(R.string.msg_saved, name), android.widget.Toast.LENGTH_SHORT).show()
                    }
                ).show()
            }
            presetPickerContainer.addView(saveCurrentBtn)

            // List saved presets — styled like (+) band buttons
            for (name in presetNames) {
                // Parse preset data for thumbnail
                val presetJson = presetManager.getJson(name)
                val bandCount = try {
                    org.json.JSONObject(presetJson ?: "{}").getJSONArray("bands").length()
                } catch (_: Exception) { 0 }

                val presetRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, (4 * density).toInt())
                    }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x00000000)
                        setStroke((1 * density).toInt(), 0xFF444444.toInt())
                        cornerRadius = 12 * density
                    }
                    val hPad = (12 * density).toInt()
                    val vPad = (10 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                }

                // Mini EQ curve thumbnail. For Channel-Side-EQ presets we
                // stack two mini graphs (L on top, R on bottom) so the
                // per-channel split is legible at a glance. Single-curve
                // presets render one grey curve as before.
                val thumbW = (48 * density).toInt()
                val thumbH = (24 * density).toInt()
                val thumbnail = object : android.view.View(this) {
                    private fun buildEq(arr: org.json.JSONArray): com.bearinmind.equalizer314.dsp.ParametricEqualizer =
                        com.bearinmind.equalizer314.dsp.EqSerializer.parseBands(arr)

                    private fun drawCurve(
                        canvas: android.graphics.Canvas,
                        eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer,
                        x0: Float, y0: Float, w: Float, h: Float,
                        color: Int,
                    ) {
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = color; strokeWidth = 0.5f * density; style = android.graphics.Paint.Style.STROKE
                        }
                        val gridPaint = android.graphics.Paint().apply { this.color = 0xFF6A6A6A.toInt(); strokeWidth = 1f }
                        canvas.drawLine(x0, y0 + h / 2f, x0 + w, y0 + h / 2f, gridPaint)
                        canvas.drawLine(x0, y0, x0, y0 + h, gridPaint)
                        val path = android.graphics.Path()
                        val maxDb = 15f; val steps = 50
                        for (s in 0..steps) {
                            val logF = 1.301f + (s.toFloat() / steps) * (4.342f - 1.301f)
                            val freq = 10f.pow(logF)
                            val db = eq.getFrequencyResponse(freq)
                            val x = x0 + w * s / steps
                            val y = (y0 + h / 2f - (db / maxDb) * (h / 2f)).coerceIn(y0, y0 + h)
                            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, paint)
                    }

                    override fun onDraw(canvas: android.graphics.Canvas) {
                        super.onDraw(canvas)
                        val w = width.toFloat(); val h = height.toFloat()
                        if (w <= 0 || h <= 0 || presetJson == null) return
                        try {
                            val obj = org.json.JSONObject(presetJson)
                            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
                            val curveColor = 0xFFAAAAAA.toInt()
                            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                                // Stacked: L on top, R on bottom, separated
                                // by a divider line and a 2px gap. Both curves
                                // use the same grey as the single-curve case;
                                // a small "L" / "R" label sits at the left of
                                // each row so the two graphs read as "two
                                // labeled graphs" rather than one mashed curve.
                                val labelCol = 9f * density
                                val gap = 2f * density
                                val halfH = (h - gap) / 2f
                                // Match the "N filters" text next to each
                                // preset row: 10 sp, medium grey (#888), not
                                // bold.
                                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = 0xFF888888.toInt()
                                    textSize = 10f * resources.displayMetrics.scaledDensity
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                val fm = labelPaint.fontMetrics
                                val textCenterOffset = (-fm.ascent - fm.descent) / 2f
                                // L label + L curve
                                canvas.drawText("L", labelCol / 2f, halfH / 2f + textCenterOffset, labelPaint)
                                drawCurve(
                                    canvas,
                                    buildEq(obj.getJSONArray("leftBands")),
                                    labelCol, 0f, w - labelCol, halfH,
                                    curveColor,
                                )
                                // Divider between the two graphs
                                val dividerPaint = android.graphics.Paint().apply {
                                    color = 0xFF444444.toInt()
                                    strokeWidth = 1f
                                }
                                canvas.drawLine(0f, halfH + gap / 2f, w, halfH + gap / 2f, dividerPaint)
                                // R label + R curve
                                val rTop = halfH + gap
                                canvas.drawText("R", labelCol / 2f, rTop + halfH / 2f + textCenterOffset, labelPaint)
                                drawCurve(
                                    canvas,
                                    buildEq(obj.getJSONArray("rightBands")),
                                    labelCol, rTop, w - labelCol, halfH,
                                    curveColor,
                                )
                            } else {
                                drawCurve(canvas, buildEq(obj.getJSONArray("bands")),
                                    0f, 0f, w, h, curveColor)
                            }
                        } catch (_: Exception) {}
                    }
                }.apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(thumbW, thumbH)
                }

                // Left side: preset name
                val nameText = android.widget.TextView(this).apply {
                    text = name
                    setTextColor(0xFFE2E2E2.toInt())
                    textSize = 14f
                    isSingleLine = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                // Right side: graph + filters count stacked vertically
                val rightCol = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (8 * density).toInt()
                    }
                }
                val filtersText = android.widget.TextView(this).apply {
                    text = "$bandCount filters"
                    setTextColor(0xFF888888.toInt())
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                }

                // × delete button
                val deleteBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "×"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * density).toInt(), (36 * density).toInt()).apply {
                        marginStart = (8 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0x00000000)
                    setTextColor(0xFFEF9A9A.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                deleteBtn.setOnClickListener {
                    com.bearinmind.equalizer314.ui.DialogFactory.confirmation(
                        this,
                        title = getString(R.string.action_delete),
                        message = getString(R.string.dialog_delete_preset, name),
                        confirmLabel = getString(R.string.action_delete),
                        onConfirm = {
                            presetManager.delete(name)
                            populatePresetPicker()
                        }
                    ).show()
                }
                // Export button
                val exportBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * density).toInt(), (36 * density).toInt()).apply {
                        marginStart = (8 * density).toInt()
                    }
                    cornerRadius = (12 * density).toInt()
                    setPadding(0, 0, 0, 0)
                    insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setBackgroundColor(0x00000000)
                    icon = resources.getDrawable(R.drawable.ic_export, theme)
                    iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    iconSize = (18 * density).toInt()
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    strokeWidth = (1 * density).toInt()
                }
                exportBtn.setOnClickListener {
                    val apoText = presetManager.toApoText(name) ?: return@setOnClickListener
                    pendingExportText = apoText
                    val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TITLE, "${name}.txt")
                    }
                    presetExportLauncher.launch(intent)
                }

                rightCol.addView(thumbnail)
                rightCol.addView(filtersText)
                presetRow.addView(nameText)
                presetRow.addView(rightCol)
                presetRow.addView(exportBtn)
                presetRow.addView(deleteBtn)
                // Tap to load preset
                presetRow.setOnClickListener {
                    val parsed = presetManager.parse(name) ?: return@setOnClickListener
                    eqViewModel.applyPresetEqs(
                        parsed.cseOn,
                        parsed.bothBands,
                        parsed.leftBands,
                        parsed.rightBands,
                    )

                    eqGraphView.setParametricEqualizer(eqViewModel.parametricEq.value)
                    eqViewModel.eqPrefs.saveState(eqViewModel.parametricEq.value)
                    eqViewModel.persistLeftRightIfCse()
                    eqViewModel.initBandSlots()
                    bandToggleManager.setupToggles()
                    if (eqViewModel.isProcessing.value) {
                        val (lEq, rEq) = eqViewModel.getChannelEqs()
                        eqViewModel.eqService.value?.let { svc ->
                            svc.dynamicsManager.stop()
                            svc.dynamicsManager.run { requestedBandCount = eqViewModel.eqPrefs.getDpBandCount(); start(eqViewModel.parametricEq.value) }
                            svc.updateEqPerChannel(lEq, rEq)
                        }
                    }
                    refreshChannelPopoutDim()
                    // Close picker with animation
                    presetPickerOpen = false
                    eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                    eqControlsContainerLocal.alpha = 0f
                    eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                        presetPickerScroll.visibility = android.view.View.GONE
                        presetPickerScroll.alpha = 1f
                    }.start()
                    saveBtn.setBackgroundColor(0x00000000)
                    saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                    saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                    android.widget.Toast.makeText(this, getString(R.string.msg_loaded, name), android.widget.Toast.LENGTH_SHORT).show()
                }
                presetPickerContainer.addView(presetRow)
            }
        }

        saveBtn.setOnClickListener {
            presetPickerOpen = !presetPickerOpen
            if (presetPickerOpen) {
                populatePresetPicker()
                presetPickerScroll.visibility = android.view.View.VISIBLE
                presetPickerScroll.alpha = 0f
                presetPickerScroll.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                eqControlsContainerLocal.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    eqControlsContainerLocal.visibility = android.view.View.GONE
                    eqControlsContainerLocal.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(0xFF555555.toInt())
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                eqControlsContainerLocal.visibility = android.view.View.VISIBLE
                eqControlsContainerLocal.alpha = 0f
                eqControlsContainerLocal.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                presetPickerScroll.animate().alpha(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    presetPickerScroll.visibility = android.view.View.GONE
                    presetPickerScroll.alpha = 1f
                }.start()
                saveBtn.setBackgroundColor(0x00000000)
                saveBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                saveBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        // Band points toggle: active by default (points shown)
        var bandPointsVisible = true
        bandPtsBtn.setBackgroundColor(0xFF555555.toInt())
        bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
        bandPtsBtn.strokeWidth = (2 * vizDensity).toInt()
        bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
        bandPtsBtn.setOnClickListener {
            bandPointsVisible = !bandPointsVisible
            eqGraphView.showBandPoints = bandPointsVisible
            eqGraphView.invalidate()
            if (bandPointsVisible) {
                bandPtsBtn.setIconResource(R.drawable.ic_visibility)
                bandPtsBtn.setBackgroundColor(0xFF555555.toInt())
                bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                bandPtsBtn.strokeWidth = (2 * vizDensity).toInt()
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                bandPtsBtn.setIconResource(R.drawable.ic_visibility_off)
                bandPtsBtn.setBackgroundColor(0x00000000)
                bandPtsBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                bandPtsBtn.strokeWidth = (1 * vizDensity).toInt()
                bandPtsBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        // Reset button: reset EQ to flat
        resetBtn.setOnClickListener {
            com.bearinmind.equalizer314.ui.DialogFactory.confirmation(
                this,
                title = getString(R.string.action_reset),
                message = getString(R.string.dialog_reset_all_values),
                confirmLabel = getString(R.string.action_reset),
                onConfirm = {
                    undoRedoManager.saveState()
                    val eq = eqViewModel.parametricEq.value
                    eq.clearBands()
                    val defaultFreqs = com.bearinmind.equalizer314.dsp.ParametricEqualizer.logSpacedFrequencies(16)
                    for (i in 0..3) {
                        eq.addBand(defaultFreqs[i], 0f, com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL)
                    }
                    eqGraphView.setParametricEqualizer(eq)
                    eqViewModel.eqPrefs.saveState(eq)
                    // Reset wipes the shared EQ — drop any stored L/R divergence
                    // so re-enabling CSE forks from the fresh defaults.
                    eqViewModel.eqPrefs.clearLeftRightBands()
                    eqViewModel.initBandSlots()
                    bandToggleManager.setupToggles()
                    if (eqViewModel.isProcessing.value) {
                        eqViewModel.eqService.value?.let { svc ->
                            svc.dynamicsManager.stop()
                            svc.dynamicsManager.requestedBandCount = eqViewModel.eqPrefs.getDpBandCount()
                            svc.dynamicsManager.start(eq)
                        }
                    }
                    android.widget.Toast.makeText(this, getString(R.string.msg_eq_reset), android.widget.Toast.LENGTH_SHORT).show()
                }
            ).show()
        }
        // Edit mode toggle — undo/redo pop out from edit button position
        var editMode = false
        editBtn.setOnClickListener {
            editMode = !editMode
            val d = resources.displayMetrics.density
            if (editMode) {
                val offsetY = -(editBtn.height.toFloat() + gapPx)

                // Show reset, undo, redo — all pop out from edit button
                resetBtn.visibility = android.view.View.VISIBLE
                undoBtn.visibility = android.view.View.VISIBLE
                redoBtn.visibility = android.view.View.VISIBLE
                resetBtn.alpha = 0f; resetBtn.scaleX = 0.3f; resetBtn.scaleY = 0.3f; resetBtn.translationY = offsetY
                undoBtn.alpha = 0f; undoBtn.scaleX = 0.3f; undoBtn.scaleY = 0.3f; undoBtn.translationY = offsetY
                redoBtn.alpha = 0f; redoBtn.scaleX = 0.3f; redoBtn.scaleY = 0.3f; redoBtn.translationY = offsetY

                resetBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                undoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(40).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()
                redoBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(250).setStartDelay(80).setInterpolator(android.view.animation.OvershootInterpolator(1.0f)).start()

                editBtn.setBackgroundColor(0xFF555555.toInt())
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                val offsetY = -(editBtn.height.toFloat() + gapPx)

                redoBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { redoBtn.visibility = android.view.View.GONE; redoBtn.translationY = 0f }.start()
                undoBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(40).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { undoBtn.visibility = android.view.View.GONE; undoBtn.translationY = 0f }.start()
                resetBtn.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(offsetY)
                    .setDuration(200).setStartDelay(80).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { resetBtn.visibility = android.view.View.GONE; resetBtn.translationY = 0f }.start()

                editBtn.setBackgroundColor(0x00000000)
                editBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                editBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }

        // Undo/Redo — EQ state history (delegated to UndoRedoManager)
        undoRedoManager = com.bearinmind.equalizer314.state.UndoRedoManager(
            eqViewModel, eqGraphView, bandToggleManager
        )
        undoRedoManager.reset()

        // Preset manager — custom preset persistence
        presetManager = com.bearinmind.equalizer314.state.PresetManager(
            getSharedPreferences("custom_presets", MODE_PRIVATE)
        )

        undoBtn.setOnClickListener { undoRedoManager.undo() }
        redoBtn.setOnClickListener { undoRedoManager.redo() }

        fun updateVizToggleStyle(active: Boolean) {
            if (active) {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(0xFF555555.toInt())
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                vizToggle.strokeWidth = (2 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            } else {
                vizToggle.alpha = 1.0f
                vizToggle.setBackgroundColor(0x00000000)
                vizToggle.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                vizToggle.strokeWidth = (1 * vizDensity).toInt()
                vizToggle.iconTint = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            }
        }
        // Restore spectrum state from preferences
        if (eqViewModel.eqPrefs.getSpectrumEnabled() &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
            updateVizToggleStyle(true)
        } else {
            updateVizToggleStyle(false)
        }
        vizToggle.setOnClickListener {
            if (visualizerHelper.isRunning) {
                visualizerHelper.stop()
                eqGraphView.spectrumRenderer = null
                eqGraphView.invalidate()
                updateVizToggleStyle(false)
                eqViewModel.eqPrefs.saveSpectrumEnabled(false)
            } else {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                    return@setOnClickListener
                }
                visualizerHelper.start(eqGraphView)
                eqGraphView.spectrumRenderer = visualizerHelper.renderer
                updateVizToggleStyle(true)
                eqViewModel.eqPrefs.saveSpectrumEnabled(true)
            }
        }
        powerButton.setOnClickListener {
            if (eqViewModel.isProcessing.value) stopProcessing() else startProcessing()
        }

        // EQ toggle
        eqToggleButton.setOnClickListener {
            val eq = eqViewModel.parametricEq.value
            eq.isEnabled = !eq.isEnabled
            updateEqToggleUI()
            if (eqViewModel.isProcessing.value) {
                eqViewModel.eqService.value?.setEqEnabled(eq.isEnabled)
            }
        }

        // Preset dropdown
        presetDropdown.setOnItemClickListener { parent, _, position, _ ->
            val presetName = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            undoRedoManager.saveState()
            eqViewModel.loadPreset(presetName, eqGraphView)
            presetDropdown.setText(presetName, false)
            bandToggleManager.updateIcons()
            if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) tableController.buildTable()
            if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
        }

        // Graph callbacks
        eqGraphView.onBandSelectedListener = { bandIndex ->
            bandToggleManager.updateSelection(bandIndex)
            updateFilterTypeButtons(bandIndex)
            updateBandInputs(bandIndex)
            if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC && graphicController.updatePageForBand(bandIndex)) {
                graphicController.buildSliders(graphicController.targetCardHeight)
            }
            reorderToggleRows()
        }

        eqGraphView.onBandChangedListener = { bandIndex, _, _ ->
            // Throttle DP writes during drag — each ACTION_MOVE would otherwise
            // trigger a full Pre-EQ rewrite on the audio thread. The drag-end
            // listener flushes the final value.
            eqViewModel.pushEqUpdateThrottled()
            updateBandInputs(bandIndex)
            if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) tableController.buildTable()
            if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC) graphicController.updateSliderValues()
        }

        eqGraphView.onBandDragEndListener = { eqViewModel.flushEqUpdate() }

        eqGraphView.onLongPressListener = { showPresetsBottomSheet() }

        // Band parameter sliders + text inputs
        setupBandSliderListeners()
        setupBandInputListeners()

        // DP band count slider
        dpBandCountSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val count = value.toInt()
            dpBandCountText.setText(count.toString())
            ParametricToDpConverter.setNumBands(count)
            eqViewModel.eqPrefs.saveDpBandCount(count)
            eqViewModel.pushEqUpdate()
        }

        dpBandCountText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val count = dpBandCountText.text.toString().toIntOrNull()?.coerceIn(128, 1024) ?: 128
                dpBandCountText.setText(count.toString())
                dpBandCountSlider.value = count.toFloat()
                ParametricToDpConverter.setNumBands(count)
                eqViewModel.eqPrefs.saveDpBandCount(count)
                eqViewModel.pushEqUpdate()
                dpBandCountText.clearFocus()
            }
            true
        }

        // Filter type buttons
        setupFilterTypeButtons()
        // Color swatches
        setupColorSwatches()

        // EQ mode selector
        modeParametricBtn.setOnClickListener { switchEqUiMode(EqUiMode.PARAMETRIC) }
        modeGraphicBtn.setOnClickListener { switchEqUiMode(EqUiMode.GRAPHIC) }
        modeTableBtn.setOnClickListener { switchEqUiMode(EqUiMode.TABLE) }

        // Settings controls
        setupSettingsListeners()
    }

    // ---- Settings ----

    private fun updateAutoEqStatus() {
        val name = eqViewModel.eqPrefs.getAutoEqName()
        val statusText = findViewById<TextView>(R.id.autoEqStatusText)
        if (!name.isNullOrBlank()) {
            val source = eqViewModel.eqPrefs.getAutoEqSource() ?: ""
            statusText.text = "$name by $source"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = "Select or import a preset"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }

    private fun updateTargetStatus() {
        val name = eqViewModel.eqPrefs.getSelectedTargetName()
        val statusText = findViewById<TextView>(R.id.targetStatusText)
        if (!name.isNullOrBlank()) {
            val type = eqViewModel.eqPrefs.getSelectedTargetType() ?: ""
            statusText.text = if (type.isNotBlank()) "$name \u00B7 $type" else name
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorPrimary, 0xFFBB86FC.toInt()))
        } else {
            statusText.text = "Import a measurement and match to a specific target"
            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                statusText, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt()))
        }
    }

    private fun setupSpectrumControl() {
        findViewById<android.view.View>(R.id.spectrumControlCard).setOnClickListener {
            startActivity(android.content.Intent(this, SpectrumControlActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun applySpectrumSettings() {
        val ppoValues = intArrayOf(1, 2, 3, 6, 12, 24, 48, 96)
        val renderer = visualizerHelper.renderer
        if (eqViewModel.eqPrefs.getPpoEnabled()) {
            renderer.ppoSmoothing = ppoValues[eqViewModel.eqPrefs.getPpoIndex().coerceIn(0, 7)]
        } else {
            renderer.ppoSmoothing = 0
        }
        renderer.setSpectrumColor(eqViewModel.eqPrefs.getSpectrumColor())
        renderer.releaseAlpha = eqViewModel.eqPrefs.getSpectrumRelease()
    }

    private fun setupSettingsListeners() {
        // Simple EQ toggle (settings page)
        val simpleEqSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.simpleEqSwitch)
        simpleEqSwitch.isChecked = eqViewModel.eqPrefs.getSimpleEqEnabled()
        simpleEqSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqViewModel.eqPrefs.saveSimpleEqEnabled(isChecked)
            if (isChecked && eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
                switchEqUiMode(EqUiMode.SIMPLE)
            } else if (!isChecked && eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
                val fallback = try { EqUiMode.valueOf(eqViewModel.eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
                switchEqUiMode(fallback)
            }
        }

        // Channel Side EQ card (settings page) — opens the per-channel editor
        findViewById<View>(R.id.channelSideEqCard).setOnClickListener {
            startActivity(Intent(this, ChannelSideEqActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Convert-to-APO card — opens the Wavelet/Poweramp converter
        findViewById<View>(R.id.convertToApoCard).setOnClickListener {
            startActivity(Intent(this, ConvertToApoActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Audio Effects Pipeline — placeholder screen for chaining/reordering
        // session-0 audio effects.
        findViewById<View>(R.id.audioEffectsPipelineCard).setOnClickListener {
            startActivity(Intent(this, AudioEffectsPipelineActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Experimental lock button — toggles locked/unlocked state
        val experimentalLockButton = findViewById<ImageButton>(R.id.experimentalLockButton)
        val experimentalCard = findViewById<View>(R.id.experimentalCard)
        fun applyExperimentalLockState() {
            val unlocked = eqViewModel.eqPrefs.getExperimentalUnlocked()
            experimentalLockButton.setImageResource(
                if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock
            )
            experimentalCard.isClickable = unlocked
            experimentalCard.alpha = if (unlocked) 1f else 0.6f
        }
        applyExperimentalLockState()
        experimentalLockButton.setOnClickListener {
            val newState = !eqViewModel.eqPrefs.getExperimentalUnlocked()
            eqViewModel.eqPrefs.saveExperimentalUnlocked(newState)
            applyExperimentalLockState()
        }
        experimentalCard.setOnClickListener {
            if (!eqViewModel.eqPrefs.getExperimentalUnlocked()) return@setOnClickListener
            startActivity(Intent(this, ExperimentalActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // AutoEQ card (settings page)
        findViewById<View>(R.id.autoEqCard).setOnClickListener {
            autoEqLauncher.launch(Intent(this, AutoEqActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // Target card (settings page) — opens Target Curve screen
        findViewById<View>(R.id.targetCard).setOnClickListener {
            targetCurveLauncher.launch(Intent(this, TargetCurveActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Spectrum Control
        setupSpectrumControl()

        // Preamp slider
        preampSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            preampText.setText(String.format("%.1f", value))
            eqViewModel.setPreampGain( value)
            eqViewModel.eqPrefs.savePreampGain(value)
            eqViewModel.pushEqUpdate()
            updateAutoGainOffsetText()
        }

        preampText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val gain = preampText.text.toString().toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
                preampText.setText(String.format("%.1f", gain))
                preampSlider.value = gain
                eqViewModel.setPreampGain( gain)
                eqViewModel.eqPrefs.savePreampGain(gain)
                eqViewModel.pushEqUpdate()
                updateAutoGainOffsetText()
                preampText.clearFocus()
            }
            true
        }

        // Auto-gain switch
        autoGainSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqViewModel.setAutoGainEnabled( isChecked)
            eqViewModel.eqPrefs.saveAutoGainEnabled(isChecked)
            eqViewModel.pushEqUpdate()
            updateAutoGainOffsetText()
        }

        // Old inline limiter controls removed — now in LimiterActivity
    }

    private fun updateAutoGainOffsetText() {
        val offset = eqViewModel.getAutoGainOffset()
        autoGainOffsetText.text = getString(R.string.offset_label, offset)
    }

    // ---- EQ UI Mode Switching ----

    private fun switchEqUiMode(mode: EqUiMode) {
        // Clean up table mode bands when leaving
        if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE && mode != EqUiMode.TABLE) {
            tableController.cleanup()
        }
        // Save simple EQ gains and restore the advanced EQ when leaving SIMPLE mode
        if (eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE && mode != EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
            // Restore the advanced EQ state that was saved when entering SIMPLE mode
            val backup = eqViewModel.eqPrefs.getAdvancedEqBackup()
            if (backup != null) {
                val eq = eqViewModel.parametricEq.value
                com.bearinmind.equalizer314.dsp.EqSerializer.loadBandsTo(eq, backup)
                // Restore band slots
                eqViewModel.initBandSlots()
                eqGraphView.setParametricEqualizer(eq)
                eqGraphView.updateBandLevels()
                eqViewModel.pushEqUpdate()
                // Refresh the DP-band overlay — drawDpBands() reads cached
                // dpCenterFrequencies/dpGains arrays that were populated with
                // the Simple EQ response. Without this call the overlay keeps
                // rendering the Simple curve as a ghost outline until the user
                // touches the graph and triggers a separate refresh path.
            }
        }
        // Save the advanced EQ state before entering SIMPLE mode.
        // Skip if the current EQ is already a simple-EQ configuration (e.g. on app
        // startup when Simple EQ was enabled in the previous session) — otherwise
        // we'd overwrite the real advanced backup with simple-EQ band data.
        if (mode == EqUiMode.SIMPLE && eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
            val eq = eqViewModel.parametricEq.value
            val isAlreadySimpleConfig = eq.getBandCount() == com.bearinmind.equalizer314.ui.SimpleEqController.FREQUENCIES.size &&
                (0 until eq.getBandCount()).all { i ->
                    val band = eq.getBand(i)
                    band != null &&
                    band.filterType == com.bearinmind.equalizer314.dsp.BiquadFilter.FilterType.BELL &&
                    kotlin.math.abs(band.frequency - com.bearinmind.equalizer314.ui.SimpleEqController.FREQUENCIES[i]) < 0.5f
                }
            if (!isAlreadySimpleConfig) {
                val bandsJson = com.bearinmind.equalizer314.dsp.EqSerializer.bandsToJson(eq)
                eqViewModel.eqPrefs.saveAdvancedEqBackup(bandsJson.toString())
            }
        }
        eqViewModel.setEqUiMode(mode)
        eqGraphView.eqUiMode = mode
        if (mode != EqUiMode.SIMPLE) eqViewModel.eqPrefs.saveEqUiMode(mode.name)
        updateModeSelectorButtons()

        // In non-SIMPLE modes, ensure standard views are visible and simple container hidden.
        // Also reparent the preamp card back into eqControlsContainer if it was moved.
        if (mode != EqUiMode.SIMPLE) {
            modeSelectorGroup.visibility = View.VISIBLE
            graphCardView.visibility = View.VISIBLE
            eqControlsContainer.visibility = View.VISIBLE
            simpleEqContainer.visibility = View.GONE

            // Ensure advanced preset picker is closed when returning from SIMPLE
            findViewById<View>(R.id.presetPickerScroll).apply {
                animate().cancel()
                visibility = View.GONE
                alpha = 1f
            }

            val preampCard = findViewById<View>(R.id.preampCardBar)
            if (preampCard.parent !== eqControlsContainer) {
                (preampCard.parent as? android.view.ViewGroup)?.removeView(preampCard)
                eqControlsContainer.addView(preampCard)
                // Restore original XML margins (topMargin=8dp, bottomMargin=0dp)
                (preampCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = 0
                }
            }

            // Re-sync band toggles and graph after returning from SIMPLE mode
            // (SIMPLE mode replaces the EQ with 10 fixed bands, so we need to
            // refresh everything after restoring the advanced EQ state)
            bandToggleManager.setupToggles()
            eqGraphView.updateBandLevels()

            // Re-position the graph overlay buttons (visibility, save, reset, edit,
            // spectrum). They were positioned via eqGraphView.post{} at startup, but
            // when the graph card was GONE in SIMPLE mode, the view's width was 0.
            // Now that it's VISIBLE again, we need to re-layout after the view has
            // its real width.
            eqGraphView.post {
                graphOverlayLayoutManager.layoutButtons(
                    graphView = eqGraphView,
                    R.id.visualizerToggle to GraphOverlayLayoutManager.LayoutPosition.VISUALIZER,
                    R.id.editButton to GraphOverlayLayoutManager.LayoutPosition.EDIT,
                    R.id.resetButton to GraphOverlayLayoutManager.LayoutPosition.RESET,
                    R.id.undoButton to GraphOverlayLayoutManager.LayoutPosition.UNDO,
                    R.id.redoButton to GraphOverlayLayoutManager.LayoutPosition.REDO,
                    R.id.bandPointsToggle to GraphOverlayLayoutManager.LayoutPosition.BAND_POINTS,
                    R.id.savePresetButton to GraphOverlayLayoutManager.LayoutPosition.SAVE_PRESET,
                    R.id.altRouteButton to GraphOverlayLayoutManager.LayoutPosition.ALT_ROUTE,
                    R.id.settingsGearButton to GraphOverlayLayoutManager.LayoutPosition.SETTINGS_GEAR,
                    R.id.channelLButton to GraphOverlayLayoutManager.LayoutPosition.CHANNEL_L,
                    R.id.channelRButton to GraphOverlayLayoutManager.LayoutPosition.CHANNEL_R,
                )
            }
        }

        when (mode) {
            EqUiMode.PARAMETRIC -> {
                tableEqCard.setOnTouchListener(null)
                // Restore preamp margin (this also accidentally sets the controls
                // FrameLayout's topMargin to 8dp — that's what positions the table
                // card; do NOT remove this line)
                val contentLayout0 = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                val preampCard0 = contentLayout0.getChildAt(contentLayout0.childCount - 1)
                (preampCard0.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * resources.displayMetrics.density).toInt()
                // Clear any visual translation on the actual preamp card from table mode
                findViewById<View>(R.id.preampCardBar).translationY = 0f
                parametricControlsCard.visibility = View.VISIBLE
                graphicScrollView.visibility = View.GONE
                filterTypeGroup.visibility = View.VISIBLE
                hzControlRow.visibility = View.VISIBLE
                qControlGroup.visibility = View.VISIBLE
                bandInputGroup.visibility = View.VISIBLE
                bandQInputLayout.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                // bandToggleGroup2 visibility managed by BandToggleManager.updateRow2Visibility()
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.VISIBLE
                tableEqCard.visibility = View.GONE
                bandToggleManager.setupToggles()
                // Always have a band selected
                if (eqViewModel.parametricEq.value.getBandCount() > 0) {
                    val band = eqViewModel.selectedBandIndex.value ?: 0
                    eqViewModel.selectBand(band)
                    eqGraphView.setActiveBand(band)
                    updateFilterTypeButtons(band)
                    updateBandInputs(band)
                }
                reorderToggleRows(animate = false)
            }
            EqUiMode.GRAPHIC -> {
                tableEqCard.setOnTouchListener(null)
                // Restore preamp margin (this also accidentally sets the controls
                // FrameLayout's topMargin to 8dp — that's what positions the table
                // card; do NOT remove this line)
                val contentLayoutG = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                val preampCardG = contentLayoutG.getChildAt(contentLayoutG.childCount - 1)
                (preampCardG.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * resources.displayMetrics.density).toInt()
                // Clear any visual translation on the actual preamp card from table mode
                findViewById<View>(R.id.preampCardBar).translationY = 0f
                parametricControlsCard.measure(
                    View.MeasureSpec.makeMeasureSpec(parametricControlsCard.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                graphicController.targetCardHeight = parametricControlsCard.measuredHeight
                parametricControlsCard.visibility = View.GONE
                graphicScrollView.visibility = View.VISIBLE
                bandToggleGroup.visibility = View.VISIBLE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.INVISIBLE
                tableEqCard.visibility = View.GONE
                bandToggleManager.setupToggles()
                graphicController.buildSliders(graphicController.targetCardHeight)
                reorderToggleRows(animate = false)
            }
            EqUiMode.TABLE -> {
                parametricControlsCard.visibility = View.GONE
                graphicScrollView.visibility = View.GONE
                bandToggleGroup.visibility = View.GONE
                bandToggleGroup2.visibility = View.GONE
                findViewById<View>(R.id.triangleIndicatorContainer).visibility = View.GONE
                tableEqCard.visibility = View.VISIBLE

                val density = resources.displayMetrics.density

                // Compute and apply the table card height + preamp translationY.
                // Wrapped in a closure so we can run it both synchronously (best effort
                // on cold start) and after the first layout pass (which corrects any
                // cold-start measurement errors when the views haven't been laid out
                // yet and parametricControlsCard.width is still 0).
                val applyTableSizing = {
                    // Use the actual outer LinearLayout's content width when available,
                    // falling back to (screen width - 32dp parent padding) on cold start.
                    val outerLayout = (pageEq as ScrollView).getChildAt(0) as LinearLayout
                    val effectiveWidth = if (outerLayout.width > 0) {
                        outerLayout.width - outerLayout.paddingLeft - outerLayout.paddingRight
                    } else {
                        resources.displayMetrics.widthPixels - (32 * density).toInt()
                    }
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(effectiveWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

                    bandToggleGroup.measure(widthSpec, heightSpec)
                    parametricControlsCard.measure(widthSpec, heightSpec)

                    // Use bandToggleGroup.measuredHeight TWICE (for both row 1 and row 2)
                    // so the table card stays a constant size regardless of band count.
                    // Hardcode triangleContainer to 8dp because measure() returns 10dp
                    // (the inner triangleIndicator child's height) instead of the 8dp
                    // layout_height the parent uses in PARAM mode.
                    var targetHeight = bandToggleGroup.measuredHeight +
                        (8 * density).toInt() +
                        parametricControlsCard.measuredHeight +
                        bandToggleGroup.measuredHeight
                    // Add bottom margin from parametric card (8dp)
                    targetHeight += (8 * density).toInt()

                    val currentLp = tableEqCard.layoutParams
                    if (currentLp.height != targetHeight) {
                        tableEqCard.layoutParams = currentLp.apply { height = targetHeight }
                    }
                }

                // Synchronous best-effort
                applyTableSizing()

                // Re-run after the first layout pass — fixes cold-start case where the
                // synchronous measurements use stale (zero) widths and the buttons in
                // bandToggleGroup haven't been measured yet.
                pageEq.post {
                    if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) applyTableSizing()
                }

                // Let table card's inner ScrollView handle touches, block outer scroll
                tableEqCard.setOnTouchListener { v, event ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }

                // Lock the table card at zero translation (defensive against any
                // leftover translationY from previous frames or animations).
                tableEqCard.translationY = 0f

                // Move ONLY the actual preamp card visually up by 7dp so it sits at
                // the same Y as in parametric/graphic mode. translationY does not
                // affect layout — the table card stays put.
                findViewById<View>(R.id.preampCardBar).translationY = -(7 * density)

                tableController.buildTable()
            }
            EqUiMode.SIMPLE -> {
                // Hide standard EQ UI
                modeSelectorGroup.visibility = View.GONE
                graphCardView.visibility = View.GONE
                eqControlsContainer.visibility = View.GONE

                // Close advanced preset picker if it was left open
                findViewById<View>(R.id.presetPickerScroll).apply {
                    animate().cancel()
                    visibility = View.GONE
                    alpha = 1f
                }

                // Ensure the controls overlay FrameLayout has the same topMargin
                // as it does in PARAMETRIC/GRAPHIC mode (the "Restore preamp margin"
                // code sets it to 8dp there, but doesn't run in SIMPLE mode).
                val overlay = simpleEqContainer.parent as? View
                (overlay?.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                    (8 * resources.displayMetrics.density).toInt()
                overlay?.requestLayout()

                // In SIMPLE mode the mode selector + graph card are GONE, so the
                // content starts right at the top of pageEq. MBC/Limiter activities
                // handle this with fitsSystemWindows on their root, but here we need
                // to manually offset. Set the outer LinearLayout's paddingTop to 0
                // (the rootLayout already handles the status bar inset) — this is
                // already 0dp from XML, no change needed.

                // Scroll to top so header starts at correct position
                (pageEq as android.widget.ScrollView).scrollTo(0, 0)

                // Show simple 10-band EQ
                simpleEqContainer.visibility = View.VISIBLE
                simpleEqController.configureParametricEq()
                simpleEqController.buildSliders()

                // Reparent the existing preamp card from eqControlsContainer into
                // simpleEqContainer (between the bars/preset area and controls card).
                val preampCard = findViewById<View>(R.id.preampCardBar)
                (preampCard.parent as? android.view.ViewGroup)?.removeView(preampCard)
                // Insert at index 5: header(0), controls(1), graph(2), bars(3), presetPicker(4), preamp(5)
                simpleEqContainer.addView(preampCard, 5)
                preampCard.translationY = 0f
                // Set consistent 8dp bottom margin (remove the XML topMargin=8dp to
                // avoid double-spacing since bars card already has bottomMargin=8dp)
                (preampCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = 0
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
        }
        // Re-push EQ so DynamicsProcessingManager re-evaluates the
        // direct-graphic-vs-feature-aware path flag against the new mode.
        // Without this the live DP keeps using whatever path was active
        // before the switch until the next slider tick / band edit.
        eqViewModel.pushEqUpdate()
        // Refresh the red experimental-DP overlay so it shows the new
        // path (feature-aware ↔ direct) the moment the mode changes.
        eqGraphView.invalidate()
    }

    private fun reorderToggleRows(animate: Boolean = true) {
        if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE || eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) return
        val parent = findViewById<LinearLayout>(R.id.eqControlsContainer)
        val triContainer = findViewById<View>(R.id.triangleIndicatorContainer)

        // Determine which page the selected band is on
        val selectedBand = eqViewModel.selectedBandIndex.value ?: 0
        val displayPos = eqViewModel.displayToBandIndex.value.indexOf(selectedBand).let { if (it < 0) 0 else it }
        val activePage = displayPos / 8
        val activeRow = if (activePage == 0) bandToggleGroup else bandToggleGroup2
        val inactiveRow = if (activePage == 0) bandToggleGroup2 else bandToggleGroup

        // Check if both rows are already in correct positions — use index 0 as base since controls container starts with toggle groups
        val graphIdx = -1  // graph is outside eqControlsContainer
        val currentActiveIdx = parent.indexOfChild(activeRow)
        val controlsView = when (eqViewModel.currentEqUiMode.value) {
            EqUiMode.PARAMETRIC -> parametricControlsCard
            EqUiMode.GRAPHIC -> graphicScrollView
            else -> null
        }
        val expectedInactiveIdx = if (controlsView != null) parent.indexOfChild(controlsView) + 1 else graphIdx + 3
        val currentInactiveIdx = parent.indexOfChild(inactiveRow)
        if (currentActiveIdx == graphIdx + 1 && currentInactiveIdx == expectedInactiveIdx) return

        // Animate the transition
        if (animate) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            android.transition.TransitionManager.beginDelayedTransition(parent, transition)
        }

        // Remove movable views
        parent.removeView(bandToggleGroup)
        parent.removeView(bandToggleGroup2)
        parent.removeView(triContainer)

        // Active row at top of controls container
        (activeRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        parent.addView(activeRow, 0)

        // Triangle indicator after active row
        parent.addView(triContainer, 1)

        // Inactive row after controls
        (inactiveRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        if (controlsView != null) {
            val controlsIdx = parent.indexOfChild(controlsView)
            parent.addView(inactiveRow, controlsIdx + 1)
        } else {
            parent.addView(inactiveRow, 2)
        }
    }

    private fun updateModeSelectorButtons() {
        val buttons = listOf(
            modeParametricBtn to EqUiMode.PARAMETRIC,
            modeGraphicBtn to EqUiMode.GRAPHIC,
            modeTableBtn to EqUiMode.TABLE
        )
        for ((btn, mode) in buttons) {
            if (mode == eqViewModel.currentEqUiMode.value) {
                btn.setBackgroundColor(getColor(R.color.filter_active))
                btn.setTextColor(getColor(R.color.filter_active_text))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_active))
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.setTextColor(getColor(R.color.filter_inactive_text))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_outline))
            }
        }
    }

    // ---- Band Parameter Sliders + Text Inputs ----

    private fun hzToSlider(hz: Float): Float {
        val logHz = kotlin.math.log10(hz.coerceIn(10f, 20000f))
        return ((logHz - hzLogMin) / (hzLogMax - hzLogMin) * 1000f)
    }

    private fun sliderToHz(pos: Float): Float {
        val logHz = hzLogMin + (pos / 1000f) * (hzLogMax - hzLogMin)
        return (10.0).pow(logHz.toDouble()).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBandSliderListeners() {
        bandHzSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            val hz = sliderToHz(value)
            isUpdatingInputs = true
            bandHzInput.setText(formatHzValue(hz))
            isUpdatingInputs = false
            applyBandHz(hz)
        }

        bandDbSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandDbInput.setText(String.format("%.1f", value))
            isUpdatingInputs = false
            applyBandDb(value)
        }

        qSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingInputs) return@addOnChangeListener
            isUpdatingInputs = true
            bandQInput.setText(String.format("%.2f", value))
            isUpdatingInputs = false
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addOnChangeListener
            eqGraphView.setQ(bandIndex, value.toDouble())
            eqViewModel.pushEqUpdate()
        }

        // Double-tap to reset sliders to default values
        addDoubleTapReset(bandHzSlider) {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            val defaults = eqViewModel.stateManager.allDefaultFrequencies
            val slot = eqViewModel.bandSlots.value.getOrElse(bandIndex) { bandIndex }
            val defaultHz = if (slot < defaults.size) defaults[slot] else 1000f
            bandHzSlider.value = hzToSlider(defaultHz)
            bandHzInput.setText(formatHzValue(defaultHz))
            applyBandHz(defaultHz)
        }

        addDoubleTapReset(bandDbSlider) {
            bandDbSlider.value = 0f
            bandDbInput.setText("0.0")
            applyBandDb(0f)
        }

        addDoubleTapReset(qSlider) {
            val defaultQ = 0.71f
            qSlider.value = defaultQ
            bandQInput.setText(String.format("%.2f", defaultQ))
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@addDoubleTapReset
            eqGraphView.setQ(bandIndex, defaultQ.toDouble())
            eqViewModel.pushEqUpdate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addDoubleTapReset(slider: Slider, onReset: () -> Unit) {
        var lastTapTime = 0L
        var consumeUntilUp = false
        slider.setOnTouchListener { v, event ->
            if (consumeUntilUp) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    consumeUntilUp = false
                }
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    isUpdatingInputs = true
                    onReset()
                    isUpdatingInputs = false
                    eqGraphView.invalidate()
                    lastTapTime = 0L
                    consumeUntilUp = true
                    return@setOnTouchListener true
                }
                lastTapTime = now
            }
            false
        }
    }

    private fun setupBandInputListeners() {
        val applyOnAction = android.view.inputmethod.EditorInfo.IME_ACTION_DONE

        bandHzInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandHzFromInput(); true } else false
        }
        bandHzInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandHzFromInput()
        }

        bandDbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandDbFromInput(); true } else false
        }
        bandDbInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandDbFromInput()
        }

        bandQInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == applyOnAction) { applyBandQFromInput(); true } else false
        }
        bandQInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyBandQFromInput()
        }
    }

    private fun applyBandHzFromInput() {
        if (isUpdatingInputs) return
        val hz = bandHzInput.text.toString().toFloatOrNull() ?: return
        val clamped = hz.coerceIn(10f, 20000f)
        isUpdatingInputs = true
        bandHzSlider.value = hzToSlider(clamped)
        isUpdatingInputs = false
        applyBandHz(clamped)
    }

    private fun applyBandDbFromInput() {
        if (isUpdatingInputs) return
        val db = bandDbInput.text.toString().toFloatOrNull() ?: return
        val clamped = db.coerceIn(-12f, 12f)
        isUpdatingInputs = true
        bandDbSlider.value = clamped
        isUpdatingInputs = false
        applyBandDb(clamped)
    }

    private fun applyBandQFromInput() {
        if (isUpdatingInputs) return
        val q = bandQInput.text.toString().toDoubleOrNull() ?: return
        val clamped = q.coerceIn(0.1, 12.0)
        isUpdatingInputs = true
        qSlider.value = clamped.toFloat()
        isUpdatingInputs = false
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        eqGraphView.setQ(bandIndex, clamped)
        eqViewModel.pushEqUpdate()
    }

    private fun applyBandHz(hz: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        eqViewModel.parametricEq.value.updateBand(bandIndex, hz, band.gain, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        eqViewModel.pushEqUpdate()
    }

    private fun applyBandDb(db: Float) {
        val bandIndex = eqGraphView.getActiveBandIndex() ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val ftNow = band.filterType
        val gainless = ftNow == BiquadFilter.FilterType.LOW_PASS ||
                       ftNow == BiquadFilter.FilterType.HIGH_PASS ||
                       ftNow == BiquadFilter.FilterType.LOW_PASS_1 ||
                       ftNow == BiquadFilter.FilterType.HIGH_PASS_1 ||
                       ftNow == BiquadFilter.FilterType.BAND_PASS ||
                       ftNow == BiquadFilter.FilterType.NOTCH ||
                       ftNow == BiquadFilter.FilterType.ALL_PASS
        val effectiveDb = if (gainless) 0f else db
        eqViewModel.parametricEq.value.updateBand(bandIndex, band.frequency, effectiveDb, band.filterType, band.q)
        eqGraphView.updateBandLevels()
        eqViewModel.pushEqUpdate()
    }

    private fun showPresetsBottomSheet() {
        val density = resources.displayMetrics.density
        val presets = arrayOf("Flat", "Bass Boost", "Treble Boost", "Vocal Enhance")
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        for (presetName in presets) {
            val item = TextView(this).apply {
                text = presetName
                textSize = 16f
                setTextColor(0xFFE2E2E2.toInt())
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                setOnClickListener {
                    eqViewModel.loadPreset(presetName, eqGraphView)
                    presetDropdown.setText(presetName, false)
                    bandToggleManager.updateIcons()
                    if (eqViewModel.currentEqUiMode.value == EqUiMode.TABLE) tableController.buildTable()
                    if (eqViewModel.currentEqUiMode.value == EqUiMode.GRAPHIC) graphicController.buildSliders(graphicController.targetCardHeight)
                    bottomSheet.dismiss()
                }
            }
            sheetLayout.addView(item)
        }
        bottomSheet.setContentView(sheetLayout)
        bottomSheet.show()
    }

    private fun formatHzValue(hz: Float): String {
        return if (hz >= 1000) String.format("%.0f", hz) else String.format("%.1f", hz)
    }

    private fun updateBandInputs(bandIndex: Int?) {
        isUpdatingInputs = true
        val idx = bandIndex ?: eqViewModel.selectedBandIndex.value ?: 0
        val band = eqViewModel.parametricEq.value.getBand(idx)
        if (band != null) {
            bandHzInput.setText(formatHzValue(band.frequency))
            bandDbInput.setText(String.format("%.1f", band.gain))
            bandQInput.setText(String.format("%.2f", band.q))
            bandHzSlider.value = hzToSlider(band.frequency)
            bandDbSlider.value = band.gain.coerceIn(-12f, 12f)
            qSlider.value = band.q.toFloat().coerceIn(0.1f, 12f)

            // dB slider / input are disabled for every gainless filter type:
            // low / high pass at either order, plus BP / NO / AP.
            val ft = band.filterType
            val gainless = ft == BiquadFilter.FilterType.LOW_PASS ||
                           ft == BiquadFilter.FilterType.HIGH_PASS ||
                           ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                           ft == BiquadFilter.FilterType.HIGH_PASS_1 ||
                           ft == BiquadFilter.FilterType.BAND_PASS ||
                           ft == BiquadFilter.FilterType.NOTCH ||
                           ft == BiquadFilter.FilterType.ALL_PASS
            bandDbSlider.isEnabled = !gainless
            bandDbInput.isEnabled = !gainless
            bandDbSlider.alpha = if (gainless) 0.3f else 1f
            bandDbInput.alpha = if (gainless) 0.3f else 1f

            // Q slider / input don't apply to 1st-order variants — they
            // collapse to a 6 dB/oct slope with no Q term.
            val is1st = ft == BiquadFilter.FilterType.LOW_SHELF_1 ||
                        ft == BiquadFilter.FilterType.HIGH_SHELF_1 ||
                        ft == BiquadFilter.FilterType.LOW_PASS_1 ||
                        ft == BiquadFilter.FilterType.HIGH_PASS_1
            qSlider.isEnabled = !is1st
            bandQInput.isEnabled = !is1st
            qSlider.alpha = if (is1st) 0.3f else 1f
            bandQInput.alpha = if (is1st) 0.3f else 1f
        } else {
            bandHzInput.setText("1000")
            bandDbInput.setText("0.0")
            bandQInput.setText("0.71")
            bandHzSlider.value = 500f
            bandDbSlider.value = 0f
            qSlider.value = 0.71f
            bandDbSlider.isEnabled = true
            bandDbInput.isEnabled = true
            bandDbSlider.alpha = 1f
            bandDbInput.alpha = 1f
            qSlider.isEnabled = true
            bandQInput.isEnabled = true
            qSlider.alpha = 1f
            bandQInput.alpha = 1f
        }
        updateColorSwatches(bandIndex)
        isUpdatingInputs = false
    }

    private fun setupColorSwatches() {
        colorSwatchRow.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (22 * density).toInt()

        for (color in TableEqController.BAND_COLORS) {
            val isNone = color == 0xFF333333.toInt()
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val swatch: View = if (isNone) {
                TextView(this).apply {
                    text = "\u2014"
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF333333.toInt())
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            } else {
                View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 6 * density
                        setStroke((1 * density).toInt(), 0xFF666666.toInt())
                    }
                }
            }
            swatch.setOnClickListener {
                val bandIndex = eqViewModel.selectedBandIndex.value ?: return@setOnClickListener
                val slotIdx = if (bandIndex < eqViewModel.bandSlots.value.size) eqViewModel.bandSlots.value[bandIndex] else return@setOnClickListener
                if (isNone) {
                    eqViewModel.bandColorsMap.remove(slotIdx)
                } else {
                    eqViewModel.bandColorsMap[slotIdx] = color
                }
                eqViewModel.saveState()
                eqGraphView.setBandColors(eqViewModel.bandColors.value)
                eqGraphView.invalidate()
                bandToggleManager.updateSelection(eqViewModel.selectedBandIndex.value)
                updateColorSwatches(bandIndex)
            }
            wrapper.addView(swatch)
            colorSwatchRow.addView(wrapper)
        }
    }

    private fun updateColorSwatches(bandIndex: Int?) {
        val idx = bandIndex ?: eqViewModel.selectedBandIndex.value ?: return
        val slotIdx = if (idx < eqViewModel.bandSlots.value.size) eqViewModel.bandSlots.value[idx] else -1
        val currentColor = if (slotIdx >= 0) eqViewModel.bandColors.value[slotIdx] else null
        val density = resources.displayMetrics.density

        for (i in 0 until colorSwatchRow.childCount) {
            val wrapper = colorSwatchRow.getChildAt(i) as? FrameLayout ?: continue
            val swatch = wrapper.getChildAt(0) ?: continue
            val bg = swatch.background as? android.graphics.drawable.GradientDrawable ?: continue

            val swatchColor = TableEqController.BAND_COLORS[i]
            val isNone = swatchColor == 0xFF333333.toInt()
            val isSelected = if (isNone) currentColor == null else currentColor == swatchColor

            if (isSelected) {
                bg.setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            } else {
                bg.setStroke((1 * density).toInt(), 0xFF666666.toInt())
            }
        }
    }

    // ---- Processing Control ----

    private fun startProcessing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, getString(R.string.msg_dsp_requires_api), Toast.LENGTH_LONG).show()
            return
        }

        // POST_NOTIFICATIONS is only needed to *display* the foreground-service
        // notification. The service starts fine without it on API 33+ — the
        // notification is just silently suppressed. Don't gate the EQ on it,
        // and don't return early: the previous behaviour caused an infinite
        // recursion freeze when notifications were OS-disabled (the system
        // returned DENIED immediately, onRequestPermissionsResult retried
        // startProcessing(), which re-requested → loop on the main thread).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !notificationPermissionRequested
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            // fall through — power-on continues regardless of grant result
        }

        // Start animation first, then do heavy work after a frame so animation plays smoothly
        showPowerSnackbar(true)
        animatePowerFab(true)

        powerFab.postDelayed({
            EqService.start(this)
            if (eqViewModel.serviceBound.value) {
                doStartEq()
            } else {
                eqViewModel.stateManager.pendingStartEq = true
                val intent = Intent(this, EqService::class.java)
                bindService(intent, eqViewModel.stateManager.serviceConnection, BIND_AUTO_CREATE)
            }
        }, 280)
    }

    private fun doStartEq() {
        eqViewModel.doStartEq { on -> animatePowerFab(on) }
    }

    private fun stopProcessing() {
        showPowerSnackbar(false)
        animatePowerFab(false)
        powerFab.postDelayed({
            eqViewModel.stopProcessing { on -> animatePowerFab(on) }
        }, 280)
    }

    private fun showPowerSnackbar(on: Boolean) {
        eqViewModel.eqPrefs.savePowerState(on)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updatePowerFab(this, on)
        val message = if (on) getString(R.string.msg_dsp_start) else getString(R.string.msg_dsp_stop)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---- UI Updates ----

    private var powerAnimator: android.animation.ValueAnimator? = null

    private fun animatePowerFab(on: Boolean) {
        // Don't duplicate — BottomNavHelper.updatePowerFab handles the full animation
        // Just update the text label
        powerButton.text = if (on) "ON" else "OFF"
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val a = ((from shr 24 and 0xFF) * inv + (to shr 24 and 0xFF) * ratio).toInt()
        val r = ((from shr 16 and 0xFF) * inv + (to shr 16 and 0xFF) * ratio).toInt()
        val g = ((from shr 8 and 0xFF) * inv + (to shr 8 and 0xFF) * ratio).toInt()
        val b = ((from and 0xFF) * inv + (to and 0xFF) * ratio).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun updatePowerUI() {
        powerButton.text = if (eqViewModel.isProcessing.value) "ON" else "OFF"
    }

    private fun updateBottomBarHighlight(isEqPage: Boolean) {
        val screen = if (isEqPage) com.bearinmind.equalizer314.ui.NavScreen.EQ else com.bearinmind.equalizer314.ui.NavScreen.SETTINGS
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateHighlight(this, screen)
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqViewModel.eqPrefs)
    }

    private fun updateEqToggleUI() {
        val enabled = eqViewModel.parametricEq.value.isEnabled
        eqToggleButton.text = if (enabled) "EQ: ON" else "EQ: OFF"
    }

    // ---- Filter Type Buttons ----

    private fun setupFilterTypeButtons() {
        filterTypeGroup.removeAllViews()

        // PEAK — first tap applies BELL (the default "Peak" / PK). When the
        // band is already in the peak family (PK / BP / NO / AP), a second
        // tap opens a dropdown to pick between those four sub-types.
        val peakBtn = buildFilterTypeButton(
            label = getString(R.string.filter_peak),
            defaultSubtitle = "",
            weightedWidth = true,
        )
        peakBtn.setOnClickListener { anchor ->
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            val band = eqViewModel.parametricEq.value.getBand(bandIndex)
            val inFamily = band != null && band.enabled && isPeakFamily(band.filterType)
            if (inFamily) {
                showPeakPopup(anchor, bandIndex)
            } else {
                applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.BELL)
            }
        }
        filterTypeGroup.addView(peakBtn)

        // Shelves & passes — each has a 2-tap 12 dB / 6 dB slope popup.
        val shelfPassTypes = listOf(
            getString(R.string.filter_low_shelf) to BiquadFilter.FilterType.LOW_SHELF,
            getString(R.string.filter_high_shelf) to BiquadFilter.FilterType.HIGH_SHELF,
            getString(R.string.filter_low_pass) to BiquadFilter.FilterType.LOW_PASS,
            getString(R.string.filter_high_pass) to BiquadFilter.FilterType.HIGH_PASS,
        )
        for ((label, type) in shelfPassTypes) {
            val btn = buildFilterTypeButton(
                label = label,
                defaultSubtitle = getString(R.string.msg_12db),
                weightedWidth = true,
            )
            btn.setOnClickListener { anchor ->
                val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
                val band = eqViewModel.parametricEq.value.getBand(bandIndex)
                val alreadyActive = band != null &&
                    band.enabled &&
                    filterTypeFamily(band.filterType) == type
                if (alreadyActive) {
                    showSlopePopup(anchor, bandIndex, type)
                } else {
                    applyFilterTypeToBand(bandIndex, type)
                }
            }
            filterTypeGroup.addView(btn)
        }

        // BYPASS — tied 1:1 with the APO `AP` (all-pass) token. Tapping
        // BYPASS sets the band to ALL_PASS (flat magnitude on-device, exports
        // as `Filter N: ON AP ...`). No dropdown, single tap applies.
        val bypassBtn = buildFilterTypeButton(
            label = getString(R.string.filter_bypass),
            defaultSubtitle = "",
            weightedWidth = true,
        )
        bypassBtn.setOnClickListener {
            val bandIndex = eqGraphView.getActiveBandIndex() ?: return@setOnClickListener
            applyFilterTypeToBand(bandIndex, BiquadFilter.FilterType.ALL_PASS)
        }
        filterTypeGroup.addView(bypassBtn)
    }

    private fun updateFilterTypeButtons(bandIndex: Int?) {
        if (bandIndex == null) return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val currentType = band.filterType
        val currentFamily = filterTypeFamily(currentType)
        val currentIs1st = currentType == BiquadFilter.FilterType.LOW_SHELF_1 ||
                           currentType == BiquadFilter.FilterType.HIGH_SHELF_1 ||
                           currentType == BiquadFilter.FilterType.LOW_PASS_1 ||
                           currentType == BiquadFilter.FilterType.HIGH_PASS_1
        val bandEnabled = band.enabled

        // Single row: order matches how buttons are added in
        // setupFilterTypeButtons: PEAK, LSHELF, HSHELF, LPF, HPF, BYPASS.
        data class Entry(val btn: MaterialButton, val label: String, val role: Role)
        val roles = listOf(Role.PEAK, Role.SHELF_PASS, Role.SHELF_PASS, Role.SHELF_PASS, Role.SHELF_PASS, Role.BYPASS)
        val labels = listOf(getString(R.string.filter_peak), getString(R.string.filter_low_shelf), getString(R.string.filter_high_shelf), getString(R.string.filter_low_pass), getString(R.string.filter_high_pass), getString(R.string.filter_bypass))
        val typesForHighlight = listOf(
            BiquadFilter.FilterType.BELL,
            BiquadFilter.FilterType.LOW_SHELF,
            BiquadFilter.FilterType.HIGH_SHELF,
            BiquadFilter.FilterType.LOW_PASS,
            BiquadFilter.FilterType.HIGH_PASS,
            BiquadFilter.FilterType.BELL,        // unused for BYPASS
        )
        val entries = mutableListOf<Entry>()
        for (i in 0 until filterTypeGroup.childCount) {
            val btn = filterTypeGroup.getChildAt(i) as? MaterialButton ?: continue
            if (i >= roles.size) break
            entries += Entry(btn, labels[i], roles[i])
        }

        val inPeakFam = isPeakFamily(currentType)
        val isAllPass = currentType == BiquadFilter.FilterType.ALL_PASS

        for ((i, e) in entries.withIndex()) {
            val buttonType = typesForHighlight[i]
            val sameFamily = filterTypeFamily(buttonType) == currentFamily
            val isActive = when (e.role) {
                Role.PEAK -> bandEnabled && inPeakFam
                Role.SHELF_PASS -> bandEnabled && sameFamily
                // BYPASS is tied to AP: highlighted when band is ALL_PASS
                // OR when band is disabled (legacy presets).
                Role.BYPASS -> !bandEnabled || isAllPass
            }

            if (isActive) {
                e.btn.setBackgroundColor(getColor(R.color.filter_active))
                e.btn.setTextColor(getColor(R.color.filter_active_text))
            } else {
                e.btn.setBackgroundColor(0x00000000)
                e.btn.setTextColor(getColor(R.color.filter_inactive_text))
                e.btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.filter_outline))
            }

            // Subtitle policy:
            //   • PEAK: blank. Sub-type is shown by replacing the primary
            //     label with "BAND PASS" / "NOTCH" instead of a subtitle.
            //   • Shelves / passes: current slope if active, else default "12 dB".
            //   • BYPASS: blank. Bypass↔AP mapping is implicit in APO export.
            val primary: String = when (e.role) {
                Role.PEAK -> peakButtonLabel(currentType, bandEnabled)
                else -> e.label
            }
            val subtitle: String = when (e.role) {
                Role.PEAK -> ""
                Role.SHELF_PASS -> when {
                    sameFamily -> if (currentIs1st) "6 dB" else getString(R.string.msg_12db)
                    else -> getString(R.string.msg_12db)
                }
                Role.BYPASS -> ""
            }
            e.btn.text = buildFilterButtonText(primary, subtitle)
        }
    }

    /** Primary label text for the PEAK button. When the band is on BP or NO
     *  the label swaps to "B. PASS" / "NOTCH" so the sub-type reads like its
     *  own filter category rather than a variant badge. "B. PASS" keeps the
     *  label short so the text stays at full size without shrinking. */
    private fun peakButtonLabel(current: BiquadFilter.FilterType, bandEnabled: Boolean): String = when {
        !bandEnabled -> getString(R.string.filter_peak)
        current == BiquadFilter.FilterType.BAND_PASS -> "B. PASS"
        current == BiquadFilter.FilterType.NOTCH -> "NOTCH"
        else -> getString(R.string.filter_peak)
    }

    private enum class Role { PEAK, SHELF_PASS, BYPASS }

    /** True when the filter type belongs to the PEAK button's dropdown:
     *  the plain bell plus the two gainless Fc+Q specials (BP / NO).
     *  ALL_PASS lives on the BYPASS button, not here. */
    private fun isPeakFamily(t: BiquadFilter.FilterType): Boolean = when (t) {
        BiquadFilter.FilterType.BELL,
        BiquadFilter.FilterType.BAND_PASS,
        BiquadFilter.FilterType.NOTCH -> true
        else -> false
    }


    /** Collapse 1st- and 2nd-order variants into a single "family" key so the
     *  filter-type button highlighting treats LShelf / LShelf (6 dB) as the
     *  same button. */
    private fun filterTypeFamily(t: BiquadFilter.FilterType): BiquadFilter.FilterType = when (t) {
        BiquadFilter.FilterType.LOW_SHELF_1 -> BiquadFilter.FilterType.LOW_SHELF
        BiquadFilter.FilterType.HIGH_SHELF_1 -> BiquadFilter.FilterType.HIGH_SHELF
        BiquadFilter.FilterType.LOW_PASS_1 -> BiquadFilter.FilterType.LOW_PASS
        BiquadFilter.FilterType.HIGH_PASS_1 -> BiquadFilter.FilterType.HIGH_PASS
        else -> t
    }

    /** Given a 2nd-order filter family button (LOW_SHELF / HIGH_SHELF /
     *  LOW_PASS / HIGH_PASS), return the matching 1st-order type. */
    private fun oneOrderVariant(family: BiquadFilter.FilterType): BiquadFilter.FilterType? = when (family) {
        BiquadFilter.FilterType.LOW_SHELF -> BiquadFilter.FilterType.LOW_SHELF_1
        BiquadFilter.FilterType.HIGH_SHELF -> BiquadFilter.FilterType.HIGH_SHELF_1
        BiquadFilter.FilterType.LOW_PASS -> BiquadFilter.FilterType.LOW_PASS_1
        BiquadFilter.FilterType.HIGH_PASS -> BiquadFilter.FilterType.HIGH_PASS_1
        else -> null
    }

    /** Build the filter-button label. Two-line form when a subtitle is
     *  supplied ("LSHELF\n12 dB"); single-line form when there's no subtitle
     *  (PEAK, B. PASS, NOTCH, BYPASS) so the visible text sits at the true
     *  vertical center of the button. Row / button minHeight keeps every
     *  cell the same size despite the line-count difference. The primary
     *  label is proportionally shrunk when it's long enough to overflow
     *  (8+ chars → 70% of the button's textSize). */
    private fun buildFilterButtonText(primary: String, subtitle: String): CharSequence {
        val full = if (subtitle.isEmpty()) primary else "$primary\n$subtitle"
        val span = android.text.SpannableString(full)
        val shrink = when {
            primary.length >= 8 -> 0.7f
            else -> 1f
        }
        if (shrink < 1f) {
            span.setSpan(
                android.text.style.RelativeSizeSpan(shrink),
                0, primary.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }

    /** Apply a chosen filter type to a band and refresh every dependent UI
     *  surface (graph, toggles, inputs, DP pipeline, saved state). Used by
     *  both the direct PEAK tap and the slope-popup selection path. */
    private fun applyFilterTypeToBand(bandIndex: Int, newType: BiquadFilter.FilterType) {
        eqViewModel.parametricEq.value.setBandEnabled(bandIndex, true)
        eqGraphView.setFilterType(bandIndex, newType)
        updateFilterTypeButtons(bandIndex)
        updateBandInputs(bandIndex)
        bandToggleManager.updateIcons()
        bandToggleManager.updateSelection(bandIndex)
        eqViewModel.pushEqUpdate()
        eqViewModel.eqPrefs.saveState(eqViewModel.parametricEq.value, eqViewModel.bandSlots.value)
        eqViewModel.persistLeftRightIfCse()
    }

    /** Shared factory for the filter-type buttons in both rows. `weightedWidth`
     *  gives the button `layout_weight=1` with width=0 (for the shelves row);
     *  passing a `fixedWidthPx` gives it that exact width (for the centered
     *  PEAK / BYPASS row). */
    private fun buildFilterTypeButton(
        label: String,
        defaultSubtitle: String,
        weightedWidth: Boolean,
        fixedWidthPx: Int = 0,
    ): MaterialButton {
        val density = resources.displayMetrics.density
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = null
            text = buildFilterButtonText(label, defaultSubtitle)
            textSize = 11f
            cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius)
            isSingleLine = false
            maxLines = 2
            // Explicit centering on both axes so primary + subtitle stay
            // centered regardless of label length or subtitle state.
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            // Drop the font's ascender/descender padding so the two-line
            // text block measures exactly 2x its line height and centers
            // cleanly inside the button's vertical space.
            includeFontPadding = false
            layoutParams = if (weightedWidth) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(fixedWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            }.apply { setMargins(3, 0, 3, 0) }
            val vertPad = (6 * density).toInt()
            setPadding(0, vertPad, 0, vertPad)
            insetTop = 0
            insetBottom = 0
            // Common minimum height so every button in the row is the
            // same size, regardless of primary-label shrink factor.
            minimumHeight = (42 * density).toInt()
            minHeight = (42 * density).toInt()
        }
    }

    /** Drop the PEAK button's PK / BP / NO / AP dropdown directly below the
     *  button, styled to match `showSlopePopup`. Tapping any item applies
     *  that filter type to the active band. */
    private fun showPeakPopup(anchor: View, bandIndex: Int) {
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val current = band.filterType
        val density = resources.displayMetrics.density
        val cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = getColor(R.color.filter_outline)
        val activeBg = getColor(R.color.filter_active)
        val activeTx = getColor(R.color.filter_active_text)
        val inactiveTx = getColor(R.color.filter_inactive_text)
        val bgColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = android.widget.PopupWindow(
            container,
            anchor.width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, type: BiquadFilter.FilterType) {
            val isActive = current == type
            val item = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    applyFilterTypeToBand(bandIndex, type)
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
            }
            container.addView(item)
        }

        fun addDivider() {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt(),
                )
                setBackgroundColor(outlineColor)
            }
            container.addView(divider)
        }

        // PK (plain bell) lives at the top so the user can return from
        // BP / NO to a classic peaking bell without round-tripping through a
        // different filter family. AP is owned by BYPASS, not listed here.
        addItem("PK", BiquadFilter.FilterType.BELL)
        addDivider()
        addItem("BP", BiquadFilter.FilterType.BAND_PASS)
        addDivider()
        addItem("NO", BiquadFilter.FilterType.NOTCH)

        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }

    /** Drop a small outlined popup directly below the tapped filter button,
     *  styled to match the button itself (same width, same outline colour,
     *  same card background). Two items — "12 dB" and "6 dB" — apply the
     *  corresponding 2nd- or 1st-order variant of the tapped filter family. */
    private fun showSlopePopup(
        anchor: View,
        bandIndex: Int,
        family2nd: BiquadFilter.FilterType,
    ) {
        val family1st = oneOrderVariant(family2nd) ?: return
        val band = eqViewModel.parametricEq.value.getBand(bandIndex) ?: return
        val currentIs1st = band.filterType == family1st
        val density = resources.displayMetrics.density
        val cornerRadius = resources.getDimensionPixelSize(R.dimen.filter_btn_radius).toFloat()
        val outlineColor = getColor(R.color.filter_outline)
        val activeBg = getColor(R.color.filter_active)
        val activeTx = getColor(R.color.filter_active_text)
        val inactiveTx = getColor(R.color.filter_inactive_text)
        // Match the parametric controls card's fill so the popup reads as an
        // extension of the same surface.
        val bgColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFF2A2930.toInt()
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * density).toInt(), outlineColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
        }

        val popup = android.widget.PopupWindow(
            container,
            anchor.width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = 8f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }

        fun addItem(label: String, isActive: Boolean, onTap: () -> Unit) {
            val item = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                setTextColor(if (isActive) activeTx else inactiveTx)
                if (isActive) setBackgroundColor(activeBg)
                val vertPad = (8 * density).toInt()
                setPadding(0, vertPad, 0, vertPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    onTap()
                    popup.dismiss()
                }
                isClickable = true
                isFocusable = true
                setBackgroundResource(0)
                if (isActive) setBackgroundColor(activeBg)
            }
            container.addView(item)
        }

        addItem(getString(R.string.msg_12db), !currentIs1st) { applyFilterTypeToBand(bandIndex, family2nd) }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt(),
            )
            setBackgroundColor(outlineColor)
        }
        container.addView(divider)
        addItem("6 dB", currentIs1st) { applyFilterTypeToBand(bandIndex, family1st) }

        // Drop it directly under the button with a tiny gap.
        popup.showAsDropDown(anchor, 0, (2 * density).toInt())
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        if (!eqViewModel.serviceBound.value) {
            val intent = Intent(this, EqService::class.java)
            bindService(intent, eqViewModel.stateManager.serviceConnection, 0)
        }
    }

    /** Paint the L / R button bg/stroke/text colours according to the current
     *  active channel. Does NOT touch alpha — callers that drive the popup
     *  animation set alpha independently. */
    /** Geometry cached on first layout so `repositionChannelBadge` can
     *  re-anchor the badge whenever its text changes (L vs R glyphs can
     *  differ in width at very small font sizes, and the XML placeholder
     *  may measure slightly differently from the runtime value).
     *  "Correct" offsets tuned by hand against the Samsung Z Flip7 UI —
     *  if the user asks to revisit L/R badge positioning, these are the
     *  baseline values:
     *    leftMargin = altRouteLeft + specWidth - badgeWidth - 6 dp
     *    topMargin  = btnTop + 5 dp
     *  Increasing the leftMargin dp-offset moves the badge LEFT; decreasing
     *  it moves RIGHT. */

    private fun paintChannelButtonStyles() {
        val enabled = eqViewModel.eqPrefs.getChannelSideEqEnabled()
        val lBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelLButton) ?: return
        val rBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.channelRButton) ?: return
        val density = resources.displayMetrics.density
        fun paint(btn: com.google.android.material.button.MaterialButton, pressed: Boolean) {
            if (pressed) {
                btn.setBackgroundColor(0xFF555555.toInt())
                btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
                btn.strokeWidth = (2 * density).toInt()
                btn.setTextColor(0xFFE3E3E3.toInt())
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                btn.strokeWidth = (1 * density).toInt()
                btn.setTextColor(0xFFBBBBBB.toInt())
            }
        }
        val badge = findViewById<android.widget.TextView>(R.id.altRouteChannelBadge)
        val altRouteBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.altRouteButton)
        if (!enabled) {
            paint(lBtn, false); paint(rBtn, false)
            badge?.visibility = View.GONE
            altRouteBtn?.setIconResource(R.drawable.ic_alt_route_right)
            return
        }
        val active = eqViewModel.activeChannel.value
        paint(lBtn, active == EqStateManager.ActiveChannel.LEFT)
        paint(rBtn, active == EqStateManager.ActiveChannel.RIGHT)
        badge?.let {
            when (active) {
                EqStateManager.ActiveChannel.LEFT -> { it.text = "L"; it.visibility = View.VISIBLE }
                EqStateManager.ActiveChannel.RIGHT -> { it.text = "R"; it.visibility = View.VISIBLE }
                else -> it.visibility = View.GONE
            }
            // Re-measure and re-anchor the badge so a subtle width change
            // between "L" and "R" doesn't shove it past the button edge.
            if (it.visibility == View.VISIBLE) graphOverlayLayoutManager.repositionChannelBadge(it)
        }
        // Swap the split-arrow for the single-branch variant when in L/R mode
        // so the icon reflects the single channel being routed.
        altRouteBtn?.setIconResource(
            if (active == EqStateManager.ActiveChannel.BOTH)
                R.drawable.ic_alt_route_right
            else
                R.drawable.ic_alt_route_right_solid
        )
    }

    /** Visual state for the L / R popout buttons.
     *  - CSE off: both buttons dim (alpha 0.4), outlined style.
     *  - CSE on: both buttons at full alpha; active uses filled "pressed"
     *    style (like the active Spectrum / Band-points buttons), the other
     *    uses the outlined style. */
    private fun refreshChannelPopoutDim() {
        paintChannelButtonStyles()
        val enabled = eqViewModel.eqPrefs.getChannelSideEqEnabled()
        val lBtn = findViewById<View>(R.id.channelLButton) ?: return
        val rBtn = findViewById<View>(R.id.channelRButton) ?: return
        val a = if (enabled) 1.0f else 0.4f
        lBtn.alpha = a; rBtn.alpha = a
    }

    /** Rebind the graph + band toggles + input widgets after the active EQ
     *  reference changes (Channel Side EQ on/off, or L/R mode switch). */
    private fun rebindActiveEq() {
        val eq = eqViewModel.parametricEq.value
        eqGraphView.setParametricEqualizer(eq)
        eqGraphView.updateBandLevels()
        eqViewModel.pushEqUpdate()

        val count = eq.getBandCount()
        if (count > 0) {
            val idx = (eqViewModel.selectedBandIndex.value ?: 0).coerceIn(0, count - 1)
            eqViewModel.selectBand(idx)
            eqGraphView.setActiveBand(idx)
            updateFilterTypeButtons(idx)
            updateBandInputs(idx)
        }
        bandToggleManager.setupToggles()
        refreshChannelPopoutDim()
    }

    override fun onResume() {
        super.onResume()
        // Pick up any limiter changes made in LimiterActivity while we were
        // paused. LimiterActivity writes prefs directly and never touches
        // EqStateManager's mirror fields, so without this sync the next
        // saveState() (onPause) would overwrite the user's prefs with stale
        // in-memory values.
        eqViewModel.setLimiterEnabled(eqViewModel.eqPrefs.getLimiterEnabled())
        eqViewModel.setLimiterAttackMs(eqViewModel.eqPrefs.getLimiterAttack())
        eqViewModel.setLimiterReleaseMs(eqViewModel.eqPrefs.getLimiterRelease())
        eqViewModel.setLimiterRatio(eqViewModel.eqPrefs.getLimiterRatio())
        eqViewModel.setLimiterThresholdDb(eqViewModel.eqPrefs.getLimiterThreshold())
        eqViewModel.setLimiterPostGainDb(eqViewModel.eqPrefs.getLimiterPostGain())
        // Apply spectrum settings (may have changed in SpectrumControlActivity)
        applySpectrumSettings()
        // Sync Channel Side EQ state — the switch lives in ChannelSideEqActivity,
        // so when we return here the pref may have flipped and we need to swap
        // the active ParametricEqualizer accordingly.
        val cseOnNow = eqViewModel.eqPrefs.getChannelSideEqEnabled()
        val cseOnInState = eqViewModel.activeChannel.value == EqStateManager.ActiveChannel.BOTH
        if (cseOnNow != cseOnInState) {
            eqViewModel.setChannelSideEqEnabled(cseOnNow)
            rebindActiveEq()
        } else {
            refreshChannelPopoutDim()
        }
        // Set FAB from saved power state — instant, no animation
        val savedPower = eqViewModel.eqPrefs.getPowerState()
        com.bearinmind.equalizer314.ui.BottomNavHelper.setPowerFabInstant(this, savedPower)
        if (eqViewModel.serviceBound.value && eqViewModel.eqService.value != null) {
            eqViewModel.stateManager.isProcessing = eqViewModel.eqService.value!!.dynamicsManager.isActive
        } else {
            eqViewModel.stateManager.isProcessing = savedPower
        }
        // Restore EQ/Settings page highlight
        val isEqPage = findViewById<View>(R.id.pageEq).visibility == View.VISIBLE
        updateBottomBarHighlight(isEqPage)
        // Restart visualizer if it was enabled (may have been stopped in onPause)
        if (eqViewModel.eqPrefs.getSpectrumEnabled() && !visualizerHelper.isRunning &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
        }
        // Refresh ON/OFF status under nav icons
        com.bearinmind.equalizer314.ui.BottomNavHelper.updateStatus(this, eqViewModel.eqPrefs)
        updateAutoEqStatus()
        updateTargetStatus()

        // Check if Simple EQ was toggled in experimental settings
        val simpleEqEnabled = eqViewModel.eqPrefs.getSimpleEqEnabled()
        if (simpleEqEnabled && eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
            switchEqUiMode(EqUiMode.SIMPLE)
        } else if (!simpleEqEnabled && eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
            val fallback = try { EqUiMode.valueOf(eqViewModel.eqPrefs.getEqUiMode()) } catch (_: Exception) { EqUiMode.PARAMETRIC }
            switchEqUiMode(fallback)
        }
    }

    override fun onPause() {
        super.onPause()
        // Release visualizer so other activities can use session 0
        visualizerHelper.stop()
        eqGraphView.spectrumRenderer = null
        // Save simple EQ gains if in simple mode
        if (eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
        }
        eqViewModel.saveState()
        eqViewModel.eqPrefs.savePresetName(presetDropdown.text.toString())
    }

    override fun onStop() {
        super.onStop()
        // Belt-and-suspenders save: onPause already saves on the way to
        // background, but onStop runs more reliably when the system is
        // about to abruptly tear down the process (low-memory kill,
        // Bluetooth A2DP disconnect cascade). Re-flushing here makes
        // sure any edits made between onPause and onStop also persist.
        if (eqViewModel.currentEqUiMode.value == EqUiMode.SIMPLE) {
            simpleEqController.saveGains()
        }
        eqViewModel.saveState()
        if (eqViewModel.serviceBound.value) {
            try { unbindService(eqViewModel.stateManager.serviceConnection) } catch (_: Exception) {}
            eqViewModel.stateManager.serviceBound = false
        }
    }

    override fun onDestroy() {
        visualizerHelper.stop()
        try { unregisterReceiver(eqStoppedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // Don't retry startProcessing() here — the original call already
            // proceeded with power-on (see startProcessing's notification
            // permission block). Retrying would re-enter the same path and
            // freeze the UI when notifications are OS-disabled.
            if (grantResults.isNotEmpty()
                && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    getString(R.string.msg_notifications_disabled),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            visualizerHelper.start(eqGraphView)
            eqGraphView.spectrumRenderer = visualizerHelper.renderer
            val vizBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.visualizerToggle)
            val d = resources.displayMetrics.density
            vizBtn.alpha = 1.0f
            vizBtn.setBackgroundColor(0xFF555555.toInt())
            vizBtn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            vizBtn.strokeWidth = (2 * d).toInt()
            vizBtn.iconTint = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (eqViewModel.serviceBound.value && eqViewModel.eqService.value != null) {
            eqViewModel.stateManager.isProcessing = eqViewModel.eqService.value!!.dynamicsManager.isActive
        } else {
            eqViewModel.stateManager.isProcessing = false
        }
        // Check if we should show settings page
        if (intent.getBooleanExtra("showSettings", false)) {
            pageEq.visibility = View.GONE
            pageSettings.visibility = View.VISIBLE
            updateBottomBarHighlight(isEqPage = false)
        } else {
            pageEq.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
            updateBottomBarHighlight(isEqPage = true)
        }
    }
}
