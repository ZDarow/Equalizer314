package com.bearinmind.equalizer314.controller

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bearinmind.equalizer314.BackupManager
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.autoeq.AutoEqParser
import com.bearinmind.equalizer314.autoeq.apoTokenToFilterType
import com.bearinmind.equalizer314.dsp.EqSerializer
import com.bearinmind.equalizer314.state.EqStateManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * Управляет импортом/экспортом пресетов и резервным копированием.
 *
 * Принимает [contract] для доступа к состоянию и коллбэки для
 * запуска ActivityResultLauncher'ов, которые должны быть созданы
 * в Activity (registerForActivityResult).
 */
@Suppress("UnusedPrivateProperty", "LongMethod", "NestedBlockDepth") // presetManager и onImportApo сохранены для будущего использования
class PresetIoController(
    private val contract: MainActivityContract,
    @Suppress("UnusedPrivateProperty")
    private val presetManager: com.bearinmind.equalizer314.state.PresetManager,
    private val onExportPreset: (text: String, fileName: String) -> Unit,
    private val onExportBackup: () -> Unit,
    private val onImportBackup: (String) -> Unit,
    @Suppress("UnusedPrivateProperty")
    private val onImportApo: () -> Unit,
) {
    private val eqViewModel get() = contract.eqViewModel
    private val stateManager get() = contract.stateManager
    private val eqPrefs get() = contract.eqPrefs
    private val context get() = contract.context
    private val resources get() = contract.context.resources
    private val isLightUi get() = contract.isLightUi

    /** Текст для экспорта, сохраняемый между кликом и результатом */
    var pendingExportText: String? = null

    /**
     * Обработать результат экспорта пресета (ActivityResult OK).
     */
    fun handlePresetExportResult(uri: Uri?) {
        if (uri == null) return
        val text = pendingExportText ?: return
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(text)
            }
            Toast.makeText(
                context,
                context.getString(R.string.msg_exported_success),
                Toast.LENGTH_SHORT
            ).show()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.msg_export_failed, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingExportText = null
    }

    /**
     * Запустить экспорт пресета (создание файла).
     */
    fun launchPresetExport(text: String, fileName: String) {
        pendingExportText = text
        onExportPreset(text, fileName)
    }

    /**
     * Обработать результат экспорта бэкапа.
     */
    fun handleBackupExportResult(uri: Uri?) {
        if (uri == null) return
        try {
            val json = BackupManager.exportAll(context)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(json)
            }
            Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Toast.makeText(
                context,
                "Backup failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Обработать результат импорта бэкапа.
     */
    fun handleBackupImportResult(uri: Uri?) {
        if (uri == null) return
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText()
            if (text != null && BackupManager.importAll(context, text)) {
                Toast.makeText(context, "Backup restored", Toast.LENGTH_SHORT).show()
                val light = context.getSharedPreferences("eq_settings", Activity.MODE_PRIVATE)
                    .getBoolean("lightTheme", false)
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (light) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                )
                (context as? Activity)?.recreate()
            } else {
                Toast.makeText(
                    context,
                    "Not a valid Equalizer314 backup",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Toast.makeText(
                context,
                "Restore failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Обработать результат импорта APO (EqualizerAPO).
     */
    @Suppress("ReturnCount")
    fun handleApoImportResult(uri: Uri?) {
        if (uri == null) return
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return
            val profile = AutoEqParser.parse(text)
            if (profile == null || profile.filters.isEmpty()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.msg_parse_apo_failed),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            fun toBandSpecs(filters: List<com.bearinmind.equalizer314.autoeq.AutoEqFilter>):
                    List<EqStateManager.BandSpec> =
                filters.map {
                    EqStateManager.BandSpec(
                        frequency = it.frequency,
                        gain = it.gain,
                        q = it.q.toDouble(),
                        filterType = apoTokenToFilterType(it.filterType),
                    )
                }

            eqPrefs.savePreampGain(profile.preampDb)
            eqPrefs.savePresetName(context.getString(R.string.msg_apo_import))
            eqPrefs.saveAutoEqName("")
            eqPrefs.saveAutoEqSource("")

            if (profile.perChannel) {
                val leftSpecs = toBandSpecs(profile.leftFilters)
                val rightSpecs = toBandSpecs(profile.rightFilters)
                val bothSpecs = toBandSpecs(profile.filters)
                eqViewModel.applyPresetEqs(
                    cseEnabled = true,
                    bothBands = bothSpecs,
                    leftBands = leftSpecs,
                    rightBands = rightSpecs,
                )
                eqPrefs.saveState(
                    eqViewModel.parametricEq.value,
                    (0 until eqViewModel.parametricEq.value.getBandCount()).toList()
                )
                eqViewModel.persistLeftRightIfCse()
                if (eqViewModel.isProcessing.value) {
                    val (lEq, rEq) = eqViewModel.getChannelEqs()
                    eqViewModel.eqService.value?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.run {
                            requestedBandCount = eqPrefs.getDpBandCount()
                            start(eqViewModel.parametricEq.value)
                        }
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                contract.eqGraphView.setParametricEqualizer(eqViewModel.parametricEq.value)
                eqViewModel.initBandSlots()
                contract.bandToggleManager.setupToggles()
                Toast.makeText(
                    context,
                    "Applied L:${profile.leftFilters.size} R:${profile.rightFilters.size} filters",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                eqViewModel.selectBand(0)
                val specs = toBandSpecs(profile.filters)
                eqViewModel.applyPresetEqs(
                    cseEnabled = false,
                    bothBands = specs,
                    leftBands = specs,
                    rightBands = specs,
                )
                eqPrefs.saveState(
                    eqViewModel.parametricEq.value,
                    (0 until eqViewModel.parametricEq.value.getBandCount()).toList()
                )
                eqViewModel.initBandSlots()
                contract.bandToggleManager.setupToggles()
                if (eqViewModel.isProcessing.value) {
                    val (lEq, rEq) = eqViewModel.getChannelEqs()
                    eqViewModel.eqService.value?.let { svc ->
                        svc.dynamicsManager.stop()
                        svc.dynamicsManager.run {
                            requestedBandCount = eqPrefs.getDpBandCount()
                            start(eqViewModel.parametricEq.value)
                        }
                        svc.updateEqPerChannel(lEq, rEq)
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.msg_applied_filters, profile.filters.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.msg_error, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Показать диалог Backup & Restore.
     */
    fun showBackupRestoreDialog() {
        val density = resources.displayMetrics.density
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(), (20 * density).toInt(),
                (24 * density).toInt(), (16 * density).toInt()
            )
        }
        val title = TextView(context).apply {
            text = "Backup & Restore"
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 20f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val msg = TextView(context).apply {
            text = "Export or import settings, presets & bindings to a .json ; " +
                "importing will override all current settings in the app"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { bottomMargin = (12 * density).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun outlinedBtn(label: String, textColor: Int) =
            MaterialButton(
                context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginEnd = (3 * density).toInt()
                    marginStart = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt()
                setTextColor(textColor)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000)
                insetTop = 0
                insetBottom = 0
            }
        val importBtn = outlinedBtn("Import", 0xFFDDDDDD.toInt())
        val exportBtn = outlinedBtn("Export", 0xFFDDDDDD.toInt())
        btnRow.addView(importBtn)
        btnRow.addView(exportBtn)
        dialogView.addView(title)
        dialogView.addView(msg)
        dialogView.addView(divider)
        dialogView.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(
            context, R.style.Theme_Equalizer314_Dialog
        ).setView(dialogView).create()
        exportBtn.setOnClickListener {
            dialog.dismiss()
            onExportBackup()
        }
        importBtn.setOnClickListener {
            dialog.dismiss()
            onImportBackup("application/json")
        }
        dialog.show()
    }

    /**
     * Сериализовать текущее состояние EQ в preset JSON.
     */
    fun buildCurrentPresetJson(): String {
        stateManager.eqPrefs.saveState(stateManager.parametricEq)
        stateManager.persistLeftRightIfCse()
        fun serialize(eq: com.bearinmind.equalizer314.dsp.ParametricEqualizer): org.json.JSONArray {
            val arr = org.json.JSONArray()
            for (b in eq.getAllBands()) {
                arr.put(JSONObject().apply {
                    put("frequency", b.frequency)
                    put("gain", b.gain)
                    put("q", b.q)
                    put("filterType", b.filterType.name)
                    put("enabled", b.enabled)
                })
            }
            return arr
        }
        val json = JSONObject()
        json.put("preamp", stateManager.preampGainDb)
        val cseOn = eqPrefs.getChannelSideEqEnabled()
        json.put("channelSideEqEnabled", cseOn)
        if (cseOn) {
            val (lEq, rEq) = stateManager.getChannelEqs()
            json.put("leftBands", serialize(lEq))
            json.put("rightBands", serialize(rEq))
            json.put("bands", serialize(stateManager.parametricEq))
        } else {
            json.put("bands", serialize(stateManager.parametricEq))
        }
        return json.toString()
    }
}
