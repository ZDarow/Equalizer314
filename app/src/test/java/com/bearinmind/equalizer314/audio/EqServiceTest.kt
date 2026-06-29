package com.bearinmind.equalizer314.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import com.bearinmind.equalizer314.audio.EqService.Companion.ACTION_STOP
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Тесты lifecycle [EqService] с использованием Robolectric.
 *
 * В [EqService] инжектится [FakeDpm] через [EqService.setDynamicsManagerForTest],
 * чтобы изолировать тесты от реального Android DSP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class EqServiceTest {

    /**
     * Создаёт сервис с [FakeDpm], инжектит его ДО [EqService.onCreate],
     * вызывает create(), destroy() для теста onDestroy.
     * Возвращает сервис.
     */
    private fun createAndStartService(): EqService {
        val controller = Robolectric.buildService(EqService::class.java)
        val service = controller.get()
        service.setDynamicsManagerForTest(FakeDpm())
        service.persistEmptyEq()
        controller.create()
        return service
    }

    /** Создаёт + destroy сервис (для теста onDestroy). */
    private fun createAndDestroyService(): EqService {
        val controller = Robolectric.buildService(EqService::class.java)
        val service = controller.get()
        service.setDynamicsManagerForTest(FakeDpm())
        service.persistEmptyEq()
        controller.create()
        controller.destroy()
        return service
    }

    /** Dispatch intent to the service's onStartCommand. */
    private fun dispatch(service: EqService, action: String) {
        val intent = Intent(service, EqService::class.java).apply { this.action = action }
        service.onStartCommand(intent, 0, 0)
    }

    /** Вспомогательный getter: кастит dynamicsManager к FakeDpm */
    private val EqService.fakeDpm: FakeDpm get() = dynamicsManager as FakeDpm

    /** Сохраняет пустой EQ в SharedPreferences, чтобы loadPersistedParametricEq() не вернул null */
    private fun EqService.persistEmptyEq() {
        val prefs = getSharedPreferences("eq_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("bands", "[]").apply()
    }

    // ── Lifecycle ───────────────────────────────────────────────

    @Test
    fun `onCreate creates notification channel`() {
        val service = createAndStartService()

        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = nm.getNotificationChannel("eq_service_channel")

        assertNotNull("Notification channel должен быть создан", channel)
        assertEquals("System EQ", channel.name.toString())
    }

    @Test
    fun `onBind returns EqBinder`() {
        val service = createAndStartService()

        val binder = service.onBind(null)
        assertNotNull("Binder должен быть не null", binder)
        assertTrue("Binder должен быть EqBinder", binder is EqService.EqBinder)
    }

    // ── ACTION_STOP ─────────────────────────────────────────────

    @Test
    fun `ACTION_STOP stops dynamicsManager`() {
        val service = createAndStartService()
        dispatch(service, EqService.ACTION_START_FROM_TILE)  // сначала включим
        dispatch(service, ACTION_STOP)

        assertFalse("FakeDpm должен быть остановлен после ACTION_STOP", service.fakeDpm.isActive)
    }

    @Test
    fun `ACTION_STOP sends ACTION_EQ_STOPPED broadcast`() {
        val service = createAndStartService()
        dispatch(service, ACTION_STOP)

        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        val broadcastIntent = shadowApp.broadcastIntents.find {
            it.action == EqService.ACTION_EQ_STOPPED
        }
        assertNotNull("Должен быть отправлен broadcast ACTION_EQ_STOPPED", broadcastIntent)
    }

    @Test
    fun `ACTION_STOP persists power state as false`() {
        val service = createAndStartService()
        dispatch(service, ACTION_STOP)

        val prefs = EqPreferencesManager(service)
        assertFalse("Power state должен быть false после STOP", prefs.getPowerState())
    }

    // ── ACTION_START_FROM_TILE ──────────────────────────────────

    @Test
    fun `ACTION_START_FROM_TILE starts dynamicsManager when inactive`() {
        val service = createAndStartService()
        dispatch(service, EqService.ACTION_START_FROM_TILE)

        assertTrue("FakeDpm должен быть активен после START_FROM_TILE", service.fakeDpm.isActive)
    }

    @Test
    fun `ACTION_START_FROM_TILE toggles dynamicsManager off when already active`() {
        val service = createAndStartService()

        // First call — turn ON
        dispatch(service, EqService.ACTION_START_FROM_TILE)
        assertTrue("FakeDpm активен после первого тика", service.fakeDpm.isActive)

        // Second call — toggle OFF
        dispatch(service, EqService.ACTION_START_FROM_TILE)
        assertFalse("FakeDpm остановлен после второго тика", service.fakeDpm.isActive)
    }

    // ── startEq ─────────────────────────────────────────────────

    @Test
    fun `startEq delegates to dynamicsManager`() {
        val service = createAndStartService()
        val dpm = service.dynamicsManager as FakeDpm
        val eq = ParametricEqualizer(4)

        println("DEBUG: before startEq - isActive=${dpm.isActive} sdk=${Build.VERSION.SDK_INT}")
        val result = service.startEq(eq)
        println("DEBUG: after startEq - result=$result isActive=${dpm.isActive} lastStartedEq=${dpm.lastStartedEq}")

        assertTrue("startEq должен вернуть true (result=$result isActive=${dpm.isActive})", result)
        assertTrue("FakeDpm должен быть активен", service.fakeDpm.isActive)
        assertNotNull("FakeDpm.lastStartedEq не должен быть null", service.fakeDpm.lastStartedEq)
    }

    // ── onDestroy ───────────────────────────────────────────────

    @Test
    fun `onDestroy stops dynamicsManager and resets dpRunning`() {
        val service = createAndDestroyService()

        assertFalse("FakeDpm должен быть остановлен после onDestroy", service.fakeDpm.isActive)
        assertFalse("isDpRunning должен быть false после destroy", EqService.isDpRunning)
    }

    // ── Notification ────────────────────────────────────────────

    @Test
    fun `startEq shows foreground notification`() {
        val service = createAndStartService()
        dispatch(service, EqService.ACTION_START_FROM_TILE)

        val notificationManager = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )
        val notifications = notificationManager.allNotifications
        val eqNotification = notifications.find {
            it.getChannelId() == "eq_service_channel"
        }
        assertNotNull(
            "Уведомление с каналом eq_service_channel должно существовать",
            eqNotification
        )
    }

    // ── updateEq ────────────────────────────────────────────────

    @Test
    fun `updateEq delegates to dynamicsManager`() {
        val service = createAndStartService()

        val eq1 = ParametricEqualizer(4)
        service.startEq(eq1)

        val eq2 = ParametricEqualizer(4)
        eq2.updateBand(0, 100f, 3f, BiquadFilter.FilterType.BELL)
        service.updateEq(eq2)

        assertEquals("lastStartedEq должен обновиться", eq2, service.fakeDpm.lastStartedEq)
    }
}
