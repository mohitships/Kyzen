package com.binarybrigade.kyzen

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DetoxBreakActivity — Voluntary Digital Detox Break Screen
 *
 * A full-screen focused environment that locks the phone during a
 * child-initiated detox break. Designed around Self-Determination Theory
 * (SDT) autonomy support — the child CHOSE this, so the experience is
 * calm and rewarding, not punitive.
 *
 * Key behaviours:
 * - Timer uses wall-clock timestamps (System.currentTimeMillis) so it
 *   survives screen off, app backgrounding, and OxygenOS memory pressure.
 *   CountDownTimer is NOT used — it pauses when screen turns off.
 * - Back button intercepted: shows cancel confirmation dialog.
 * - Emergency Cancel: returns to ChildDashboardActivity, NO credits awarded.
 * - On completion: awards detox bonus via KyzenPreferences, then finishes.
 * - showWhenLocked + turnScreenOn in Manifest: screen wakes at timer end
 *   to show the celebration dialog even if phone was face-down / locked.
 * - excludeFromRecents: hidden from Android Overview (Recents) screen so
 *   child cannot side-step the break by switching apps from recents.
 */
class DetoxBreakActivity : AppCompatActivity() {

    companion object {
        /** Duration in milliseconds passed from ChildDashboardActivity */
        const val EXTRA_DETOX_DURATION_MS = "extra_detox_duration_ms"

        /** Current instance — used by UsageMonitorService to check if activity is showing
         *  and to finish it when detox is cancelled/completed externally. */
        var currentInstance: DetoxBreakActivity? = null
            private set

        /**
         * Rotating motivational quotes shown below the timer (one per minute).
         *
         * Curated to align with:
         * — Self-Determination Theory (SDT): affirm autonomy, competence, relatedness
         * — Kaizen philosophy: small consistent steps, process over outcome, no perfectionism
         * — Mindfulness research: present-moment awareness reduces digital craving
         *
         * Deliberately avoid outcome-pressure language ("be productive", "achieve more").
         * Instead: celebrate the act of pausing itself as a form of growth.
         */
        private val QUOTES = listOf(
            Pair("Rest is not a reward. It is a requirement.", "— Arianna Huffington"),
            Pair("Almost everything will work again if you unplug it.", "— Anne Lamott"),
            Pair("The quieter you become, the more you can hear.", "— Ram Dass"),
            Pair("Doing nothing is better than being busy doing nothing.", "— Lao Tzu"),
            Pair("Your calm mind is your greatest weapon.", "— Bryant McGill"),
            Pair("Be here. Right now. That is enough.", "— Thich Nhat Hanh"),
            Pair("A rested mind sees clearly.", "— Marcus Aurelius"),
            Pair("Peace begins the moment you choose it.", "— Unknown"),
            Pair("Be gentle with yourself. You are a child of the universe.", "— Max Ehrmann"),
            Pair("Stillness is where creativity and wisdom live.", "— Eckhart Tolle")
        )
    }

    private lateinit var prefs: KyzenPreferences
    private lateinit var txtCountdown: TextView
    private lateinit var txtProgress: TextView
    private lateinit var txtQuote: TextView
    private lateinit var txtQuoteAuthor: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: Button

    // Wall-clock start time — survives screen off because it's just a Long
    private var startTimeMs: Long = 0L
    private var durationMs: Long = 0L

    // Handler ticks the UI every second while screen is on
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val remainingMs = durationMs - elapsedMs

            if (remainingMs <= 0) {
                // Timer complete — award credits and celebrate
                onDetoxComplete()
            } else {
                updateTimerUI(remainingMs, elapsedMs)
                handler.postDelayed(this, 500L) // 500ms tick for smooth display
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detox_break)

        // Register as current instance so service can check/finish us
        currentInstance = this

        // Ensure this activity consumes all touches.
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        prefs = KyzenPreferences(this)

        bindViews()

