package com.binarybrigade.kyzen

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var layoutSetup: LinearLayout
    private lateinit var btnGrantUsage: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnAllowRestricted: Button
    private lateinit var txtRestrictedAssurance: TextView
    private lateinit var txtRestrictedHint: TextView

    // Tracks how many times the user has returned from Settings without granting
    // a permission — used to detect greyed-out (restricted) toggles on Android 13+.
    private var usageSettingsReturnCount   = 0
    private var overlaySettingsReturnCount = 0

    // Flags set to true when user taps a permission button and navigates to Settings.
    // Only increment counters when these flags are true — prevents false increments
    // on unrelated resumes (e.g. returning from another activity, screen on/off).
    private var wentToUsageSettings   = false
    private var wentToOverlaySettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force light mode app-wide — all UI colours designed for light mode only.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        setContentView(R.layout.activity_main)

        layoutSetup            = findViewById(R.id.layoutSetup)
        btnGrantUsage          = findViewById(R.id.btnGrantUsage)
        btnGrantOverlay        = findViewById(R.id.btnGrantOverlay)
        btnAllowRestricted     = findViewById(R.id.btnAllowRestricted)
        txtRestrictedAssurance = findViewById(R.id.txtRestrictedAssurance)
        txtRestrictedHint      = findViewById(R.id.txtRestrictedHint)

        // Button 1 — Usage Access
        btnGrantUsage.setOnClickListener {
            if (!hasUsageStatsPermission()) {
                wentToUsageSettings = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "Find Kyzen in the list and turn it on.", Toast.LENGTH_LONG).show()
            }
        }

        // Button 2 — Display Over Apps
        btnGrantOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                wentToOverlaySettings = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "Turn on Allow display over other apps.", Toast.LENGTH_LONG).show()
            }
        }

        // Button 3 — Allow Restricted Settings (Android 13+ only)
        // Opens App Info directly — user taps 3-dot menu > Allow restricted settings
        btnAllowRestricted.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Only update counters when the user actually went to Settings via our buttons.
        if (wentToUsageSettings) {
            wentToUsageSettings = false
            if (hasUsageStatsPermission()) usageSettingsReturnCount = 0
            else usageSettingsReturnCount++
        }

        if (wentToOverlaySettings) {
            wentToOverlaySettings = false
            if (hasOverlayPermission()) overlaySettingsReturnCount = 0
            else overlaySettingsReturnCount++
        }

        refreshUI()
    }

    private fun refreshUI() {
        if (hasUsageStatsPermission() && hasOverlayPermission()) {
            // Both permissions granted — start service, go to ModeSelection, then
            // FINISH this activity so it is removed from the back stack entirely.
            // This means pressing Back from ModeSelection exits the app cleanly
            // instead of returning to this permission screen.
            startMonitoringService()
            startActivity(
                Intent(this, ModeSelectionActivity::class.java).apply {
                    // Clear back stack so ModeSelection is the new root
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        } else {
            updateButtonStates()
            updateRestrictedSettingsButton()
        }
    }

    /**
     * Shows the "Allow Restricted Settings" block on Android 13+ ONLY when ALL
     * three conditions are simultaneously true:
     * 1. Device is Android 13+
     * 2. At least one permission is still denied
     * 3. User has visited Settings more than once for a denied permission without granting
     */
    private fun updateRestrictedSettingsButton() {
        val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val usageBlockedByRestriction   = !hasUsageStatsPermission() && usageSettingsReturnCount > 1
        val overlayBlockedByRestriction = !hasOverlayPermission()    && overlaySettingsReturnCount > 1
        val restrictedLikelyBlocking    = usageBlockedByRestriction || overlayBlockedByRestriction

        if (isAndroid13Plus && restrictedLikelyBlocking) {
            txtRestrictedAssurance.visibility = View.VISIBLE
            btnAllowRestricted.visibility     = View.VISIBLE
            txtRestrictedHint.visibility      = View.VISIBLE
        } else {
            txtRestrictedAssurance.visibility = View.GONE
            btnAllowRestricted.visibility     = View.GONE
            txtRestrictedHint.visibility      = View.GONE
        }
    }

    private fun startMonitoringService() {
        ContextCompat.startForegroundService(this, Intent(this, UsageMonitorService::class.java))
    }

    private fun updateButtonStates() {
        val restrictedVisible = btnAllowRestricted.visibility == View.VISIBLE
        val usagePrefix   = if (restrictedVisible) "2." else "1."
        val overlayPrefix = if (restrictedVisible) "3." else "2."

        if (hasUsageStatsPermission()) {
            btnGrantUsage.text      = "$usagePrefix Usage Access — Granted"
            btnGrantUsage.isEnabled = false
        } else {
            btnGrantUsage.text      = "$usagePrefix Authorize Usage Access"
            btnGrantUsage.isEnabled = true
        }

        if (hasOverlayPermission()) {
            btnGrantOverlay.text      = "$overlayPrefix Display Access — Granted"
            btnGrantOverlay.isEnabled = false
        } else {
            btnGrantOverlay.text      = "$overlayPrefix Authorize Display Access"
            btnGrantOverlay.isEnabled = true
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }
}
