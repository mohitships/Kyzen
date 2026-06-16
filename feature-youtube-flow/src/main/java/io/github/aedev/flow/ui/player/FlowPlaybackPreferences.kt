package io.github.aedev.flow.ui.player

import android.content.Context

object FlowPlaybackPreferences {

    private const val PREFS_NAME = "flow_playback_prefs"
    private const val KEY_VIDEO_ID = "last_video_id"
    private const val KEY_TITLE = "last_video_title"
    private const val KEY_CHANNEL = "last_channel_name"
    private const val KEY_URL = "last_stream_url"
    private const val KEY_POSITION = "last_position_ms"

    data class LastPlayedVideo(
        val videoId: String,
        val title: String,
        val channel: String,
        val url: String,
        val positionMs: Long
    )

    fun saveLastPlayed(
        context: Context,
        videoId: String,
        title: String,
        channel: String,
        url: String,
        positionMs: Long
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_VIDEO_ID, videoId)
            .putString(KEY_TITLE, title)
            .putString(KEY_CHANNEL, channel)
            .putString(KEY_URL, url)
            .putLong(KEY_POSITION, positionMs)
            .apply()
    }

    fun loadLastPlayed(context: Context): LastPlayedVideo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val videoId = prefs.getString(KEY_VIDEO_ID, null) ?: return null
        return LastPlayedVideo(
            videoId = videoId,
            title = prefs.getString(KEY_TITLE, "") ?: "",
            channel = prefs.getString(KEY_CHANNEL, "") ?: "",
            url = prefs.getString(KEY_URL, "") ?: "",
            positionMs = prefs.getLong(KEY_POSITION, 0L)
        )
    }

    fun clearLastPlayed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
