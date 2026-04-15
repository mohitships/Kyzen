package com.binarybrigade.kyzen

/**
 * AppUsageItem — UI-Layer Data Model (Phase 3 Upgraded)
 *
 * Represents a sanitised, classified, user-facing application and its usage data.
 * This is a pure UI model — it carries no Room annotations (see AppUsageEntity
 * for the database-layer equivalent). The Repository handles all mapping.
 *
 * Phase 3 additions:
 * - category: AI classifier output (PRODUCTIVE / ENTERTAINMENT / NEUTRAL)
 * - confidence: Classifier confidence score 0–99% (displayed as badge on dashboard)
 */
data class AppUsageItem(
    val packageName: String,                    // e.g., "com.google.android.youtube"
    val appName: String,                        // e.g., "YouTube"
    val usageDurationMillis: Long,              // Total foreground time today in milliseconds
    val category: AppClassifier.AppCategory =  // AI-classified behavioural category
        AppClassifier.AppCategory.NEUTRAL,
    val confidence: Int = 0                     // Classifier confidence score (0–99%)
)