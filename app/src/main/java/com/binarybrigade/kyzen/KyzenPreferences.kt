package com.binarybrigade.kyzen

import android.content.Context
import android.content.SharedPreferences

/**
 * KyzenPreferences — Local Settings & State Storage (Gems Economy — Post Phase 3)
 *
 * A clean wrapper around Android SharedPreferences for storing all
 * app-level configuration and daily state. No Firebase dependency —
 * all settings are stored locally on the device.
 *
 * ── Gems Economy Model ────────────────────────────────────────────────────────
 * Currency: 💎 Gems
 *
 * Earning paths:
 *   1. Productive app use → 1 Gem per 2 minutes (2:1 ratio, fixed in service)
 *   2. Voluntary detox break → +10 Gems on completion (fixed reward)
 *   3. Parent gift → parent can add any amount directly via Parent Dashboard
 *
 * Spending:
 *   - Entertainment app use → 1 Gem per minute (fixed)
 *   - Daily spending cap enforced — gems carry forward but only X gems
 *     can be SPENT per day (parent-configurable, default 60)
 *
 * Key design decisions:
 *   - NO daily top-up / starter credits — gems must be earned
 *   - NO exchange rate setting — fixed 2:1 ratio (productive:entertainment)
 *   - Gem WALLET is persistent across days (never reset on midnight)
 *   - Only gemsSpentToday resets at midnight
 *
 * Philosophical basis: Kaizen + SDT — work before play, always.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class KyzenPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "kyzen_prefs", Context.MODE_PRIVATE
    )

    companion object {
        // ── Keys ──────────────────────────────────────────────────────────────
        private const val KEY_GEM_WALLET          = "gem_wallet"
        private const val KEY_GEMS_SPENT_TODAY    = "gems_spent_today"
        private const val KEY_DAILY_SPENDING_CAP  = "daily_spending_cap"
        private const val KEY_LAST_RESET_DATE     = "last_reset_date"
        private const val KEY_GAME_PAUSE_ENABLED  = "game_pause_enabled"
        private const val KEY_PARENT_PIN_HASH     = "parent_pin_hash"
        private const val KEY_RECOVERY_PIN_HASH   = "recovery_pin_hash"
        private const val KEY_DETOX_DURATION      = "detox_duration_minutes"
        private const val KEY_DETOX_IS_ACTIVE     = "detox_is_active"
        private const val KEY_DETOX_START_MS      = "detox_start_ms"
        private const val KEY_DETOX_DURATION_MS   = "detox_duration_ms"
        // Guards against double-award when both DetoxBreakActivity and
        // UsageMonitorService race to complete the same detox session.
        private const val KEY_DETOX_BONUS_AWARDED = "detox_bonus_awarded"
        private const val KEY_FIRST_LAUNCH_DONE   = "first_launch_done"
        private const val KEY_GEMS_EARNED_TODAY   = "gems_earned_today"
        // Prefix for per-package gem award tracking — full key = "gems_awarded_pkg_com.example.app"
        // Stores how many complete 2-min blocks have been awarded for each productive app today.
        // Reset to 0 on daily reset. Enables the cumulative gem model in UsageMonitorService.
        private const val KEY_GEMS_AWARDED_PKG_PREFIX = "gems_awarded_pkg_"

        // Prefix for per-package poll-confirmed ms tracking
        // Full key = "confirmed_ms_pkg_com.example.app"
        // Persists confirmed foreground ms across app switches for cross-session accumulation.
        private const val KEY_CONFIRMED_MS_PKG_PREFIX     = "confirmed_ms_pkg_"

        // Prefix for per-package poll-confirmed entertainment ms tracking
        private const val KEY_CONFIRMED_ENT_MS_PKG_PREFIX = "confirmed_ent_ms_pkg_"

        // Prefix for per-package gems-spent tracking
        private const val KEY_GEMS_SPENT_PKG_PREFIX        = "gems_spent_pkg_"

        /** Gems gifted on very first app launch — one time only, never repeated */
        const val FIRST_LAUNCH_BONUS              = 5
        /** Maximum gems a parent can deduct in a single operation */
        const val MAX_DEDUCT_AMOUNT               = 200
        /** Maximum gems a parent can gift in a single operation */
        const val MAX_GIFT_AMOUNT                 = 200
        const val LOW_GEM_THRESHOLD               = 3     // Push warning when wallet ≤ this value
        /**
         * Maximum gems a child can EARN from productive app use per day.
         * Prevents abuse (leaving a productive app open all day).
         * Detox bonus and parent gifts are NOT counted against this cap.
         * 120 gems = 240 minutes (4 hours) of productive use at 2:1 ratio → fair daily limit.
         */
        const val MAX_GEMS_EARNED_PER_DAY         = 120

        // ── Economy Constants (fixed, not parent-configurable) ─────────────
        /**
         * Gems awarded per completed detox break.
         * Fixed at 10 — equivalent to 10 minutes of entertainment credit.
         */
        const val DEFAULT_DETOX_BONUS         = 10

        /**
         * Default detox break duration in minutes.
         * Parent-configurable (5 / 10 / 15 / 20 min).
         */
        const val DEFAULT_DETOX_DURATION      = 10

        /**
         * Default daily spending cap in gems.
         * Child can spend at most this many gems on entertainment per day.
         * Remaining gems carry forward to the next day.
         * Parent-configurable (30 / 60 / 90 / 120).
         */
        const val DEFAULT_DAILY_SPENDING_CAP  = 60

        /**
         * Productive app seconds required to earn 1 gem.
         * 120 seconds = 2 minutes productive → 1 gem.
         * Enforces the 2:1 productive-to-entertainment ratio.
         * Fixed in code — not parent-configurable (keeps UX simple).
         */
        const val PRODUCTIVE_SECONDS_PER_GEM  = 120L

        /**
         * Entertainment seconds consumed per gem spent.
         * 60 seconds = 1 minute entertainment → 1 gem.
         */
        const val ENTERTAINMENT_SECONDS_PER_GEM = 60L
    }

    // ─── Gem Wallet ───────────────────────────────────────────────────────────
    // Persistent across days. Never reset at midnight.
    // Increases via productive use, detox completion, parent gifts.
    // Decreases via entertainment spending.

    var gemWallet: Int
        get() = prefs.getInt(KEY_GEM_WALLET, 0)
        set(value) = prefs.edit().putInt(KEY_GEM_WALLET, maxOf(0, value)).apply()

    /**
     * Checks if this is the very first app launch and awards the one-time
     * welcome bonus of 5 gems if so. Safe to call on every launch —
     * the flag ensures it only fires once ever.
     *
     * Uses a single atomic commit to prevent the race condition where two
     * simultaneous calls both read KEY_FIRST_LAUNCH_DONE=false and both
     * award the bonus. The commit() is synchronous (unlike apply()) so the
     * flag write completes before any other thread can check it.
     */
    fun checkAndAwardFirstLaunchBonus() {
        // Check if the flag already exists BEFORE writing anything.
        // prefs.contains() is the correct way to detect first-ever launch —
        // commit() returns true on every successful write, not just the first.
        if (!prefs.contains(KEY_FIRST_LAUNCH_DONE)) {
            // Use commit() (synchronous) instead of apply() (async) so that if
            // onCreate() and onResume() both call this in rapid succession,
            // the second call sees the flag already set and skips the bonus.
            //
            // Bug 7 Fix: Previously used putInt(KEY_GEM_WALLET, FIRST_LAUNCH_BONUS)
            // which SET the wallet TO 5 — overwriting any gems already present.
            // Now we mark the flag first (synchronously), then use addGems() which
            // ADDS to whatever is currently in the wallet (always 0 on fresh install,
            // but safe if any edge case caused gems to exist before first launch).
            val flagWritten = prefs.edit()
                .putBoolean(KEY_FIRST_LAUNCH_DONE, true)
                .commit()
            if (flagWritten) {
                addGems(FIRST_LAUNCH_BONUS)
            }
        }
    }

    /**
     * Adds gems to the child's wallet.
     * Used for: productive earning, detox bonus, parent gifts.
     * @param amount Number of gems to add (must be > 0, capped at MAX_GIFT_AMOUNT)
     */
    fun addGems(amount: Int) {
        if (amount <= 0) return
        // No cap here — addGems() is a shared primitive used for productive earning (1 gem),
        // detox bonus (10 gems), and parent gifts. The MAX_GIFT_AMOUNT cap is enforced at
        // the call site in ParentDashboardActivity.showCustomGiftDialog(), which is the
        // correct layer. A hidden cap here would silently truncate legitimate awards.
        gemWallet += amount
    }

    /**
     * Adjusts the child's gem balance downward to help reset expectations.
     * Called by parent from Parent Dashboard. Wallet is clamped to 0 (never negative).
     * @param amount Number of gems to remove (must be > 0, capped at MAX_DEDUCT_AMOUNT)
     * @return Actual amount removed (may be less if wallet didn't have enough)
     */
    fun deductGems(amount: Int): Int {
        if (amount <= 0) return 0
        val capped  = minOf(amount, MAX_DEDUCT_AMOUNT)
        val current = gemWallet
        val actual  = minOf(capped, current) // can't deduct more than wallet has
        gemWallet   = current - actual
        return actual
    }

    /**
     * Attempts to spend 1 gem from the wallet for entertainment use.
     * Fails (returns false) if:
     *   - Daily spending cap is already reached
     *   - Wallet is empty (no gems to spend)
     *
     * @return true if gem was successfully spent, false if blocked
     */
    fun spendGem(): Boolean {
        if (isDailyCapReached()) return false
        if (gemWallet <= 0) return false
        gemWallet -= 1
        gemsSpentToday += 1
        return true
    }

    /**
     * Returns true if the child has reached their daily entertainment spending cap.
     * Even if the wallet has gems, no more can be spent today.
     */
    fun isDailyCapReached(): Boolean = gemsSpentToday >= dailySpendingCap

    // ─── Daily Spending Tracker ───────────────────────────────────────────────
    // Resets to 0 at midnight. Tracks gems SPENT (not earned) today.

    var gemsSpentToday: Int
        get() = prefs.getInt(KEY_GEMS_SPENT_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_GEMS_SPENT_TODAY, maxOf(0, value)).apply()

    // ─── Daily Earning Tracker ────────────────────────────────────────────────
    // Resets to 0 at midnight. Tracks gems EARNED from productive app use today.
    // Does NOT count detox bonus or parent gifts — those are uncapped.

    var gemsEarnedToday: Int
        get() = prefs.getInt(KEY_GEMS_EARNED_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_GEMS_EARNED_TODAY, maxOf(0, value)).apply()

    /**
     * Returns true if the child has hit the daily productive earning cap.
     * Earning from productive apps is blocked — detox/parent gifts still work.
     */
    fun isDailyEarningCapReached(): Boolean = gemsEarnedToday >= MAX_GEMS_EARNED_PER_DAY

    /**
     * Awards 1 gem from productive app use, respecting the daily earning cap.
     * @return true if gem was awarded, false if daily cap already reached
     */
    fun earnProductiveGem(): Boolean {
        if (isDailyEarningCapReached()) return false
        gemsEarnedToday += 1
        addGems(1)
        return true
    }

    // ─── Per-Package Poll-Confirmed Ms Tracking ──────────────────────────────
    // Stores total poll-confirmed foreground ms per productive package today.
    // Persists across app switches so cross-session accumulation works:
    // 1 min Coursera + switch + 1 min Coursera = 120s confirmed = 1 gem ✅

    fun getConfirmedProductiveMsForPackage(packageName: String): Long =
        prefs.getLong(KEY_CONFIRMED_MS_PKG_PREFIX + packageName, 0L)

    fun setConfirmedProductiveMsForPackage(packageName: String, ms: Long) {
        prefs.edit().putLong(KEY_CONFIRMED_MS_PKG_PREFIX + packageName, ms).apply()
    }

    // ─── Per-Package Poll-Confirmed Entertainment Ms Tracking ────────────────
    // Mirrors the productive ms tracking — same model, same guarantees.

    fun getConfirmedEntertainmentMsForPackage(packageName: String): Long =
        prefs.getLong(KEY_CONFIRMED_ENT_MS_PKG_PREFIX + packageName, 0L)

    fun setConfirmedEntertainmentMsForPackage(packageName: String, ms: Long) {
        prefs.edit().putLong(KEY_CONFIRMED_ENT_MS_PKG_PREFIX + packageName, ms).apply()
    }

    fun getGemsSpentTodayForPackage(packageName: String): Int =
        prefs.getInt(KEY_GEMS_SPENT_PKG_PREFIX + packageName, 0)

    /**
     * Atomically spends new gems for entertainment use, aligned with the
     * poll-confirmed accumulator model. Only spends genuinely NEW complete
     * 60s blocks — never re-spends for the same block.
     *
     * @param packageName  Entertainment app package name
     * @param gemsOwed     Total complete 60s blocks confirmed today for this pkg
     * @return Number of new gems spent (0 if nothing new or wallet empty)
     */
    fun spendNewEntertainmentGems(packageName: String, gemsOwed: Int): Int {
        val alreadySpent = getGemsSpentTodayForPackage(packageName)
        val newSpend = (gemsOwed - alreadySpent).coerceAtLeast(0)
        if (newSpend <= 0) return 0

        // Spend each gem individually — check wallet + daily cap per gem
        var actualSpent = 0
        repeat(newSpend) {
            if (spendGem()) actualSpent++
            else return@repeat // wallet empty or cap reached — stop
        }

        // ── Ghost-debt fix ────────────────────────────────────────────────────
        // Even if we couldn't spend all newSpend gems (wallet hit zero or daily
        // cap reached mid-loop), we MUST record gemsOwed as the new baseline,
        // not just alreadySpent + actualSpent.
        //
        // Why: confirmedEntertainmentMs keeps accumulating every poll cycle even
        // while the overlay is showing and spendGem() is returning false. This
        // silently widens the gap between gemsOwed and the stored alreadySpent.
        // When the parent later tops up the wallet, the very first poll sees a
        // huge gemsOwed - alreadySpent delta and drains all those "phantom" gems
        // in one shot (e.g. 10 gems gone instantly in under a minute).
        //
        // Fix: always write gemsOwed as the new per-package baseline. Gems we
        // couldn't spend (because wallet was empty) are forgiven — the child is
        // already being blocked by the overlay. The parent top-up starts fresh.
        prefs.edit()
            .putInt(KEY_GEMS_SPENT_PKG_PREFIX + packageName, gemsOwed)
            .apply()

        return actualSpent
    }

    // ─── Per-Package Gem Award Tracking (Cumulative Model) ───────────────────
    // Tracks how many complete 2-minute productive blocks have been awarded
    // for each package today. Key: "gems_awarded_pkg_[packageName]"
    // This is the single source of truth for the cumulative gem model —
    // prevents double-awarding when the child switches apps and returns.

    /**
     * Returns the number of gem-blocks already awarded today for this package.
     * Returns 0 if no gems have been awarded yet today for this package.
     */
    fun getGemsAwardedTodayForPackage(packageName: String): Int {
        return prefs.getInt(KEY_GEMS_AWARDED_PKG_PREFIX + packageName, 0)
    }

    /**
     * Atomically checks if a new gem should be awarded for this package,
     * awards it if so, and increments the per-package counter.
     *
     * @param packageName  The productive app's package name
     * @param gemsOwed     Total complete 2-min blocks earned today (from live OS query)
     * @return Number of new gems awarded (0 if nothing new, 1+ if new blocks completed)
     */
    fun awardNewProductiveGems(packageName: String, gemsOwed: Int): Int {
        val alreadyAwarded = getGemsAwardedTodayForPackage(packageName)
        val newGems = (gemsOwed - alreadyAwarded).coerceAtLeast(0)
        if (newGems <= 0) return 0

        // Respect daily earning cap — only award what the cap allows
        val remainingCap = (MAX_GEMS_EARNED_PER_DAY - gemsEarnedToday).coerceAtLeast(0)
        val toAward = minOf(newGems, remainingCap)
        if (toAward <= 0) return 0

        // Update per-package counter + daily total + wallet atomically
        prefs.edit()
            .putInt(KEY_GEMS_AWARDED_PKG_PREFIX + packageName, alreadyAwarded + toAward)
            .putInt(KEY_GEMS_EARNED_TODAY, gemsEarnedToday + toAward)
            .putInt(KEY_GEM_WALLET, gemWallet + toAward)
            .apply()
        return toAward
    }

    // ─── Daily Spending Cap ───────────────────────────────────────────────────
    // Maximum gems the child can SPEND on entertainment per day.
    // Gems above the cap carry forward but cannot be spent until tomorrow.

    var dailySpendingCap: Int
        get() = prefs.getInt(KEY_DAILY_SPENDING_CAP, DEFAULT_DAILY_SPENDING_CAP)
        set(value) {
            // Clamp to valid range: 10–300 gems. Prevents parent accidentally setting
            // 0 (blocks all entertainment forever) or negative values.
            val clamped = value.coerceIn(10, 300)
            prefs.edit().putInt(KEY_DAILY_SPENDING_CAP, clamped).apply()
        }

    // ─── Last Reset Date ──────────────────────────────────────────────────────
    // ISO date string of the last daily reset. Compared against today's date.

    var lastResetDate: String
        get() = prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RESET_DATE, value).apply()

    // ─── Game Pause State ─────────────────────────────────────────────────────
    // When true, ALL entertainment apps are blocked regardless of gem balance.

    var isGamePauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_GAME_PAUSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GAME_PAUSE_ENABLED, value).apply()

    // ─── Parent PIN (SHA-256 Hashed) ──────────────────────────────────────────

    fun isPinSet(): Boolean = prefs.getString(KEY_PARENT_PIN_HASH, null) != null

    fun setPin(pin: String) {
        require(pin.isNotBlank()) { "PIN must not be empty or blank" }
        require(pin.length >= 4)  { "PIN must be at least 4 digits" }
        val hash = sha256(pin)
        prefs.edit().putString(KEY_PARENT_PIN_HASH, hash).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PARENT_PIN_HASH, null) ?: return false
        return storedHash == sha256(pin)
    }

    // ─── Recovery PIN ─────────────────────────────────────────────────────────
    // Set once during first-time PIN creation. Stored as SHA-256 hash.
    // Used to reset the main PIN if the parent forgets it.
    // Child cannot exploit this — they'd need to know the recovery PIN too.

    fun isRecoveryPinSet(): Boolean = prefs.getString(KEY_RECOVERY_PIN_HASH, null) != null

    fun setRecoveryPin(pin: String) {
        require(pin.isNotBlank()) { "Recovery PIN must not be empty" }
        require(pin.length >= 4)  { "Recovery PIN must be at least 4 digits" }
        prefs.edit().putString(KEY_RECOVERY_PIN_HASH, sha256(pin)).apply()
    }

    fun verifyRecoveryPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_RECOVERY_PIN_HASH, null) ?: return false
        return storedHash == sha256(pin)
    }

    /** Clears the main PIN only — recovery PIN stays intact for future resets. */
    fun clearMainPin() {
        prefs.edit().remove(KEY_PARENT_PIN_HASH).apply()
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ─── Detox Session State ──────────────────────────────────────────────────
    // Persisted flags read by UsageMonitorService to enforce the detox overlay.

    var isDetoxActive: Boolean
        get() = prefs.getBoolean(KEY_DETOX_IS_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_DETOX_IS_ACTIVE, value).apply()

    var detoxStartMs: Long
        get() = prefs.getLong(KEY_DETOX_START_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_DETOX_START_MS, value).apply()

    var detoxTotalDurationMs: Long
        get() = prefs.getLong(KEY_DETOX_DURATION_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_DETOX_DURATION_MS, value).apply()

    fun startDetoxSession(durationMs: Long) {
        prefs.edit()
            .putBoolean(KEY_DETOX_IS_ACTIVE, true)
            .putLong(KEY_DETOX_START_MS, System.currentTimeMillis())
            .putLong(KEY_DETOX_DURATION_MS, durationMs)
            .apply()
    }

    fun endDetoxSession() {
        prefs.edit()
            .putBoolean(KEY_DETOX_IS_ACTIVE, false)
            .putLong(KEY_DETOX_START_MS, 0L)
            .putLong(KEY_DETOX_DURATION_MS, 0L)
            // Reset the bonus-awarded flag so the next detox session can award again
            .putBoolean(KEY_DETOX_BONUS_AWARDED, false)
            .apply()
    }

    // ─── Detox Break Duration ─────────────────────────────────────────────────
    // Parent-configurable: 5 / 10 / 15 / 20 minutes.

    var detoxDurationMinutes: Int
        get() = prefs.getInt(KEY_DETOX_DURATION, DEFAULT_DETOX_DURATION)
        set(value) = prefs.edit().putInt(KEY_DETOX_DURATION, value).apply()

    /**
     * Awards the detox completion bonus directly to the gem wallet.
     * Called by DetoxBreakActivity on successful completion.
     */
    fun awardDetoxBonus() {
        // Idempotent guard — prevents double-award when both DetoxBreakActivity
        // and UsageMonitorService race to complete the same detox session.
        // Uses synchronous commit() so both threads see the write immediately.
        val alreadyAwarded = prefs.getBoolean(KEY_DETOX_BONUS_AWARDED, false)
        if (alreadyAwarded) return
        val committed = prefs.edit()
            .putBoolean(KEY_DETOX_BONUS_AWARDED, true)
            .commit() // synchronous — ensures only one winner in the race
        if (committed) {
            addGems(DEFAULT_DETOX_BONUS)
        }
    }

    // ─── Daily Reset ──────────────────────────────────────────────────────────

    /**
     * Resets daily spending counter at midnight.
     * IMPORTANT: gemWallet is NOT reset — gems carry forward.
     * Only gemsSpentToday resets so the daily cap refreshes.
     */
    fun performDailyReset(todayDate: String) {
        // Use commit() (synchronous) so that the next read of lastResetDate in the
        // 5-second polling loop sees the updated date immediately. With apply() (async),
        // the next poll cycle could read the old date and trigger a second reset.
        val editor = prefs.edit()
            .putString(KEY_LAST_RESET_DATE, todayDate)
            .putInt(KEY_GEMS_SPENT_TODAY, 0)
            .putInt(KEY_GEMS_EARNED_TODAY, 0)

        // Clear all per-package counters — new day, fresh start.
        // Remove productive ms, entertainment ms, gems-awarded and gems-spent keys atomically.
        prefs.all.keys
            .filter {
                it.startsWith(KEY_GEMS_AWARDED_PKG_PREFIX)      ||
                it.startsWith(KEY_CONFIRMED_MS_PKG_PREFIX)      ||
                it.startsWith(KEY_CONFIRMED_ENT_MS_PKG_PREFIX)  ||
                it.startsWith(KEY_GEMS_SPENT_PKG_PREFIX)
            }
            .forEach { editor.remove(it) }

        editor.commit()
        // gemWallet intentionally NOT cleared — persistent carry-forward
    }
}
