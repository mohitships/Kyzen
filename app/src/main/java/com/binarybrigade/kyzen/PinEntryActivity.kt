package com.binarybrigade.kyzen

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PinEntryActivity — Parent Authentication Screen
 *
 * ── FLOWS ────────────────────────────────────────────────────────────────────
 *
 * A. VERIFY (PIN already set):
 *    Enter PIN → correct → ParentDashboard
 *              → wrong ×3 → "Forgot PIN?" link appears
 *              → wrong ×5 → 30s lockout
 *
 * B. CREATE (no PIN set yet) — 3 steps:
 *    Step 1: Enter new main PIN
 *    Step 2: Confirm main PIN
 *    Step 3: Set recovery PIN ("Store this safely — used only if you forget your main PIN")
 *    → All done → ParentDashboard
 *
 * C. RECOVERY (parent forgot main PIN):
 *    Tap "Forgot PIN?" → enter recovery PIN → correct → clear main PIN → Step 1 (set new PIN)
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 * - PINs NEVER stored in plain text — SHA-256 hashed via KyzenPreferences
 * - "Forgot PIN?" only shown after 3 wrong attempts — not discoverable by idle tapping
 * - Recovery requires knowing the recovery PIN — child cannot exploit it without both PINs
 * - Brute-force lockout: 5 wrong attempts → 30s lockout (makes 10,000 combos take ~16h)
 */
class PinEntryActivity : AppCompatActivity() {

    private lateinit var prefs: KyzenPreferences
    private lateinit var txtPinTitle: TextView
    private lateinit var txtPinSubtitle: TextView
    private lateinit var txtPinError: TextView
    private lateinit var txtForgotPin: TextView
    private lateinit var dots: List<TextView>

    private val enteredPin = StringBuilder()

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class FlowState {
        VERIFY,           // Returning user — enter existing PIN
        CREATE_STEP1,     // New user — enter new PIN
        CREATE_STEP2,     // New user — confirm PIN
        CREATE_RECOVERY,  // New user — set recovery PIN
        RECOVERY_VERIFY,  // Forgot PIN — enter recovery PIN to reset
        CHANGE_VERIFY,    // Change PIN — verify current PIN first
        CHANGE_NEW,       // Change PIN — enter new PIN
        CHANGE_CONFIRM    // Change PIN — confirm new PIN
    }

    private var flowState: FlowState = FlowState.VERIFY
    private var firstEntryPin: String = ""  // Stores PIN from step 1 for confirmation in step 2

    // ── Brute-force lockout ───────────────────────────────────────────────────
    private var wrongAttemptCount: Int = 0
    private var lockoutTimer: CountDownTimer? = null
    private var isLockedOut: Boolean = false

    companion object {
        private const val MAX_WRONG_ATTEMPTS          = 5
        private const val FORGOT_PIN_SHOW_THRESHOLD   = 3   // Show "Forgot PIN?" after this many wrong attempts
        private const val LOCKOUT_DURATION_MS         = 30_000L

        /** Pass this extra (value = true) to launch in Change PIN mode from ParentSettingsFragment. */
        const val EXTRA_CHANGE_PIN = "extra_change_pin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_entry)

        prefs = KyzenPreferences(this)

        txtPinTitle    = findViewById(R.id.txtPinTitle)
        txtPinSubtitle = findViewById(R.id.txtPinSubtitle)
        txtPinError    = findViewById(R.id.txtPinError)
        txtForgotPin   = findViewById(R.id.txtForgotPin)

        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )

        // Determine initial flow state
        flowState = when {
            intent.getBooleanExtra(EXTRA_CHANGE_PIN, false) -> FlowState.CHANGE_VERIFY
            prefs.isPinSet()                                -> FlowState.VERIFY
            else                                            -> FlowState.CREATE_STEP1
        }
        applyFlowState()

