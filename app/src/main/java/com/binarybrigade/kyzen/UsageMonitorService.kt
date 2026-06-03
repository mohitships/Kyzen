package com.binarybrigade.kyzen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.animation.ObjectAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UsageMonitorService — Persistent Background Foreground Session Monitor
 *
 * ForegroundService that runs continuously with a visible notification.
 * Polls the foreground app every 2 seconds using UsageStatsManager.queryEvents().
 *
 * ── Gems Economy Integration ──────────────────────────────────────────────────
 * EARNING:  1 gem awarded per 2 minutes (120s) of PRODUCTIVE app use.
 *           Tracked via productiveSessionStartMs — resets on app switch.
 *           Gems added to wallet via prefs.addGems(1).
 *
 * SPENDING: 1 gem deducted per 1 minute (60s) of ENTERTAINMENT app use.
 *           Tracked via entertainmentSessionStartMs.
 *           prefs.spendGem() returns false if wallet empty or daily cap reached.
 *           → Intervention overlay fired immediately on false return.
 *
 * OVERLAY TRIGGERS (3 independent conditions):
 *   1. Parent manually paused entertainment (kill-switch)
 *   2. Daily spending cap reached (gems carry forward but done for today)
 *   3. Gem wallet is empty (no gems to spend)
 *
 * DETOX ENFORCEMENT: If isDetoxActive, block any non-Kyzen app with the
 *   detox overlay. Timer validated via wall-clock timestamps.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID_MONITOR    = "kyzen_monitor_channel"
        const val CHANNEL_ID_COACHING   = "kyzen_coaching_channel"
        const val NOTIF_ID_MONITOR      = 1001
        const val NOTIF_ID_COACHING     = 1002
        const val POLL_INTERVAL_MS      = 2000L   // 2-second polling interval
        const val ACTION_START_SERVICE  = "com.binarybrigade.kyzen.START_MONITOR"

        /** Static reference to the running service instance.
         *  Used by OverlayActivity to call back into the service for
         *  go-home and grace-period actions. */
        var instance: UsageMonitorService? = null
            private set

        /**
         * High-quality mood-lifting quotes for both overlays.
         * Rotate every 5 seconds after a 2-second initial delay.
         * Curated to feel warm, human, and genuinely uplifting — not preachy.
         * No emoji in quote text. Fresh, never overused.
         */
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

    private lateinit var prefs: KyzenPreferences
    private lateinit var repository: UsageRepository
    private lateinit var rewardEngine: RewardEngine
    private lateinit var txRepository: GemTransactionRepository

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Foreground tracking

    // Classification change tracking — detects when the SAME package changes
    // classification (e.g., YouTube ENTERTAINMENT → PRODUCTIVE when the user
    // switches from MrBeast to Khan Academy). Without this, the accumulator
    // flags and ms values are stale from the previous classification, causing
    // missing gem awards or incorrect spending.
    private var lastClassification: AppClassifier.AppCategory = AppClassifier.AppCategory.NEUTRAL
    private var currentForegroundPackage: String = ""

    // Entertainment spending tracker — poll-confirmed accumulator model
    // Mirrors the productive model exactly. activeEntertainmentMs is persisted
    // per package in prefs so cross-session accumulation works correctly.
    private var entertainmentSessionStartMs: Long  = 0L
    private var activeEntertainmentMs: Long        = 0L
    private var lastPollWasEntertainment: Boolean  = false
    private var lastGemSpentMs: Long               = 0L // kept for re-entry detection only

    // Productive earning tracker — poll-confirmed accumulator model
    // activeProductiveMs tracks milliseconds of CONFIRMED foreground time per package.
    // Only incremented when the 2s poll confirms the app is STILL in foreground.
    // Resets when a different app comes to foreground. Persisted across app switches
    // via gemsAlreadyAwarded in KyzenPreferences (per-package, per-day).
    private var productiveSessionStartMs: Long = 0L
    private var activeProductiveMs: Long       = 0L   // confirmed foreground ms this session
    private var lastPollWasProductive: Boolean = false // was last poll productive for same pkg?

    private var isMonitoring: Boolean = false
    private var lastSentHomeTimeMs: Long = 0L // Tracks the exact moment we sent the user home

    // WindowManager overlays
    private var overlayView: View? = null
    private var detoxOverlayView: View? = null
    private var detoxCancelOverlayView: View? = null      // cancel confirmation overlay
    private var detoxOverlayHandler: Handler? = null
    private var overlayQuoteHandler: Handler? = null      // quote rotation for coaching overlay
    private var detoxQuoteHandler: Handler? = null        // quote rotation for detox overlay
    private var overlayQuoteIndex: Int = 0                // current quote index, shuffled per show
    private lateinit var windowManager: WindowManager

    // Low-gem warning — fired once per entertainment session when gems drop to threshold
    private var lowGemWarningSentThisSession: Boolean = false

    // Escalating intervention — counts how many times overlay has fired this session
    private var interventionCountThisSession: Int = 0

    // Grace period state — true while the child has 15s to wrap up in the app.
    // Polling loop skips intervention while this is true.
    private var inGracePeriod: Boolean = false
    private var gracePeriodPackage: String = ""
    private var gracePeriodHandler: Handler? = null

    // Per-package grace tracking — each package gets ONE "Give me 15s" opportunity
    // per service lifetime (i.e. per day). Resets only on service restart.
    // Stored as a Set so switching away and back doesn't re-grant grace.
    private val graceUsedPackages: MutableSet<String> = mutableSetOf()

    // Tracks the previous state of isGamePauseEnabled so we can detect
    // when the parent toggles entertainment back ON — clears graceUsedPackages
    // so the child gets a fresh "Give me 15 seconds" on first open after resume.
    private var lastKnownPauseState: Boolean = false

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs        = KyzenPreferences(this)
        repository   = UsageRepository(this)
        rewardEngine = RewardEngine(prefs)
        txRepository = GemTransactionRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannels()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID_MONITOR, buildMonitorNotification())
        if (!isMonitoring) {
            isMonitoring = true
            serviceScope.launch { runMonitoringLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlay()
        removeDetoxOverlay()
        gracePeriodHandler?.removeCallbacksAndMessages(null)
        gracePeriodHandler = null
        serviceJob.cancel()
        serviceScope.cancel()
        isMonitoring = false
    }

    // ─── Core Monitoring Loop ─────────────────────────────────────────────────

    private suspend fun runMonitoringLoop() {
        while (serviceScope.isActive) {
            try {
                // 1. Check for midnight day boundary → daily reset
                checkAndPerformDailyReset()

                // 2. Global Audio Mute (The Ultimate Defender)
                // If gems are 0 or the parent hit pause, the device simply CANNOT play media.
                // We enforce this globally every 2 seconds. However, if the child has explicitly 
                // been granted a 15-second grace period, we pause the muting so they can wrap up.
                if (rewardEngine.shouldIntervene() && !inGracePeriod) {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (audioManager.isMusicActive) {
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    }
                }

                // 3. Detect entertainment pause toggle — if parent just RESUMED
                // entertainment (pause was ON, now OFF), clear graceUsedPackages
                // so the child gets a fresh "Give me 15 seconds" on first open.
                val currentPauseState = prefs.isGamePauseEnabled
                if (lastKnownPauseState && !currentPauseState) {
                    // Pause just turned OFF — fresh start for grace eligibility
                    graceUsedPackages.clear()
                }
                lastKnownPauseState = currentPauseState

                // 3. Detect current foreground app
                val foregroundPackage = getCurrentForegroundPackage()

                // ── Detox break enforcement ────────────────────────────────
                if (prefs.isDetoxActive) {
                    val elapsedMs = System.currentTimeMillis() - prefs.detoxStartMs
                    if (elapsedMs >= prefs.detoxTotalDurationMs) {
                        // Timer completed while DetoxBreakActivity was not visible.
                        // endDetoxSession() clears isDetoxActive FIRST, then award bonus.
                        // The outer if (prefs.isDetoxActive) above already guards against
                        // re-entry — the redundant inner check has been removed.
                        prefs.endDetoxSession()   // clear flag FIRST
                        prefs.awardDetoxBonus()   // then award
                        withContext(Dispatchers.Main) { removeDetoxOverlay() }
                    } else if (foregroundPackage.isNotEmpty() &&
                               foregroundPackage != applicationContext.packageName) {
                        val remaining = prefs.detoxTotalDurationMs - elapsedMs
                        withContext(Dispatchers.Main) { showDetoxOverlay(remaining) }
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    delay(POLL_INTERVAL_MS)
                    continue
                } else {
                    if (detoxOverlayView != null) {
                        withContext(Dispatchers.Main) { removeDetoxOverlay() }
                    }
                }

                // ── App tracking & Gems economy ────────────────────────────
                if (foregroundPackage.isNotEmpty() &&
                    foregroundPackage != applicationContext.packageName) {

                    val appName        = getAppName(foregroundPackage)
                    val classification = AppClassifier.classify(foregroundPackage, appName)
                    val now            = System.currentTimeMillis()

                    // Debug: log classification result for diagnosing YouTube content detection
                    android.util.Log.d("UsageMonitorService",
                        "Foreground: $foregroundPackage ($appName) → ${classification.category} conf=${classification.confidence}")

                    // -- Classification Change Detection --
                    // When the SAME package changes classification (e.g., YouTube
                    // ENTERTAINMENT -> PRODUCTIVE when user switches from MrBeast to
                    // Khan Academy), we must reset accumulator flags and load the
                    // correct ms values -- just like a package switch.
                    if (currentForegroundPackage == foregroundPackage &&
                        lastClassification != classification.category &&
                        lastClassification != AppClassifier.AppCategory.NEUTRAL) {
                        android.util.Log.d("UsageMonitorService",
                            "Classification changed within $foregroundPackage: $lastClassification -> ${classification.category}")
                        when (classification.category) {
                            AppClassifier.AppCategory.PRODUCTIVE -> {
                                // ENTERTAINMENT -> PRODUCTIVE: reset flags, load productive ms
                                lastPollWasProductive = false
                                lastPollWasEntertainment = false
                                activeProductiveMs = prefs.getConfirmedProductiveMsForPackage(foregroundPackage)
                            }
                            AppClassifier.AppCategory.ENTERTAINMENT -> {
                                // PRODUCTIVE -> ENTERTAINMENT: reset flags, load entertainment ms
                                lastPollWasProductive = false
                                lastPollWasEntertainment = false
                                activeEntertainmentMs = prefs.getConfirmedEntertainmentMsForPackage(foregroundPackage)
                            }
                            AppClassifier.AppCategory.NEUTRAL -> {
                                // Any -> NEUTRAL: pause both accumulators
                                lastPollWasProductive = false
                                lastPollWasEntertainment = false
                            }
                        }
                    }
                    lastClassification = classification.category

                    when (classification.category) {

                        AppClassifier.AppCategory.ENTERTAINMENT -> {
                            // ── Poll-Confirmed Accumulator Model (Entertainment) ──────────
                            // Mirrors the productive model exactly — only counts time our
                            // 2s poll CONFIRMS the app is in foreground. No session timers.

                            if (currentForegroundPackage != foregroundPackage) {
                                // Switched TO this entertainment app
                                currentForegroundPackage     = foregroundPackage
                                entertainmentSessionStartMs  = now
                                lastGemSpentMs               = now // re-entry detection sentinel
                                productiveSessionStartMs     = 0L
                                lastPollWasProductive        = false
                                lastPollWasEntertainment     = false
                                lowGemWarningSentThisSession = false
                                interventionCountThisSession = 0
                                // Load already-confirmed entertainment ms for this package
                                activeEntertainmentMs = prefs.getConfirmedEntertainmentMsForPackage(foregroundPackage)
                                // Cancel grace period if it was for a different package
                                if (inGracePeriod && gracePeriodPackage != foregroundPackage) {
                                    gracePeriodHandler?.removeCallbacksAndMessages(null)
                                    gracePeriodHandler = null
                                    inGracePeriod      = false
                                    gracePeriodPackage = ""
                                }
                            } else if (lastGemSpentMs == 0L) {
                                // Re-entry to SAME entertainment app after visiting neutral/productive.
                                // lastGemSpentMs was cleared by neutral/productive block — reset session.
                                entertainmentSessionStartMs  = now
                                lastGemSpentMs               = now
                                lastPollWasEntertainment     = false
                                lowGemWarningSentThisSession = false
                                interventionCountThisSession = 0
                                activeEntertainmentMs = prefs.getConfirmedEntertainmentMsForPackage(foregroundPackage)
                            }

                            // Add one poll interval of confirmed entertainment foreground time
                            if (lastPollWasEntertainment) {
                                activeEntertainmentMs += POLL_INTERVAL_MS
                                prefs.setConfirmedEntertainmentMsForPackage(foregroundPackage, activeEntertainmentMs)
                            }
                            lastPollWasEntertainment = true

                            // ── Low-gem warning notification ──────────────────
                            if (!lowGemWarningSentThisSession &&
                                prefs.gemWallet in 1..KyzenPreferences.LOW_GEM_THRESHOLD &&
                                !rewardEngine.shouldIntervene()) {
                                lowGemWarningSentThisSession = true
                                withContext(Dispatchers.Main) { fireLowGemWarning() }
                            }

                            // ── Coaching intervention check ───────────────────
                            if (rewardEngine.shouldIntervene()) {
                                if (inGracePeriod && gracePeriodPackage == foregroundPackage) {
                                    continue
                                }
                                // ── Ghost-debt fix (accumulator reset) ───────────
                                // Reset the in-memory and persisted confirmed ms for
                                // this package to zero. The overlay is now showing —
                                // the child is blocked. Any ms that accumulated while
                                // spendGem() was returning false are already forgiven
                                // in spendNewEntertainmentGems() via the gemsOwed
                                // baseline write. Resetting here ensures that after a
                                // parent top-up the child gets a genuinely fresh start:
                                // activeEntertainmentMs won't carry the old high value
                                // into the next session and immediately trigger a new
                                // huge gemsOwed calculation.
                                activeEntertainmentMs = 0L
                                prefs.setConfirmedEntertainmentMsForPackage(foregroundPackage, 0L)
                                // ── Session state reset on block ─────────────────
                                // Bug A fix: re-arm the low-gem warning so the child
                                // gets a fresh heads-up warning after a parent top-up.
                                lowGemWarningSentThisSession = false
                                // Bug B fix: reset escalation counter so the first
                                // intervention after a top-up uses the calm "mindful
                                // break" tone, not the harsh "Time to step away" tone.
                                interventionCountThisSession = 0
                                withContext(Dispatchers.Main) {
                                    fireCoachingIntervention(foregroundPackage, appName)
                                }
                                continue
                            }

                            // ── Spend gems for confirmed entertainment time ────
                            val entIntervalMs = KyzenPreferences.ENTERTAINMENT_SECONDS_PER_GEM * 1000L
                            val gemsOwed  = (activeEntertainmentMs / entIntervalMs).toInt()
                            val spent = prefs.spendNewEntertainmentGems(foregroundPackage, gemsOwed)
                            if (spent > 0) {
                                // Child successfully spent gems — wallet was topped up by parent.
                                // Reset grace eligibility so the child gets "Give me 15 seconds"
                                // again after a genuine fresh wallet top-up. Only done here
                                // (on actual spend) — NOT on every intervention fire — to prevent
                                // infinite grace when isGamePauseEnabled or wallet stays at 0.
                                graceUsedPackages.remove(foregroundPackage)
                                repeat(spent) {
                                    txRepository.logTransaction(
                                        GemTransactionRepository.TYPE_ENTERTAINMENT_SPEND,
                                        -1,
                                        "Used $appName"
                                    )
                                }
                            }

                            // If wallet now empty after spending, fire intervention
                            if (rewardEngine.shouldIntervene()) {
                                if (!inGracePeriod || gracePeriodPackage != foregroundPackage) {
                                    // ── Ghost-debt fix (post-spend accumulator reset) ─
                                    // Wallet just hit zero during this poll's spend loop.
                                    // Reset confirmed ms so the next session after a
                                    // parent top-up starts from zero — no phantom debt.
                                    activeEntertainmentMs = 0L
                                    prefs.setConfirmedEntertainmentMsForPackage(foregroundPackage, 0L)
                                    // ── Session state reset on block ─────────────────
                                    // Bug A fix: re-arm low-gem warning for next session.
                                    lowGemWarningSentThisSession = false
                                    // Bug B fix: reset escalation so tone stays calm
                                    // on first intervention after a parent top-up.
                                    interventionCountThisSession = 0
                                    withContext(Dispatchers.Main) {
                                        fireCoachingIntervention(foregroundPackage, appName)
                                    }
                                    continue
                                }
                            }

                            // Dismiss overlay if gems now available
                            if ((overlayView != null || OverlayActivity.currentInstance != null) && !rewardEngine.shouldIntervene()) {
                                withContext(Dispatchers.Main) { removeOverlay() }
                            }
                        }

                        AppClassifier.AppCategory.PRODUCTIVE -> {
                            // ── Poll-Confirmed Accumulator Model ─────────────────────────────
                            // We track ONLY time that our 2s poll CONFIRMS the app is in
                            // foreground. The OS UsageStatsManager is NOT queried for usage —
                            // it includes background wakes, notification triggers, and system
                            // events that inflate real usage time unfairly.
                            //
                            // How it works:
                            //   - On app switch → reset activeProductiveMs to 0 for this pkg
                            //     (BUT load already-awarded gems from prefs so we don't re-award)
                            //   - Every poll where the same productive app is confirmed foreground
                            //     → add POLL_INTERVAL_MS (2000ms) to activeProductiveMs
                            //   - gemsOwed = floor(activeProductiveMs / 120_000)
                            //   - awardNewProductiveGems() ensures no double-awards
                            //   - On return after switch → activeProductiveMs resumes from 0
                            //     (previous session's gems already counted via prefs counter)
                            //
                            // Example: Coursera 1 min → YouTube 5 min → Coursera 1 min more:
                            //   Session 1: activeMs accumulates to 60_000 → gemsOwed=0. Switch.
                            //   Session 2: activeMs resets to 0. gemsAwarded still = 0 from prefs.
                            //   activeMs accumulates to 60_000 → total "genuine" time = 120_000ms
                            //   BUT activeMs only has 60_000ms in session 2 → gemsOwed still 0.
                            //   ❌ This misses cross-session accumulation.
                            //
                            // CORRECT approach for cross-session: persist activeProductiveMs
                            // per package in prefs so it survives app switches.
                            // gemsOwed = floor(totalConfirmedMs / 120_000)
                            // awardNewProductiveGems() guards against double-award.

                            if (currentForegroundPackage != foregroundPackage) {
                                // App switched TO this productive app — reset session state
                                currentForegroundPackage    = foregroundPackage
                                productiveSessionStartMs    = now
                                entertainmentSessionStartMs = 0L
                                lastGemSpentMs              = 0L  // sentinel: cleared so entertainment re-entry detected
                                lastPollWasProductive       = false
                                lastPollWasEntertainment    = false
                                // Load already-confirmed ms for this package from prefs
                                // so cross-session accumulation works correctly
                                activeProductiveMs = prefs.getConfirmedProductiveMsForPackage(foregroundPackage)
                            }

                            // Add one poll interval of confirmed foreground time
                            // Only add if last poll was also productive for same package
                            // (prevents adding time for the very first poll on switch)
                            if (lastPollWasProductive) {
                                activeProductiveMs += POLL_INTERVAL_MS
                                // Persist updated confirmed ms to prefs
                                prefs.setConfirmedProductiveMsForPackage(foregroundPackage, activeProductiveMs)
                            }
                            lastPollWasProductive = true

                            // Auto-dismiss coaching overlay if intervention no longer needed
                            if ((overlayView != null || OverlayActivity.currentInstance != null) && !rewardEngine.shouldIntervene()) {
                                withContext(Dispatchers.Main) { removeOverlay() }
                            }

                            // Compute how many complete 2-min blocks confirmed
                            val gemIntervalMs = KyzenPreferences.PRODUCTIVE_SECONDS_PER_GEM * 1000L
                            val gemsOwed = (activeProductiveMs / gemIntervalMs).toInt()

                            // Debug: log productive accumulator state
                            android.util.Log.d("UsageMonitorService",
                                "Productive: $foregroundPackage activeMs=$activeProductiveMs gemsOwed=$gemsOwed wallet=${prefs.gemWallet}")

                            // Award only new complete blocks — never re-award
                            val newGems = prefs.awardNewProductiveGems(foregroundPackage, gemsOwed)
                            if (newGems > 0) {
                                android.util.Log.d("UsageMonitorService",
                                    "★ EARNED $newGems gem(s) for $foregroundPackage! Wallet now: ${prefs.gemWallet}")
                                val label = "Focused on ${getAppName(foregroundPackage)}"
                                repeat(newGems) {
                                    txRepository.logTransaction(
                                        GemTransactionRepository.TYPE_PRODUCTIVE_EARN, +1, label
                                    )
                                }
                            }
                        }

                        AppClassifier.AppCategory.NEUTRAL -> {
                            // Neutral apps: reset session trackers
                            if (currentForegroundPackage != foregroundPackage) {
                                currentForegroundPackage    = foregroundPackage
                                entertainmentSessionStartMs = 0L
                                lastGemSpentMs              = 0L  // sentinel: cleared so re-entry detected
                                productiveSessionStartMs    = 0L
                            }
                            // Pause both accumulators — neither productive nor entertainment
                            lastPollWasProductive    = false
                            lastPollWasEntertainment = false

                            // Auto-dismiss coaching overlay
                            if (overlayView != null || OverlayActivity.currentInstance != null) {
                                withContext(Dispatchers.Main) { removeOverlay() }
                            }
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Re-throw — swallowing CancellationException prevents the coroutine
                // from being properly cancelled and causes the loop to run forever
                // even after serviceJob.cancel() is called in onDestroy().
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    // ─── Coaching Intervention Overlay ────────────────────────────────────────

    /**
     * Fires the coaching intervention overlay via WindowManager.
     *
     * FIRST TIME (package not in graceUsedPackages):
     *   Two buttons:
     *   — "Give me 15 seconds to wrap up" → dismisses overlay, returns child to their
     *     app via explicit launch intent, starts 15s silent timer. After 15s, if child
     *     is still on the entertainment app, overlay fires again (single button mode).
     *   — "I'm Ready — Take a Break 🌱" → goes to home screen immediately
     *
     * SUBSEQUENT (package already in graceUsedPackages):
     *   One button only:
     *   — "I'm Ready — Take a Break 🌱" → goes to home screen
     *
     * Message is always short (2 lines max). Title changes based on intervention count.
     */
    private fun fireCoachingIntervention(packageName: String, appName: String) {
        // Guard 1: overlay already showing — don't stack overlays
        if (OverlayActivity.currentInstance != null) return
        // Guard 2: grace period active for this package
        if (inGracePeriod && gracePeriodPackage == packageName) return

        interventionCountThisSession++

        // ── Title escalates with intervention count (no emoji — clean, direct) ─
        val title = when {
            interventionCountThisSession >= 3 -> "Time to step away."
            interventionCountThisSession >= 2 -> "Take a break."
            else                              -> "Time for a mindful break."
        }

        // ── Pill text — only shows wallet empty when truly 0 gems ────────────
        val pillText = when {
            prefs.isGamePauseEnabled  -> "Entertainment paused by parent"
            prefs.isDailyCapReached() -> "Daily allowance of ${prefs.dailySpendingCap} gems reached"
            else                      -> "0 gems — wallet empty"
        }

        val baseMessage = when {
            prefs.isGamePauseEnabled  ->
                "Your parent has paused entertainment for now. Step away and come back later."
            prefs.isDailyCapReached() ->
                "You have used your gem allowance for today. Unused gems carry forward to tomorrow."
            else ->
                "Your gems are all spent. Switch to a productive app for 2 minutes to earn 1 gem, or start a detox break to earn 10."
        }
        
        // Permanent Guidance:
        // Always include instructions for floating windows or background audio
        // because session count resets make dynamic text unreliable across Home screen visits.
        val finalMessage = "$baseMessage\n\n(If this app is in a small window or playing audio, please swipe it away from your Recent Apps menu.)"

        // ── Pill is red for empty wallet, amber for parent pause / daily cap ──
        val pillIsRed = !prefs.isGamePauseEnabled && !prefs.isDailyCapReached()

        // ── Grace used? ──────────────────────────────────────────────────────
        val graceUsed = packageName in graceUsedPackages

        try {
            // Force pause any background audio/video (e.g. Spotify, YMusic)
            // This acts exactly like pressing the pause button on Bluetooth headphones.
            // Note: launching OverlayActivity also pushes YouTube to the background,
            // which pauses the video naturally — this is a belt-and-suspenders approach.
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

            // Launch OverlayActivity instead of WindowManager overlay.
            // Advantages:
            //   1. Forces portrait orientation (fixes landscape misalignment)
            //   2. Pushes YouTube to background → video auto-pauses
            //   3. Activity lifecycle handles orientation changes properly
            val intent = Intent(this@UsageMonitorService, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(OverlayActivity.EXTRA_TRIGGER_APP_NAME, appName)
                putExtra(OverlayActivity.EXTRA_TRIGGER_PACKAGE, packageName)
                putExtra(OverlayActivity.EXTRA_TITLE, title)
                putExtra(OverlayActivity.EXTRA_MESSAGE, finalMessage)
                putExtra(OverlayActivity.EXTRA_PILL_TEXT, pillText)
                putExtra(OverlayActivity.EXTRA_PILL_IS_RED, pillIsRed)
                putExtra(OverlayActivity.EXTRA_GRACE_USED, graceUsed)
            }
            startActivity(intent)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Callbacks from OverlayActivity ─────────────────────────────────────────

    /**
     * Called by OverlayActivity when the child taps "I'm Ready — Take a Break".
     * Pauses media, sends child home, starts 5s grace for PiP windows.
     */
    fun onOverlayGoHome(packageName: String) {
        // Record exactly when we sent the user home to invalidate ghost apps
        lastSentHomeTimeMs = System.currentTimeMillis()

        // Ensure media is paused one last time before leaving
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

        // Send child to home screen
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })

        // Prevent infinite loop with floating windows (PiP).
        // Give the user a 5-second escape hatch on the home screen to physically
        // close the floating window or swipe it away in Recents.
        inGracePeriod = true
        gracePeriodPackage = packageName
        gracePeriodHandler?.removeCallbacksAndMessages(null)

        val gpHandler = Handler(Looper.getMainLooper())
        gracePeriodHandler = gpHandler
        gpHandler.postDelayed({
            inGracePeriod = false
            gracePeriodPackage = ""
            gracePeriodHandler = null
            // After 5 seconds, if the PiP window is still open, the Neutral
            // background audio defender will automatically kick in and mute it.
        }, 5_000L)
    }

    /**
     * Called by OverlayActivity when the child taps "Give me 15 seconds to wrap up".
     * Sets grace period, returns child to their app, starts 15s timer.
     */
    fun onOverlayGraceRequested(packageName: String, appName: String) {
        // CRITICAL ORDER:
        // 1. Set inGracePeriod = true FIRST — the monitoring loop (IO thread)
        //    checks this every 2s. Setting it before the overlay disappears ensures
        //    that even if the next poll fires before this completes, it will see
        //    inGracePeriod=true and skip the intervention.
        inGracePeriod      = true
        gracePeriodPackage = packageName

        // Return child to their app explicitly so they aren't left on home screen.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                 Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launchIntent)
        }

        // 15s silent grace period — child wraps up, then hard overlay fires.
        val gpHandler = Handler(Looper.getMainLooper())
        gracePeriodHandler = gpHandler
        gpHandler.postDelayed({
            inGracePeriod      = false
            gracePeriodPackage = ""
            gracePeriodHandler = null
            // Mark grace as used NOW — any subsequent overlay will be single-button
            graceUsedPackages.add(packageName)
            if (OverlayActivity.currentInstance != null) return@postDelayed
            // Check on IO thread to avoid blocking Main
            serviceScope.launch {
                val currentPkg = getCurrentForegroundPackage()
                if (currentPkg == packageName && rewardEngine.shouldIntervene()) {
                    withContext(Dispatchers.Main) {
                        // Single button mode — grace now marked above
                        fireCoachingIntervention(packageName, appName)
                    }
                }
            }
        }, 15_000L)
    }

        /**
     * Fires a push notification warning when gems are running low (≤ LOW_GEM_THRESHOLD).
     * Gives the child advance notice so they can wrap up naturally before the overlay fires.
     */
    private fun fireLowGemWarning() {
        // SDT: informational (not controlling) — give the child awareness so they
        // can self-regulate and choose when to wrap up. No shame, no urgency pressure.
        val gemCount = prefs.gemWallet
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COACHING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💎 Almost time for a break")
            .setContentText(
                "You have $gemCount 💎 gem${if (gemCount == 1) "" else "s"} left. " +
                "A good stopping point is coming up. 🌿"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "You have $gemCount 💎 gem${if (gemCount == 1) "" else "s"} remaining.\n\n" +
                "Now is a great time to find a natural stopping point — " +
                "finish your turn, save your progress, or pause your video.\n\n" +
                "Kaizen: choosing when to stop is a skill too. 🌱"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifManager.notify(NOTIF_ID_COACHING, notification)
    }

    private fun removeOverlay() {
        overlayQuoteHandler?.removeCallbacksAndMessages(null)
        overlayQuoteHandler = null
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
            overlayView = null
        }
        // Also dismiss OverlayActivity if it's showing
        OverlayActivity.currentInstance?.finish()
    }

    // ─── Detox Enforcement Overlay ────────────────────────────────────────────

    private fun showDetoxOverlay(remainingMs: Long) {
        if (detoxOverlayView != null) {
            detoxOverlayView?.findViewById<TextView>(R.id.txtOverlayDetoxCountdown)?.let {
                val s = remainingMs / 1000L
                it.text = "%02d:%02d".format(s / 60, s % 60)
            }
            return
        }

        try {
            val inflater     = LayoutInflater.from(this@UsageMonitorService)
            val view         = inflater.inflate(R.layout.overlay_detox, null)
            val txtCountdown = view.findViewById<TextView>(R.id.txtOverlayDetoxCountdown)
            val btnCancel    = view.findViewById<Button>(R.id.btnOverlayDetoxCancel)

            val secs = remainingMs / 1000L
            txtCountdown.text = "%02d:%02d".format(secs / 60, secs % 60)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            windowManager.addView(view, params)
            detoxOverlayView = view

            // ── Countdown ticker — updates every second ───────────────────────
            val handler = Handler(Looper.getMainLooper())
            detoxOverlayHandler = handler
            val ticker = object : Runnable {
                override fun run() {
                    val remaining = prefs.detoxTotalDurationMs -
                            (System.currentTimeMillis() - prefs.detoxStartMs)
                    if (remaining <= 0 || !prefs.isDetoxActive) {
                        removeDetoxOverlay()
                        return
                    }
                    val s = remaining / 1000L
                    txtCountdown.text = "%02d:%02d".format(s / 60, s % 60)
                    handler.postDelayed(this, 1000L)
                }
            }
            handler.postDelayed(ticker, 1000L)

            // ── Quote rotation — same system as coaching overlay ──────────────
            val txtDetoxQuote       = view.findViewById<TextView>(R.id.txtDetoxQuote)
            val txtDetoxQuoteAuthor = view.findViewById<TextView>(R.id.txtDetoxQuoteAuthor)
            val shuffledDetoxQuotes = OVERLAY_QUOTES.shuffled()
            var detoxQuoteIndex     = 0

            val dqHandler = Handler(Looper.getMainLooper())
            detoxQuoteHandler = dqHandler

            fun showDetoxQuote(index: Int, immediate: Boolean = false) {
                val q = shuffledDetoxQuotes[index % shuffledDetoxQuotes.size]
                if (immediate) {
                    // No blank gap — set text and fade in straight away
                    txtDetoxQuote.text       = q.first
                    txtDetoxQuoteAuthor.text = q.second
                    ObjectAnimator.ofFloat(txtDetoxQuote, "alpha", 0f, 1f).apply {
                        duration = 500; start()
                    }
                    ObjectAnimator.ofFloat(txtDetoxQuoteAuthor, "alpha", 0f, 1f).apply {
                        duration = 500; start()
                    }
                } else {
                    // Subsequent quotes: fade out → swap → fade in
                    ObjectAnimator.ofFloat(txtDetoxQuote, "alpha", 1f, 0f).apply {
                        duration = 300; start()
                    }
                    ObjectAnimator.ofFloat(txtDetoxQuoteAuthor, "alpha", 1f, 0f).apply {
                        duration = 300; start()
                    }
                    dqHandler.postDelayed({
                        txtDetoxQuote.text       = q.first
                        txtDetoxQuoteAuthor.text = q.second
                        ObjectAnimator.ofFloat(txtDetoxQuote, "alpha", 0f, 1f).apply {
                            duration = 500; start()
                        }
                        ObjectAnimator.ofFloat(txtDetoxQuoteAuthor, "alpha", 0f, 1f).apply {
                            duration = 500; start()
                        }
                    }, 300)
                }
            }

            // Show first quote immediately — no blank gap
            showDetoxQuote(detoxQuoteIndex++, immediate = true)

            val detoxQuoteTicker = object : Runnable {
                override fun run() {
                    if (detoxOverlayView == null) return
                    showDetoxQuote(detoxQuoteIndex++)
                    dqHandler.postDelayed(this, 6_000L)
                }
            }
            // Subsequent quotes rotate every 6 seconds
            dqHandler.postDelayed(detoxQuoteTicker, 6_000L)

            // ── Cancel confirmation — inflates dialog_detox_cancel.xml as a
            // second WindowManager overlay. Cannot use AlertDialog from a Service.
            // Tap "End Break Early" → shows confirmation overlay on top.
            // Confirmation overlay: "Stay" dismisses it, "End the break" cancels detox.
            btnCancel.setOnClickListener {
                showDetoxCancelConfirmOverlay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Shows dialog_detox_cancel.xml as a WindowManager overlay on top of the
     * detox enforcement overlay. Cannot use AlertDialog from a Service context.
     * Mirrors exactly what DetoxBreakActivity.showCancelConfirmation() does.
     */
    private fun showDetoxCancelConfirmOverlay() {
        if (detoxCancelOverlayView != null) return

        try {
            val inflater    = LayoutInflater.from(this@UsageMonitorService)
            val cancelView  = inflater.inflate(R.layout.dialog_detox_cancel, null)

            // Elapsed time pill
            val elapsedMs      = System.currentTimeMillis() - prefs.detoxStartMs
            val elapsedMinutes = (elapsedMs / 60_000L).toInt()
            cancelView.findViewById<TextView>(R.id.txtCancelElapsed).text =
                if (elapsedMinutes > 0) "$elapsedMinutes mins elapsed — no gems awarded"
                else "No time elapsed — no gems awarded"

            // Body message — SDT honest framing (same as DetoxBreakActivity)
            val partial = if (elapsedMinutes > 0)
                "You have already been present for $elapsedMinutes minute${if (elapsedMinutes == 1) "" else "s"} — that counts.\n\n"
            else ""
            cancelView.findViewById<TextView>(R.id.txtCancelBody).text =
                "${partial}Ending now means no gems this time — but that is okay.\n\n" +
                "Is there something you genuinely need to do right now?"

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            windowManager.addView(cancelView, params)
            detoxCancelOverlayView = cancelView

            // Stay — dismiss the confirmation overlay, detox continues
            cancelView.findViewById<Button>(R.id.btnCancelStay).setOnClickListener {
                detoxCancelOverlayView?.let {
                    try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
                    detoxCancelOverlayView = null
                }
            }

            // Confirm cancel — end detox, go to child dashboard
            cancelView.findViewById<Button>(R.id.btnCancelConfirm).setOnClickListener {
                detoxCancelOverlayView?.let {
                    try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
                    detoxCancelOverlayView = null
                }
                prefs.endDetoxSession()
                removeDetoxOverlay()
                startActivity(
                    Intent(this@UsageMonitorService, ChildDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeDetoxOverlay() {
        detoxOverlayHandler?.removeCallbacksAndMessages(null)
        detoxOverlayHandler = null
        detoxQuoteHandler?.removeCallbacksAndMessages(null)
        detoxQuoteHandler = null
        // Also remove cancel confirmation if showing
        detoxCancelOverlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
            detoxCancelOverlayView = null
        }
        detoxOverlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
            detoxOverlayView = null
        }
    }

    // ─── Real-Time Foreground Detection ──────────────────────────────────────

    private fun getCurrentForegroundPackage(): String {
        val usm       = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime   = System.currentTimeMillis()
        // 10-minute window handles service restarts, deep sleep, and OEM battery kills.
        val startTime = endTime - 600_000L

        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        var lastResumedPackage = ""
        var lastResumedTime    = 0L
        val packageLatestState = mutableMapOf<String, Int>()

        // Strategy: "last RESUMED wins", BUT with a crucial safety check.
        // We track the most recent event type for every package.
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            
            packageLatestState[event.packageName] = event.eventType
            
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (event.timeStamp >= lastResumedTime) {
                    lastResumedPackage = event.packageName
                    lastResumedTime    = event.timeStamp
                }
            }
        }

        // ── OEM Bug Fix (OxygenOS / Custom Launchers) ──
        // When an app is swiped away from Recents, it is killed. However, some launchers 
        // do not emit a new ACTIVITY_RESUMED event for the Home screen if it was already 
        // running in the background. This causes the killed app to remain stuck as 
        // "lastResumedPackage" forever!
        // 
        // Fix: If the latest state of the app is STOPPED (23) or DESTROYED (24), we know 
        // for an absolute fact it is no longer visible. We treat it as Neutral.
        val latestState = packageLatestState[lastResumedPackage]
        if (latestState == 23 /* ACTIVITY_STOPPED */ || latestState == 24 /* ACTIVITY_DESTROYED */) {
            return "" // App is fully hidden or closed, treat as Neutral
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── Harmless Background App Defender ──
        // If an app is PAUSED (e.g. notification, PiP) OR if it is a "Ghost" app 
        // (resumed BEFORE we explicitly sent the user Home), we must check its audio.
        // If it's an Entertainment app and NOT playing audio, it is harmless. Ignore it.
        if (lastResumedPackage.isNotEmpty() && lastResumedPackage != applicationContext.packageName) {
            val classification = AppClassifier.classify(lastResumedPackage, getAppName(lastResumedPackage))
            
            val isVisiblyPaused = (latestState == UsageEvents.Event.ACTIVITY_PAUSED)
            val isGhostFromBeforeHome = (lastResumedTime <= lastSentHomeTimeMs)

            if (classification.category == AppClassifier.AppCategory.ENTERTAINMENT) {
                if (isVisiblyPaused || isGhostFromBeforeHome) {
                    if (!audioManager.isMusicActive) {
                        return "" // Harmless paused media or ghost app, ignore it
                    }
                }
            } else {
                // For non-entertainment apps, if it's a ghost from before Home, ignore it
                if (isGhostFromBeforeHome) {
                    return ""
                }
            }
        }

        // ── Active Window Priority Strategy (PiP Defender) ──
        // If any OTHER paused Entertainment app is actively playing music, prioritize it.
        for ((pkg, state) in packageLatestState) {
            if (pkg != lastResumedPackage && state == UsageEvents.Event.ACTIVITY_PAUSED) {
                if (pkg != applicationContext.packageName) {
                    val classification = AppClassifier.classify(pkg, getAppName(pkg))
                    if (classification.category == AppClassifier.AppCategory.ENTERTAINMENT) {
                        if (audioManager.isMusicActive) {
                            return pkg // Force prioritize the active PiP window
                        }
                    }
                }
            }
        }

        return lastResumedPackage
    }

    // ─── Daily Reset ──────────────────────────────────────────────────────────

    private suspend fun checkAndPerformDailyReset() {
        val today = UsageRepository.todayString()
        if (prefs.lastResetDate != today) {
            prefs.performDailyReset(today)
            repository.pruneOldRecords()
            // Clear gem transaction history — fresh start each day
            txRepository.clearAll()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) { packageName }
    }

    // ─── Notification Infrastructure ─────────────────────────────────────────

    private fun createNotificationChannels() {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notifManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MONITOR,
                "Kyzen Session Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification indicating Kyzen is actively monitoring."
                setShowBadge(false)
            }
        )

        notifManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_COACHING,
                "Kyzen Coaching Breaks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Coaching intervention alerts when entertainment gems are exhausted."
            }
        )
    }

    private fun buildMonitorNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Kyzen is active")
            .setContentText("Monitoring your digital wellbeing session.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
