package com.binarybrigade.kyzen

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DashboardFragment — Page 1 of the child dashboard ViewPager2.
 * Contains: Header, Hero Wallet Card, Action Grid (GEMS + DETOX).
 * App usage list lives in ActivityFragment (Page 2).
 */
class DashboardFragment : Fragment() {

    private lateinit var prefs: KyzenPreferences
    private lateinit var rewardEngine: RewardEngine
    private lateinit var usageTracker: UsageTracker

    // Header
    private lateinit var txtChildDate: TextView

    // Gems wallet card
    private lateinit var imgGemRotating: ImageView
    private lateinit var gemWalletCard: LinearLayout
    private lateinit var txtGemWallet: TextView
    private lateinit var txtGemsLabel: TextView
    private lateinit var btnHowItWorks: Button
    private lateinit var txtGemsSpentToday: TextView
    private lateinit var txtDailyCapLabel: TextView
    private lateinit var barGemsSpent: View
    private lateinit var barGemsRemaining: View

    // Stats row
    private lateinit var txtChildProductiveTime: TextView
    private lateinit var txtChildEntertainmentTime: TextView

    // Earn gems card
    private lateinit var txtEarnGemsStatus: TextView

    // Detox break card
    private lateinit var txtDetoxBreakHint: TextView
    private lateinit var btnDetoxBreak: Button

    private val marqueeStartHandler = Handler(Looper.getMainLooper())

    private val quotePool = listOf(
        "Small steps daily lead to big changes.  — Kaizen",
        "Done is better than perfect.  — Sheryl Sandberg",
        "Discipline is choosing what you want most.  — Abraham Lincoln",
        "You become what you repeatedly do.  — Aristotle",
        "It always seems impossible until it is done.  — Nelson Mandela",
        "Start where you are. Use what you have.  — Theodore Roosevelt",
        "Focus on progress, not perfection.  — Bill Phillips",
        "The best time to begin is now.  — Chinese Proverb",
        "One step at a time is all it takes.  — Emily Dickinson",
        "Courage is doing it afraid.  — Joyce Meyer",
        "What you do today shapes tomorrow.  — Unknown",
        "A little progress every day adds up.  — Satya Nani",
        "Energy flows where attention goes.  — Tony Robbins",
        "Believe you can and you are halfway there.  — Theodore Roosevelt",
        "Your only limit is your mind.  — Unknown",
        "Act as if what you do makes a difference. It does.  — William James",
        "Dream big. Start small. Act now.  — Robin Sharma",
        "Growth begins at the edge of comfort.  — Unknown",
        "Make today count.  — Unknown",
        "Show up. That is half the battle.  — Woody Allen"
    )

    private fun startPillTicker(firstMessage: String) {
        val separator   = "   ·   "
        val allMessages = listOf(firstMessage) + quotePool.shuffled()
        val tickerText  = allMessages.joinToString(separator)
        txtDailyCapLabel.isSelected = false
        txtDailyCapLabel.text       = tickerText
        marqueeStartHandler.removeCallbacksAndMessages(null)
        marqueeStartHandler.postDelayed({
            txtDailyCapLabel.isSelected = true
        }, 2000L)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs        = KyzenPreferences(requireContext())
        rewardEngine = RewardEngine(prefs)
        usageTracker = UsageTracker(requireContext().applicationContext)

        bindViews(view)

        val today = LocalDate.now()
        txtChildDate.text = today.format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
        )

        val durationMinutes = prefs.detoxDurationMinutes
        txtDetoxBreakHint.text =
            "Step away for $durationMinutes minutes and earn ${KyzenPreferences.DEFAULT_DETOX_BONUS} gems."

        btnDetoxBreak.setOnClickListener { handleDetoxBreakTap() }

        btnHowItWorks.setOnClickListener { showGemEconomyDialog() }

