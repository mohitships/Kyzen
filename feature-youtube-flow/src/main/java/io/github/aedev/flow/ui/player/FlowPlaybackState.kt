package io.github.aedev.flow.ui.player

/**
 * FlowPlaybackState — In-Process Bridge Singleton (Phase 1 Tracking Contract)
 *
 * This object is the single communication channel between the embedded Flow player
 * (inside :feature-youtube-flow) and Kyzen's UsageMonitorService (inside :app).
 *
 * CONTRACT (from Phase 1 design):
 *   - Writer: FlowPlayerActivity (Main thread) + Player.Listener (player thread)
 *   - Reader: UsageMonitorService.runMonitoringLoop() (Dispatchers.IO, every 2s)
 *   - All mutable fields are @Volatile to guarantee visibility across threads.
 *
 * The monitor's effectiveTrackablePackage() helper reads [isPlayerActive] and
 * [currentCategory] to decide whether Flow is foreground and whether the current
 * video is productive (educational) or entertainment:
 *   - isPlayerActive == false  → monitor self-excludes (dashboard-like)
 *   - isPlayerActive == true, currentCategory == "PRODUCTIVE"   → "flow_productive"
 *   - isPlayerActive == true, currentCategory == "ENTERTAINMENT" → "flow_entertainment"
 *   - isPlayerActive == true, currentCategory == null (15s grace) → "flow_entertainment"
 *     (safe default — prevents channel-surfing exploit; see Phase 1 contract §1.3)
 *
 * Lifecycle:
 *   - isPlayerActive set true in FlowPlayerActivity.onResume()
 *   - isPlayerActive set false in FlowPlayerActivity.onPause() / onDestroy()
 *   - currentCategory set by the classification layer after DeBERTa/keyword inference
 *   - currentCategory set null on new video load (triggers 15s neutral grace window)
 */
object FlowPlaybackState {

    /** True while FlowPlayerActivity is in the foreground (resumed). @Volatile for cross-thread reads. */
    @Volatile
    var isPlayerActive: Boolean = false
        private set

    /** True when the user has manually paused playback. Prevents gem earning while paused. */
    @Volatile
    var isPaused: Boolean = false
        private set

    /** The YouTube video ID currently loaded in the player. Null when no video is loaded. */
    @Volatile
    var videoId: String? = null
        private set

    /** The title of the current video. Used by the DeBERTa classifier as part of the premise. */
    @Volatile
    var videoTitle: String? = null
        private set

    /** The channel name of the current video. Used by the VIP fast-path and DeBERTa classifier. */
    @Volatile
    var channelName: String? = null
        private set

    /**
     * The classified category of the current video.
     *   "PRODUCTIVE"   — educational content (earns gems)
     *   "ENTERTAINMENT" — non-educational content (spends gems)
     *   null           — not yet classified (15s neutral grace window in progress)
     *
     * Default during the unclassified window is treated as ENTERTAINMENT by the
     * monitor helper (safe default — see Phase 1 contract §1.3).
     */
    @Volatile
    var currentCategory: String? = null
        private set

    /**
     * Called by FlowPlayerActivity.onResume() to signal the player is now foreground.
     * The monitor's interceptInProgress flag is cleared when this becomes true.
     */
    fun onPlayerActivated() {
        isPlayerActive = true
    }

    /**
     * Called by FlowPlayerActivity.onPause() / onDestroy() to signal the player
     * is no longer foreground. The monitor will self-exclude Flow from tracking.
     */
    fun onPlayerDeactivated() {
        isPlayerActive = false
    }

    /**
     * Called by the Player.Listener when a new video loads. Resets the category
     * to null (unclassified), which triggers the 15-second neutral grace window
     * before the classification layer runs DeBERTa or the keyword fallback.
     *
     * @param id       The YouTube video ID
     * @param title    The video title (for classification)
     * @param channel  The channel name (for VIP fast-path + classification)
     */
    fun onVideoLoaded(id: String?, title: String?, channel: String?) {
        videoId = id
        videoTitle = title
        channelName = channel
        currentCategory = null
    }

    /**
     * Called by the classification layer (DeBERTa or keyword fallback) after
     * inference completes. Sets the category, which the monitor reads on its
     * next 2-second poll. If the category differs from the previous poll's
     * category, the monitor executes the category-flip reset (Phase 1 §3).
     *
     * @param category "PRODUCTIVE" or "ENTERTAINMENT"
     */
    fun onCategoryClassified(category: String) {
        currentCategory = category
    }

    /**
     * Called by the Player.Listener when the user pauses or resumes playback.
     * When paused, the monitor self-excludes Flow from gem counting to prevent
     * the "pause-and-leave" exploit.
     */
    fun onPlayPaused(paused: Boolean) {
        isPaused = paused
    }

    /**
     * Resets all state. Called when the player exits completely or when the
     * child navigates away from the player screen.
     */
    fun reset() {
        isPlayerActive = false
        isPaused = false
        videoId = null
        videoTitle = null
        channelName = null
        currentCategory = null
    }
}
