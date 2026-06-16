package io.github.aedev.flow.classification

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * FlowDatabase — Room database for the :feature-youtube-flow module.
 *
 * Self-contained within the library module — does NOT reference KyzenDatabase
 * from :app (which would create a circular dependency). Holds only the
 * video_classification cache table.
 */
@Database(
    entities = [VideoClassificationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FlowDatabase : RoomDatabase() {

    abstract fun videoClassificationDao(): VideoClassificationDao

    companion object {
        @Volatile
        private var INSTANCE: FlowDatabase? = null

        fun getInstance(context: Context): FlowDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowDatabase::class.java,
                    "flow_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
