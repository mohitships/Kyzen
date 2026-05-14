package com.binarybrigade.kyzen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * YouTubeContentMonitor — Accessibility Service for YouTube Content Detection
 *
 * Watches YouTube's UI in real-time and determines whether the user is
 * watching educational content (from a known channel) or entertainment.
 *
 * HOW IT WORKS:
 *   1. Listens for WINDOW_CONTENT_CHANGED and WINDOW_STATE_CHANGED events
 *      from the YouTube app only (configured in accessibility_service_config.xml)
 *   2. On each event, performs a depth-first traversal of the accessibility
 *      node tree to collect text content from the VIDEO PLAYER AREA ONLY
 *      (top 55% of screen — excludes recommendations below the video)
 *   3. Passes the collected text to EducationalChannelMatcher
 *   4. Updates YouTubeContentState with the classification result
 *   5. UsageMonitorService reads YouTubeContentState every 2 seconds
 *
 * ROBUSTNESS:
 *   - Does NOT rely on specific view resource IDs (they change with YouTube updates)
 *   - Reads TEXT CONTENT only, which is stable across YouTube versions
 *   - BOUNDS-BASED FILTERING: Only collects text from the top 55% of the screen
 *     to prevent false PRODUCTIVE matches from educational channel names appearing
 *     in the recommendations feed below the video (portrait mode)
 *   - Debounced: max 1 processing per 2 seconds (aligned with UsageMonitorService poll)
 *   - Stale timeout: if no events for 5min, YouTubeContentState reverts to NEUTRAL
 *   - Classification cooldown (30s): prevents false ENTERTAINMENT blips when
 *     YouTube's UI temporarily hides the channel name during transitions/ads/controls
 *
 * PERMISSIONS REQUIRED:
 *   - User must enable this Accessibility Service in Android Settings
 *   - AndroidManifest must declare BIND_ACCESSIBILITY_SERVICE permission
 *
 * Privacy: Zero network calls. Zero data egress. All processing on-device.
 * The service only processes events from com.google.android.youtube.
 */
class YouTubeContentMonitor : AccessibilityService() {

    companion object {
        private const val TAG = "YouTubeContentMonitor"

        /** YouTube's package name. */
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** Minimum interval between processing events (ms). Aligned with UsageMonitorService's 2s poll. */
        private const val DEBOUNCE_INTERVAL_MS = 2000L

        /** Maximum depth for accessibility tree traversal to prevent infinite loops. */
        private const val MAX_TRAVERSAL_DEPTH = 50

        /**
         * Fraction of the screen height to include in text collection.
         * Only nodes whose TOP edge is within this fraction of the screen
         * will have their text collected.
         *
         * WHY 0.55:
         *   - Portrait: Video player + channel name ≈ top 40-50%,
         *     recommendations start at ~50%. 55% gives a small margin.
         *   - Landscape: Video takes full screen, channel name overlay at top ≈ 15%.
         *   - Homepage: Logo + search bar at top, feed starts at ~30%.
         *     Channel names in the feed are below 55% → correctly excluded.
         *   - Shorts: Channel name at bottom (outside 55%), but Shorts are
         *     typically entertainment → acceptable trade-off.
         */
        private const val SCREEN_FRACTION_FOR_TEXT_COLLECTION = 0.55f

        /**
         * Minimum time (ms) after a PRODUCTIVE classification before we allow
         * flipping to ENTERTAINMENT. YouTube's UI intermittently hides the
         * channel name during video transitions, ad overlays, or player control
         * changes — causing false ENTERTAINMENT blips that would incorrectly
         * deduct gems from educational content. The cooldown ensures only
         * SUSTAINED non-educational content triggers ENTERTAINMENT.
         *
         * 60 seconds covers YouTube ad durations (15-30s ads) AND the typical
         * period where player controls are auto-hidden (5-10s) before the user
         * taps to reveal them again. The previous 30s value was too short —
         * it allowed the classification to flip to ENTERTAINMENT during
         * educational video playback when the channel name was hidden, causing
         * timing gaps in gem awards.
         *
         * Maximum false positive: ~0.5 gems earned during the hold period
         * when the user genuinely switched from educational to entertainment.
         * This is negligible and acceptable.
         */
        private const val CLASSIFICATION_COOLDOWN_MS = 60_000L

        /**
         * Fraction of the screen height to include for BOTTOM text collection in Shorts mode.
         * In Shorts, the channel name appears at the bottom of the screen.
         * We collect text from the bottom 25% of the screen.
         */
        private const val SHORTS_BOTTOM_FRACTION = 0.25f
    }

