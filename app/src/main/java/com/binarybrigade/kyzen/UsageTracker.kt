package com.binarybrigade.kyzen

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * UsageTracker — Foreground Session Monitoring & Data Sanitization Engine (Phase 3 Upgraded)
 *
 * Phase 3 additions:
 * - Each sanitised app is now passed through AppClassifier for AI-driven categorisation
 * - Results are persisted to Room SQLite via UsageRepository
 * - getSanitizedUsageList() is now a suspend function (must be called from a coroutine)
 *
 * Tracking approach — queryEvents() (event-based):
 * We use queryEvents() instead of queryUsageStats() because:
 * 1. queryUsageStats(INTERVAL_DAILY) uses Android's own internal daily bucket which does NOT
 *    align with calendar midnight — it bleeds yesterday's data into today on many OEMs.
 * 2. queryUsageStats(INTERVAL_BEST) is better but still relies on OS bucket granularity.
 * 3. queryEvents() gives raw ACTIVITY_RESUMED / ACTIVITY_PAUSED events with exact millisecond
 *    timestamps. We compute foreground duration ourselves from midnight to now — this is the
 *    same approach used internally by Android's Digital Wellbeing app. It is OEM-proof,
 *    calendar-day-accurate, and immune to bucket boundary bleed.
 */
class UsageTracker(private val context: Context) {

    private val repository = UsageRepository(context)

    /**
     * Queries, aggregates, sanitises, and classifies today's foreground app usage.
     * Persists the result to Room SQLite via UsageRepository.
     * Returns the classified list for immediate UI rendering.
     *
     * MUST be called from a coroutine (Dispatchers.IO recommended).
     */
    suspend fun getSanitizedUsageList(): List<AppUsageItem> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        // Define the strict calendar-day window: today's midnight → right now.
        // This is the authoritative boundary — no OEM bucket can override it because
        // we are computing durations ourselves from raw events.
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        // Query raw foreground events from the OS within our exact window.
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

        // --- EVENT-BASED AGGREGATION ---
        // Walk through ACTIVITY_RESUMED / ACTIVITY_PAUSED pairs and accumulate
        // foreground time per package within our midnight-to-now window.
        val aggregatedMs = mutableMapOf<String, Long>()   // package → total foreground ms
        val resumeTimeMs = mutableMapOf<String, Long>()   // package → last RESUMED timestamp

        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Record when this app came to foreground
                    resumeTimeMs[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // Compute duration since last RESUMED (if we have one)
                    val resumedAt = resumeTimeMs.remove(pkg) ?: continue
                    val duration = event.timeStamp - resumedAt
                    if (duration > 0) {
                        aggregatedMs[pkg] = (aggregatedMs[pkg] ?: 0L) + duration
                    }
                }
            }
        }

        // Handle apps that are still in the foreground right now (no PAUSED event yet).
        // Their session started at resumeTimeMs[pkg] and is still ongoing → use endTime.
        for ((pkg, resumedAt) in resumeTimeMs) {
            val duration = endTime - resumedAt
            if (duration > 0) {
                aggregatedMs[pkg] = (aggregatedMs[pkg] ?: 0L) + duration
            }
        }

        if (aggregatedMs.isEmpty()) {
            return emptyList()
        }

        // Generate the strict CATEGORY_LAUNCHER filter to exclude OS background noise
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        // Use MATCH_ALL to safely query packages on Android 11+
        val resolveInfos = try {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            // PackageManager unavailable (extremely rare OEM process kill) — bail safely
            return emptyList()
        }
        val launchablePackages = resolveInfos.map { it.activityInfo.packageName }.toSet()

        val sanitizedList = mutableListOf<AppUsageItem>()

        // Transform the aggregated map into human-readable, classified UI objects
        for ((packageName, totalTimeMs) in aggregatedMs) {

            // 1. Self-Exclusion: Do not show Kyzen in its own dashboard
            if (packageName == context.packageName) continue

            // 2. Threshold Filter: Ignore apps with less than 30 seconds of usage
            // Lowered from 60s to 30s to ensure consistency between Child and Parent
            // dashboards — brief but real app interactions are now included.
            if (totalTimeMs < 30_000L) continue

            // 3. CATEGORY_LAUNCHER inclusion filter — strips OS background noise
            if (launchablePackages.contains(packageName)) {
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()

                    // --- PHASE 3: AI CLASSIFICATION ---
                    // Run the weighted keyword scoring classifier on each app
                    val classification = AppClassifier.classify(packageName, appName)

                    sanitizedList.add(
                        AppUsageItem(
                            packageName = packageName,
                            appName = appName,
                            usageDurationMillis = totalTimeMs,
                            category = classification.category,
                            confidence = classification.confidence
                        )
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    // Safety catch: App was uninstalled during the query execution.
                    continue
                }
            }
        }

        // Sort descending by total aggregated usage time
        val sortedList = sanitizedList.sortedByDescending { it.usageDurationMillis }

        // --- PHASE 3: PERSIST TO ROOM ---
        // Save classified results to SQLite for the RewardEngine and history
        repository.saveTodayUsage(sortedList)

        return sortedList
    }
}
