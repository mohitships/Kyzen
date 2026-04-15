package com.binarybrigade.kyzen

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * KyzenDatabase — Room SQLite Database Singleton
 *
 * Single source of truth for all local persistent storage in Kyzen.
 * Implemented as a thread-safe singleton using double-checked locking.
 *
 * Version history:
 * v1 — Phase 3: Initial schema — app_usage table (AppUsageEntity)
 * v2 — Session 7: Added gem_transactions table (GemTransactionEntity)
 *      Migration: purely additive — creates new table, preserves all app_usage data.
 */
@Database(
    entities = [AppUsageEntity::class, GemTransactionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class KyzenDatabase : RoomDatabase() {

    abstract fun appUsageDao(): AppUsageDao
    abstract fun gemTransactionDao(): GemTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: KyzenDatabase? = null

        /**
         * Migration from v1 to v2 — adds gem_transactions table.
         * Purely additive: existing app_usage data is fully preserved.
         * No columns dropped, no tables altered.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gem_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Returns the singleton database instance, creating it if necessary.
         * Thread-safe via double-checked locking.
         */
        fun getInstance(context: Context): KyzenDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KyzenDatabase::class.java,
                    "kyzen_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