        loadDashboardData()
    }

    override fun onResume() {
        super.onResume()
        val today = java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        )
        if (prefs.lastResetDate != today) {
            prefs.performDailyReset(today)
            // Safety net: mirrors the full daily reset in UsageMonitorService.checkAndPerformDailyReset()
            // including txRepository.clearAll() added in Session 10 — so gem history is always
            // wiped at midnight regardless of whether the service is alive when a new day begins.
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        UsageRepository(requireContext().applicationContext).pruneOldRecords()
                        GemTransactionRepository(requireContext().applicationContext).clearAll()
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Non-critical — log and continue
                }
            }
        }
        val durationMinutes = prefs.detoxDurationMinutes
        txtDetoxBreakHint.text =
            "Step away for $durationMinutes minutes and earn ${KyzenPreferences.DEFAULT_DETOX_BONUS} gems."
        loadDashboardData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        marqueeStartHandler.removeCallbacksAndMessages(null)
    }

    private fun bindViews(view: View) {
        txtChildDate              = view.findViewById(R.id.txtChildDate)
        imgGemRotating            = view.findViewById(R.id.imgGemRotating)
        gemWalletCard             = view.findViewById(R.id.gemWalletCard)
        txtGemWallet              = view.findViewById(R.id.txtGemWallet)
        txtGemsLabel              = view.findViewById(R.id.txtGemsLabel)

        // Load rotating gem GIF — Glide handles animation automatically
        Glide.with(this)
            .asGif()
            .load(R.drawable.gem_rotating)
            .into(imgGemRotating)
        txtGemsSpentToday         = view.findViewById(R.id.txtGemsSpentToday)
        txtDailyCapLabel          = view.findViewById(R.id.txtDailyCapLabel)
        barGemsSpent              = view.findViewById(R.id.barGemsSpent)
        barGemsRemaining          = view.findViewById(R.id.barGemsRemaining)
        txtChildProductiveTime    = view.findViewById(R.id.txtChildProductiveTime)
        txtChildEntertainmentTime = view.findViewById(R.id.txtChildEntertainmentTime)
        txtEarnGemsStatus         = view.findViewById(R.id.txtEarnGemsStatus)
        txtDetoxBreakHint         = view.findViewById(R.id.txtDetoxBreakHint)
        btnDetoxBreak             = view.findViewById(R.id.btnDetoxBreak)
        btnHowItWorks             = view.findViewById(R.id.btnHowItWorks)
    }

    /**
     * Returns a colour res ID based on gem count:
     *   0        → kyzen_red_medium  (empty — urgent)
     *   1–9      → kyzen_amber_dark  (low — caution)
     *   10–24    → kyzen_green_medium (healthy)
     *   25+      → kyzen_green_dark  (great — rich)
     */
    private fun gemColourRes(gems: Int): Int = when {
        gems == 0  -> R.color.kyzen_red_medium
        gems < 10  -> R.color.kyzen_amber_dark
        gems < 25  -> R.color.kyzen_green_medium
        else       -> R.color.kyzen_green_dark
    }

    private fun applyGemColour(gems: Int) {
        // backgroundTintList preserves the drawable shape, 20dp corners, and 2dp stroke
        // from bg_gem_wallet_card.xml — setBackgroundColor() would replace the entire
        // drawable and lose the border and rounded corners.
        val colour = ContextCompat.getColor(requireContext(), gemColourRes(gems))
        gemWalletCard.backgroundTintList = ColorStateList.valueOf(colour)
        txtGemWallet.setTextColor(ContextCompat.getColor(requireContext(), R.color.kyzen_white))
        txtGemsLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.kyzen_green_dark))
    }

    /**
     * Shows a polished custom dialog explaining the gem economy.
     * Styled with bg_dialog_card — matches the Kyzen dialog system exactly.
     */
    private fun showGemEconomyDialog() {
        val cap        = prefs.dailySpendingCap
        val detoxMin   = prefs.detoxDurationMinutes
        val detoxBonus = KyzenPreferences.DEFAULT_DETOX_BONUS

        // Inflate a custom view for the dialog
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_gem_economy, null)

        // Bind dynamic values
        dialogView.findViewById<TextView>(R.id.txtEarnRate1).text =
            "2 mins on a productive app  →  +1 gem"
        dialogView.findViewById<TextView>(R.id.txtEarnRate2).text =
            "${detoxMin}-min screen break  →  +${detoxBonus} gems"
        dialogView.findViewById<TextView>(R.id.txtSpendRate).text =
            "1 gem = 1 minute of entertainment"
        dialogView.findViewById<TextView>(R.id.txtDailyCap).text =
            "Daily limit: ${cap} gems. Unused gems carry forward."

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        dialogView.findViewById<Button>(R.id.btnGemEconomyClose).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun loadDashboardData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val appList = try {
                withContext(Dispatchers.IO) { usageTracker.getSanitizedUsageList() }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }

            val productiveMs = appList
                .filter { it.category == AppClassifier.AppCategory.PRODUCTIVE }
                .sumOf { it.usageDurationMillis }
            val entertainmentMs = appList
                .filter { it.category == AppClassifier.AppCategory.ENTERTAINMENT }
                .sumOf { it.usageDurationMillis }

            val productiveMins    = productiveMs / 60_000L
            val entertainmentMins = entertainmentMs / 60_000L

            val status = rewardEngine.quickCheck(productiveMins, entertainmentMins)

            txtGemWallet.text      = "${status.gemsInWallet}"
            txtGemsSpentToday.text = ""
            applyGemColour(status.gemsInWallet)

            val pillFirstMessage = when {
                productiveMins >= 30 -> "Incredible — ${productiveMins} mins of productive work!"
                productiveMins >= 10 -> "Great work — ${productiveMins} mins of productive work today."
                productiveMins >= 2  -> "${productiveMins} mins of productive work — great start!"
                status.gemsInWallet > 0 -> "${status.gemsInWallet} gems saved up — well done!"
                else -> "Do 2 mins of productive work to earn a gem."
            }
            startPillTicker(pillFirstMessage)

            txtChildProductiveTime.text    = "$productiveMins"
            txtChildEntertainmentTime.text = "$entertainmentMins"

            txtEarnGemsStatus.text = when {
                status.gemsInWallet == 0 && !prefs.isDetoxActive ->
                    "Wallet empty. Use a productive app for 2 mins to earn 1 gem, or take a screen break to earn ${KyzenPreferences.DEFAULT_DETOX_BONUS}."
                status.isDailyCapReached ->
                    "Daily allowance used. Your ${status.gemsInWallet} gems carry forward to tomorrow."
                status.gemsInWallet < 10 ->
                    "${status.gemsInWallet} gems remaining. Keep using productive apps to earn more."
                else ->
                    "${status.gemsInWallet} gems in your wallet. Earned through focused work."
            }
        }
    }

    private fun handleDetoxBreakTap() {
        val durationMinutes = prefs.detoxDurationMinutes
        val bonus           = KyzenPreferences.DEFAULT_DETOX_BONUS

        // Inflate custom dialog view
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_detox_confirm, null)

        dialogView.findViewById<TextView>(R.id.txtDialogDuration).text =
            "$durationMinutes mins detox · +$bonus 💎 gems"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<Button>(R.id.btnDialogConfirm).setOnClickListener {
            dialog.dismiss()
            startActivity(
                Intent(requireContext(), DetoxBreakActivity::class.java).apply {
                    putExtra(DetoxBreakActivity.EXTRA_DETOX_DURATION_MS, durationMinutes * 60_000L)
                }
            )
        }

        dialogView.findViewById<Button>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    companion object {
        fun newInstance() = DashboardFragment()
    }
}
