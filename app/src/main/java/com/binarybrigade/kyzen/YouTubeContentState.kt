package com.binarybrigade.kyzen

/**
 * YouTubeContentState — Thread-Safe Singleton for Real-Time YouTube Content Classification
 *
 * Bridges the AccessibilityService (YouTubeContentMonitor) and the
 * UsageMonitorService's 2-second polling loop. Uses @Volatile fields
 * to guarantee cross-thread visibility without SharedPreferences race
 * conditions (apply() is async; commit() is blocking and slow).
 *
 * FLOW:
 *   YouTubeContentMonitor (AccessibilityService, Main thread)
 *     → reads YouTube's UI tree, matches against educational channels
 *     → calls YouTubeContentState.update(PRODUCTIVE / ENTERTAINMENT / NEUTRAL)
 *
 *   UsageMonitorService (IO thread, every 2s)
 *     → calls AppClassifier.classify("com.google.android.youtube", ...)
 *     → AppClassifier reads YouTubeContentState.contentCategory
 *     → Returns the content-aware classification
 *
 * STALE TIMEOUT:
 *   If no update arrives for 30 seconds (service killed, YouTube in background,
 *   OEM battery optimization, etc.), isStale() returns true and AppClassifier
 *   falls back to NEUTRAL — the safest default (no unfair deduction, no unearned reward).
 *
 * Privacy: Zero network calls. Zero data egress. All processing on-device.
 */
object YouTubeContentState {

    /** Maximum age (ms) before a classification is considered stale.
     *  5 minutes — YouTube's UI is static during video playback, so no
     *  accessibility events fire for minutes at a time. The old 30s timeout
     *  caused classification to flicker PRODUCTIVE→NEUTRAL every 30s,
     *  resetting the productive accumulator before it could reach the
     *  2-minute earning threshold. 5 minutes is safe because:
     *    - If the AccessibilityService is killed, UsageMonitorService will
     *      detect YouTube left the foreground naturally via UsageStatsManager
     *    - If YouTube goes to background, the next poll sees a different
     *      foreground package and ignores YouTubeContentState entirely
     */
    private const val STALE_TIMEOUT_MS = 300_000L

    @Volatile
    private var _contentCategory: AppClassifier.AppCategory = AppClassifier.AppCategory.NEUTRAL

    @Volatile
    internal var _lastUpdatedMs: Long = 0L

    /** The current content-aware classification for YouTube. Thread-safe read. */
    val contentCategory: AppClassifier.AppCategory
        get() = _contentCategory

    /** Timestamp (epoch ms) of the last update. Thread-safe read. */
    val lastUpdatedMs: Long
        get() = _lastUpdatedMs

    /**
     * Updates the YouTube content classification.
     * Called by YouTubeContentMonitor on the Main thread.
     *
     * @param category PRODUCTIVE (educational), ENTERTAINMENT (non-educational),
     *                 or NEUTRAL (no video detected / browsing home feed)
     */
    fun update(category: AppClassifier.AppCategory) {
        _contentCategory = category
        _lastUpdatedMs = System.currentTimeMillis()
    }

    /**
     * Returns true if the classification is stale (no update for [maxAgeMs]).
     * When stale, AppClassifier falls back to NEUTRAL for YouTube.
     *
     * @param maxAgeMs Maximum age in milliseconds before considering stale.
     *                  Defaults to 30 seconds.
     */
    fun isStale(maxAgeMs: Long = STALE_TIMEOUT_MS): Boolean {
        return _lastUpdatedMs == 0L ||
               (System.currentTimeMillis() - _lastUpdatedMs) > maxAgeMs
    }

    /**
     * Resets the state to NEUTRAL.
     * Called when the AccessibilityService is destroyed or when YouTube
     * is no longer the foreground app.
     */
    fun reset() {
        _contentCategory = AppClassifier.AppCategory.NEUTRAL
        _lastUpdatedMs = 0L
    }
}