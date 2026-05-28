package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.state.EqPreferencesManager

/** Restores the global DynamicsProcessing engine after a device reboot
 *  if the user had it on before powering off. BOOT_COMPLETED receivers
 *  are explicitly exempted from background-start restrictions on API
 *  31+, so the foreground-service start is legal here. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = EqPreferencesManager(context)
        if (!prefs.getPowerState()) return
        Log.d(TAG, "BOOT_COMPLETED — persisted powerOn=true, starting EqService")
        val svc = Intent(context, EqService::class.java)
            .setAction(EqService.ACTION_AUTO_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
