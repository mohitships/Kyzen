package com.binarybrigade.kyzen

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.animation.ObjectAnimator

/**
 * OverlayActivity — SDT-Aligned Coaching Intervention Screen
 *
 * Full-screen Activity that forces portrait orientation even when the
 * background app (e.g., YouTube) is in landscape mode. This fixes the
 * UI misalignment issue where the WindowManager overlay was rendered in
 * landscape, cutting off buttons at the bottom.
 *
 * ADVANTAGES OVER WindowManager overlay:
 *   1. Portrait mode — forced by manifest (android:screenOrientation="portrait")
 *   2. YouTube auto-pauses — pushing YouTube to the background pauses the video
 *   3. No UI glitches — Activity lifecycle handles orientation changes properly
 *
 * TWO MODES:
 *   SOFT (first time): Blue "Give me 15s" + Green "I'm Ready"
 *   SINGLE BUTTON (grace used): Only Green "I'm Ready"
 *
 * Communication with UsageMonitorService is via static reference (serviceInstance).
 * The service keeps all state management; this Activity only handles UI.
 */
class OverlayActivity : AppCompatActivity() {

    companion object {
        // Intent extras sent by UsageMonitorService when firing the intervention
        const val EXTRA_TRIGGER_APP_NAME = "extra_trigger_app_name"
        const val EXTRA_TRIGGER_PACKAGE  = "extra_trigger_package"
        const val EXTRA_TITLE            = "extra_title"
        const val EXTRA_MESSAGE          = "extra_message"
        const val EXTRA_PILL_TEXT        = "extra_pill_text"
        const val EXTRA_PILL_IS_RED      = "extra_pill_is_red"
        const val EXTRA_GRACE_USED       = "extra_grace_used"

        /** Current instance — used by UsageMonitorService.removeOverlay() to finish this activity */
        var currentInstance: OverlayActivity? = null
            private set

        /** Quotes — copied from UsageMonitorService to keep this Activity self-contained */
        private val OVERLAY_QUOTES = listOf(
            Pair("Rest is not a reward. It is a requirement.", "— Arianna Huffington"),
            Pair("Almost everything will work again if you unplug it.", "— Anne Lamott"),
            Pair("The quieter you become, the more you can hear.", "— Ram Dass"),
            Pair("Doing nothing is better than being busy doing nothing.", "— Lao Tzu"),
            Pair("Your calm mind is your greatest weapon.", "— Bryant McGill"),
            Pair("Be here. Right now. That is enough.", "— Thich Nhat Hanh"),
            Pair("A rested mind sees clearly.", "— Marcus Aurelius"),
            Pair("Peace begins the moment you choose it.", "— Unknown"),
            Pair("Be gentle with yourself. You are a child of the universe.", "— Max Ehrmann"),
            Pair("Stillness is where creativity and wisdom live.", "— Eckhart Tolle"),
            Pair("You do not have to earn your rest.", "— Tricia Hersey"),
            Pair("Slow down and everything you are chasing will come around.", "— John De Paola")
        )
    }

