package com.binarybrigade.kyzen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * BootReceiver — Service Auto-Restart on Device Reboot & Screen-On (Phase 3)
 *
 * A BroadcastReceiver that listens for two critical system events:
 *
 * 1. BOOT_COMPLETED — Fired when the device finishes booting.
 *    Without this, the child could bypass monitoring simply by rebooting the phone.
 *    With this, UsageMonitorService restarts automatically after every reboot.
 *
 * 2. SCREEN_ON — Fired when the screen wakes from sleep.
 *    This is the dual-layer survival strategy (Gap #1 fix):
 *    Even if Android kills the ForegroundService during Doze mode,
 *    it will be immediately restarted the moment the screen turns back on.
 *    For demo scenarios, this guarantees the service is always alive when visible.
 *
 * Registered in AndroidManifest.xml with:
 *   - RECEIVE_BOOT_COMPLETED permission
 *   - exported="false" (cannot be triggered by external apps)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
            // USER_PRESENT fires after the user dismisses the lock screen — more
            // reliable than SCREEN_ON alone on OxygenOS where the service may be
            // killed during Doze. Must be handled here to match the manifest filter.
            Intent.ACTION_USER_PRESENT -> {
                // Start (or restart) the monitoring service as a foreground service
                val serviceIntent = Intent(context, UsageMonitorService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
