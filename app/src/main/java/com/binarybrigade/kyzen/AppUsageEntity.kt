package com.binarybrigade.kyzen

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AppUsageEntity — Room SQLite Persistent Data Model (Phase 3)
 *
 * This is the database-layer representation of a single app's usage record.
 * It is deliberately kept separate from AppUsageItem (the UI-layer model)
 * to maintain clean MVVM separation of concerns. The Repository handles
 * all mapping between this Entity and the UI model.
 *
 * Schema:
 * - Primary key is auto-generated (id)
 * - Composite UNIQUE index on (packageName, date) — the true logical key.
 *   This ensures REPLACE conflict strategy correctly upserts records instead
 *   of creating duplicate rows on each onResume() call.
 * - category and confidence are populated by AppClassifier on each write
 * - syncedToCloud reserved for future Firebase integration
 */
@Entity(
    tableName = "app_usage",
    indices = [Index(value = ["packageName", "date"], unique = true)]
)
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val packageName: String,          // e.g., "com.google.android.youtube"
    val appName: String,              // e.g., "YouTube"
    val usageDurationMillis: Long,    // Total foreground time in milliseconds
    val date: String,                 // ISO date string "YYYY-MM-DD" — daily boundary key
    val category: String,             // "PRODUCTIVE" | "ENTERTAINMENT" | "NEUTRAL"
    val confidence: Int,              // Classifier confidence 0–99%
    val syncedToCloud: Boolean = false // Reserved for Phase 4 Firebase integration
)
