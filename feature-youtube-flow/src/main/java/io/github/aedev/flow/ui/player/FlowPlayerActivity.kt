package io.github.aedev.flow.ui.player

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.aedev.flow.classification.DebertaInferenceEngine
import io.github.aedev.flow.classification.VideoClassifier
import io.github.aedev.flow.innertube.InnerTubeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * FlowPlayerActivity — Embedded YouTube Player with Search (Phase 2 + Phase 4)
 *
 * Launched via explicit Intent from Kyzen's UsageMonitorService intercept overlay
 * when the child selects "Watch for Learning".
 *
 * Flow:
 *   1. Search bar at the top — child types a query
 *   2. InnerTube search returns video results (title, channel, thumbnail, duration)
 *   3. Child taps a result → stream URL resolved → ExoPlayer plays it
 *   4. VideoClassifier runs the 3-tier classification pipeline (cache → VIP → DeBERTa/keyword)
 *   5. FlowPlaybackState.currentCategory is set → monitor's economy tracks it
 *
 * Shorts are blocked by design.
 */
class FlowPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "flow_video_id"
        const val EXTRA_VIDEO_TITLE = "flow_video_title"
        const val EXTRA_CHANNEL_NAME = "flow_channel_name"
        const val EXTRA_STREAM_URL = "flow_stream_url"

        @Volatile
        var activeExoPlayer: ExoPlayer? = null

        private val SHORTS_URL_PATTERNS = listOf(
            Regex("/shorts/"),
            Regex("youtube\\.com/shorts"),
            Regex("youtu\\.be/shorts")
        )
    }

    private var videoClassifier: VideoClassifier? = null
    private var classificationJob: Job? = null
    private var trimMemoryCallback: ComponentCallbacks2? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        if (isShortsContent(videoId, streamUrl)) {
            finish()
            return
        }

        videoClassifier = VideoClassifier(applicationContext)

        trimMemoryCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                        DebertaInferenceEngine.closeOnMemoryCritical()
                    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
                        kotlinx.coroutines.runBlocking { DebertaInferenceEngine.closeOnMemoryModerate() }
                }
            }
            override fun onLowMemory() { DebertaInferenceEngine.closeOnMemoryCritical() }
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        applicationContext.registerComponentCallbacks(trimMemoryCallback)

        FlowPlaybackState.onVideoLoaded(videoId, videoTitle, channelName)

        if (!videoId.isNullOrBlank()) {
            startClassification(videoId, videoTitle ?: "", channelName ?: "")
        }

        setContent {
            MaterialTheme {
                FlowPlayerScreen(
                    initialStreamUrl = streamUrl,
                    onPlayerReady = { player -> setupPlayerListener(player) },
                    onVideoClassify = { videoId, title, channel ->
                        classificationJob?.cancel()
                        FlowPlaybackState.onVideoLoaded(videoId, title, channel)
                        startClassification(videoId, title, channel)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FlowPlaybackState.onPlayerActivated()
    }

    override fun onPause() {
        super.onPause()
        FlowPlaybackState.onPlayerDeactivated()
    }

    override fun onDestroy() {
        super.onDestroy()
        classificationJob?.cancel()
        classificationJob = null
        videoClassifier?.shutdown()
        videoClassifier = null
        kotlinx.coroutines.runBlocking { DebertaInferenceEngine.closeOnPlayerExit() }
        trimMemoryCallback?.let { applicationContext.unregisterComponentCallbacks(it) }
        trimMemoryCallback = null
        releasePlayer()
        FlowPlaybackState.reset()
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Classification is handled by playVideo() when the user selects a video
                // from search results. This listener handles transitions from auto-playlists
                // or related videos (not currently implemented). The mediaItem.mediaId is
                // the stream URL, not the video ID, so we don't use it for classification.
            }
        })
    }

    private fun startClassification(videoId: String, title: String, channel: String) {
        classificationJob = videoClassifier?.classify(videoId, title, channel)
    }

    private fun releasePlayer() {
        // Player is managed by the Compose DisposableEffect — nothing to release here.
    }

    private fun isShortsContent(videoId: String?, streamUrl: String?): Boolean {
        val id = videoId.orEmpty()
        val url = streamUrl.orEmpty()
        return SHORTS_URL_PATTERNS.any { it.containsMatchIn(id) || it.containsMatchIn(url) }
    }
}

