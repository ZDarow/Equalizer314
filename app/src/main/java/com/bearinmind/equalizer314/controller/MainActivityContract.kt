package com.bearinmind.equalizer314.controller

import com.bearinmind.equalizer314.EqUiMode
import com.bearinmind.equalizer314.audio.VisualizerHelper
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager
import com.bearinmind.equalizer314.state.EqViewModel
import com.bearinmind.equalizer314.state.PresetManager
import com.bearinmind.equalizer314.state.UndoRedoManager
import com.bearinmind.equalizer314.ui.BandToggleManager
import com.bearinmind.equalizer314.ui.EqGraphView

/**
 * Контракт для взаимодействия контроллеров с MainActivity.
 *
 * Предоставляет минимальный доступ к общему состоянию, ресурсам и коллбэкам,
 * не привязывая контроллер к конкретной реализации Activity.
 */
interface MainActivityContract {
    val eqViewModel: EqViewModel
    val stateManager: EqStateManager
    val eqPrefs: EqPreferencesManager
    val bandToggleManager: BandToggleManager
    val presetManager: PresetManager
    val undoRedoManager: UndoRedoManager
    val context: android.content.Context
    val eqGraphView: EqGraphView
    val visualizerHelper: VisualizerHelper

    val isLightUi: Boolean
        get() = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun getString(id: Int): String
    fun getColor(id: Int): Int

    /** Обновить подсветку нижней навигации */
    fun updateBottomBarHighlight(isEqPage: Boolean)

    /** Запустить/остановить обработку (анимация FAB) */
    fun animatePowerFab(on: Boolean)

    /** Переключить режим EQ */
    fun switchEqUiMode(mode: EqUiMode)

    /** Обновить метки L/R кнопок */
    fun refreshChannelPopoutDim()

    /** Проверить, инициализирован ли presetManager */
    fun isPresetManagerInitialized(): Boolean
}
