package com.binarybrigade.kyzen

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * AppUsageDao — Room Data Access Object
 *
 * Defines all database operations for the app_usage table.
 * All functions are suspend functions — they MUST be called from a coroutine
 * (never from the main thread) to comply with Room's threading policy.
 */
@Dao
interface AppUsageDao {

    /**
     * Inserts or replaces all usage records for a given day.
     * REPLACE strategy ensures re-running the tracker on the same day
     * overwrites stale data rather than creating duplicate rows.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<AppUsageEntity>)

    /**
     * Retrieves all usage records for a specific date, sorted by duration descending.
     * Used by the dashboard to display today's usage.
     *
     * @param date ISO date string e.g. "2026-02-27"
     */
    @Query("SELECT * FROM app_usage WHERE date = :date ORDER BY usageDurationMillis DESC")
    suspend fun getUsageForDate(date: String): List<AppUsageEntity>

    /**
     * Retrieves all usage records for a specific date AND category.
     * Used by the RewardEngine to compute productive/entertainment time totals.
     *
     * @param date ISO date string e.g. "2026-02-27"
     * @param category One of "PRODUCTIVE", "ENTERTAINMENT", "NEUTRAL"
     */
    @Query("SELECT * FROM app_usage WHERE date = :date AND category = :category")
    suspend fun getUsageForDateAndCategory(date: String, category: String): List<AppUsageEntity>

    /**
     * Deletes all records older than the specified date.
     * Called by the nightly maintenance routine to keep the database lean.
     * Retains the last 7 days of history for future analytics features.
     *
     * @param cutoffDate ISO date string — records older than this are purged
     */
    @Query("DELETE FROM app_usage WHERE date < :cutoffDate")
    suspend fun deleteRecordsOlderThan(cutoffDate: String)

    /**
     * Returns the count of records for a given date.
     * Used to detect a fresh install / first run of the day scenario.
     */
    @Query("SELECT COUNT(*) FROM app_usage WHERE date = :date")
    suspend fun getRecordCountForDate(date: String): Int

    /**
     * Returns the usage record for a specific package on a specific date, or null if none.
     * Used by UsageMonitorService to pre-offset the gem earning timer based on
     * already-accumulated usage when the service first detects a productive app.
     *
     * @param date        ISO date string e.g. "2026-02-27"
     * @param packageName The app package name to look up
     */
    @Query("SELECT * FROM app_usage WHERE date = :date AND packageName = :packageName LIMIT 1")
    suspend fun getUsageForDateAndPackage(date: String, packageName: String): AppUsageEntity?
}