    private var quoteHandler: Handler? = null
    private var quoteIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay)

        // Register as current instance so service can finish us
        currentInstance = this

        // Ensure this activity consumes all touches
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        // Turn screen on and show over lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Read intent extras
        val triggerAppName = intent.getStringExtra(EXTRA_TRIGGER_APP_NAME) ?: ""
        val triggerPackage = intent.getStringExtra(EXTRA_TRIGGER_PACKAGE) ?: ""
        val title          = intent.getStringExtra(EXTRA_TITLE) ?: "Time for a mindful break."
        val message        = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val pillText       = intent.getStringExtra(EXTRA_PILL_TEXT) ?: "0 gems — wallet empty"
        val pillIsRed      = intent.getBooleanExtra(EXTRA_PILL_IS_RED, true)
        val graceUsed      = intent.getBooleanExtra(EXTRA_GRACE_USED, true)

        // Set up UI
        val txtTitle = findViewById<TextView>(R.id.txtOverlayTitle)
        val txtMessage = findViewById<TextView>(R.id.txtOverlayMessage)
        val txtPill = findViewById<TextView>(R.id.txtOverlayStatusPill)
        val btnGoBack = findViewById<Button>(R.id.btnGoBackToApp)
        val btnHome = findViewById<Button>(R.id.btnReturnHome)

        txtTitle.text = title
        txtMessage.text = message
        txtPill.text = pillText

        // Set pill color
        if (pillIsRed) {
            txtPill.setTextColor(ContextCompat.getColor(this, R.color.kyzen_red_medium))
            txtPill.background = ContextCompat.getDrawable(this, R.drawable.bg_intervention_pill)
        } else {
            txtPill.setTextColor(ContextCompat.getColor(this, R.color.kyzen_amber_dark))
            txtPill.background = ContextCompat.getDrawable(this, R.drawable.bg_cancel_pill)
        }

        // ── Button mode ──────────────────────────────────────────────────────
        if (graceUsed) {
            // Single button mode — grace already used for this package
            btnGoBack.visibility = android.view.View.GONE
            btnHome.setOnClickListener { goHome(triggerPackage) }
        } else {
            // Two button mode — first time for this package
            btnHome.setOnClickListener { goHome(triggerPackage) }
            btnGoBack.setOnClickListener { requestGrace(triggerPackage, triggerAppName) }
        }

        // Intercept the hardware/gesture back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome(triggerPackage)
            }
        })

        // ── Quote rotation ───────────────────────────────────────────────────
        setupQuoteRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up quote handler
        quoteHandler?.removeCallbacksAndMessages(null)
        quoteHandler = null
        // Clear instance reference
        if (currentInstance == this) {
            currentInstance = null
        }
    }

    // ─── Actions ────────────────────────────────────────────────────────────────

    /**
     * "I'm Ready — Take a Break" button action.
     * Tells the service to go home (pauses media, starts 5s grace for PiP).
     */
    private fun goHome(packageName: String) {
        val service = UsageMonitorService.instance
        service?.onOverlayGoHome(packageName)
        finish()
    }

    /**
     * "Give me 15 seconds" button action.
     * Tells the service to grant a 15-second grace period and return to the app.
     */
    private fun requestGrace(packageName: String, appName: String) {
        val service = UsageMonitorService.instance
        service?.onOverlayGraceRequested(packageName, appName)
        finish()
    }

    // ─── Quote Rotation ─────────────────────────────────────────────────────────

    private fun setupQuoteRotation() {
        val txtQuote = findViewById<TextView>(R.id.txtOverlayQuote)
        val txtQuoteAuthor = findViewById<TextView>(R.id.txtOverlayQuoteAuthor)
        val shuffledQuotes = OVERLAY_QUOTES.shuffled()
        quoteIndex = 0

        val handler = Handler(Looper.getMainLooper())
        quoteHandler = handler

        fun showQuote(index: Int, immediate: Boolean = false) {
            val q = shuffledQuotes[index % shuffledQuotes.size]
            if (immediate) {
                txtQuote.text = q.first
                txtQuoteAuthor.text = q.second
                ObjectAnimator.ofFloat(txtQuote, "alpha", 0f, 1f).apply {
                    duration = 500; start()
                }
                ObjectAnimator.ofFloat(txtQuoteAuthor, "alpha", 0f, 1f).apply {
                    duration = 500; start()
                }
            } else {
                ObjectAnimator.ofFloat(txtQuote, "alpha", 1f, 0f).apply {
                    duration = 300; start()
                }
                ObjectAnimator.ofFloat(txtQuoteAuthor, "alpha", 1f, 0f).apply {
                    duration = 300; start()
                }
                handler.postDelayed({
                    txtQuote.text = q.first
                    txtQuoteAuthor.text = q.second
                    ObjectAnimator.ofFloat(txtQuote, "alpha", 0f, 1f).apply {
                        duration = 500; start()
                    }
                    ObjectAnimator.ofFloat(txtQuoteAuthor, "alpha", 0f, 1f).apply {
                        duration = 500; start()
                    }
                }, 300)
            }
        }

        // Show first quote immediately
        showQuote(quoteIndex++, immediate = true)

        // Rotate quotes every 6 seconds
        val quoteTicker = object : Runnable {
            override fun run() {
                if (currentInstance == null) return
                showQuote(quoteIndex++)
                handler.postDelayed(this, 6_000L)
            }
        }
        handler.postDelayed(quoteTicker, 6_000L)
    }
}