// ─── Compose UI ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
private fun FlowPlayerScreen(
    initialStreamUrl: String?,
    onPlayerReady: (Player) -> Unit,
    onVideoClassify: (videoId: String, title: String, channel: String) -> Unit
) {
    var currentStreamUrl by remember { mutableStateOf(initialStreamUrl) }
    var currentVideoId by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentChannel by remember { mutableStateOf<String?>(null) }
    var videoDetails by remember { mutableStateOf<InnerTubeClient.VideoDetails?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<InnerTubeClient.VideoResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var continuationToken by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity

    var lastPlayedVideo by remember { mutableStateOf(FlowPlaybackPreferences.loadLastPlayed(context)) }
    var contentView by remember { mutableStateOf<ContentView>(ContentView.Search) }
    // (playlist support removed — playlist URLs open in Chrome)


    // Build a MergingMediaSource that pairs video-only + audio-only streams for adaptive formats
    fun buildMediaSource(
        videoUrl: String,
        audioUrl: String?,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(videoUrl))
        return if (audioUrl != null) {
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
    }

    // Helper to create ExoPlayer with OkHttp DataSource
    fun createPlayer(url: String, videoId: String, audioUrl: String? = null, startPositionMs: Long = 0, mimeType: String? = null): ExoPlayer {
        val okHttpFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .followRedirects(true)
                .build()
        ).setUserAgent("Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("FlowPlayer", "ExoPlayer error: ${error.errorCodeName} — ${error.message}", error)
                streamError = "Playback error: ${error.errorCodeName}. Try another video."
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> android.util.Log.d("FlowPlayer", "Buffering...")
                    Player.STATE_READY -> android.util.Log.d("FlowPlayer", "Ready to play")
                    Player.STATE_ENDED -> android.util.Log.d("FlowPlayer", "Playback ended")
                    Player.STATE_IDLE -> android.util.Log.d("FlowPlayer", "Idle")
                }
                val isActive = p.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
                FlowPlaybackState.onPlayPaused(!isActive)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val state = p.playbackState
                val isActive = playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
                FlowPlaybackState.onPlayPaused(!isActive)
            }
        })

        val isLive = mimeType == "application/x-mpegURL" || mimeType == "application/dash+xml"
        if (audioUrl != null) {
            p.setMediaSource(buildMediaSource(url, audioUrl, dataSourceFactory))
        } else if (isLive && mimeType != null) {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(videoId)
                .setMimeType(mimeType)
                .build()
            p.setMediaItem(mediaItem)
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(videoId)
                .build()
            p.setMediaItem(mediaItem)
        }
        p.prepare()
        if (startPositionMs > 0) {
            p.seekTo(startPositionMs)
        }
        p.playWhenReady = true
        return p
    }

    // Play a video from search results
    fun playVideo(result: InnerTubeClient.VideoResult) {
        scope.launch {
            isResolvingStream = true
            streamError = null

            // Fetch full video details (includes all quality options + description)
            val details = InnerTubeClient.getVideoDetails(result.videoId)
            isResolvingStream = false

            if (details != null && details.qualities.isNotEmpty()) {
                currentVideoId = result.videoId
                currentTitle = details.title
                currentChannel = details.channel
                videoDetails = details

                // Default to highest quality muxed format (360p) for stable playback
                val bestMuxed = details.qualities.filter { it.isMuxed }
                    .maxByOrNull { extractQualityNumber(it.label) }
                val chosen = bestMuxed ?: details.qualities.first()
                currentStreamUrl = chosen.url

                // Save as Continue Watching immediately — user started this video
                FlowPlaybackPreferences.saveLastPlayed(
                    context, result.videoId, details.title, details.channel,
                    chosen.url, 0L
                )
                lastPlayedVideo = FlowPlaybackPreferences.loadLastPlayed(context)

                // Trigger classification via the callback
                onVideoClassify(result.videoId, details.title, details.channel)

                // Create player — pass audioUrl for adaptive formats, mimeType for HLS/DASH
                player?.release()
                val chosenMimeType = if (chosen.mimeType.contains("mpegURL") || chosen.mimeType.contains("dash+xml")) chosen.mimeType else null
                val p = createPlayer(chosen.url, result.videoId, chosen.audioUrl, mimeType = chosenMimeType)
                player = p
                onPlayerReady(p)

                android.util.Log.d("FlowPlayer", "Player created, quality: ${chosen.label}")
            } else {
                streamError = "Could not load this video. It may be age-restricted, private, or temporarily unavailable. Try another video."
            }
        }
    }

    // Search with debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank() && searchQuery.length >= 3) {
            kotlinx.coroutines.delay(500)
            isSearching = true
            continuationToken = null
            val response = InnerTubeClient.search(searchQuery)
            searchResults = response.results
            continuationToken = response.continuation
            isSearching = false
        } else {
            searchResults = emptyList()
            continuationToken = null
        }
    }

    fun loadMore() {
        val token = continuationToken ?: return
        if (isLoadingMore) return
        scope.launch {
            isLoadingMore = true
            val response = InnerTubeClient.searchMore(token)
            searchResults = searchResults + response.results
            continuationToken = response.continuation
            isLoadingMore = false
        }
    }

    // Play a video by video ID (e.g. from description links)
    fun playVideoId(videoId: String) {
        playVideo(InnerTubeClient.VideoResult(videoId, "Loading...", "", "", ""))
    }

    fun handleYouTubeUrl(url: String, ctx: android.content.Context, onPlayVideoId: (String) -> Unit) {
        val cleanUrl = url.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
        val videoIdMatch = Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|youtu\.be/|youtube\.com/embed/|youtube\.com/live/)([\w-]{11})""").find(cleanUrl)

        when {
            videoIdMatch != null -> onPlayVideoId(videoIdMatch.groupValues[1])
            else -> {
                player?.pause()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
                intent.setPackage("com.android.chrome")
                try {
                    ctx.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    intent.setPackage(null)
                    ctx.startActivity(intent)
                }
            }
        }
    }

    // Release the player when it changes or when the composable leaves composition
    DisposableEffect(player) {
        FlowPlayerActivity.activeExoPlayer = player
        val playerToRelease = player
        onDispose {
            FlowPlayerActivity.activeExoPlayer = null
            playerToRelease?.release()
        }
    }

    // Lock orientation: portrait always. Fullscreen uses the button only.
    DisposableEffect(isFullscreen) {
        if (isFullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose { }
    }

    // Back navigation state machine:
    //   fullscreen → player mode → search results → empty search → phone home
    BackHandler(enabled = true) {
        when {
            isFullscreen -> {
                isFullscreen = false
            }
            currentStreamUrl != null -> {
                player?.let { p ->
                    FlowPlaybackPreferences.saveLastPlayed(
                        context, currentVideoId ?: "", currentTitle ?: "",
                        currentChannel ?: "", currentStreamUrl ?: "", p.currentPosition
                    )
                }
                lastPlayedVideo = FlowPlaybackPreferences.loadLastPlayed(context)
                player?.release()
                player = null
                currentStreamUrl = null
                videoDetails = null
            }
            searchQuery.isNotBlank() || searchResults.isNotEmpty() -> {
                searchQuery = ""
                searchResults = emptyList()
            }
            else -> {
                FlowPlayerActivity.activeExoPlayer = null
                player?.release()
                player = null
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
                activity?.finishAndRemoveTask()
            }
        }
    }

    if (isFullscreen && player != null) {
        PlayerViewWithSeek(
            player = player,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            onFullscreenClick = { isFullscreen = false }
        )
        return@FlowPlayerScreen
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learn with Kyzen", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F0F)
                ),
                navigationIcon = {
                    if (currentStreamUrl != null || contentView !is ContentView.Search) {
                        IconButton(onClick = {
                            if (currentStreamUrl != null) {
                                player?.let { p ->
                                    FlowPlaybackPreferences.saveLastPlayed(
                                        context, currentVideoId ?: "", currentTitle ?: "",
                                        currentChannel ?: "", currentStreamUrl ?: "", p.currentPosition
                                    )
                                }
                                lastPlayedVideo = FlowPlaybackPreferences.loadLastPlayed(context)
                                player?.release()
                                player = null
                                currentStreamUrl = null
                                videoDetails = null
                            } else {
                                contentView = ContentView.Search
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F0F))
                .padding(paddingValues)
        ) {
            if (currentStreamUrl != null && player != null) {
                // ── Video player view ──
                Column(modifier = Modifier.fillMaxSize()) {
                    PlayerViewWithSeek(
                        player = player,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        onFullscreenClick = { isFullscreen = true }
                    )

                    // Video info + scrollable description below the player
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        currentTitle?.let {
                            Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        currentChannel?.let {
                            Text(it, color = Color.Gray, fontSize = 14.sp)
                        }
                        videoDetails?.let { details ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "${formatViewCount(details.viewCount)} views • ${formatDuration(details.lengthSeconds)}",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            if (details.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                val descText = if (showDescription) details.description else details.description.take(150) + if (details.description.length > 150) "..." else ""
                                AndroidView(
                                    factory = { ctx ->
                                        android.widget.TextView(ctx).apply {
                                            movementMethod = android.text.method.LinkMovementMethod.getInstance()
                                            highlightColor = android.graphics.Color.TRANSPARENT
                                            setTextColor(android.graphics.Color.rgb(180, 180, 180))
                                            textSize = 13f
                                            maxLines = if (showDescription) Int.MAX_VALUE else 3
                                        }
                                    },
                                    update = { tv ->
                                        val spannable = android.text.SpannableString(descText)
                                        val urlRegex = Regex("https?://[\\w\\-./~%!$'()*+,;=:@?&#]+")
                                        for (match in urlRegex.findAll(descText)) {
                                            val rawUrl = match.value.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
                                            val start = match.range.first
                                            val end = match.range.last + 1
                                            spannable.setSpan(
                                                object : android.text.style.ClickableSpan() {
                                                    override fun onClick(widget: android.view.View) {
                                                        handleYouTubeUrl(rawUrl, context, { playVideoId(it) })
                                                    }
                                                    override fun updateDrawState(ds: android.text.TextPaint) {
                                                        ds.color = android.graphics.Color.rgb(62, 166, 255)
                                                        ds.isUnderlineText = true
                                                    }
                                                },
                                                start, end,
                                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                            )
                                        }
                                        tv.text = spannable
                                        tv.maxLines = if (showDescription) Int.MAX_VALUE else 3
                                    }
                                )
                                if (details.description.length > 150) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    androidx.compose.material3.TextButton(onClick = { showDescription = !showDescription }) {
                                        Text(if (showDescription) "Show less" else "Show more", color = Color(0xFF3EA6FF), fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                when (contentView) {
                    is ContentView.Search -> {
                    // ── Search interface ──
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search videos...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    when {
                        isSearching -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                        isResolvingStream -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Loading video...", color = Color.Gray, fontSize = 14.sp)
                                }
                            }
                        }
                        streamError != null -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    streamError!!,
                                    color = Color.Red,
                                    fontSize = 16.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                androidx.compose.material3.TextButton(
                                    onClick = { streamError = null }
                                ) {
                                    Text("Back to search", color = Color.White)
                                }
                            }
                        }
                        searchResults.isNotEmpty() -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(searchResults) { result ->
                                    SearchResultRow(
                                        result = result,
                                        onClick = { playVideo(result) }
                                    )
                                }
                                if (continuationToken != null) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isLoadingMore) {
                                                CircularProgressIndicator(
                                                    color = Color(0xFF3EA6FF),
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                androidx.compose.material3.TextButton(
                                                    onClick = { loadMore() }
                                                ) {
                                                    Text(
                                                        "Show more",
                                                        color = Color(0xFF3EA6FF),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        searchQuery.length >= 3 -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No results found", color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                        else -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Continue Watching card — only on home screen, not in search results
                                lastPlayedVideo?.let { saved ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .clickable {
                                                scope.launch {
                                                    val details = InnerTubeClient.getVideoDetails(saved.videoId)
                                                    if (details != null && details.qualities.isNotEmpty()) {
                                                        val bestMuxed = details.qualities.filter { it.isMuxed }
                                                            .maxByOrNull { extractQualityNumber(it.label) }
                                                        val chosen = bestMuxed ?: details.qualities.first()
                                                        currentVideoId = saved.videoId
                                                        currentTitle = saved.title
                                                        currentChannel = saved.channel
                                                        videoDetails = details
                                                        currentStreamUrl = chosen.url
                                                        onVideoClassify(saved.videoId, saved.title, saved.channel)
                                                        player?.release()
                                                        val p = createPlayer(chosen.url, saved.videoId, chosen.audioUrl, saved.positionMs)
                                                        player = p
                                                        onPlayerReady(p)
                                                        lastPlayedVideo = null
                                                        FlowPlaybackPreferences.clearLastPlayed(context)
                                                    }
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = "https://img.youtube.com/vi/${saved.videoId}/mqdefault.jpg",
                                                contentDescription = saved.title,
                                                modifier = Modifier
                                                    .size(width = 120.dp, height = 68.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.DarkGray),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.size(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Continue Watching", color = Color(0xFF3EA6FF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(saved.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(saved.channel, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("Resume at ${formatDuration((saved.positionMs / 1000).toString())}", color = Color.LightGray, fontSize = 11.sp)
                                            }
                                            IconButton(onClick = {
                                                lastPlayedVideo = null
                                                FlowPlaybackPreferences.clearLastPlayed(context)
                                            }) {
                                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Educational videos earn gems. Entertainment videos spend them.",
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Find educational videos to learn", color = Color.Gray, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}

private enum class SeekDirection { FORWARD, BACKWARD }

private sealed class ContentView {
    object Search : ContentView()
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerViewWithSeek(
    player: ExoPlayer?,
    modifier: Modifier,
    onFullscreenClick: (() -> Unit)? = null
) {
    var seekIndicator by remember { mutableStateOf<SeekDirection?>(null) }

    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            kotlinx.coroutines.delay(600)
            seekIndicator = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                var viewWidth = 0

                PlayerView(ctx).apply {
                    useController = true
                    if (onFullscreenClick != null) {
                        setFullscreenButtonClickListener { onFullscreenClick() }
                    }

                    val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val p = FlowPlayerActivity.activeExoPlayer ?: return true
                            val isRight = e.x > viewWidth / 2
                            if (isRight) {
                                p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                                seekIndicator = SeekDirection.FORWARD
                            } else {
                                p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                                seekIndicator = SeekDirection.BACKWARD
                            }
                            return true
                        }
                    })

                    setOnTouchListener { v, event ->
                        viewWidth = v.width
                        detector.onTouchEvent(event)
                        false
                    }
                }
            },
            update = { view ->
                (view as PlayerView).player = player
            }
        )

        AnimatedVisibility(
            visible = seekIndicator != null,
            enter = fadeIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(400)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = when (seekIndicator) {
                    SeekDirection.FORWARD -> Alignment.CenterEnd
                    SeekDirection.BACKWARD -> Alignment.CenterStart
                    null -> Alignment.Center
                }
            ) {
                Box(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = when (seekIndicator) {
                            SeekDirection.FORWARD -> "→ 10s"
                            SeekDirection.BACKWARD -> "10s ←"
                            null -> ""
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Extracts the numeric quality value from a label like "720p" → 720 */
private fun extractQualityNumber(label: String): Int {
    return label.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
}

/** Formats a view count string for display */
private fun formatViewCount(count: String): String {
    val n = count.toLongOrNull() ?: return count
    return when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }
}

/** Formats seconds into "MM:SS" or "HH:MM:SS" */
private fun formatDuration(seconds: String): String {
    val total = seconds.toLongOrNull() ?: return ""
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}

@Composable
private fun SearchResultRow(
    result: InnerTubeClient.VideoResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Thumbnail with duration badge
            Box {
                AsyncImage(
                    model = result.thumbnailUrl,
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(194.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(Color.DarkGray)
                )
                if (result.isLive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                Color.Red,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (result.durationText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                Color.Black.copy(alpha = 0.8f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = result.durationText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Title + channel
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = result.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.channel,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
