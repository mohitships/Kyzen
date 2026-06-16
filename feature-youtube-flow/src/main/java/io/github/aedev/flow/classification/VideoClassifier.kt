package io.github.aedev.flow.classification

import android.content.Context
import android.util.Log
import io.github.aedev.flow.ui.player.FlowPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.json.JSONObject

/**
 * VideoClassifier — 3-Tier Classification Pipeline Orchestrator (Phase 4 §4.2)
 *
 * Runs when a new video loads in FlowPlayerActivity's Player.Listener.
 * The pipeline has three tiers, evaluated in order:
 *
 *   Tier 1: Local Cache Check (<5ms)
 *     Query the video_classification Room table by videoId. If a cached result
 *     exists, push it to FlowPlaybackState and terminate.
 *
 *   Tier 2: VIP Fast-Path Check (<5ms)
 *     Parse the bundled res/raw/educational_channels_raw.json (736 channels).
 *     If the channel name matches a whitelisted channel, write PRODUCTIVE to the
 *     cache and FlowPlaybackState, bypassing the AI entirely.
 *
 *   Tier 3: 15-Second Anti-Cheat Window + DeBERTa/Keyword Inference
 *     Set FlowPlaybackState.currentCategory = null (NEUTRAL — 0 gem impact).
 *     Wait 15 seconds. If the user skips before 15s, cancel the Job (battery save).
 *     If they stay, run DeBERTa inference (with 500ms kill-switch). If DeBERTa
 *     is disabled/fails, fall back to KeywordClassifier. Write the result to the
 *     cache table and FlowPlaybackState.
 */
class VideoClassifier(private val context: Context) {

    companion object {
        private const val TAG = "VideoClassifier"
        const val ANTI_CHEAT_DELAY_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao = FlowDatabase.getInstance(context).videoClassificationDao()

    @Volatile
    private var vipChannels: Set<String>? = null

    fun classify(videoId: String, title: String, channel: String): Job? {
        return scope.launch {
            try {
                val cached = dao.getByVideoId(videoId)
                if (cached != null) {
                    FlowPlaybackState.onCategoryClassified(cached.category)
                    Log.i(TAG, "Tier 1 cache hit: $videoId → ${cached.category}")
                    return@launch
                }

                if (isVipChannel(channel)) {
                    val result = KeywordClassifier.ClassificationResult("PRODUCTIVE", 1.0f)
                    cacheResult(videoId, title, channel, result)
                    FlowPlaybackState.onCategoryClassified("PRODUCTIVE")
                    Log.i(TAG, "Tier 2 VIP hit: $channel → PRODUCTIVE")
                    return@launch
                }

                Log.i(TAG, "Tier 3: starting 15s anti-cheat window for $videoId")
                delay(ANTI_CHEAT_DELAY_MS)

                if (!isActive) return@launch

                val result = DebertaInferenceEngine.classify(context, title, channel)
                    ?: KeywordClassifier.classify(title, channel).also {
                        Log.i(TAG, "DeBERTa unavailable — keyword fallback: $videoId → ${it.category}")
                    }

                if (result != null) {
                    cacheResult(videoId, title, channel, result)
                    FlowPlaybackState.onCategoryClassified(result.category)
                    Log.i(TAG, "Tier 3 inference complete: $videoId → ${result.category} (${result.confidence})")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Classification cancelled (user skipped video): $videoId")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Classification pipeline error for $videoId", e)
                FlowPlaybackState.onCategoryClassified("ENTERTAINMENT")
            }
        }
    }

    private fun isVipChannel(channelName: String): Boolean {
        if (channelName.isBlank()) return false
        if (vipChannels == null) {
            synchronized(this) {
                if (vipChannels == null) {
                    vipChannels = loadVipChannels()
                }
            }
        }
        val channels = vipChannels ?: return false
        val normalized = channelName.trim().lowercase()
        return channels.any { it.equals(normalized, ignoreCase = true) }
    }

    private fun loadVipChannels(): Set<String> {
        return try {
            val resId = context.resources.getIdentifier(
                "educational_channels_raw",
                "raw",
                context.packageName
            )
            val inputStream = context.resources.openRawResource(resId)
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonStr)
            val channelsArray = jsonObject.getJSONArray("channels")

            val channelSet = mutableSetOf<String>()
            for (i in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(i)
                val name = channel.optString("name", "")
                if (name.isNotBlank()) {
                    channelSet.add(name.trim().lowercase())
                }
            }
            Log.i(TAG, "Loaded ${channelSet.size} VIP educational channels")
            channelSet
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VIP channels JSON", e)
            emptySet()
        }
    }

    private suspend fun cacheResult(
        videoId: String,
        title: String,
        channel: String,
        result: KeywordClassifier.ClassificationResult
    ) {
        try {
            dao.upsert(
                VideoClassificationEntity(
                    videoId = videoId,
                    title = title,
                    channel = channel,
                    category = result.category,
                    confidence = result.confidence,
                    classifiedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache classification for $videoId", e)
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
