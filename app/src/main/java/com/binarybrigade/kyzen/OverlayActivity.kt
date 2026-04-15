package com.binarybrigade.kyzen

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * OverlayActivity — SDT-Aligned Coaching Intervention Screen (Phase 3 Upgraded)
 *
 * Phase 3 additions:
 * - Receives trigger context (app name, trigger reason) from UsageMonitorService
 * - Displays personalised coaching message showing which app triggered the break
 * - Shows a contextual sub-message (credits exhausted vs. game pause active)
 * - Back button interception preserved from Phase 2
 */
class OverlayActivity : AppCompatActivity() {

    companion object {
        // Intent extras sent by UsageMonitorService when firing the intervention
        const val EXTRA_TRIGGER_APP_NAME = "extra_trigger_app_name"
        const val EXTRA_TRIGGER_PACKAGE  = "extra_trigger_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay)

        // Ensure this activity consumes all touches.
        // By default, full-screen activities consume touches unless FLAG_NOT_TOUCH_MODAL is set.
        // We previously set it by mistake. Here we clear any rogue flags to ensure a solid shield.
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        val btnReturnHome   = findViewById<Button>(R.id.btnReturnHome)
        val txtOverlayTitle = findViewById<TextView>(R.id.txtOverlayTitle)
        val txtOverlayMsg   = findViewById<TextView>(R.id.txtOverlayMessage)

        // Read trigger context passed by UsageMonitorService (or manual trigger)
        val triggerAppName = intent.getStringExtra(EXTRA_TRIGGER_APP_NAME)

        // Personalise the coaching message based on trigger context
        if (!triggerAppName.isNullOrEmpty()) {
            txtOverlayTitle.text = "Time for a Mindful Break"
            txtOverlayMsg.text =
                "You've been spending time on $triggerAppName.\n\n" +
                "Your entertainment credits for this session have been used up. " +
                "Take a short break, or spend some time on a productive activity " +
                "to earn more credits.\n\n" +
                "Kaizen — continuous improvement, one step at a time. 🌱"
        }
        // If launched manually (Phase 2 test button), default XML text is shown as-is

        // Handle the "Return to Home" button click
        btnReturnHome.setOnClickListener {
            returnToHomeScreen()
        }

        // Intercept the Android hardware/gesture back button.
        // This enforces the SDT coaching break without locking the device —
        // the child is guided home, not imprisoned.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToHomeScreen()
            }
        })
    }

    private fun returnToHomeScreen() {
        // Execute standard OS routing to device home screen
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)

        // Immediate teardown prevents zombie overlay states and frees memory
        finish()
    }
}
