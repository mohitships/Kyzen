package com.binarybrigade.kyzen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ParentSettingsFragment — Page 0 (index 0) of ParentDashboardActivity ViewPager2.
 *
 * Contains: Today's summary stat pills + all parent control cards:
 *   - Pause Entertainment toggle
 *   - Daily Spending Cap (30 / 60 / 90 / 120 gems)
 *   - Gift Gems (+10 / +20 / +50 / Custom)
 *   - Adjust Gem Balance (-5 / -10 / -20 / Custom)
 *   - Detox Break Duration (5 / 10 / 15 / 20 min)
 *
 * All coroutines use viewLifecycleOwner.lifecycleScope — safe against view destruction.
 * All button highlight logic swaps full background drawables (filled vs outline) —
 * avoids backgroundTintList washing out border strokes. Never uses setBackgroundColor().
 *
 * Summary stat display: uses the same format logic as AppUsageAdapter —
 * seconds for sub-60s, to avoid "0" for brief usage.
 *
 * Dedup guard: loadSummaryStats() skips the OS query if called within 5 seconds —
 * prevents double getSanitizedUsageList() when both fragments resume simultaneously.
 * Fast path reads from Room instead (already written by the OS query winner).
 */
class ParentSettingsFragment : Fragment() {

    private lateinit var prefs: KyzenPreferences
    private lateinit var usageTracker: UsageTracker

    // Header
    private lateinit var txtParentDate: TextView

    // Change PIN
    private lateinit var btnChangePIN: Button

    // Summary stats
    private lateinit var txtParentProductiveTime: TextView
    private lateinit var txtParentEntertainmentTime: TextView
    private lateinit var txtParentGemWallet: TextView

    // Controls
    private lateinit var switchPauseEntertainment: SwitchMaterial

    // Daily spending cap buttons
    private lateinit var btnCap30: Button
    private lateinit var btnCap60: Button
    private lateinit var btnCap90: Button
    private lateinit var btnCap120: Button

    // Gift gems buttons
    private lateinit var btnGift10: Button
    private lateinit var btnGift20: Button
    private lateinit var btnGift50: Button
    private lateinit var btnGiftCustom: Button

    // Adjust balance buttons
    private lateinit var btnDeduct5: Button
    private lateinit var btnDeduct10: Button
    private lateinit var btnDeduct20: Button
    private lateinit var btnDeductCustom: Button

    // Detox duration buttons
    private lateinit var btnDetoxDuration5: Button
    private lateinit var btnDetoxDuration10: Button
    private lateinit var btnDetoxDuration15: Button
    private lateinit var btnDetoxDuration20: Button

    // Dedup guard — skip OS query if called within 5 seconds
    private var lastLoadTimeMs: Long = 0L
    private val DEDUP_THRESHOLD_MS = 5_000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_parent_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs        = KyzenPreferences(requireContext())
        usageTracker = UsageTracker(requireContext().applicationContext)

