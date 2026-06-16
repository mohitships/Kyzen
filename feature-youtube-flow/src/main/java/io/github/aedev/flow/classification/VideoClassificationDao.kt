package io.github.aedev.flow.classification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VideoClassificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VideoClassificationEntity)

    @Query("SELECT * FROM video_classification WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): VideoClassificationEntity?

    @Query("SELECT COUNT(*) FROM video_classification")
    suspend fun count(): Int
}
