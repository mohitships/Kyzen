package com.binarybrigade.kyzen

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ModeSelectionActivity — Dual-Mode Entry Point (Phase 3)
 *
 * Shown on every app launch after permissions are granted.
 * Routes the user to either Child Dashboard or Parent PIN entry.
 * Also ensures UsageMonitorService is running — critical since this
 * is the screen users spend most time on after the permission setup.
 */
class ModeSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        // Ensure the background monitoring service is running.
        // Called here as a safety net — service may not have started if user
        // navigated directly without going through MainActivity's refreshUI().
        ContextCompat.startForegroundService(
            this, Intent(this, UsageMonitorService::class.java)
        )

        // Award the one-time welcome bonus here — mode-agnostic.
        // Fires as soon as the user reaches the profile selection screen,
        // regardless of whether they choose Child or Parent mode first.
        // checkAndAwardFirstLaunchBonus() is idempotent — safe to call every launch.
        val isFirstLaunch = !getSharedPreferences("kyzen_prefs", MODE_PRIVATE)
            .contains("first_launch_done")
        val prefs = KyzenPreferences(this)
        prefs.checkAndAwardFirstLaunchBonus()
        if (isFirstLaunch) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        GemTransactionRepository(applicationContext).logTransaction(
                            GemTransactionRepository.TYPE_FIRST_LAUNCH,
                            +KyzenPreferences.FIRST_LAUNCH_BONUS,
                            "Welcome to Kyzen"
                        )
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        val btnChildMode  = findViewById<Button>(R.id.btnChildMode)
        val btnParentMode = findViewById<Button>(R.id.btnParentMode)

        btnChildMode.setOnClickListener {
            startActivity(Intent(this, ChildDashboardActivity::class.java))
        }

        btnParentMode.setOnClickListener {
            startActivity(Intent(this, PinEntryActivity::class.java))
        }
    }
}
