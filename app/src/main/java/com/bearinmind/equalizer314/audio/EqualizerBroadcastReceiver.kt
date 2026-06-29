package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.bearinmind.equalizer314.audio.EqService

/**
 * Вынесенный BroadcastReceiver для событий EQ.
 *
 * Убирает 3 анонимных ресивера из [MainActivity], позволяя их переиспользовать
 * в unit-тестах через mock-коллбэки. Живёт столько же, сколько Activity.
 *
 * Использование:
 * ```
 * val receiver = EqualizerBroadcastReceiver(
 *     onEqStarted = { /* перерисовать UI */ },
 *     onEqStopped = { /* сбросить состояние */ },
 *     onRefresh = { /* обновить статус */ }
 * )
 * receiver.register(activity)
 * // ...
 * receiver.unregister(activity)
 * ```
 */
class EqualizerBroadcastReceiver(
    private val onEqStarted: () -> Unit,
    private val onEqStopped: () -> Unit,
    private val onRefresh: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            EqService.ACTION_EQ_STARTED -> onEqStarted()
            EqService.ACTION_EQ_STOPPED -> onEqStopped()
            else -> onRefresh()
        }
    }

    companion object {
        /** IntentFilter для всех трёх событий. */
        val INTENT_FILTER: IntentFilter
            get() = IntentFilter().apply {
                addAction(EqService.ACTION_EQ_STARTED)
                addAction(EqService.ACTION_EQ_STOPPED)
                addAction(RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED)
                addAction(EqService.ACTION_NOTIFICATION_REFRESH)
            }

        /** Флаг экспорта для Tiramisu+ (RECEIVER_NOT_EXPORTED — только свои broadcast). */
        private val FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.content.Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
    }

    /** Зарегистрировать ресивер в [context]. */
    fun register(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, INTENT_FILTER, FLAGS)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(this, INTENT_FILTER)
        }
    }

    /** Отменить регистрацию. */
    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
    }
}