        bindViews(view)
        styleStaticButtons()
        restoreCurrentSettings()
        setupListeners()
        loadSummaryStats()
    }

    override fun onResume() {
        super.onResume()
        // Lightweight prefs refresh — nulls listener before setting isChecked to
        // prevent spurious "Entertainment paused/resumed" Toast on resume.
        restoreCurrentSettings()
        loadSummaryStats()

        // Remind parent if YouTube content detection is not enabled —
        // without it, YouTube is classified as NEUTRAL (no gems earned or spent).
        if (!prefs.isYouTubeContentMonitorEnabled()) {
            Toast.makeText(requireContext(),
                "YouTube content detection is off. Enable it in Accessibility Settings to classify YouTube as productive or entertainment.",
                Toast.LENGTH_LONG).show()
        }
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        txtParentDate              = view.findViewById(R.id.txtParentDate)
        btnChangePIN               = view.findViewById(R.id.btnChangePIN)
        txtParentProductiveTime    = view.findViewById(R.id.txtParentProductiveTime)
        txtParentEntertainmentTime = view.findViewById(R.id.txtParentEntertainmentTime)
        txtParentGemWallet         = view.findViewById(R.id.txtParentGemWallet)
        switchPauseEntertainment   = view.findViewById(R.id.switchPauseEntertainment)
        btnCap30                   = view.findViewById(R.id.btnCap30)
        btnCap60                   = view.findViewById(R.id.btnCap60)
        btnCap90                   = view.findViewById(R.id.btnCap90)
        btnCap120                  = view.findViewById(R.id.btnCap120)
        btnGift10                  = view.findViewById(R.id.btnGift10)
        btnGift20                  = view.findViewById(R.id.btnGift20)
        btnGift50                  = view.findViewById(R.id.btnGift50)
        btnGiftCustom              = view.findViewById(R.id.btnGiftCustom)
        btnDeduct5                 = view.findViewById(R.id.btnDeduct5)
        btnDeduct10                = view.findViewById(R.id.btnDeduct10)
        btnDeduct20                = view.findViewById(R.id.btnDeduct20)
        btnDeductCustom            = view.findViewById(R.id.btnDeductCustom)
        btnDetoxDuration5          = view.findViewById(R.id.btnDetoxDuration5)
        btnDetoxDuration10         = view.findViewById(R.id.btnDetoxDuration10)
        btnDetoxDuration15         = view.findViewById(R.id.btnDetoxDuration15)
        btnDetoxDuration20         = view.findViewById(R.id.btnDetoxDuration20)
    }

    // ─── Restore Saved Settings ───────────────────────────────────────────────

    private fun restoreCurrentSettings() {
        // Set date header — scrolls with content inside the fragment
        txtParentDate.text = LocalDate.now().format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
        )

        // Null the listener before setting isChecked — prevents the listener from
        // firing a spurious Toast when restoreCurrentSettings() is called from
        // onResume(). The listener is re-attached immediately after in setupListeners().
        switchPauseEntertainment.setOnCheckedChangeListener(null)
        switchPauseEntertainment.isChecked = prefs.isGamePauseEnabled
        attachSwitchListener()

        updateCapHighlight(prefs.dailySpendingCap)
        updateDetoxDurationHighlight(prefs.detoxDurationMinutes)
        // Show wallet immediately from prefs (synchronous) — clean number only.
        txtParentGemWallet.text = "${prefs.gemWallet}"
    }

    // ─── Listeners ────────────────────────────────────────────────────────────

    private fun attachSwitchListener() {
        switchPauseEntertainment.setOnCheckedChangeListener { _, isChecked ->
            prefs.isGamePauseEnabled = isChecked
            Toast.makeText(requireContext(),
                if (isChecked) "Entertainment blocked." else "Entertainment unlocked.",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // Switch listener attached via attachSwitchListener() — called from
        // restoreCurrentSettings() to prevent spurious Toast on isChecked restore.

        // Daily spending cap
        btnCap30.setOnClickListener  { setDailySpendingCap(30)  }
        btnCap60.setOnClickListener  { setDailySpendingCap(60)  }
        btnCap90.setOnClickListener  { setDailySpendingCap(90)  }
        btnCap120.setOnClickListener { setDailySpendingCap(120) }

        // Gift gems — presets
        btnGift10.setOnClickListener { giftGems(10) }
        btnGift20.setOnClickListener { giftGems(20) }
        btnGift50.setOnClickListener { giftGems(50) }

        // Gift gems — custom amount
        btnGiftCustom.setOnClickListener { showCustomGiftDialog() }

        // Adjust balance — presets
        btnDeduct5.setOnClickListener  { deductGems(5)  }
        btnDeduct10.setOnClickListener { deductGems(10) }
        btnDeduct20.setOnClickListener { deductGems(20) }
        btnDeductCustom.setOnClickListener { showCustomDeductDialog() }

        btnChangePIN.setOnClickListener {
            val intent = android.content.Intent(requireContext(), PinEntryActivity::class.java).apply {
                putExtra(PinEntryActivity.EXTRA_CHANGE_PIN, true)
            }
            startActivity(intent)
        }

        // Detox break duration
        btnDetoxDuration5.setOnClickListener  { setDetoxDuration(5)  }
        btnDetoxDuration10.setOnClickListener { setDetoxDuration(10) }
        btnDetoxDuration15.setOnClickListener { setDetoxDuration(15) }
        btnDetoxDuration20.setOnClickListener { setDetoxDuration(20) }
    }

    // ─── Action Helpers ───────────────────────────────────────────────────────

    private fun setDailySpendingCap(gems: Int) {
        prefs.dailySpendingCap = gems
        updateCapHighlight(gems)
    }

    private fun giftGems(amount: Int) {
        prefs.addGems(amount)
        val newBalance = prefs.gemWallet
        Toast.makeText(requireContext(),
            "💎 $amount gems added. Balance: $newBalance",
            Toast.LENGTH_SHORT).show()
        txtParentGemWallet.text = "$newBalance"
        // Log transaction
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GemTransactionRepository(requireContext().applicationContext).logTransaction(
                        GemTransactionRepository.TYPE_PARENT_GIFT, +amount, "Gift from parent"
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showCustomGiftDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_gift, null)
        val etAmount   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etGiftAmount)
        val btnCancel  = dialogView.findViewById<Button>(R.id.btnGiftCancel)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnGiftConfirm)

        // Null out Material theme tint on dialog buttons
        btnCancel.backgroundTintList  = null
        btnConfirm.backgroundTintList = null

        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val amount = etAmount.text.toString().toIntOrNull()
            when {
                amount == null || amount <= 0 ->
                    Toast.makeText(requireContext(),
                        "Enter a number greater than zero.", Toast.LENGTH_SHORT).show()
                amount > KyzenPreferences.MAX_GIFT_AMOUNT ->
                    Toast.makeText(requireContext(),
                        "Maximum gift is ${KyzenPreferences.MAX_GIFT_AMOUNT} gems.",
                        Toast.LENGTH_SHORT).show()
                else -> { giftGems(amount); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun deductGems(amount: Int) {
        val actual     = prefs.deductGems(amount)
        val newBalance = prefs.gemWallet
        Toast.makeText(requireContext(),
            "💎 $actual gems deducted. Balance: $newBalance",
            Toast.LENGTH_SHORT).show()
        txtParentGemWallet.text = "$newBalance"
        // Log transaction — only if something was actually adjusted
        if (actual > 0) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        GemTransactionRepository(requireContext().applicationContext).logTransaction(
                            GemTransactionRepository.TYPE_PARENT_DEDUCT, -actual, "Parent adjustment"
                        )
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun showCustomDeductDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_deduct, null)
        val etAmount   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDeductAmount)
        val btnCancel  = dialogView.findViewById<Button>(R.id.btnDeductCancel)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnDeductConfirm)

        // Null out Material theme tint on dialog buttons
        btnCancel.backgroundTintList  = null
        btnConfirm.backgroundTintList = null

        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val amount = etAmount.text.toString().toIntOrNull()
            when {
                amount == null || amount <= 0 ->
                    Toast.makeText(requireContext(),
                        "Enter a number greater than zero.", Toast.LENGTH_SHORT).show()
                amount > KyzenPreferences.MAX_DEDUCT_AMOUNT ->
                    Toast.makeText(requireContext(),
                        "Maximum deduction is ${KyzenPreferences.MAX_DEDUCT_AMOUNT} gems.",
                        Toast.LENGTH_SHORT).show()
                else -> { deductGems(amount); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun setDetoxDuration(minutes: Int) {
        prefs.detoxDurationMinutes = minutes
        updateDetoxDurationHighlight(minutes)
    }

    // ─── Static Button Styling ────────────────────────────────────────────────
    // Material Button applies a theme-level backgroundTint (purple) that overrides
    // android:background set in XML. The only reliable fix is to set background and
    // null the tint programmatically after the view is inflated.

    private fun styleStaticButtons() {
        val white = ContextCompat.getColor(requireContext(), R.color.kyzen_white)

        // Gift Gems — green buttons
        listOf(btnGift10, btnGift20, btnGift50).forEach { btn ->
            btn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_filled_green)
            btn.backgroundTintList = null
            btn.setTextColor(white)
        }
        // Gift Custom — blue
        btnGiftCustom.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_filled_blue)
        btnGiftCustom.backgroundTintList = null
        btnGiftCustom.setTextColor(white)

        // Adjust Balance — red buttons
        listOf(btnDeduct5, btnDeduct10, btnDeduct20).forEach { btn ->
            btn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_filled_red)
            btn.backgroundTintList = null
            btn.setTextColor(white)
        }
        // Deduct Custom — purple
        btnDeductCustom.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_filled_purple)
        btnDeductCustom.backgroundTintList = null
        btnDeductCustom.setTextColor(white)

        // Change PIN — navy
        btnChangePIN.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_filled_navy)
        btnChangePIN.backgroundTintList = null
        btnChangePIN.setTextColor(white)
    }

    // ─── UI Highlight Helpers — use backgroundTintList, NOT setBackgroundColor ─
    // setBackgroundColor() replaces the entire Material button drawable (shape,
    // corner radius, ripple) with a flat rectangle. backgroundTintList applies
    // colour as a tint over the existing shape, preserving all Material styling.

    private fun updateCapHighlight(activeCap: Int) {
        val navyColor = ContextCompat.getColor(requireContext(), R.color.kyzen_navy)
        val white     = ContextCompat.getColor(requireContext(), R.color.kyzen_white)
        fun style(btn: Button, match: Int) {
            val isActive = activeCap == match
            // Swap full drawable — avoids backgroundTintList washing out the border stroke
            btn.background = ContextCompat.getDrawable(
                requireContext(),
                if (isActive) R.drawable.bg_btn_filled_navy else R.drawable.bg_btn_outline_navy
            )
            btn.backgroundTintList = null
            btn.setTextColor(if (isActive) white else navyColor)
        }
        style(btnCap30,  30)
        style(btnCap60,  60)
        style(btnCap90,  90)
        style(btnCap120, 120)
    }

    private fun updateDetoxDurationHighlight(activeDuration: Int) {
        val blueColor = ContextCompat.getColor(requireContext(), R.color.kyzen_blue_dark)
        val white     = ContextCompat.getColor(requireContext(), R.color.kyzen_white)
        fun style(btn: Button, match: Int) {
            val isActive = activeDuration == match
            // Swap full drawable — avoids backgroundTintList washing out the border stroke
            btn.background = ContextCompat.getDrawable(
                requireContext(),
                if (isActive) R.drawable.bg_btn_filled_blue else R.drawable.bg_btn_outline_blue
            )
            btn.backgroundTintList = null
            btn.setTextColor(if (isActive) white else blueColor)
        }
        style(btnDetoxDuration5,  5)
        style(btnDetoxDuration10, 10)
        style(btnDetoxDuration15, 15)
        style(btnDetoxDuration20, 20)
    }

    // ─── Summary Stats ────────────────────────────────────────────────────────
    // Dedup guard mirrors ActivityFragment: if called within 5 seconds of the
    // last OS query, reads from Room instead of hitting UsageStatsManager again.
    // This prevents two concurrent getSanitizedUsageList() calls when both
    // ParentSettingsFragment and ParentUsageFragment resume simultaneously.

    private fun loadSummaryStats() {
        val now = System.currentTimeMillis()

        if (now - lastLoadTimeMs < DEDUP_THRESHOLD_MS && lastLoadTimeMs > 0L) {
            // Fast path — read already-classified data from Room
            viewLifecycleOwner.lifecycleScope.launch {
                val appList = try {
                    withContext(Dispatchers.IO) {
                        UsageRepository(requireContext().applicationContext).getTodayUsage()
                    }.sortedByDescending { it.usageDurationMillis }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
                updateStatPills(appList)
            }
            return
        }

        // Fresh OS query — authoritative, live data
        lastLoadTimeMs = now
        viewLifecycleOwner.lifecycleScope.launch {
            val appList = try {
                withContext(Dispatchers.IO) { usageTracker.getSanitizedUsageList() }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            updateStatPills(appList)
        }
    }

    private fun updateStatPills(appList: List<AppUsageItem>) {
        val productiveMs    = appList
            .filter { it.category == AppClassifier.AppCategory.PRODUCTIVE }
            .sumOf { it.usageDurationMillis }
        val entertainmentMs = appList
            .filter { it.category == AppClassifier.AppCategory.ENTERTAINMENT }
            .sumOf { it.usageDurationMillis }

        txtParentProductiveTime.text    = formatDurationForPill(productiveMs)
        txtParentEntertainmentTime.text = formatDurationForPill(entertainmentMs)
        txtParentGemWallet.text         = "${prefs.gemWallet}"
    }

    /**
     * Formats a millisecond duration for display in the stat pills.
     * Mirrors AppUsageAdapter's format rules:
     *   < 60s   → "Xs"    e.g. "45s"
     *   ≥ 60s   → "Xm"    e.g. "24m" (rounded to nearest minute)
     *   ≥ 3600s → "Xh Ym" e.g. "1h 3m"
     * Unit is embedded in the string — no separate MIN label needed in XML.
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        return when {
            totalSeconds < 60L -> "${totalSeconds}s"
            totalSeconds < 3600L -> {
                val rounded = (ms + 30_000L) / 60_000L
                "${rounded}m"
            }
            else -> {
                val hours = totalSeconds / 3600L
                val remainingMs = ms - (hours * 3_600_000L)
                val rounded = (remainingMs + 30_000L) / 60_000L
                if (rounded > 0L) "${hours}h ${rounded}m" else "${hours}h"
            }
        }
    }

    /**
     * Formats a millisecond duration for the FOCUS/FUN stat pills.
     * The pills have a static "MIN" label below — so we return just the number.
     *   < 60s   → "0"     (rounds down, shows as 0 MIN)
     *   ≥ 60s   → "21"    (minutes only, no suffix — MIN label is in XML)
     *   ≥ 3600s → "1h 3m" (hours override the MIN label — still readable)
     */
    private fun formatDurationForPill(ms: Long): String {
        val totalSeconds = ms / 1000L
        return when {
            totalSeconds < 60L   -> "${totalSeconds}s"
            totalSeconds < 3600L -> "${(ms + 30_000L) / 60_000L}"
            else -> {
                val hours        = totalSeconds / 3600L
                val remainingMs  = ms - (hours * 3_600_000L)
                val mins         = (remainingMs + 30_000L) / 60_000L
                if (mins > 0L) "${hours}h ${mins}m" else "${hours}h"
            }
        }
    }

    companion object {
        fun newInstance() = ParentSettingsFragment()
    }
}
