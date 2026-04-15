package com.binarybrigade.kyzen

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * GemTransactionEntity — Room entity for the gem_transactions table.
 *
 * Each row represents one gem credit or debit event.
 * The table is capped at 50 rows — oldest are pruned on each insert.
 *
 * Transaction types (stored as String for readability in DB):
 *   PRODUCTIVE_EARN    — +1 gem per 2 min of productive app use
 *   DETOX_BONUS        — +10 gems on detox break completion
 *   FIRST_LAUNCH       — +5 gems one-time welcome bonus
 *   PARENT_GIFT        — parent adds gems via Parent Dashboard
 *   ENTERTAINMENT_SPEND — -1 gem per 60s of entertainment use
 *   PARENT_DEDUCT      — parent deducts gems via Parent Dashboard
 */
@Entity(tableName = "gem_transactions")
data class GemTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** One of the 6 transaction type strings above */
    val type: String,

    /** Amount — positive for credits, negative for debits */
    val amount: Int,

    /** Human-readable label shown in the UI e.g. "Focused on Coursera" */
    val label: String,

    /** Epoch milliseconds — used for display and ordering */
    val timestampMs: Long = System.currentTimeMillis()
)