    /** Timestamp of the last processed event — used for debouncing. */
    private var lastProcessedMs: Long = 0L

    /**
     * Timestamp of the last PRODUCTIVE classification.
     * Used for the classification cooldown to prevent false ENTERTAINMENT blips.
     */
    private var lastProductiveClassificationMs: Long = 0L

    /**
     * The current classification for the active video session.
     * Persists as long as isVideoPlaying() returns true, even when the
     * channel name is temporarily hidden (auto-hide controls, comments,
     * mini player). Only resets when the user leaves the video.
     *
     * This fixes the "timing gaps" issue where the classification flipped
     * to ENTERTAINMENT every time the channel name was hidden, causing
     * missed gem awards during educational video playback.
     */
    private var currentVideoClassification: AppClassifier.AppCategory? = null

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "YouTube Content Monitor service connected")

        // Initialize the channel matcher with application context
        EducationalChannelMatcher.initialize(applicationContext)

        // Set initial state
        YouTubeContentState.update(AppClassifier.AppCategory.NEUTRAL)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only process events from YouTube
        if (event.packageName?.toString() != YOUTUBE_PACKAGE) return

        // Debounce: skip if we processed an event too recently
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < DEBOUNCE_INTERVAL_MS) return
        lastProcessedMs = now

        // Collect all visible text from the accessibility node tree
        val rootNode = rootInActiveWindow ?: return

        try {
            // ── Homepage / No-Video Detection ────────────────────────────────
            // When the user is on the YouTube homepage (or any screen where no
            // full video player is active), we classify as ENTERTAINMENT.
            //
            // WHY ENTERTAINMENT (not NEUTRAL):
            //   YouTube's inline autoplay feature lets users hover over a video
            //   in the feed and watch it directly — without opening the full
            //   player. If we classified the homepage as NEUTRAL, children could
            //   watch entertainment content "for free" (no gem deduction).
            //   Classifying as ENTERTAINMENT closes this loophole.
            //
            //   Brief homepage visits (under 60 seconds) cost 0 gems since the
            //   deduction rate is 1 gem/minute. This only affects sustained
            //   browsing, which is passive entertainment consumption anyway.
            if (!isVideoPlaying(rootNode)) {
                // User left the video — reset the video session
                currentVideoClassification = null
                YouTubeContentState.update(AppClassifier.AppCategory.ENTERTAINMENT)
                Log.d(TAG, "No video playing (homepage/browsing) → ENTERTAINMENT")
                return
            }

            // ── Shorts Detection ────────────────────────────────────────────
            // In Shorts mode, the channel name appears at the BOTTOM of the
            // screen (below the 55% threshold). We need to expand text collection
            // to include the bottom area where the channel name is displayed.
            val shortsMode = isShortsMode(rootNode)

            val visibleText = if (shortsMode) {
                collectAllText(rootNode, collectBottomArea = true)
            } else {
                collectAllText(rootNode)
            }

            if (visibleText.isBlank()) {
                // No text detected — likely a transition or loading state
                // Keep the previous classification (don't reset to NEUTRAL on every blank frame)
                return
            }

            // Check if the visible text matches any educational channel
            val isEducational = EducationalChannelMatcher.isEducationalContent(visibleText)

            val category = if (isEducational) {
                // Educational content found — update session
                lastProductiveClassificationMs = now
                currentVideoClassification = AppClassifier.AppCategory.PRODUCTIVE
                AppClassifier.AppCategory.PRODUCTIVE
            } else {
                // ── Video-Session-Based Classification ──────────────────────
                // If we previously classified this video as PRODUCTIVE, keep it
                // as PRODUCTIVE even when the channel name is temporarily hidden
                // (auto-hide controls, comments section, mini player, ads).
                // The channel name being hidden does NOT mean the content changed.
                //
                // We only flip to ENTERTAINMENT if:
                //   1. This is a new video session (currentVideoClassification == null)
                //   2. OR the cooldown expired (safety net: the user may have
                //      switched to a different video within YouTube)
                //
                // This fixes the "timing gaps" issue where the classification
                // flipped to ENTERTAINMENT every time the channel name was hidden,
                // causing missed gem awards during educational video playback.
                if (currentVideoClassification == AppClassifier.AppCategory.PRODUCTIVE) {
                    // Check cooldown as a safety net
                    val timeSinceProductive = now - lastProductiveClassificationMs
                    if (timeSinceProductive < CLASSIFICATION_COOLDOWN_MS) {
                        // Cooldown active — keep PRODUCTIVE (channel name just hidden)
                        Log.d(TAG, "Video session: keeping PRODUCTIVE " +
                                "(cooldown: ${timeSinceProductive}ms / ${CLASSIFICATION_COOLDOWN_MS}ms)")
                        AppClassifier.AppCategory.PRODUCTIVE
                    } else {
                        // Cooldown expired — the user may have switched videos
                        // within YouTube. Flip to ENTERTAINMENT.
                        Log.d(TAG, "Video session: cooldown expired, flipping PRODUCTIVE → ENTERTAINMENT")
                        currentVideoClassification = AppClassifier.AppCategory.ENTERTAINMENT
                        AppClassifier.AppCategory.ENTERTAINMENT
                    }
                } else {
                    // No active PRODUCTIVE session — classify as ENTERTAINMENT
                    currentVideoClassification = AppClassifier.AppCategory.ENTERTAINMENT
                    AppClassifier.AppCategory.ENTERTAINMENT
                }
            }

            YouTubeContentState.update(category)

            Log.d(TAG, "YouTube content classified: $category " +
                    "(text length: ${visibleText.length}, isEducational: $isEducational, " +
                    "shorts: $shortsMode, session: $currentVideoClassification)")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        } finally {
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "YouTube Content Monitor service interrupted")
        YouTubeContentState.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "YouTube Content Monitor service destroyed")
        YouTubeContentState.reset()
    }

    // ─── Video Playback Detection ─────────────────────────────────────────────

    /**
     * Detects whether a video is currently playing in the YouTube app.
     *
     * When no full video player is detected (homepage, search results,
     * channel browsing), we classify as ENTERTAINMENT — this closes the
     * inline autoplay loophole where children can watch videos in the
     * feed without opening the full player. Brief visits (<60s) cost
     * 0 gems at the 1 gem/min deduction rate.
     *
     * Without this check, channel names in feed thumbnails could also
     * trigger false PRODUCTIVE or ENTERTAINMENT classifications.
     *
     * DETECTION STRATEGY:
     *   We look for indicators that a video player is active:
     *   1. SeekBar nodes — the video progress bar only appears during playback
     *   2. Large clickable nodes in the top portion of the screen — the video
     *      player surface is a large clickable area for pause/play
     *   3. Nodes with "player" in their class name
     *
     *   We do NOT rely on specific resource IDs (they change with YouTube updates).
     *
     * @param rootNode The root accessibility node to search
     * @return true if a video appears to be playing, false if on homepage/browsing
     */
    private fun isVideoPlaying(rootNode: AccessibilityNodeInfo): Boolean {
        return hasVideoPlayerIndicator(rootNode, 0)
    }

    /**
     * Recursive search for video player indicators in the accessibility tree.
     * Looks for:
     *   - SeekBar (android.widget.SeekBar) — the video scrubber/progress bar
     *   - Nodes with className containing "Player" or "Video"
     *   - Large clickable areas in the top portion of the screen (video surface)
     */
    private fun hasVideoPlayerIndicator(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_TRAVERSAL_DEPTH) return false

        // Check className for video player indicators
        node.className?.toString()?.let { className ->
            val lower = className.lowercase()
            // SeekBar is the video progress bar — definitive indicator of video playback
            if (lower.contains("seekbar")) return true
            // Player/Video view classes
            if (lower.contains("player") && lower.contains("view")) return true
            if (lower.contains("video") && lower.contains("view")) return true
            // SurfaceView is used for video rendering (also catches custom subclasses
            // like YouTube's internal PlayerSurfaceView)
            if (lower.contains("surfaceview")) return true
        }

        // Check contentDescription for player-related hints
        node.contentDescription?.toString()?.let { desc ->
            val lower = desc.lowercase()
            if (lower.contains("video player")) return true
            if (lower.contains("player control")) return true
        }

        // Check text/contentDescription for live stream indicators.
        // YouTube live streams show a "Live" badge instead of a SeekBar.
        // Without this check, live streams would be classified as "no video playing".
        node.text?.toString()?.let { text ->
            val trimmed = text.trim().lowercase()
            if (trimmed == "live") return true
        }
        node.contentDescription?.toString()?.let { desc ->
            val trimmed = desc.trim().lowercase()
            if (trimmed == "live") return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    if (hasVideoPlayerIndicator(child, depth + 1)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            } catch (e: Exception) {
                // SecurityException on some devices — skip
            }
        }

        return false
    }

    /**
     * Detects whether the user is watching a YouTube Short.
     *
     * YouTube Shorts have a different layout from regular videos:
     *   - No SeekBar (progress bar) — Shorts are short and auto-advance
     *   - Channel name appears at the BOTTOM of the screen
     *   - Video takes up most/all of the screen
     *
     * When Shorts mode is detected, we expand text collection to include
     * the bottom portion of the screen where the channel name appears.
     * This ensures educational Shorts are correctly identified.
     *
     * DETECTION HEURISTIC:
     *   - SurfaceView is present (video rendering)
     *   - No SeekBar (Shorts don't have progress bars)
     *   - Not a live stream (no "Live" badge)
     *
     * @param rootNode The root accessibility node to search
     * @return true if the user appears to be watching a Short
     */
    private fun isShortsMode(rootNode: AccessibilityNodeInfo): Boolean {
        val indicators = detectPlayerIndicators(rootNode, 0)
        // Shorts mode: video playing (SurfaceView) but no SeekBar and no Live badge
        return indicators.hasSurfaceView && !indicators.hasSeekBar && !indicators.hasLiveBadge
    }

    /**
     * Data class holding which player indicators were found in the accessibility tree.
     * Used by isShortsMode() to distinguish Shorts from regular videos and live streams.
     */
    private data class PlayerIndicators(
        val hasSeekBar: Boolean = false,
        val hasSurfaceView: Boolean = false,
        val hasLiveBadge: Boolean = false
    )

    /**
     * Traverses the accessibility tree to detect which player indicators are present.
     * Returns a PlayerIndicators object with the results.
     */
    private fun detectPlayerIndicators(node: AccessibilityNodeInfo, depth: Int): PlayerIndicators {
        if (depth > MAX_TRAVERSAL_DEPTH) return PlayerIndicators()

        var hasSeekBar = false
        var hasSurfaceView = false
        var hasLiveBadge = false

        // Check className
        node.className?.toString()?.let { className ->
            val lower = className.lowercase()
            if (lower.contains("seekbar")) hasSeekBar = true
            if (lower.contains("surfaceview")) hasSurfaceView = true
        }

        // Check for "Live" badge
        node.text?.toString()?.let { text ->
            if (text.trim().lowercase() == "live") hasLiveBadge = true
        }
        node.contentDescription?.toString()?.let { desc ->
            if (desc.trim().lowercase() == "live") hasLiveBadge = true
        }

        // Recurse into children
        var childSeekBar = false
        var childSurfaceView = false
        var childLiveBadge = false

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    val childIndicators = detectPlayerIndicators(child, depth + 1)
                    if (childIndicators.hasSeekBar) childSeekBar = true
                    if (childIndicators.hasSurfaceView) childSurfaceView = true
                    if (childIndicators.hasLiveBadge) childLiveBadge = true
                    child.recycle()
                }
            } catch (e: Exception) {
                // SecurityException on some devices — skip
            }
        }

        return PlayerIndicators(
            hasSeekBar = hasSeekBar || childSeekBar,
            hasSurfaceView = hasSurfaceView || childSurfaceView,
            hasLiveBadge = hasLiveBadge || childLiveBadge
        )
    }

    // ─── Accessibility Tree Traversal ─────────────────────────────────────────

    /**
     * Performs a depth-first traversal of the accessibility node tree
     * and collects text content from nodes in the VIDEO PLAYER AREA ONLY
     * (top 55% of the screen).
     *
     * This is the key robustness decision: we do NOT look for specific
     * view IDs (which change with every YouTube update). Instead, we
     * collect text from the top portion of the screen and let
     * EducationalChannelMatcher handle matching.
     *
     * BOUNDS-BASED FILTERING:
     *   In portrait mode, YouTube shows the video player on top and a
     *   scrollable recommendations feed below. If we collect ALL text,
     *   educational channel names in the recommendations would cause
     *   false PRODUCTIVE classification for entertainment videos.
     *   By only collecting text from the top 55% of the screen, we
     *   ensure only the currently-playing video's metadata is considered.
     *
     * @param node The root accessibility node to start traversal from
     * @return Concatenated string of text content found in the top portion of the screen
     */
    private fun collectAllText(node: AccessibilityNodeInfo, collectBottomArea: Boolean = false): String {
        val textBuilder = StringBuilder()

        // Get screen height from the root node's bounds
        val rootBounds = Rect()
        node.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height()
        val maxTopForCollection = (screenHeight * SCREEN_FRACTION_FOR_TEXT_COLLECTION).toInt()

        Log.d(TAG, "Screen bounds: ${rootBounds.width()}x${screenHeight}, " +
                "collecting text from top ${maxTopForCollection}px " +
                "(${(SCREEN_FRACTION_FOR_TEXT_COLLECTION * 100).toInt()}%)" +
                if (collectBottomArea) ", + bottom ${(SHORTS_BOTTOM_FRACTION * 100).toInt()}%" else "")

        traverseNode(node, textBuilder, 0, maxTopForCollection, Int.MAX_VALUE)

        // In Shorts mode, also collect text from the bottom portion of the screen
        // where the channel name is displayed
        if (collectBottomArea) {
            val minTopForBottomCollection = (screenHeight * (1.0f - SHORTS_BOTTOM_FRACTION)).toInt()
            traverseNode(node, textBuilder, 0, Int.MAX_VALUE, minTopForBottomCollection)
        }

        return textBuilder.toString()
    }

    /**
     * Recursive depth-first traversal of the accessibility node tree.
     * Collects text from nodes within the specified vertical range.
     *
     * @param node Current node to process
     * @param builder StringBuilder to append text to
     * @param depth Current traversal depth (safety limit)
     * @param maxTopForCollection Maximum Y coordinate for top-area collection
     * @param minTopForBottomCollection Minimum Y coordinate for bottom-area collection
     *   (Int.MAX_VALUE = don't collect bottom area)
     */
    private fun traverseNode(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        depth: Int,
        maxTopForCollection: Int,
        minTopForBottomCollection: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return

        // Check if this node is in the video player area (top portion of screen)
        val nodeBounds = Rect()
        try {
            node.getBoundsInScreen(nodeBounds)
        } catch (e: Exception) {
            // SecurityException on some devices — skip this node
            return
        }

        // Check if this node is in a collectible area:
        //   1. Top area: node's top edge is within maxTopForCollection
        //   2. Bottom area (Shorts): node's top edge is at or below minTopForBottomCollection
        val inTopArea = nodeBounds.top <= maxTopForCollection
        val inBottomArea = minTopForBottomCollection < Int.MAX_VALUE &&
                          nodeBounds.top >= minTopForBottomCollection

        if (!inTopArea && !inBottomArea) {
            // Node is not in any collectible area — skip it and all its children
            return
        }

        // Node is within the video player area — collect its text
        node.text?.toString()?.let { text ->
            if (text.isNotBlank()) {
                builder.append(text)
                builder.append(' ')
            }
        }

        // Collect content description (used for accessibility labels)
        node.contentDescription?.toString()?.let { desc ->
            if (desc.isNotBlank()) {
                builder.append(desc)
                builder.append(' ')
            }
        }

        // Recurse into child nodes
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    traverseNode(child, builder, depth + 1, maxTopForCollection, minTopForBottomCollection)
                    child.recycle()
                }
            } catch (e: Exception) {
                // SecurityException can be thrown for some nodes on certain devices
                // Skip and continue with other children
            }
        }
    }
}