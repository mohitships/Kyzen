package com.binarybrigade.kyzen

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * GemTransactionDao — Room DAO for the gem_transactions table.
 * All functions are suspend functions — must be called from a coroutine.
 */
@Dao
interface GemTransactionDao {

    /**
     * Inserts a single transaction record.
     * IGNORE strategy — safe duplicate protection (shouldn't happen in practice).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: GemTransactionEntity)

    /**
     * Returns the most recent 50 transactions, newest first.
     * This is the full dataset shown in GemHistoryFragment.
     */
    @Query("SELECT * FROM gem_transactions ORDER BY timestampMs DESC LIMIT 50")
    suspend fun getRecent50(): List<GemTransactionEntity>

    /**
     * Prunes the table to keep only the most recent 50 rows.
     * Called immediately after every insert to enforce the cap.
     * Uses a subquery to find the 50th row's timestamp and deletes everything older.
     */
    @Query("""
        DELETE FROM gem_transactions 
        WHERE id NOT IN (
            SELECT id FROM gem_transactions 
            ORDER BY timestampMs DESC 
            LIMIT 50
        )
    """)
    suspend fun pruneToLimit()

    /**
     * Returns total count of transactions — used for empty state detection.
     */
    @Query("SELECT COUNT(*) FROM gem_transactions")
    suspend fun getCount(): Int

    /**
     * Deletes all transactions — called on daily reset for a fresh start each day.
     */
    @Query("DELETE FROM gem_transactions")
    suspend fun deleteAll()
}
