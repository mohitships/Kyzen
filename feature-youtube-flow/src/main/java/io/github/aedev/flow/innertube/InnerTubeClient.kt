package io.github.aedev.flow.innertube

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * InnerTubeClient — YouTube InnerTube API client
 *
 * Provides three capabilities, all using YouTube's internal InnerTube API:
 *   1. search(query) → list of video results (title, channel, videoId, thumbnail)
 *   2. getStreamUrl(videoId) → a playable stream URL (no signature deciphering needed)
 *   3. getVideoMetadata(videoId) → (title, channel) for the classification pipeline
 *
 * ── Stream URL Resolution Strategy ──────────────────────────────────────────
 * The WEB client returns ciphered URLs that require JavaScript signature
 * deciphering. The ANDROID client returns UNCIPHERED (direct) URLs — no
 * deciphering needed. We use the ANDROID client for stream resolution.
 *
 * This eliminates the need for NewPipeExtractor (which scrapes the watch page
 * and deciphers signatures using Rhino JavaScript engine — fragile and breaks
 * when YouTube changes its page structure).
 *
 * All network calls run on Dispatchers.IO.
 */
object InnerTubeClient {

    private const val TAG = "InnerTubeClient"
    private const val BASE_URL = "https://www.youtube.com/youtubei/v1"

    // WEB client — used for search (returns rich results with thumbnails)
    private const val WEB_CLIENT_NAME = "WEB"
    private const val WEB_CLIENT_VERSION = "2.20240807.04.00"

    // ANDROID client — used for stream URL resolution (returns UNCIPHERED URLs)
    private const val ANDROID_CLIENT_NAME = "ANDROID"
    private const val ANDROID_CLIENT_VERSION = "20.10.38"