        // Read duration from intent (set by ChildDashboardActivity).
        // SECURITY: Clamp to valid range (5–20 minutes) so a child cannot
        // launch the activity with a 1ms duration via adb or intent injection
        // to instantly earn detox gems without actually taking a break.
        val rawDurationMs = intent.getLongExtra(
            EXTRA_DETOX_DURATION_MS,
            prefs.detoxDurationMinutes * 60_000L
        )
        val minDurationMs = 5 * 60_000L   // 5 minutes minimum
        val maxDurationMs = 20 * 60_000L  // 20 minutes maximum
        durationMs = rawDurationMs.coerceIn(minDurationMs, maxDurationMs)

        // Restore start time from SharedPreferences (set by startDetoxSession).
        // This survives activity recreation, screen rotation, AND process death.
        //
        // CRITICAL: Only call startDetoxSession() if no session is currently active.
        // Calling it unconditionally would overwrite detoxStartMs on every onCreate()
        // (e.g. screen rotation), resetting the timer — a child-exploitable bug.
        val existingStart = prefs.detoxStartMs
        if (existingStart > 0L && prefs.isDetoxActive) {
            // Restore existing session — timer continues from original start time
            startTimeMs = existingStart
        } else {
            // Fresh session — record start time ONCE
            startTimeMs = System.currentTimeMillis()
            prefs.startDetoxSession(durationMs)
        }

        // Set initial progress label
        val totalMinutes = (durationMs / 60_000L).toInt()
        txtProgress.text = "0 of $totalMinutes minutes completed"

        // Show first quote
        updateQuote(0)

