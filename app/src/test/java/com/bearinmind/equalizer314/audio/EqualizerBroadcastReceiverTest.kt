package com.bearinmind.equalizer314.audio

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bearinmind.equalizer314.audio.EqService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тест [EqualizerBroadcastReceiver].
 *
 * Проверяет, что receiver корректно диспатчит события по action
 * в соответствующие коллбэки, и не вызывает лишних.
 */
@RunWith(RobolectricTestRunner::class)
class EqualizerBroadcastReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `onEqStarted dispatches to onEqStarted callback`() {
        var started = false
        var stopped = false
        var refreshed = false

        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = { started = true },
            onEqStopped = { stopped = true },
            onRefresh = { refreshed = true },
        )

        receiver.onReceive(context, Intent(EqService.ACTION_EQ_STARTED))

        assertTrue("onEqStarted должен быть вызван", started)
        assertFalse("onEqStopped не должен быть вызван", stopped)
        assertFalse("onRefresh не должен быть вызван", refreshed)
    }

    @Test
    fun `onEqStopped dispatches to onEqStopped callback`() {
        var started = false
        var stopped = false
        var refreshed = false

        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = { started = true },
            onEqStopped = { stopped = true },
            onRefresh = { refreshed = true },
        )

        receiver.onReceive(context, Intent(EqService.ACTION_EQ_STOPPED))

        assertFalse("onEqStarted не должен быть вызван", started)
        assertTrue("onEqStopped должен быть вызван", stopped)
        assertFalse("onRefresh не должен быть вызван", refreshed)
    }

    @Test
    fun `unknown action dispatches to onRefresh callback`() {
        var started = false
        var stopped = false
        var refreshed = false

        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = { started = true },
            onEqStopped = { stopped = true },
            onRefresh = { refreshed = true },
        )

        receiver.onReceive(context, Intent("com.example.UNKNOWN_ACTION"))

        assertFalse("onEqStarted не должен быть вызван", started)
        assertFalse("onEqStopped не должен быть вызван", stopped)
        assertTrue("onRefresh должен быть вызван", refreshed)
    }

    @Test
    fun `route preset applied dispatches to onRefresh`() {
        var refreshed = false

        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = {},
            onEqStopped = {},
            onRefresh = { refreshed = true },
        )

        receiver.onReceive(context, Intent(RouteSwitchCoordinator.ACTION_ROUTE_PRESET_APPLIED))

        assertTrue("onRefresh должен быть вызван для ACTION_ROUTE_PRESET_APPLIED", refreshed)
    }

    @Test
    fun `null action falls to else and triggers onRefresh`() {
        var refreshed = false

        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = {},
            onEqStopped = {},
            onRefresh = { refreshed = true },
        )

        // Не должен кидать исключение — null action не совпадает ни с одним
        // известным action, поэтому попадает в else → onRefresh.
        receiver.onReceive(context, Intent().apply { action = null })

        assertTrue("onRefresh должен быть вызван при null action (else-ветка)", refreshed)
    }

    @Test
    fun `register and unregister do not throw`() {
        val receiver = EqualizerBroadcastReceiver(
            onEqStarted = {},
            onEqStopped = {},
            onRefresh = {},
        )

        // Двойная регистрация и отмена — не должны кидать
        receiver.register(context)
        receiver.unregister(context)
        receiver.unregister(context)  // второй unregister — no-op
    }
}