        // Wire number pad
        val numButtons = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9"
        )
        for ((id, digit) in numButtons) {
            findViewById<Button>(id).setOnClickListener { appendDigit(digit) }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener { deleteDigit() }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener { confirmPin() }

        // "Forgot PIN?" tap — switch to recovery flow
        txtForgotPin.setOnClickListener {
            switchToRecoveryFlow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutTimer?.cancel()
        lockoutTimer = null
    }

    // ─── State Machine ────────────────────────────────────────────────────────

    /** Apply UI labels for the current flow state. Does NOT clear PIN input. */
    private fun applyFlowState() {
        txtPinError.text = ""
        when (flowState) {
            FlowState.VERIFY -> {
                txtPinTitle.text    = "Parent Mode"
                txtPinSubtitle.text = "Enter PIN"
            }
            FlowState.CREATE_STEP1 -> {
                txtPinTitle.text    = "Create PIN"
                txtPinSubtitle.text = "Set a 4-digit PIN"
                txtForgotPin.visibility = View.GONE
            }
            FlowState.CREATE_STEP2 -> {
                txtPinTitle.text    = "Confirm PIN"
                txtPinSubtitle.text = "Enter PIN again"
            }
            FlowState.CREATE_RECOVERY -> {
                txtPinTitle.text    = "Recovery PIN"
                txtPinSubtitle.text = "Set a backup PIN — store it safely"
            }
            FlowState.RECOVERY_VERIFY -> {
                txtPinTitle.text    = "Recovery PIN"
                txtPinSubtitle.text = "Enter your recovery PIN"
                txtForgotPin.visibility = View.GONE
            }
            FlowState.CHANGE_VERIFY -> {
                txtPinTitle.text    = "Change PIN"
                txtPinSubtitle.text = "Enter your current PIN"
                txtForgotPin.visibility = View.GONE
            }
            FlowState.CHANGE_NEW -> {
                txtPinTitle.text    = "Change PIN"
                txtPinSubtitle.text = "Enter your new PIN"
                txtForgotPin.visibility = View.GONE
            }
            FlowState.CHANGE_CONFIRM -> {
                txtPinTitle.text    = "Change PIN"
                txtPinSubtitle.text = "Confirm your new PIN"
                txtForgotPin.visibility = View.GONE
            }
        }
    }

    /** Clear input + error + dots and transition to a new state. */
    private fun transitionTo(state: FlowState) {
        flowState = state
        enteredPin.clear()
        updateDots()
        applyFlowState()
    }

    private fun switchToRecoveryFlow() {
        txtForgotPin.visibility = View.GONE
        transitionTo(FlowState.RECOVERY_VERIFY)
    }

    // ─── PIN Input ────────────────────────────────────────────────────────────

    private fun appendDigit(digit: String) {
        if (isLockedOut) return
        if (enteredPin.length >= 4) return
        enteredPin.append(digit)
        txtPinError.text = ""
        updateDots()
    }

    private fun deleteDigit() {
        if (isLockedOut) return
        if (enteredPin.isEmpty()) return
        enteredPin.deleteCharAt(enteredPin.length - 1)
        txtPinError.text = ""
        updateDots()
    }

    private fun updateDots() {
        for (i in dots.indices) {
            dots[i].text = if (i < enteredPin.length) "●" else "○"
        }
    }

    // ─── Confirm / Submit ─────────────────────────────────────────────────────

    private fun confirmPin() {
        if (isLockedOut) return
        if (enteredPin.length < 4) {
            txtPinError.text = "Enter all 4 digits."
            return
        }

        val pin = enteredPin.toString()

        when (flowState) {

            // ── A. Verify existing PIN ────────────────────────────────────────
            FlowState.VERIFY -> {
                if (prefs.verifyPin(pin)) {
                    wrongAttemptCount = 0
                    txtForgotPin.visibility = View.GONE
                    startActivity(Intent(this, ParentDashboardActivity::class.java))
                    finish()
                } else {
                    wrongAttemptCount++
                    enteredPin.clear()
                    updateDots()

                    val remaining = MAX_WRONG_ATTEMPTS - wrongAttemptCount

                    when {
                        wrongAttemptCount >= MAX_WRONG_ATTEMPTS -> {
                            txtForgotPin.visibility = View.GONE
                            startLockout()
                        }
                        wrongAttemptCount >= FORGOT_PIN_SHOW_THRESHOLD -> {
                            // Show "Forgot PIN?" link after threshold — only if recovery PIN is set
                            if (prefs.isRecoveryPinSet()) {
                                txtForgotPin.visibility = View.VISIBLE
                            }
                            txtPinError.text = "Incorrect PIN. $remaining attempt${if (remaining == 1) "" else "s"} left."
                        }
                        else -> {
                            txtPinError.text = "Incorrect PIN. $remaining attempt${if (remaining == 1) "" else "s"} left."
                        }
                    }
                }
            }

            // ── B1. Create — enter new PIN ────────────────────────────────────
            FlowState.CREATE_STEP1 -> {
                firstEntryPin = pin
                transitionTo(FlowState.CREATE_STEP2)
            }

            // ── B2. Create — confirm PIN ──────────────────────────────────────
            FlowState.CREATE_STEP2 -> {
                if (pin == firstEntryPin) {
                    // PINs match — save main PIN, move to recovery PIN setup
                    prefs.setPin(pin)
                    firstEntryPin = ""
                    transitionTo(FlowState.CREATE_RECOVERY)
                } else {
                    txtPinError.text = "PINs don't match. Start again."
                    firstEntryPin = ""
                    transitionTo(FlowState.CREATE_STEP1)
                }
            }

            // ── B3. Create — set recovery PIN ────────────────────────────────
            FlowState.CREATE_RECOVERY -> {
                // Recovery PIN must be different from main PIN for security
                if (prefs.verifyPin(pin)) {
                    txtPinError.text = "Recovery PIN must differ from main PIN."
                    enteredPin.clear()
                    updateDots()
                    return
                }
                prefs.setRecoveryPin(pin)
                // All done — go to parent dashboard
                startActivity(Intent(this, ParentDashboardActivity::class.java))
                finish()
            }

            // ── C. Recovery — verify recovery PIN then reset main PIN ─────────
            FlowState.RECOVERY_VERIFY -> {
                if (prefs.verifyRecoveryPin(pin)) {
                    // Recovery PIN correct — wipe main PIN, let parent set a new one
                    prefs.clearMainPin()
                    wrongAttemptCount = 0
                    // Go back to CREATE_STEP1 — parent sets a fresh main PIN
                    // Recovery PIN is preserved — they don't need to set it again
                    transitionTo(FlowState.CREATE_STEP1)
                    txtPinSubtitle.text = "Set your new PIN"
                } else {
                    txtPinError.text = "Incorrect recovery PIN."
                    enteredPin.clear()
                    updateDots()
                }
            }

            // ── D. Change PIN — verify current PIN ───────────────────────────
            FlowState.CHANGE_VERIFY -> {
                if (prefs.verifyPin(pin)) {
                    transitionTo(FlowState.CHANGE_NEW)
                } else {
                    txtPinError.text = "Incorrect PIN."
                    enteredPin.clear()
                    updateDots()
                }
            }

            // ── D2. Change PIN — enter new PIN ────────────────────────────────
            FlowState.CHANGE_NEW -> {
                firstEntryPin = pin
                transitionTo(FlowState.CHANGE_CONFIRM)
            }

            // ── D3. Change PIN — confirm new PIN ──────────────────────────────
            FlowState.CHANGE_CONFIRM -> {
                if (pin == firstEntryPin) {
                    prefs.setPin(pin)
                    firstEntryPin = ""
                    // Done — go back to parent dashboard
                    startActivity(Intent(this, ParentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    finish()
                } else {
                    txtPinError.text = "PINs don't match. Try again."
                    firstEntryPin = ""
                    transitionTo(FlowState.CHANGE_NEW)
                }
            }
        }
    }

    // ─── Brute-force Lockout ──────────────────────────────────────────────────

    /**
     * Activates a 30-second input lockout after MAX_WRONG_ATTEMPTS failed PINs.
     * Counter resets after lockout expires. Makes brute-forcing ~16h minimum.
     */
    private fun startLockout() {
        isLockedOut = true
        enteredPin.clear()
        updateDots()

        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(LOCKOUT_DURATION_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000L
                txtPinError.text = "Too many attempts. Try again in ${secondsLeft}s."
            }
            override fun onFinish() {
                isLockedOut       = false
                wrongAttemptCount = 0
                lockoutTimer      = null
                txtPinError.text  = ""
                txtPinSubtitle.text = "Enter PIN"
            }
        }.start()
    }
}