        // Intercept back button — same pattern as OverlayActivity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showCancelConfirmation()
            }
        })

        btnCancel.setOnClickListener {
            showCancelConfirmation()
        }
    }

    override fun onResume() {
        super.onResume()

        // If the session was cancelled externally (e.g. child tapped Emergency Cancel
        // on the service WindowManager overlay while DetoxBreakActivity was in the
        // background), prefs.isDetoxActive will be false. Finish immediately — no credits.
        if (!prefs.isDetoxActive) {
            finish()
            return
        }

        // Check immediately in case timer already finished while screen was off
        val elapsedMs = System.currentTimeMillis() - startTimeMs
        if (elapsedMs >= durationMs) {
            onDetoxComplete()
        } else {
            // Start ticking the UI
            handler.post(tickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop UI ticks — timer logic uses wall-clock so no state is lost.
        // The phone CAN go to sleep. onResume() recalculates elapsed time correctly.
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        // Clear instance reference
        if (currentInstance == this) {
            currentInstance = null
        }
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        txtCountdown    = findViewById(R.id.txtDetoxCountdown)
        txtProgress     = findViewById(R.id.txtDetoxProgress)
        txtQuote        = findViewById(R.id.txtDetoxQuote)
        txtQuoteAuthor  = findViewById(R.id.txtDetoxQuoteAuthor)
        progressBar     = findViewById(R.id.progressDetox)
        btnCancel       = findViewById(R.id.btnDetoxCancel)
    }

    // ─── Timer UI Update ──────────────────────────────────────────────────────

    /**
     * Updates the countdown display, progress bar, and motivational quote.
     * Called every 500ms while the screen is active.
     *
     * @param remainingMs Milliseconds remaining in the detox break
     * @param elapsedMs   Milliseconds elapsed since detox start
     */
    private fun updateTimerUI(remainingMs: Long, elapsedMs: Long) {
        // Format MM:SS
        val totalSeconds = remainingMs / 1000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        txtCountdown.text = "%02d:%02d".format(minutes, seconds)

        // Progress bar (0–100)
        val progressPercent = ((elapsedMs.toFloat() / durationMs) * 100).toInt()
        progressBar.progress = progressPercent.coerceIn(0, 100)

        // Progress label: "X of Y minutes completed"
        val totalMinutes = (durationMs / 60_000L).toInt()
        val elapsedMinutes = (elapsedMs / 60_000L).toInt()
        txtProgress.text = "$elapsedMinutes of $totalMinutes minutes completed"

        // Rotate quote every 60 seconds
        val quoteIndex = (elapsedMs / 60_000L).toInt() % QUOTES.size
        updateQuote(quoteIndex)
    }

    private fun updateQuote(index: Int) {
        val quote = QUOTES[index.coerceIn(0, QUOTES.size - 1)]
        txtQuote.text = quote.first
        txtQuoteAuthor.text = quote.second
    }

    // ─── Completion ───────────────────────────────────────────────────────────

    /**
     * Called when the detox break timer reaches zero.
     * Awards the bonus credits and shows a celebration dialog.
     * Does NOT navigate anywhere — the dialog dismiss returns to ChildDashboard
     * naturally because this activity finishes.
     */
    private fun onDetoxComplete() {
        handler.removeCallbacks(tickRunnable)

        // Clear detox session from prefs — service will stop enforcing the overlay
        prefs.endDetoxSession()

        // Update UI to show completion
        txtCountdown.text = "00:00"
        progressBar.progress = 100
        val totalMinutes = (durationMs / 60_000L).toInt()
        txtProgress.text = "$totalMinutes of $totalMinutes minutes completed"

        // Award the detox bonus gems to the wallet
        prefs.awardDetoxBonus()

        // Log the detox bonus transaction
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GemTransactionRepository(applicationContext).logTransaction(
                        GemTransactionRepository.TYPE_DETOX_BONUS,
                        +KyzenPreferences.DEFAULT_DETOX_BONUS,
                        "Completed a mindful break"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace() // Non-critical — never crash UI for a log
            }
        }

        // SDT competence: celebrate what the child DID, not just the reward.
        // Kaizen: frame completion as proof of their growing self-regulation ability.
        val completeView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_detox_complete, null)

        completeView.findViewById<TextView>(R.id.txtCompleteGems).text =
            "+${KyzenPreferences.DEFAULT_DETOX_BONUS} 💎 Gems added to your wallet!"
        completeView.findViewById<TextView>(R.id.txtCompleteBody).text =
            "You stayed present for $totalMinutes whole minutes — that takes real self-awareness.\n\n" +
            "Kaizen: you just proved to yourself that you can choose to pause. " +
            "That skill grows every time you use it. 🌱"

        val completeDialog = AlertDialog.Builder(this)
            .setView(completeView)
            .setCancelable(false)
            .create()

        completeDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        completeView.findViewById<Button>(R.id.btnCompleteCollect).setOnClickListener {
            completeDialog.dismiss()
            finish()
        }

        completeDialog.show()
    }

    // ─── Cancel Flow ──────────────────────────────────────────────────────────

    /**
     * Shows a confirmation dialog before cancelling the detox break.
     * If confirmed: finishes this activity with no credits awarded.
     * If dismissed: detox continues uninterrupted.
     */
    private fun showCancelConfirmation() {
        val elapsedMs = System.currentTimeMillis() - startTimeMs
        val elapsedMinutes = (elapsedMs / 60_000L).toInt()
        val totalMinutes = (durationMs / 60_000L).toInt()

        // SDT autonomy: acknowledge the child's feeling, provide honest information,
        // then give them a genuine choice. Avoid shame or threat framing.
        // Kaizen: even $elapsedMinutes minutes was valuable — acknowledge partial effort.
        val partialAcknowledgement = if (elapsedMinutes > 0)
            "You've already been present for $elapsedMinutes minute${if (elapsedMinutes == 1) "" else "s"} — that counts for something.\n\n"
        else ""

        val cancelView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_detox_cancel, null)

        // Elapsed pill
        val elapsedPillText = if (elapsedMinutes > 0)
            "$elapsedMinutes mins elapsed — no gems awarded"
        else
            "No time elapsed — no gems awarded"
        cancelView.findViewById<TextView>(R.id.txtCancelElapsed).text = elapsedPillText

        // Body message
        val bodyText = "${partialAcknowledgement}Ending now means no 💎 Gems this time — but that's okay.\n\n" +
            "Is there something you genuinely need to do right now, or is it just hard to stay? Both are okay to admit. 🌿"
        cancelView.findViewById<TextView>(R.id.txtCancelBody).text = bodyText

        val cancelDialog = AlertDialog.Builder(this)
            .setView(cancelView)
            .setCancelable(true)
            .create()

        cancelDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        cancelView.findViewById<Button>(R.id.btnCancelStay).setOnClickListener {
            cancelDialog.dismiss()
        }

        cancelView.findViewById<Button>(R.id.btnCancelConfirm).setOnClickListener {
            cancelDialog.dismiss()
            prefs.endDetoxSession()
            finish()
        }

        cancelDialog.show()
    }
}
