package com.binarybrigade.kyzen

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * UsageRepository — MVVM Data Access Abstraction Layer (Phase 3)
 *
 * This is the single point of contact between all application logic
 * (ViewModels, Services, RewardEngine) and the underlying data sources
 * (Room SQLite database). No class should ever talk directly to the DAO.
 *
 * Responsibilities:
 * 1. Save sanitised + classified usage data to Room
 * 2. Read usage data for the dashboard and reward engine
 * 3. Handle date formatting consistently across the entire app
 * 4. Perform routine database maintenance (old record deletion)
 *
 * All functions are suspend functions — must be called from a coroutine.
 */
class UsageRepository(context: Context) {

    private val dao: AppUsageDao = KyzenDatabase.getInstance(context).appUsageDao()
    private val usm: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    companion object {
        // Thread-safe ISO 8601 date formatter (java.time API, requires API 26+ — our minSdk is 29 ✓)
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Returns today's date as an ISO string e.g. "2026-02-27".
         * LocalDate is immutable and fully thread-safe — safe for Dispatchers.IO.
         */
        fun todayString(): String = LocalDate.now().format(DATE_FORMATTER)

        /**
         * Returns a date string N days in the past e.g. dateStringForDaysAgo(7) = "2026-02-20".
         * Used by pruneOldRecords() to compute the 7-day retention cutoff.
         */
        fun dateStringForDaysAgo(days: Int): String =
            LocalDate.now().minusDays(days.toLong()).format(DATE_FORMATTER)
    }

    /**
     * Saves the full sanitised + classified usage list for today to Room.
     * Maps each AppUsageItem to an AppUsageEntity before persisting.
     * Called by UsageTracker after every data extraction cycle.
     */
    suspend fun saveTodayUsage(items: List<AppUsageItem>) {
        val today = todayString()
        val entities = items.map { item ->
            AppUsageEntity(
                packageName = item.packageName,
                appName = item.appName,
                usageDurationMillis = item.usageDurationMillis,
                date = today,
                category = item.category.name,
                confidence = item.confidence
            )
        }
        dao.insertAll(entities)
    }

    /**
     * Retrieves today's usage records from Room, mapped back to AppUsageItem
     * for the UI layer. Returns empty list if no records exist yet.
     */
    suspend fun getTodayUsage(): List<AppUsageItem> {
        return dao.getUsageForDate(todayString()).mapNotNull { entity ->
            AppUsageItem(
                packageName = entity.packageName,
                appName = entity.appName,
                usageDurationMillis = entity.usageDurationMillis,
                category = safeCategory(entity.category),
                confidence = entity.confidence
            )
        }
    }

    /**
     * Retrieves today's usage for a specific category.
     * Used by RewardEngine to compute productive vs. entertainment totals.
     */
    suspend fun getTodayUsageByCategory(category: AppClassifier.AppCategory): List<AppUsageItem> {
        return dao.getUsageForDateAndCategory(todayString(), category.name).mapNotNull { entity ->
            AppUsageItem(
                packageName = entity.packageName,
                appName = entity.appName,
                usageDurationMillis = entity.usageDurationMillis,
                category = safeCategory(entity.category),
                confidence = entity.confidence
            )
        }
    }

    /**
     * Safely converts a category string from the database to AppCategory.
     * Defaults to NEUTRAL if the string is corrupted or unrecognised —
     * prevents IllegalArgumentException from AppCategory.valueOf() crashing the app.
     */
    private fun safeCategory(categoryStr: String): AppClassifier.AppCategory {
        return try {
            AppClassifier.AppCategory.valueOf(categoryStr)
        } catch (e: IllegalArgumentException) {
            AppClassifier.AppCategory.NEUTRAL
        }
    }

    /**
     * Returns today's already-accumulated usage duration in milliseconds for a
     * specific package, or 0 if no record exists yet.
     * Used by UsageMonitorService to pre-offset the gem earning timer so that
     * gems fire aligned with cumulative daily usage (what the UI displays),
     * not just the current service session duration.
     */
    suspend fun getTodayUsageMsForPackage(packageName: String): Long {
        return dao.getUsageForDateAndPackage(todayString(), packageName)
            ?.usageDurationMillis ?: 0L
    }

    /**
     * Queries the OS directly (UsageStatsManager.queryEvents) to get the
     * cumulative usage duration in milliseconds for a specific package since
     * midnight today. This is the live, authoritative source — not dependent
     * on the DB having been written by the dashboard.
     *
     * Used by the cumulative gem model in UsageMonitorService to compute
     * exactly how many complete 2-minute blocks a child has used a productive
     * app today, regardless of app switches or service restarts.
     *
     * Returns 0L if no usage found or permission not granted.
     */
    fun getLiveUsageMsForPackage(packageName: String): Long {
        val midnight = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()

        return try {
            val events = usm.queryEvents(midnight, now)
            val event  = UsageEvents.Event()
            var totalMs    = 0L
            var resumedAt  = -1L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (resumedAt > 0L) {
                            totalMs  += event.timeStamp - resumedAt
                            resumedAt = -1L
                        }
                    }
                }
            }
            // Still in foreground — add ongoing session
            if (resumedAt > 0L) totalMs += now - resumedAt
            totalMs
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Returns true if there are no usage records for today.
     * Used by RewardEngine to detect first-run / fresh-install scenario
     * and apply starter credits instead of computing from empty data.
     */
    suspend fun isTodayDatabaseEmpty(): Boolean {
        return dao.getRecordCountForDate(todayString()) == 0
    }

    /**
     * Deletes records older than 7 days to keep the database lean.
     * Called once per day by the background maintenance routine.
     */
    suspend fun pruneOldRecords(daysToKeep: Int = 7) {
        // Guard: daysToKeep must be positive to prevent deleting recent/future records
        val safeDays = daysToKeep.coerceAtLeast(1)
        val cutoff = dateStringForDaysAgo(safeDays)
        dao.deleteRecordsOlderThan(cutoff)
    }
}
