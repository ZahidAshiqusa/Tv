package com.example.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.data.model.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Resolving : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data object Paused : PlaybackStatus
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : PlaybackStatus
    data class Error(val message: String) : PlaybackStatus
}

data class PlayerUiState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val isMuted: Boolean = false,
    val isPlaying: Boolean = false,
    val activeVideoHeightPx: Int = 0,
    val availableHeightsPx: List<Int> = emptyList(),
    val activeSourceKind: StreamSourceKind? = null
)

/**
 * Lightweight in-memory CookieJar so CDN session cookies issued on master .m3u8 playlists
 * are preserved on child variant playlists and .ts/.m4s media segments (prevents HTTP 403).
 */
internal class InMemoryStreamCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }
        synchronized(existing) {
            cookies.forEach { incoming ->
                existing.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                existing.add(incoming)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = mutableListOf<Cookie>()
        store.values.forEach { list ->
            synchronized(list) {
                list.removeAll { it.expiresAt < now }
                list.forEach { cookie ->
                    if (cookie.matches(url)) {
                        matched.add(cookie)
                    }
                }
            }
        }
        return matched
    }
}

@OptIn(UnstableApi::class)
class LivePlayerController(
    context: Context,
    private val onStreamNeedsM3u8Refresh: (failedUrl: String?, httpStatusCode: Int?) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(InMemoryStreamCookieJar())
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // Neutral default factory — per-stream headers supply Referer/Origin only when appropriate
    private val defaultDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(TamashaPluginEngine.TAMASHA_USER_AGENT)
        .setDefaultRequestProperties(mapOf("Accept" to "*/*"))

    private val trackSelector = DefaultTrackSelector(appContext)

    // Tuned for ultra-fast first-frame startup (500ms playback threshold vs default 2500ms)
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 3_500,
            /* maxBufferMs = */ 20_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1_200
        )
        .build()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var currentStreamUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentQuality: VideoQuality = VideoQuality.AUTO
    private var currentIsLive: Boolean = true
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val maxQuickReconnects = 1

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (_state.value.status !is PlaybackStatus.Resolving) {
                        _state.update { it.copy(status = PlaybackStatus.Buffering) }
                    }
                }
                Player.STATE_READY -> {
                    reconnectAttempts = 0
                    val playing = exoPlayer.playWhenReady
                    _state.update {
                        it.copy(
                            status = if (playing) PlaybackStatus.Playing else PlaybackStatus.Paused,
                            isPlaying = playing
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    if (currentIsLive) {
                        handleFailureAndAutoRefresh(httpStatusCode = null)
                    } else {
                        _state.update { it.copy(status = PlaybackStatus.Paused, isPlaying = false) }
                    }
                }
                Player.STATE_IDLE -> {
                    if (currentStreamUrl == null && _state.value.status !is PlaybackStatus.Resolving) {
                        _state.update { it.copy(status = PlaybackStatus.Idle, isPlaying = false) }
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { current ->
                if (current.status is PlaybackStatus.Resolving) return@update current
                val updatedStatus = when {
                    isPlaying -> PlaybackStatus.Playing
                    exoPlayer.playbackState == Player.STATE_READY -> PlaybackStatus.Paused
                    else -> current.status
                }
                current.copy(isPlaying = isPlaying, status = updatedStatus)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val heights = mutableSetOf<Int>()
            var selectedHeight = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    for (i in 0 until group.length) {
                        val h = group.getTrackFormat(i).height
                        if (h > 0) {
                            heights.add(h)
                            if (group.isTrackSelected(i)) {
                                selectedHeight = h
                            }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    activeVideoHeightPx = selectedHeight,
                    availableHeightsPx = heights.sorted()
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val httpCode = extractHttpStatusCode(error)
            handleFailureAndAutoRefresh(httpStatusCode = httpCode)
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    fun setResolvingState() {
        reconnectJob?.cancel()
        exoPlayer.stop()
        _state.update {
            it.copy(
                status = PlaybackStatus.Resolving,
                isPlaying = false
            )
        }
    }

    fun setErrorState(message: String) {
        reconnectJob?.cancel()
        exoPlayer.stop()
        _state.update {
            it.copy(
                status = PlaybackStatus.Error(message),
                isPlaying = false
            )
        }
    }

    fun playStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sourceKind: StreamSourceKind = StreamSourceKind.TAMASHA_HLS,
        quality: VideoQuality = currentQuality,
        isLive: Boolean = true
    ) {
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentStreamUrl = url
        currentHeaders = headers
        currentQuality = quality
        currentIsLive = isLive
        applyQuality(quality)
        _state.update { it.copy(activeSourceKind = sourceKind) }
        loadAndStart(url, headers)
    }

    private fun loadAndStart(url: String, headers: Map<String, String>) {
        _state.update { it.copy(status = PlaybackStatus.Buffering) }

        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }
            ?: TamashaPluginEngine.TAMASHA_USER_AGENT

        // Strip hop-by-hop / host-bound headers that cause CDN edge servers to reject redirects with HTTP 403
        val requestProps = headers.filterKeys { key ->
            val lower = key.lowercase()
            lower != "user-agent" &&
                lower != "host" &&
                lower != "connection" &&
                lower != "content-length" &&
                lower != "range" &&
                lower != "accept-encoding" &&
                !lower.startsWith("sec-") &&
                !lower.startsWith(":")
        }

        val factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .apply {
                if (requestProps.isNotEmpty()) {
                    setDefaultRequestProperties(requestProps)
                }
            }

        val uri = Uri.parse(url)
        val isHls = url.contains(".m3u8", ignoreCase = true) ||
            url.contains("chunks", ignoreCase = true) ||
            url.contains("hls", ignoreCase = true)

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem = mediaItemBuilder.build()

        if (isHls) {
            val hlsSource = HlsMediaSource.Factory(factory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            exoPlayer.setMediaSource(hlsSource)
        } else {
            val mediaSource = DefaultMediaSourceFactory(factory).createMediaSource(mediaItem)
            exoPlayer.setMediaSource(mediaSource)
        }

        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    fun applyQuality(quality: VideoQuality) {
        currentQuality = quality
        val builder = trackSelector.buildUponParameters()
        if (quality == VideoQuality.AUTO) {
            builder
                .clearVideoSizeConstraints()
                .setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            builder
                .setMaxVideoSize(Int.MAX_VALUE, quality.maxHeightPx)
                .setMaxVideoBitrate(quality.maxBitrateBps)
        }
        trackSelector.setParameters(builder)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
                currentStreamUrl?.let { loadAndStart(it, currentHeaders) }
            } else {
                exoPlayer.play()
            }
        }
    }

    fun toggleMute() {
        val nextMuted = !_state.value.isMuted
        exoPlayer.volume = if (nextMuted) 0f else 1f
        _state.update { it.copy(isMuted = nextMuted) }
    }

    fun stopAndClear() {
        reconnectJob?.cancel()
        currentStreamUrl = null
        currentHeaders = emptyMap()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.update {
            it.copy(
                status = PlaybackStatus.Idle,
                isPlaying = false,
                activeVideoHeightPx = 0,
                availableHeightsPx = emptyList(),
                activeSourceKind = null
            )
        }
    }

    fun pauseForBackground() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    private fun extractHttpStatusCode(error: PlaybackException): Int? {
        var cursor: Throwable? = error
        var depth = 0
        while (cursor != null && depth < 12) {
            if (cursor is HttpDataSource.InvalidResponseCodeException) {
                return cursor.responseCode
            }
            cursor = cursor.cause
            depth++
        }
        return if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) 403 else null
    }

    private fun handleFailureAndAutoRefresh(httpStatusCode: Int?) {
        val url = currentStreamUrl ?: return
        reconnectJob?.cancel()

        val isAuthOrForbiddenError = httpStatusCode != null && httpStatusCode in setOf(401, 403, 404, 410, 451)
        if (reconnectAttempts < maxQuickReconnects && !isAuthOrForbiddenError) {
            reconnectAttempts++
            _state.update {
                it.copy(
                    status = PlaybackStatus.Reconnecting(reconnectAttempts, maxQuickReconnects),
                    isPlaying = false
                )
            }
            reconnectJob = scope.launch {
                delay(1_200L)
                if (currentStreamUrl == url) {
                    loadAndStart(url, currentHeaders)
                }
            }
        } else {
            // Immediately stop retrying the 403/401/404 URL and request a fresh verified stream
            exoPlayer.stop()
            onStreamNeedsM3u8Refresh(url, httpStatusCode)
        }
    }

    fun release() {
        reconnectJob?.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        scope.cancel()
    }
}
