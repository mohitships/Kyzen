package com.binarybrigade.kyzen

/**
 * RewardEngine — Gems Economy Evaluation Engine (Post Phase 3)
 *
 * Implements the Gems-based digital wellbeing reward system aligned with
 * Self-Determination Theory (SDT) and Kaizen philosophy.
 *
 * ── Economy Rules ─────────────────────────────────────────────────────────────
 *   Earning  : 1 Gem per 2 minutes of productive app use (2:1 ratio, fixed)
 *   Earning  : +10 Gems on voluntary detox break completion (fixed)
 *   Earning  : Parent can gift any amount directly to wallet
 *   Spending : 1 Gem per 1 minute of entertainment app use (fixed)
 *   Cap      : Daily spending cap (default 60 gems) — enforced by spendGem()
 *   Wallet   : Persistent — gems carry forward across days
 *
 * Philosophical basis:
 *   The 2:1 productive-to-entertainment ratio means the child always does
 *   more real work than play. This maps to Kaizen (continuous improvement)
 *   and SDT competence/autonomy — the child earns their entertainment,
 *   feels capable, and builds genuine screen-time habits.
 *
 * This is a pure Kotlin logic class — Android dependencies injected via KyzenPreferences — making it
 * fully unit-testable with standard JUnit without an emulator.
 */
class RewardEngine(private val prefs: KyzenPreferences) {

    // ─── Reward Status Data Class ─────────────────────────────────────────────

    /**
     * Snapshot of the child's current gem economy state.
     *
     * @param productiveMinutes    Total productive app minutes today (from usage tracker)
     * @param entertainmentMinutes Total entertainment app minutes today (from usage tracker)
     * @param gemsInWallet         Current gem wallet balance (persistent, carry-forward)
     * @param gemsSpentToday       Gems spent on entertainment today (resets at midnight)
     * @param dailySpendingCap     Max gems spendable today (parent-configured)
     * @param gemsRemainingToday   How many more gems can be spent today
     *                             = max(0, dailySpendingCap - gemsSpentToday)
     * @param isGamePauseActive    True if parent has manually paused all entertainment
     * @param isDailyCapReached    True if child has hit their daily spending limit
     */
    data class RewardStatus(
        val productiveMinutes: Long,
        val entertainmentMinutes: Long,
        val gemsInWallet: Int,
        val gemsSpentToday: Int,
        val dailySpendingCap: Int,
        val gemsRemainingToday: Int,
        val isGamePauseActive: Boolean,
        val isDailyCapReached: Boolean
    )

    // ─── Core Evaluation Function ─────────────────────────────────────────────

    /**
     * Full async evaluation using Room database data.
     * Called by UsageMonitorService every poll cycle for intervention decisions.
     *
     * @param repository UsageRepository to fetch today's categorised usage data
     * @return RewardStatus snapshot of current gem economy state
     */
    suspend fun evaluate(repository: UsageRepository): RewardStatus {
        val productiveItems    = repository.getTodayUsageByCategory(AppClassifier.AppCategory.PRODUCTIVE)
        val entertainmentItems = repository.getTodayUsageByCategory(AppClassifier.AppCategory.ENTERTAINMENT)

        val productiveMs    = productiveItems.sumOf { it.usageDurationMillis }
        val entertainmentMs = entertainmentItems.sumOf { it.usageDurationMillis }

        return buildStatus(productiveMs / 60_000L, entertainmentMs / 60_000L)
    }

    // ─── Quick Synchronous Check ──────────────────────────────────────────────

    /**
     * Fast synchronous status check using pre-computed usage minutes.
     * Used by dashboard UI for display — does NOT hit the database.
     * For authoritative intervention decisions, always use evaluate() instead.
     *
     * @param productiveMinutesToday    Productive minutes computed from usage list
     * @param entertainmentMinutesToday Entertainment minutes computed from usage list
     */
    fun quickCheck(
        productiveMinutesToday: Long,
        entertainmentMinutesToday: Long = 0L
    ): RewardStatus = buildStatus(productiveMinutesToday, entertainmentMinutesToday)

    // ─── Internal Status Builder ──────────────────────────────────────────────

    private fun buildStatus(
        productiveMinutes: Long,
        entertainmentMinutes: Long
    ): RewardStatus {
        val wallet         = prefs.gemWallet
        val spentToday     = prefs.gemsSpentToday
        val cap            = prefs.dailySpendingCap
        val capReached     = prefs.isDailyCapReached()
        val remainingToday = maxOf(0, cap - spentToday)

        return RewardStatus(
            productiveMinutes    = productiveMinutes,
            entertainmentMinutes = entertainmentMinutes,
            gemsInWallet         = wallet,
            gemsSpentToday       = spentToday,
            dailySpendingCap     = cap,
            gemsRemainingToday   = remainingToday,
            isGamePauseActive    = prefs.isGamePauseEnabled,
            isDailyCapReached    = capReached
        )
    }

    // ─── Intervention Check ───────────────────────────────────────────────────

    /**
     * Returns true if a coaching intervention overlay should be shown.
     * Three independent conditions trigger the overlay:
     *   1. Parent manually paused all entertainment (kill-switch)
     *   2. Daily spending cap reached (child is done for today)
     *   3. Gem wallet is empty (child has no gems to spend)
     *
     * Used by UsageMonitorService to decide whether to fire the overlay.
     */
    fun shouldIntervene(): Boolean {
        if (prefs.isGamePauseEnabled) return true
        if (prefs.isDailyCapReached()) return true
        if (prefs.gemWallet <= 0) return true
        return false
    }
}
