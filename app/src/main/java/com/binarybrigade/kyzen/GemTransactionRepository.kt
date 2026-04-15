package com.binarybrigade.kyzen

import android.content.Context

/**
 * GemTransactionRepository — Clean API for gem transaction logging and retrieval.
 *
 * All call sites (UsageMonitorService, DetoxBreakActivity, ParentDashboardActivity,
 * ChildDashboardActivity) use this class exclusively — never the DAO directly.
 *
 * Transaction types as constants — enforces consistency across call sites.
 */
class GemTransactionRepository(context: Context) {

    private val dao: GemTransactionDao =
        KyzenDatabase.getInstance(context).gemTransactionDao()

    companion object {
        const val TYPE_PRODUCTIVE_EARN     = "PRODUCTIVE_EARN"
        const val TYPE_DETOX_BONUS         = "DETOX_BONUS"
        const val TYPE_FIRST_LAUNCH        = "FIRST_LAUNCH"
        const val TYPE_PARENT_GIFT         = "PARENT_GIFT"
        const val TYPE_ENTERTAINMENT_SPEND = "ENTERTAINMENT_SPEND"
        const val TYPE_PARENT_DEDUCT       = "PARENT_DEDUCT"
    }

    /**
     * Logs a gem transaction and immediately prunes to 50-row cap.
     * Safe to call from any coroutine on Dispatchers.IO.
     *
     * @param type   One of the TYPE_* constants above
     * @param amount Positive for credits, negative for debits
     * @param label  Human-readable label shown in the history UI
     */
    suspend fun logTransaction(type: String, amount: Int, label: String) {
        dao.insert(
            GemTransactionEntity(
                type      = type,
                amount    = amount,
                label     = label,
                timestampMs = System.currentTimeMillis()
            )
        )
        dao.pruneToLimit()
    }

    /**
     * Returns the most recent 50 transactions, newest first.
     * Returns empty list if no transactions exist yet.
     */
    suspend fun getHistory(): List<GemTransactionEntity> = dao.getRecent50()

    /**
     * Returns true if there are no transactions yet — used for empty state.
     */
    suspend fun isEmpty(): Boolean = dao.getCount() == 0

    /**
     * Deletes all transactions — called on daily reset so each day starts fresh.
     */
    suspend fun clearAll() {
        dao.deleteAll()
    }
}