    // Public API key (same one the YouTube web app uses)
    private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Yd_64QvAo"

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
    }

    @Serializable
    data class VideoResult(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String,
        val durationText: String = "",
        val channelId: String = "",
        val isLive: Boolean = false
    )

    data class SearchResponse(
        val results: List<VideoResult>,
        val continuation: String? = null
    )

    data class PlaylistInfo(
        val playlistId: String,
        val title: String,
        val videos: List<VideoResult>,
        val continuation: String? = null
    )

    data class ChannelInfo(
        val channelId: String,
        val title: String,
        val avatarUrl: String,
        val subscriberCount: String,
        val description: String,
        val videos: List<VideoResult>,
        val continuation: String? = null
    )

    /**
     * Detailed video info including description and available quality formats.
     * Fetched from the InnerTube player endpoint (WEB client).
     */
    data class VideoDetails(
        val videoId: String,
        val title: String,
        val channel: String,
        val description: String,
        val viewCount: String,
        val lengthSeconds: String,
        val qualities: List<QualityOption>,
        val audioOptions: List<AudioOption> = emptyList()
    )

    data class QualityOption(
        val label: String,       // e.g., "360p", "720p"
        val url: String,         // playable stream URL
        val mimeType: String,    // e.g., "video/mp4"
        val isMuxed: Boolean,    // true = audio+video combined
        val audioUrl: String? = null  // paired audio stream for adaptive formats
    )

    data class AudioOption(
        val url: String,
        val mimeType: String,
        val bitrate: Long,
        val audioChannels: Int
    )

    /**
     * Searches YouTube for videos matching the query.
     * Uses the WEB client (rich results with thumbnails).
     */
    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", WEB_CLIENT_NAME)
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", query)
            }

            val responseText = httpClient.post("$BASE_URL/search") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            parseSearchResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query': ${e.message}", e)
            SearchResponse(emptyList())
        }
    }

    /**
     * Fetches the next page of search results using a continuation token.
     */
    suspend fun searchMore(continuation: String): SearchResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", WEB_CLIENT_NAME)
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("continuation", continuation)
            }

            val responseText = httpClient.post("$BASE_URL/search") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            parseContinuationResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "searchMore failed: ${e.message}", e)
            SearchResponse(emptyList())
        }
    }

    /**
     * Fetches a YouTube playlist's contents by playlist ID.
     * Uses the WEB browse endpoint with a "VL" prefixed browse ID.
     */
    suspend fun getPlaylist(playlistId: String): PlaylistInfo? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", WEB_CLIENT_NAME)
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("browseId", "VL$playlistId")
            }

            val responseText = httpClient.post("$BASE_URL/browse") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            parsePlaylistResponse(responseText, playlistId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playlist $playlistId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches channel info and videos by channel ID (UC...).
     * Uses the WEB browse endpoint.
     */
    suspend fun getChannel(channelId: String): ChannelInfo? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", WEB_CLIENT_NAME)
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("browseId", channelId)
            }

            val responseText = httpClient.post("$BASE_URL/browse") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            parseChannelResponse(responseText, channelId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get channel $channelId: ${e.message}", e)
            null
        }
    }

    /**
     * Resolves a playable stream URL for a video ID.
     *
     * Uses the ANDROID InnerTube client, which returns UNCIPHERED stream URLs —
     * no JavaScript signature deciphering needed. This is more reliable than
     * NewPipeExtractor's page-scraping approach.
     *
     * Returns a progressive (muxed audio+video) URL that ExoPlayer can play directly.
     * Falls back to adaptive formats (video-only + audio-only) if no muxed format exists.
     * Returns null if no playable URL is found.
     */
    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Resolving stream URL for video: $videoId")

            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", ANDROID_CLIENT_NAME)
                        put("clientVersion", ANDROID_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                        // The Android client requires these fields for proper format delivery
                        put("androidSdkVersion", "30")
                    })
                })
                put("videoId", videoId)
            }

            val responseText = httpClient.post("$BASE_URL/player") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            val json = Json { ignoreUnknownKeys = true }
            val response = json.parseToJsonElement(responseText).jsonObject

            // Check for playability status errors
            val playabilityStatus = response["playabilityStatus"]?.jsonObject
            if (playabilityStatus != null) {
                val status = playabilityStatus["status"]?.jsonPrimitive?.contentOrNull
                if (status != "OK") {
                    val reason = playabilityStatus["reason"]?.jsonPrimitive?.contentOrNull
                        ?: playabilityStatus["messages"]?.jsonArray
                            ?.joinToString(" ") { it.jsonPrimitive.contentOrNull.orEmpty() }
                        ?: "Unknown error"
                    Log.w(TAG, "Video $videoId playability status: $status — $reason")
                    return@withContext null
                }
            }

            val streamingData = response["streamingData"]?.jsonObject
                ?: run {
                    Log.w(TAG, "No streamingData in player response for $videoId")
                    return@withContext null
                }

            // Try progressive (muxed audio+video) formats first — directly playable
            val formats = streamingData["formats"]?.jsonArray
            if (formats != null) {
                Log.d(TAG, "Found ${formats.size} muxed formats")
                for (format in formats) {
                    val formatObj = format.jsonObject
                    val url = formatObj["url"]?.jsonPrimitive?.contentOrNull
                    if (url != null) {
                        val mimeType = formatObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                        val quality = formatObj["qualityLabel"]?.jsonPrimitive?.contentOrNull ?: ""
                        Log.d(TAG, "Selected muxed format: $quality ($mimeType)")
                        Log.d(TAG, "Stream URL (first 100 chars): ${url.take(100)}...")
                        return@withContext url
                    }
                }
            }

            // Fallback: adaptive formats — need to pick best video + best audio
            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
            if (adaptiveFormats != null) {
                Log.d(TAG, "Found ${adaptiveFormats.size} adaptive formats")
                // Try to find a video stream with both video and audio
                for (format in adaptiveFormats) {
                    val formatObj = format.jsonObject
                    val mimeType = formatObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (mimeType.contains("video/") && !mimeType.contains("video-only")) {
                        val url = formatObj["url"]?.jsonPrimitive?.contentOrNull
                        if (url != null) {
                            Log.d(TAG, "Selected adaptive video format: $mimeType")
                            Log.d(TAG, "Stream URL (first 100 chars): ${url.take(100)}...")
                            return@withContext url
                        }
                    }
                }

                // Last resort: first available URL from adaptive formats
                for (format in adaptiveFormats) {
                    val url = format.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                    if (url != null) {
                        Log.d(TAG, "Using first adaptive format URL: ${url.take(100)}...")
                        return@withContext url
                    }
                }
            }

            // Fallback: HLS manifest (live streams)
            streamingData["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull?.let { hlsUrl ->
                Log.d(TAG, "Using HLS manifest: ${hlsUrl.take(100)}...")
                return@withContext hlsUrl
            }

            // Fallback: DASH manifest (live streams)
            streamingData["dashManifestUrl"]?.jsonPrimitive?.contentOrNull?.let { dashUrl ->
                Log.d(TAG, "Using DASH manifest: ${dashUrl.take(100)}...")
                return@withContext dashUrl
            }

            Log.w(TAG, "No playable stream URL found for video: $videoId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL for $videoId: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Extracts video metadata (title, channel) using the InnerTube player endpoint.
     * Uses the WEB client (returns videoDetails with title and author).
     */
    suspend fun getVideoMetadata(videoId: String): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", WEB_CLIENT_NAME)
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("videoId", videoId)
            }

            val responseText = httpClient.post("$BASE_URL/player") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            val json = Json { ignoreUnknownKeys = true }
            val response = json.parseToJsonElement(responseText).jsonObject
            val videoDetails = response["videoDetails"]?.jsonObject ?: return@withContext null
            val title = videoDetails["title"]?.jsonPrimitive?.contentOrNull
            val author = videoDetails["author"]?.jsonPrimitive?.contentOrNull
            Pair(title, author)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video metadata for $videoId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches full video details: title, channel, description, view count, duration,
     * and all available quality options (muxed + adaptive).
     * Uses the ANDROID client for unciphered stream URLs.
     */
    suspend fun getVideoDetails(videoId: String): VideoDetails? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", ANDROID_CLIENT_NAME)
                        put("clientVersion", ANDROID_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "IN")
                        put("androidSdkVersion", "30")
                    })
                })
                put("videoId", videoId)
            }

            val responseText = httpClient.post("$BASE_URL/player") {
                parameter("key", KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText()

            val json = Json { ignoreUnknownKeys = true }
            val response = json.parseToJsonElement(responseText).jsonObject

            // Check playability
            val playabilityStatus = response["playabilityStatus"]?.jsonObject
            if (playabilityStatus != null) {
                val status = playabilityStatus["status"]?.jsonPrimitive?.contentOrNull
                if (status != "OK") {
                    Log.w(TAG, "Video $videoId playability: $status")
                    return@withContext null
                }
            }

            val videoDetails = response["videoDetails"]?.jsonObject ?: return@withContext null
            val title = videoDetails["title"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
            val author = videoDetails["author"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
            val description = videoDetails["shortDescription"]?.jsonPrimitive?.contentOrNull ?: ""
            val viewCount = videoDetails["viewCount"]?.jsonPrimitive?.contentOrNull ?: "0"
            val lengthSeconds = videoDetails["lengthSeconds"]?.jsonPrimitive?.contentOrNull ?: "0"

            // Collect all quality options
            val qualities = mutableListOf<QualityOption>()
            val streamingData = response["streamingData"]?.jsonObject

            // Muxed formats (audio+video combined) — best for simple playback
            streamingData?.get("formats")?.jsonArray?.let { formats ->
                for (format in formats) {
                    val formatObj = format.jsonObject
                    val url = formatObj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                    val mimeType = formatObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                    val quality = formatObj["qualityLabel"]?.jsonPrimitive?.contentOrNull
                        ?: formatObj["quality"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    qualities.add(QualityOption(quality, url, mimeType, true))
                }
            }

            // Adaptive formats (video-only or audio-only) — for higher quality
            val adaptiveVideoQualities = mutableListOf<QualityOption>()
            val audioOptions = mutableListOf<AudioOption>()

            streamingData?.get("adaptiveFormats")?.jsonArray?.let { adaptiveFormats ->
                for (format in adaptiveFormats) {
                    val formatObj = format.jsonObject
                    val mimeType = formatObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (mimeType.contains("video/")) {
                        val url = formatObj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val quality = formatObj["qualityLabel"]?.jsonPrimitive?.contentOrNull
                            ?: formatObj["quality"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                        adaptiveVideoQualities.add(QualityOption(quality, url, mimeType, false))
                    } else if (mimeType.contains("audio/")) {
                        val url = formatObj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val bitrate = formatObj["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L
                        val audioChannels = formatObj["audioChannels"]?.jsonPrimitive?.intOrNull ?: 0
                        audioOptions.add(AudioOption(url, mimeType, bitrate, audioChannels))
                    }
                }
            }

            // Pick the best audio stream: highest bitrate, prefer opus over mp4a
            val bestAudio = audioOptions.maxByOrNull { audio ->
                val codecBonus = if (audio.mimeType.contains("opus")) 1_000_000L else 0L
                audio.bitrate + codecBonus
            }

            // Add adaptive video qualities with paired audio URL
            for (videoQuality in adaptiveVideoQualities) {
                qualities.add(videoQuality.copy(audioUrl = bestAudio?.url))
            }

            // Check for HLS manifest (live streams and some VOD)
            streamingData?.get("hlsManifestUrl")?.jsonPrimitive?.contentOrNull?.let { hlsUrl ->
                qualities.add(QualityOption("HLS", hlsUrl, "application/x-mpegURL", true))
            }

            // Check for DASH manifest (live streams and some VOD)
            streamingData?.get("dashManifestUrl")?.jsonPrimitive?.contentOrNull?.let { dashUrl ->
                qualities.add(QualityOption("DASH", dashUrl, "application/dash+xml", true))
            }

            Log.d(TAG, "Fetched ${qualities.size} quality options for $videoId (${audioOptions.size} audio streams)")

            VideoDetails(videoId, title, author, description, viewCount, lengthSeconds, qualities, audioOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video details for $videoId: ${e.message}", e)
            null
        }
    }

    private fun parsePlaylistVideo(renderer: JsonObject): VideoResult? {
        val videoId = renderer["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = renderer["title"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: renderer["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?: "Unknown"
        val channel = renderer["shortBylineText"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: "Unknown"
        val channelId = renderer["shortBylineText"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("navigationEndpoint")?.jsonObject
            ?.get("browseEndpoint")?.jsonObject
            ?.get("browseId")?.jsonPrimitive?.contentOrNull
            ?: ""
        val thumbnail = renderer["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: ""
        val duration = renderer["lengthText"]?.jsonObject
            ?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?: ""
        val isLive = checkForLive(renderer)
        return VideoResult(videoId, title, channel, thumbnail, duration, channelId, isLive)
    }

    private fun parsePlaylistResponse(responseText: String, playlistId: String): PlaylistInfo? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseText).jsonObject

            // Extract playlist title from the "header" section
            val playlistTitle = root["header"]?.jsonObject
                ?.get("playlistHeaderRenderer")?.jsonObject
                ?.get("title")?.jsonObject
                ?.get("simpleText")?.jsonPrimitive?.contentOrNull
                ?: root["header"]?.jsonObject
                    ?.get("playlistHeaderRenderer")?.jsonObject
                    ?.get("title")?.jsonObject
                    ?.get("runs")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                ?: "Playlist"

            val tabs = root["contents"]?.jsonObject
                ?.get("twoColumnBrowseResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray
                ?: return PlaylistInfo(playlistId, playlistTitle, emptyList())

            for (tab in tabs) {
                val tabContent = tab.jsonObject["tabRenderer"]?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("sectionListRenderer")?.jsonObject
                    ?: continue

                val sections = tabContent["contents"]?.jsonArray ?: continue
                for (section in sections) {
                    val playlistVideoList = section.jsonObject["playlistVideoListRenderer"]?.jsonObject ?: continue
                    val items = playlistVideoList["contents"]?.jsonArray ?: continue
                    val results = mutableListOf<VideoResult>()
                    for (item in items) {
                        val videoRenderer = item.jsonObject["playlistVideoRenderer"]?.jsonObject
                        if (videoRenderer != null) {
                            parsePlaylistVideo(videoRenderer)?.let { results.add(it) }
                        }
                    }
                    val continuation = playlistVideoList["continuations"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("nextContinuationData")?.jsonObject
                        ?.get("continuation")?.jsonPrimitive?.contentOrNull
                    return PlaylistInfo(playlistId, playlistTitle, results, continuation)
                }
            }
            return PlaylistInfo(playlistId, playlistTitle, emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playlist response: ${e.message}", e)
            return null
        }
    }

    private fun parseChannelResponse(responseText: String, channelId: String): ChannelInfo? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseText).jsonObject

            // Channel header — try multiple possible renderers
            val headerSection = root["header"]?.jsonObject

            val title: String
            val avatarUrl: String
            val subscriberCount: String

            // Try c4TabbedHeaderRenderer (classic)
            val c4Header = headerSection?.get("c4TabbedHeaderRenderer")?.jsonObject
            if (c4Header != null) {
                title = c4Header["title"]?.jsonPrimitive?.contentOrNull
                    ?: c4Header["title"]?.jsonObject?.get("runs")?.jsonArray
                        ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                    ?: "Channel"
                avatarUrl = c4Header["avatar"]?.jsonObject
                    ?.get("thumbnails")?.jsonArray
                    ?.lastOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                subscriberCount = c4Header["subscriberCountText"]?.jsonObject
                    ?.get("simpleText")?.jsonPrimitive?.contentOrNull
                    ?: c4Header["subscriberCountText"]?.jsonObject
                        ?.get("runs")?.jsonArray
                        ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                    ?: ""
            } else {
                // Fallback: metadata from channelMetadataRenderer
                val meta = root["metadata"]?.jsonObject
                    ?.get("channelMetadataRenderer")?.jsonObject
                title = meta?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: meta?.get("channelIdentifier")?.jsonPrimitive?.contentOrNull
                    ?: "Channel"
                avatarUrl = meta?.get("avatar")?.jsonObject
                    ?.get("thumbnails")?.jsonArray
                    ?.lastOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                subscriberCount = ""
            }

            // Channel description from microformat or metadata
            val description = root["microformat"]?.jsonObject
                ?.get("microformatDataRenderer")?.jsonObject
                ?.get("description")?.jsonPrimitive?.contentOrNull
                ?: root["metadata"]?.jsonObject
                    ?.get("channelMetadataRenderer")?.jsonObject
                    ?.get("description")?.jsonPrimitive?.contentOrNull
                ?: ""

            // Parse tab contents for videos
            val tabs = root["contents"]?.jsonObject
                ?.get("twoColumnBrowseResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray
                ?: return ChannelInfo(channelId, title, avatarUrl, subscriberCount, description, emptyList())

            val results = mutableListOf<VideoResult>()
            var continuation: String? = null

            for (tab in tabs) {
                val tabRenderer = tab.jsonObject["tabRenderer"]?.jsonObject ?: continue
                // Use the selected tab (Videos)
                val content = tabRenderer["content"]?.jsonObject
                if (content == null) continue

                // Try richGridRenderer (modern channel layout)
                val richGrid = content["richGridRenderer"]?.jsonObject
                if (richGrid != null) {
                    val items = richGrid["contents"]?.jsonArray ?: continue
                    for (item in items) {
                        val videoRenderer = item.jsonObject["richItemRenderer"]?.jsonObject
                            ?.get("content")?.jsonObject
                            ?.get("videoRenderer")?.jsonObject
                        if (videoRenderer != null) {
                            parseVideoRenderer(videoRenderer)?.let { results.add(it) }
                        }
                        // Check for continuation
                        val contToken = extractContinuationFromSection(item)
                        if (contToken != null) continuation = contToken
                    }
                    // Also check top-level continuations
                    richGrid["continuations"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("nextContinuationData")?.jsonObject
                        ?.get("continuation")?.jsonPrimitive?.contentOrNull?.let { continuation = it }
                    break
                }

                // Fallback: sectionListRenderer (legacy layout)
                val sectionList = content["sectionListRenderer"]?.jsonObject
                if (sectionList != null) {
                    val sections = sectionList["contents"]?.jsonArray ?: continue
                    for (section in sections) {
                        val contToken = extractContinuationFromSection(section)
                        if (contToken != null) { continuation = contToken; continue }
                        results.addAll(parseItemsFromSection(section))
                    }
                    sectionList["continuations"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("nextContinuationData")?.jsonObject
                        ?.get("continuation")?.jsonPrimitive?.contentOrNull?.let { continuation = it }
                    break
                }
            }

            return ChannelInfo(channelId, title, avatarUrl, subscriberCount, description, results, continuation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse channel response: ${e.message}", e)
            return null
        }
    }

    private fun parseVideoRenderer(videoRenderer: JsonObject): VideoResult? {
        val videoId = videoRenderer["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = videoRenderer["title"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: videoRenderer["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?: "Unknown"
        val channel = videoRenderer["ownerText"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: videoRenderer["shortBylineText"]?.jsonObject
                ?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: "Unknown"
        val channelId = videoRenderer["ownerText"]?.jsonObject
            ?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("navigationEndpoint")?.jsonObject
            ?.get("browseEndpoint")?.jsonObject
            ?.get("browseId")?.jsonPrimitive?.contentOrNull
            ?: videoRenderer["shortBylineText"]?.jsonObject
                ?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("navigationEndpoint")?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseId")?.jsonPrimitive?.contentOrNull
            ?: ""
        val thumbnail = videoRenderer["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: ""
        val duration = videoRenderer["lengthText"]?.jsonObject
            ?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?: ""
        val isLive = checkForLive(videoRenderer)
        return VideoResult(videoId, title, channel, thumbnail, duration, channelId, isLive)
    }

    private fun checkForLive(renderer: JsonObject): Boolean {
        if (renderer["lengthText"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull == "LIVE") return true
        val badges = renderer["badges"]?.jsonArray ?: return false
        return badges.any { badge ->
            badge.jsonObject["metadataBadgeRenderer"]?.jsonObject
                ?.get("label")?.jsonPrimitive?.contentOrNull == "LIVE"
        }
    }

    /**
     * Parses the InnerTube search response JSON to extract video results.
     */
    private fun parseItemsFromSection(section: JsonElement): List<VideoResult> {
        val results = mutableListOf<VideoResult>()
        val itemSection = section.jsonObject["itemSectionRenderer"]?.jsonObject ?: return results
        val items = itemSection["contents"]?.jsonArray ?: return results
        for (item in items) {
            item.jsonObject["videoRenderer"]?.jsonObject
                ?.let { parseVideoRenderer(it) }
                ?.let { results.add(it) }
        }
        return results
    }

    private fun extractContinuationFromSection(section: JsonElement): String? {
        return section.jsonObject["continuationItemRenderer"]?.jsonObject
            ?.get("continuationEndpoint")?.jsonObject
            ?.get("continuationCommand")?.jsonObject
            ?.get("token")?.jsonPrimitive?.contentOrNull
    }

    private fun parseSearchResponse(responseText: String): SearchResponse {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseText).jsonObject

            val sectionList = root["contents"]?.jsonObject
                ?.get("twoColumnSearchResultsRenderer")?.jsonObject
                ?.get("primaryContents")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject
                ?: return SearchResponse(emptyList())

            val contents = sectionList["contents"]?.jsonArray ?: return SearchResponse(emptyList())
            val results = mutableListOf<VideoResult>()
            var continuation: String? = null

            for (section in contents) {
                // Check if this section is a continuation token carrier
                val token = extractContinuationFromSection(section)
                if (token != null) {
                    continuation = token
                    continue
                }
                results.addAll(parseItemsFromSection(section))
            }

            // Fallback: check sectionListRenderer.continuations if not found in contents
            if (continuation == null) {
                continuation = sectionList["continuations"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("nextContinuationData")?.jsonObject
                    ?.get("continuation")?.jsonPrimitive?.contentOrNull
            }

            return SearchResponse(results, continuation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search response: ${e.message}", e)
            return SearchResponse(emptyList())
        }
    }

    private fun parseContinuationResponse(responseText: String): SearchResponse {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseText).jsonObject

            // Try onResponseReceivedCommands path (search continuation)
            val commands = root["onResponseReceivedCommands"]?.jsonArray
            if (commands != null) {
                for (cmd in commands) {
                    val items = cmd.jsonObject["appendContinuationItemsAction"]?.jsonObject
                        ?.get("continuationItems")?.jsonArray
                        ?: continue

                    val results = mutableListOf<VideoResult>()
                    for (section in items) {
                        results.addAll(parseItemsFromSection(section))
                    }

                    // Check for further continuation in the last item
                    val contToken = items.lastOrNull()?.jsonObject
                        ?.get("continuationItemRenderer")?.jsonObject
                        ?.get("continuationEndpoint")?.jsonObject
                        ?.get("continuationCommand")?.jsonObject
                        ?.get("token")?.jsonPrimitive?.contentOrNull

                    return SearchResponse(results, contToken)
                }
            }

            // Fallback: try continuationContents path
            val sectionList = root["continuationContents"]?.jsonObject
                ?.get("sectionListContinuation")?.jsonObject

            if (sectionList != null) {
                val contents = sectionList["contents"]?.jsonArray ?: return SearchResponse(emptyList())
                val results = mutableListOf<VideoResult>()
                for (section in contents) {
                    results.addAll(parseItemsFromSection(section))
                }

                val continuation = sectionList["continuations"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("nextContinuationData")?.jsonObject
                    ?.get("continuation")?.jsonPrimitive?.contentOrNull

                return SearchResponse(results, continuation)
            }

            return SearchResponse(emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse continuation response: ${e.message}", e)
            return SearchResponse(emptyList())
        }
    }

    private fun JsonElement?.contentOrNull(): String? =
        (this as? JsonPrimitive)?.content
}
