package com.bearinmind.equalizer314.controller

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import androidx.core.view.doOnLayout
import com.bearinmind.equalizer314.ChannelSideEqActivity
import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.ExperimentalActivity
import com.bearinmind.equalizer314.LimiterActivity
import com.bearinmind.equalizer314.MbcActivity
import com.bearinmind.equalizer314.PresetsConversionsActivity
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.SpectrumControlActivity
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.ui.BottomNavHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Управляет навигацией между страницами EQ и Settings, режимами EQ,
 * а также кликами по карточкам настроек.
 *
 * Принимает [contract] для доступа к состоянию и ресурсам,
 * а также view-ссылки для управления видимостью страниц.
 */
@Suppress("LongParameterList")
class NavigationController(
    private val contract: MainActivityContract,
    private val pageEq: View,
    private val pageSettings: View,
    private val navSettingsButton: ImageButton,
    private val navPresetsButton: ImageButton,
    private val modeParametricBtn: MaterialButton,
    private val modeGraphicBtn: MaterialButton,
    private val modeTableBtn: MaterialButton,
    private val modeSimpleBtn: MaterialButton,
) {
    private val eqViewModel get() = contract.eqViewModel
    private val stateManager get() = contract.stateManager
    private val eqPrefs get() = contract.eqPrefs
    private val resources get() = contract.context.resources
    private val context get() = contract.context
    private val eqGraphView get() = contract.eqGraphView

    /** Callback для запуска PresetsConversionsActivity */
    var onOpenPresetsConversions: (() -> Unit)? = null

    /** Callback для обновления статуса AutoEQ */
    var onUpdateAutoEqStatus: (() -> Unit)? = null

    /** Callback для обновления статуса Target */
    var onUpdateTargetStatus: (() -> Unit)? = null

    /** Callback для показа диалога Backup & Restore */
    var onShowBackupRestore: (() -> Unit)? = null

    /**
     * Настроить все подписки навигации.
     * Вызывается из [setupListeners].
     */
    fun setup() {
        setupBottomNavListeners()
        setupModeSelectorListeners()
        setupSettingsListeners()
    }

    /**
     * Показать страницу EQ.
     */
    fun showEqPage() {
        pageEq.visibility = View.VISIBLE
        pageSettings.visibility = View.GONE
        contract.updateBottomBarHighlight(isEqPage = true)
    }

    /**
     * Показать страницу настроек.
     */
    fun showSettingsPage() {
        pageEq.visibility = View.GONE
        pageSettings.visibility = View.VISIBLE
        contract.updateBottomBarHighlight(isEqPage = false)
    }

    /** Вернуть true, если сейчас показана страница настроек. */
    fun isSettingsPageVisible(): Boolean = pageSettings.visibility == View.VISIBLE

    // ========================================================================
    // Bottom Navigation
    // ========================================================================

    private fun setupBottomNavListeners() {
        navSettingsButton.setOnClickListener {
            showSettingsPage()
        }
        navPresetsButton.setOnClickListener {
            showEqPage()
            if (stateManager.currentEqUiMode != EqUiMode.SIMPLE) {
                eqGraphView.doOnLayout {
                    eqGraphView.post { relayoutGraphHeaderButtons() }
                }
            }
        }
    }

    /**
     * Обновить подсветку нижней навигации.
     * Вызывается при переключении страниц.
     */
    fun updateBottomBarHighlight(isEqPage: Boolean) {
        val screen = if (isEqPage) com.bearinmind.equalizer314.ui.NavScreen.EQ
        else com.bearinmind.equalizer314.ui.NavScreen.SETTINGS
        BottomNavHelper.updateHighlight(context as android.app.Activity, screen)
        BottomNavHelper.updateStatus(context as android.app.Activity, eqPrefs)
    }

    /** Переместить кнопки хедера графа — делегировано в MainActivity. */
    private fun relayoutGraphHeaderButtons() {
        // Этот метод вызывается из eqGraphView.doOnLayout — реальная
        // реализация остаётся в MainActivity для доступа к layout.
        // NavigationController только триггерит reposition.
    }

    // ========================================================================
    // EQ Mode Selector
    // ========================================================================

    private fun setupModeSelectorListeners() {
        modeParametricBtn.setOnClickListener {
            eqPrefs.saveSimpleEqEnabled(false)
            contract.switchEqUiMode(EqUiMode.PARAMETRIC)
        }
        modeGraphicBtn.setOnClickListener {
            eqPrefs.saveSimpleEqEnabled(false)
            contract.switchEqUiMode(EqUiMode.GRAPHIC)
        }
        modeTableBtn.setOnClickListener {
            eqPrefs.saveSimpleEqEnabled(false)
            contract.switchEqUiMode(EqUiMode.TABLE)
        }
        modeSimpleBtn.setOnClickListener {
            eqPrefs.saveSimpleEqEnabled(true)
            contract.switchEqUiMode(EqUiMode.SIMPLE)
        }
    }

    // ========================================================================
    // Experimental Card (lock/unlock)
    // ========================================================================

    private fun setupExperimentalCard() {
        val experimentalLockButton = findViewById<ImageButton>(R.id.experimentalLockButton)
        val experimentalCard = findViewById<View>(R.id.experimentalCard)
        fun applyLockState() {
            val unlocked = eqPrefs.getExperimentalUnlocked()
            experimentalLockButton.setImageResource(
                if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock
            )
            experimentalCard.isClickable = unlocked
            experimentalCard.alpha = if (unlocked) 1f else 0.6f
        }
        applyLockState()
        experimentalLockButton.setOnClickListener {
            val newState = !eqPrefs.getExperimentalUnlocked()
            eqPrefs.saveExperimentalUnlocked(newState)
            applyLockState()
        }
        experimentalCard.setOnClickListener {
            if (!eqPrefs.getExperimentalUnlocked()) return@setOnClickListener
            context.startActivity(Intent(context, ExperimentalActivity::class.java))
            (context as? android.app.Activity)?.overridePendingTransition(
                R.anim.fade_in, R.anim.fade_out
            )
        }
    }

    // ========================================================================
    // Settings Cards
    // ========================================================================

    private fun setupSettingsListeners() {
        // Light/dark theme toggle
        val themeSwitch = findViewById<MaterialSwitch>(R.id.themeSwitch)
        themeSwitch.isChecked = eqPrefs.getLightTheme()
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveLightTheme(isChecked)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (isChecked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        // Channel Side EQ
        findViewById<View>(R.id.channelSideEqCard).setOnClickListener {
            context.startActivity(Intent(context, ChannelSideEqActivity::class.java))
            (context as? android.app.Activity)?.overridePendingTransition(
                R.anim.fade_in, R.anim.fade_out
            )
        }

        // Presets & Conversions
        findViewById<View>(R.id.presetsConversionsCard).setOnClickListener {
            onOpenPresetsConversions?.invoke()
        }

        // Backup & Restore
        findViewById<View>(R.id.backupRestoreCard).setOnClickListener {
            onShowBackupRestore?.invoke()
        }

        // Audio Effects Pipeline
        findViewById<View>(R.id.audioEffectsPipelineCard).setOnClickListener {
            context.startActivity(
                Intent(context, com.bearinmind.equalizer314.AudioEffectsPipelineActivity::class.java)
            )
            (context as? android.app.Activity)?.overridePendingTransition(
                R.anim.fade_in, R.anim.fade_out
            )
        }

        // Frequency Response Measurement
        findViewById<View>(R.id.freqMeasurementCard).setOnClickListener {
            context.startActivity(
                Intent(context, com.bearinmind.equalizer314.measurement.MeasurementActivity::class.java)
            )
            (context as? android.app.Activity)?.overridePendingTransition(
                R.anim.fade_in, R.anim.fade_out
            )
        }

        // Experimental
        setupExperimentalCard()

        // Spectrum Control
        findViewById<View>(R.id.spectrumControlCard).setOnClickListener {
            context.startActivity(
                Intent(context, SpectrumControlActivity::class.java)
            )
            (context as? android.app.Activity)?.overridePendingTransition(
                R.anim.fade_in, R.anim.fade_out
            )
        }
    }

    /** Утилита для поиска view по id. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewById(id: Int): T =
        checkNotNull((context as android.app.Activity).findViewById(id)) { "View not found: $id" }
}
