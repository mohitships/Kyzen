package io.github.aedev.flow.classification

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * VideoClassificationEntity — Room entity for the video classification cache.
 *
 * Caches the DeBERTa/keyword classification result for each YouTube video so the
 * classification engine never re-runs inference on a previously-seen video.
 */
@Entity(tableName = "video_classification")
data class VideoClassificationEntity(
    @PrimaryKey
    val videoId: String,
    val title: String,
    val channel: String,
    val category: String,
    val confidence: Float,
    val classifiedAt: Long
)
