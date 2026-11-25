package com.example.player

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.example.data.M3uParser
import com.example.data.ParsedM3uChannel
import com.example.data.local.AppDatabase
import com.example.data.local.ChannelEntity
import com.example.data.local.StreamCacheEntity
import com.example.data.local.VodItemEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class StreamSourceKind {
    TAMASHA_HLS,
    PUBLIC_HLS
}

sealed interface TamashaResolveResult {
    data class FreshHlsStream(
        val streamUrl: String,
        val headers: Map<String, String>,
        val sourceKind: StreamSourceKind
    ) : TamashaResolveResult

    data class Failure(val reason: String) : TamashaResolveResult
}

data class HiddenFetcherTask(
    val taskId: Long,
    val pageUrl: String,
    val channelStreamKey: String,
    val isVodPage: Boolean = false,
    val onCaptured: (url: String, headers: Map<String, String>, isMasterPlaylist: Boolean) -> Unit
)

private enum class HlsCheckResult {
    VALID,
    FORBIDDEN_OR_DEAD,
    NETWORK_INDETERMINATE
}

private data class SniffedMedia(
    val url: String,
    val headers: Map<String, String>
)

private data class PublicStreamCandidate(
    val url: String,
    val headers: Map<String, String>
)

/**
 * Ultra-Fast SQLite-Accelerated Native Stream Engine.
 * Resolves live TV (.m3u8) and free VOD (.m3u8 / .mp4) streams directly for Media3 ExoPlayer:
 * 1. Tier-0 SQLite (Room stream_cache) + Memory cache -> 0ms instant start across taps & restarts.
 * 2. True Parallel-Race Resolver -> concurrently races OkHttp SSR/_next/data extraction,
 *    bandwidth-stripped invisible Chromium sniffer, and public IPTV live fallback; first verified
 *    playable link wins immediately and cancels slower lanes.
 * 3. Background Pre-Warming -> pre-hydrates catalog index + top popular channels into SQLite.
 */
class TamashaPluginEngine(context: Context) {

    companion object {
        const val TAMASHA_BASE_URL = "https://tamashaweb.com"
        const val TAMASHA_REFERER = "https://tamashaweb.com/"
        const val TAMASHA_ORIGIN = "https://tamashaweb.com"
        const val TAMASHA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val IPTV_PK_M3U = "https://iptv-org.github.io/iptv/countries/pk.m3u"
        private const val IPTV_URD_M3U = "https://iptv-org.github.io/iptv/languages/urd.m3u"
        private const val IPTV_INT_M3U = "https://iptv-org.github.io/iptv/categories/news.m3u"

        private const val SESSION_FRESHNESS_MS = 45 * 60_000L
        private const val FAILED_URL_TTL_MS = 10 * 60_000L
        private const val WEB_SNIFFER_TIMEOUT_MS = 9_500L
        private const val VOD_SNIFFER_TIMEOUT_MS = 14_500L
        private const val MASTER_PLAYLIST_GRACE_MS = 280L
    }

    private val streamCacheDao = AppDatabase.getInstance(context.applicationContext).streamCacheDao()
    private val resolveMutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeFetcherTask = MutableStateFlow<HiddenFetcherTask?>(null)
    val activeFetcherTask: StateFlow<HiddenFetcherTask?> = _activeFetcherTask.asStateFlow()

    private val sharedPool = ConnectionPool(16, 5, TimeUnit.MINUTES)
    private val sharedCookies = InMemoryStreamCookieJar()

    // Fast-timeout client for parallel stream probing & SSR extraction (dead links fail fast!)
    private val fastHttpClient = OkHttpClient.Builder()
        .cookieJar(sharedCookies)
        .connectionPool(sharedPool)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Standard client for larger playlist/index downloads
    private val indexHttpClient = OkHttpClient.Builder()
        .cookieJar(sharedCookies)
        .connectionPool(sharedPool)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var catalogLoadedAt = 0L
    private var nextBuildId: String? = null
    private val discoveredSlugsByKey = ConcurrentHashMap<String, String>()
    private val discoveredTamashaStreamsByKey = ConcurrentHashMap<String, String>()

    private var publicIndexLoadedAt = 0L
    @Volatile
    private var publicLiveChannels: List<ParsedM3uChannel> = emptyList()

    private val recentlyFailedUrls = ConcurrentHashMap<String, Long>()

    private data class CachedSessionStream(
        val url: String,
        val headers: Map<String, String>,
        val sourceKind: StreamSourceKind,
        val resolvedAt: Long,
        val expiresAt: Long
    )
    private val sessionCache = ConcurrentHashMap<String, CachedSessionStream>()

    private val m3u8RawRegex = Regex(
        """https?:\\?/\\?/[^"'\s<>\\]+\.m3u8(?:[^"'\s<>\\]*)""",
        RegexOption.IGNORE_CASE
    )
    private val m3u8EncodedRegex = Regex(
        """https?%3A%2F%2F[^"'\s<>]+\.m3u8(?:[^"'\s<>]*)""",
        RegexOption.IGNORE_CASE
    )
    private val mp4RawRegex = Regex(
        """https?:\\?/\\?/[^"'\s<>\\]+\.mp4(?:[^"'\s<>\\]*)""",
        RegexOption.IGNORE_CASE
    )
    private val slugHrefRegex = Regex(
        """href=["']/((?:live-tv/)?[a-z0-9][a-z0-9-]{2,60})["']""",
        RegexOption.IGNORE_CASE
    )
    private val jsonSlugRegex = Regex(
        """"(?:slug|channel_slug|web_slug)"\s*:\s*"([a-z0-9][a-z0-9-]{2,60})"""",
        RegexOption.IGNORE_CASE
    )
    private val buildIdRegex = Regex(""""buildId"\s*:\s*"([^"]+)"""")

    init {
        // Hydrate in-memory cache immediately from SQLite stream_cache (<5ms)
        engineScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                streamCacheDao.pruneExpired(now)
                streamCacheDao.getAllValid(now).forEach { row ->
                    val kind = if (row.sourceKindKey == StreamSourceKind.PUBLIC_HLS.name) {
                        StreamSourceKind.PUBLIC_HLS
                    } else {
                        StreamSourceKind.TAMASHA_HLS
                    }
                    sessionCache[row.targetId] = CachedSessionStream(
                        url = row.streamUrl,
                        headers = deserializeHeaders(row.headersJson),
                        sourceKind = kind,
                        resolvedAt = row.resolvedAt,
                        expiresAt = row.expiresAt
                    )
                }
            }
        }
    }

    fun defaultTamashaHeaders(cookie: String? = null): Map<String, String> = buildMap {
        put("Referer", TAMASHA_REFERER)
        put("Origin", TAMASHA_ORIGIN)
        put("User-Agent", TAMASHA_USER_AGENT)
        put("Accept", "*/*")
        if (!cookie.isNullOrBlank()) {
            put("Cookie", cookie)
        }
    }

    fun markStreamUrlFailed(url: String) {
        if (url.isBlank()) return
        recentlyFailedUrls[url] = System.currentTimeMillis()
        sessionCache.entries.removeAll { it.value.url == url }
        engineScope.launch {
            runCatching { streamCacheDao.deleteByUrl(url) }
        }
    }

    fun invalidateChannelCache(channelId: String) {
        sessionCache.remove(channelId)
        engineScope.launch {
            runCatching { streamCacheDao.deleteByTargetId(channelId) }
        }
    }

    private fun isRecentlyFailed(url: String): Boolean {
        val failedAt = recentlyFailedUrls[url] ?: return false
        if ((System.currentTimeMillis() - failedAt) > FAILED_URL_TTL_MS) {
            recentlyFailedUrls.remove(url)
            return false
        }
        return true
    }

    /**
     * Silently warms catalog indexes + top popular channels into SQLite stream_cache in the
     * background right after launch so clicking a channel plays near-instantly.
     */
    fun prewarmInBackground(priorityChannels: List<ChannelEntity>) {
        engineScope.launch {
            runCatching {
                coroutineScope {
                    val catJob = async { ensureTamashaCatalogIndex(force = false) }
                    val pubJob = async { ensurePublicM3uIndex() }
                    catJob.await()
                    pubJob.await()
                }
                val now = System.currentTimeMillis()
                priorityChannels.take(10).chunked(5).forEach { batch ->
                    coroutineScope {
                        batch.map { channel ->
                            async {
                                if (getValidCachedStream(channel.id, now) != null) return@async
                                runCatching {
                                    val quick = resolveViaFastHttpOnly(channel, now)
                                    if (quick != null) {
                                        cacheResolvedStream(
                                            targetId = channel.id,
                                            url = quick.streamUrl,
                                            headers = quick.headers,
                                            sourceKind = quick.sourceKind
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }

    /**
     * Ultra-Fast Live TV Stream Resolver:
     * 1. Instant 0ms return from memory or SQLite stream_cache if valid & non-expired.
     * 2. Parallel-race resolution across OkHttp SSR/_next/data, fast invisible WebView sniffer,
     *    and public IPTV fallback so the fastest verified live stream starts immediately.
     */
    suspend fun fetchLiveM3u8(
        channel: ChannelEntity,
        forceRefresh: Boolean = false,
        preferPublicFallback: Boolean = false
    ): TamashaResolveResult = resolveMutex.withLock {
        val now = System.currentTimeMillis()

        // Tier 0: Zero-latency Memory / SQLite Cache Hit (<5ms)
        if (!forceRefresh && !preferPublicFallback) {
            val cached = getValidCachedStream(channel.id, now)
            if (cached != null) {
                return@withLock TamashaResolveResult.FreshHlsStream(
                    streamUrl = cached.url,
                    headers = cached.headers,
                    sourceKind = cached.sourceKind
                )
            }
        }

        // Parallel-Race Resolution (first verified working stream wins & cancels slower lanes)
        val winner = withContext(Dispatchers.IO) {
            raceLiveStreamResolution(
                channel = channel,
                forceRefresh = forceRefresh,
                preferPublicFallback = preferPublicFallback
            )
        }

        if (winner != null) {
            cacheResolvedStream(
                targetId = channel.id,
                url = winner.streamUrl,
                headers = winner.headers,
                sourceKind = winner.sourceKind
            )
            return@withLock winner
        }

        TamashaResolveResult.Failure("Broadcast is temporarily busy. Please try again.")
    }

    /**
     * Ultra-Fast VOD Stream Resolver for REAL free Dramas, TV Shows, Movies, Wrestling & Highlights.
     * Supports BOTH HLS (.m3u8) and progressive MP4 (.mp4) streams.
     * Never substitutes unrelated Live TV channels.
     */
    suspend fun fetchVodLiveStream(
        item: VodItemEntity,
        forceRefresh: Boolean = false
    ): TamashaResolveResult = resolveMutex.withLock {
        val now = System.currentTimeMillis()

        // 1. Instant SQLite/Memory Cache Hit when re-opening a recently played show/movie
        if (!forceRefresh) {
            val cached = getValidCachedStream(item.id, now)
            if (cached != null) {
                return@withLock TamashaResolveResult.FreshHlsStream(
                    streamUrl = cached.url,
                    headers = cached.headers,
                    sourceKind = cached.sourceKind
                )
            }
        }

        // 2. Direct stream URL discovered from Tamasha's live catalog JSON
        if (item.streamUrl.isNotBlank() && !isRecentlyFailed(item.streamUrl)) {
            val expiry = M3uParser.parseWmsAuthSignExpiry(item.streamUrl, now)
            if (!expiry.isExpired) {
                val headers = defaultTamashaHeaders(getTamashaCookie(item.streamUrl))
                if (verifyPlayableMediaUrl(item.streamUrl, headers) != HlsCheckResult.FORBIDDEN_OR_DEAD) {
                    val result = TamashaResolveResult.FreshHlsStream(
                        streamUrl = item.streamUrl,
                        headers = headers,
                        sourceKind = StreamSourceKind.TAMASHA_HLS
                    )
                    cacheResolvedStream(item.id, result.streamUrl, result.headers, result.sourceKind)
                    return@withLock result
                } else {
                    markStreamUrlFailed(item.streamUrl)
                }
            }
        }

        val candidateSlugs = LinkedHashSet<String>().apply {
            addAll(item.allCandidateSlugs())
            val primary = item.pageSlug.trim().trimStart('/')
            if (primary.isNotBlank() && !primary.contains('/') && !primary.contains('?')) {
                if (item.section == com.example.data.model.VodSection.MOVIE) {
                    add("movie/$primary")
                } else {
                    add("shows/$primary")
                    add("watch/$primary")
                }
            }
        }.toList()

        if (candidateSlugs.isEmpty()) {
            return@withLock TamashaResolveResult.Failure("This video is temporarily unavailable.")
        }

        val virtualChannel = ChannelEntity(
            id = item.id,
            name = item.title,
            urduName = item.urduTitle,
            streamUrl = item.streamUrl,
            tamashaSlug = item.pageSlug,
            tamashaAltSlugs = item.altSlugs,
            tamashaStreamKey = "",
            categoryKey = com.example.data.model.ChannelCategory.ENTERTAINMENT.storageKey,
            logoUrl = item.posterUrl,
            fallbackLogoUrl = item.fallbackPosterUrl,
            accentHex = item.accentHex,
            resolvedAt = item.resolvedAt
        )

        // 3. Parallel Race: Fast OkHttp VOD extraction vs Headless Chromium VOD sniffer (auto-clicks Watch/Episode 1)
        val resolved = withContext(Dispatchers.IO) {
            val winnerDeferred = CompletableDeferred<TamashaResolveResult.FreshHlsStream?>()
            coroutineScope {
                val httpJob = launch {
                    val extractedList = candidateSlugs.take(3).map { slug ->
                        async { extractMediaViaHttpAndNextData(slug, virtualChannel, allowMp4 = true) }
                    }.awaitAll().filterNotNull().distinct()

                    for (mediaUrl in extractedList) {
                        if (winnerDeferred.isCompleted) break
                        if (isRecentlyFailed(mediaUrl)) continue
                        if (M3uParser.parseWmsAuthSignExpiry(mediaUrl, now).isExpired) continue
                        val hdrs = defaultTamashaHeaders(getTamashaCookie(mediaUrl))
                        if (verifyPlayableMediaUrl(mediaUrl, hdrs) == HlsCheckResult.VALID) {
                            winnerDeferred.complete(
                                TamashaResolveResult.FreshHlsStream(mediaUrl, hdrs, StreamSourceKind.TAMASHA_HLS)
                            )
                            break
                        } else {
                            markStreamUrlFailed(mediaUrl)
                        }
                    }
                }

                val webJob = launch {
                    delay(200L)
                    for (slug in candidateSlugs.take(2)) {
                        if (winnerDeferred.isCompleted) break
                        val pageUrl = "$TAMASHA_BASE_URL/${slug.trimStart('/')}"
                        val sniffed = sniffMediaWithHiddenWebView(
                            pageUrl = pageUrl,
                            channel = virtualChannel,
                            isVodPage = true,
                            timeoutMs = VOD_SNIFFER_TIMEOUT_MS
                        )
                        if (sniffed != null && !isRecentlyFailed(sniffed.url)) {
                            val mergedHeaders = buildMap {
                                putAll(defaultTamashaHeaders(getTamashaCookie(sniffed.url)))
                                putAll(sanitizeSniffedHeaders(sniffed.headers))
                            }
                            // Accept freshly sniffed Chromium VOD stream unless explicitly 401/403/404/410/451
                            val check = verifyPlayableMediaUrl(sniffed.url, mergedHeaders)
                            if (check != HlsCheckResult.FORBIDDEN_OR_DEAD) {
                                winnerDeferred.complete(
                                    TamashaResolveResult.FreshHlsStream(
                                        streamUrl = sniffed.url,
                                        headers = mergedHeaders,
                                        sourceKind = StreamSourceKind.TAMASHA_HLS
                                    )
                                )
                                break
                            } else {
                                markStreamUrlFailed(sniffed.url)
                            }
                        }
                    }
                }

                launch {
                    httpJob.join()
                    webJob.join()
                    if (!winnerDeferred.isCompleted) winnerDeferred.complete(null)
                }

                val result = winnerDeferred.await()
                coroutineContext.cancelChildren()
                result
            }
        }

        if (resolved != null) {
            cacheResolvedStream(item.id, resolved.streamUrl, resolved.headers, resolved.sourceKind)
            return@withLock resolved
        }

        TamashaResolveResult.Failure("This video is temporarily unavailable. Please try again.")
    }

    /**
     * Bulk refreshes catalog slugs and verified live stream links for all channels into SQLite
     * stream_cache in parallel batches of 8 for ultra-fast full refresh.
     */
    suspend fun bulkRefreshChannelLinks(
        channels: List<ChannelEntity>,
        onChannelResolved: suspend (channelId: String, freshUrl: String) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val total = channels.size.coerceAtLeast(1)
        val completed = AtomicInteger(0)
        onProgress(0, total)

        coroutineScope {
            val catalogJob = async { runCatching { ensureTamashaCatalogIndex(force = true) } }
            val publicJob = async { runCatching { ensurePublicM3uIndex() } }
            catalogJob.await()
            publicJob.await()
        }

        val now = System.currentTimeMillis()
        channels.chunked(8).forEach { batch ->
            coroutineScope {
                batch.map { channel ->
                    async {
                        runCatching {
                            val resolved = resolveViaFastHttpOnly(channel, now)
                            if (resolved != null) {
                                cacheResolvedStream(
                                    targetId = channel.id,
                                    url = resolved.streamUrl,
                                    headers = resolved.headers,
                                    sourceKind = resolved.sourceKind
                                )
                                onChannelResolved(channel.id, resolved.streamUrl)
                            }
                        }
                        onProgress(completed.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
        }
    }

    fun release() {
        _activeFetcherTask.value = null
        engineScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Races up to 3 lanes in parallel so whichever live stream source succeeds first wins immediately:
     * - Lane 1 (0ms): Tamasha Catalog SSR + parallel OkHttp _next/data & HTML extraction
     * - Lane 2 (300ms head-start): Invisible Chromium WebView sniffer (strips CSS/images/ads)
     * - Lane 3 (0ms if preferPublicFallback, else 1_500ms head-start): Parallel public IPTV probe
     */
    private suspend fun raceLiveStreamResolution(
        channel: ChannelEntity,
        forceRefresh: Boolean,
        preferPublicFallback: Boolean
    ): TamashaResolveResult.FreshHlsStream? = coroutineScope {
        val winner = CompletableDeferred<TamashaResolveResult.FreshHlsStream?>()
        val now = System.currentTimeMillis()

        val lane1Http = launch {
            if (preferPublicFallback) return@launch
            ensureTamashaCatalogIndex(force = forceRefresh && catalogLoadedAt == 0L)

            // 1A: Catalog SSR stream
            val fromCatalog = findInCatalogStreams(channel)
            if (fromCatalog != null &&
                !isRecentlyFailed(fromCatalog) &&
                !M3uParser.parseWmsAuthSignExpiry(fromCatalog, now).isExpired
            ) {
                val hdrs = defaultTamashaHeaders(getTamashaCookie(fromCatalog))
                if (verifyHlsReachable(fromCatalog, hdrs) == HlsCheckResult.VALID) {
                    winner.complete(TamashaResolveResult.FreshHlsStream(fromCatalog, hdrs, StreamSourceKind.TAMASHA_HLS))
                    return@launch
                } else {
                    markStreamUrlFailed(fromCatalog)
                }
            }

            // 1B: Parallel OkHttp extraction across top 2 slugs
            val slugs = findBestSlugs(channel).take(2)
            val candidates = slugs.map { slug ->
                async { extractMediaViaHttpAndNextData(slug, channel, allowMp4 = false) }
            }.awaitAll().filterNotNull().distinct()

            candidates.map { url ->
                async {
                    if (winner.isCompleted || isRecentlyFailed(url)) return@async
                    if (M3uParser.parseWmsAuthSignExpiry(url, now).isExpired) return@async
                    val hdrs = defaultTamashaHeaders(getTamashaCookie(url))
                    if (verifyHlsReachable(url, hdrs) == HlsCheckResult.VALID) {
                        winner.complete(TamashaResolveResult.FreshHlsStream(url, hdrs, StreamSourceKind.TAMASHA_HLS))
                    } else {
                        markStreamUrlFailed(url)
                    }
                }
            }.awaitAll()
        }

        val lane2WebSniffer = launch {
            if (preferPublicFallback) return@launch
            delay(300L) // Brief head-start for ~250ms OkHttp SSR lane
            if (winner.isCompleted) return@launch

            val slugs = findBestSlugs(channel).take(2)
            for (slug in slugs) {
                if (winner.isCompleted) break
                val pageUrl = "$TAMASHA_BASE_URL/${slug.trimStart('/')}"
                val sniffed = sniffMediaWithHiddenWebView(
                    pageUrl = pageUrl,
                    channel = channel,
                    isVodPage = false,
                    timeoutMs = WEB_SNIFFER_TIMEOUT_MS
                )
                if (sniffed != null && !isRecentlyFailed(sniffed.url)) {
                    val mergedHeaders = buildMap {
                        putAll(defaultTamashaHeaders(getTamashaCookie(sniffed.url)))
                        putAll(sanitizeSniffedHeaders(sniffed.headers))
                    }
                    if (verifyHlsReachable(sniffed.url, mergedHeaders) == HlsCheckResult.VALID) {
                        winner.complete(
                            TamashaResolveResult.FreshHlsStream(
                                streamUrl = sniffed.url,
                                headers = mergedHeaders,
                                sourceKind = StreamSourceKind.TAMASHA_HLS
                            )
                        )
                        break
                    } else {
                        markStreamUrlFailed(sniffed.url)
                    }
                }
            }
        }

        val lane3Public = launch {
            if (!preferPublicFallback) {
                delay(1_500L) // Give Tamasha native stream first crack before probing public fallback
            }
            if (winner.isCompleted) return@launch
            val publicCandidate = tryFetchPublicLiveM3u8Parallel(channel)
            if (publicCandidate != null) {
                winner.complete(
                    TamashaResolveResult.FreshHlsStream(
                        streamUrl = publicCandidate.url,
                        headers = publicCandidate.headers,
                        sourceKind = StreamSourceKind.PUBLIC_HLS
                    )
                )
            }
        }

        launch {
            lane1Http.join()
            lane2WebSniffer.join()
            lane3Public.join()
            if (!winner.isCompleted) winner.complete(null)
        }

        val result = winner.await()
        coroutineContext.cancelChildren()
        result
    }

    private suspend fun resolveViaFastHttpOnly(
        channel: ChannelEntity,
        now: Long
    ): TamashaResolveResult.FreshHlsStream? = coroutineScope {
        val candidates = mutableListOf<Pair<String, Map<String, String>>>()

        val freshFromCatalog = findInCatalogStreams(channel)
        if (freshFromCatalog != null && !M3uParser.parseWmsAuthSignExpiry(freshFromCatalog, now).isExpired) {
            candidates.add(freshFromCatalog to defaultTamashaHeaders())
        } else {
            val firstSlug = findBestSlugs(channel).firstOrNull()
            val httpExtracted = if (firstSlug != null) {
                withTimeoutOrNull(3_200L) {
                    extractMediaViaHttpAndNextData(firstSlug, channel, allowMp4 = false)
                }
            } else null
            if (httpExtracted != null && !M3uParser.parseWmsAuthSignExpiry(httpExtracted, now).isExpired) {
                candidates.add(httpExtracted to defaultTamashaHeaders())
            }
        }

        findMatchingPublicStreams(channel).take(2).forEach { pub ->
            candidates.add(pub.streamUrl to buildPublicHeaders(pub))
        }
        if (channel.publicFallbackUrl.isNotBlank()) {
            candidates.add(
                channel.publicFallbackUrl to mapOf(
                    "User-Agent" to TAMASHA_USER_AGENT,
                    "Accept" to "*/*"
                )
            )
        }

        val winner = CompletableDeferred<TamashaResolveResult.FreshHlsStream?>()
        val jobs = candidates
            .filterNot { (url, _) -> isRecentlyFailed(url) }
            .map { (url, hdrs) ->
                launch {
                    if (winner.isCompleted) return@launch
                    if (verifyHlsReachable(url, hdrs) == HlsCheckResult.VALID) {
                        val isTamasha = url.contains("tamashaweb.com", ignoreCase = true)
                        winner.complete(
                            TamashaResolveResult.FreshHlsStream(
                                streamUrl = url,
                                headers = hdrs,
                                sourceKind = if (isTamasha) StreamSourceKind.TAMASHA_HLS else StreamSourceKind.PUBLIC_HLS
                            )
                        )
                    }
                }
            }
        launch {
            jobs.forEach { it.join() }
            if (!winner.isCompleted) winner.complete(null)
        }
        val res = winner.await()
        coroutineContext.cancelChildren()
        res
    }

    private suspend fun getValidCachedStream(targetId: String, now: Long): CachedSessionStream? {
        val mem = sessionCache[targetId]
        if (mem != null && isCacheEntryUsable(mem, now)) {
            return mem
        }
        // Fallback check directly against SQLite if init hydration hasn't finished yet
        val row = withContext(Dispatchers.IO) {
            runCatching { streamCacheDao.getByTargetId(targetId) }.getOrNull()
        } ?: return null

        val kind = if (row.sourceKindKey == StreamSourceKind.PUBLIC_HLS.name) {
            StreamSourceKind.PUBLIC_HLS
        } else {
            StreamSourceKind.TAMASHA_HLS
        }
        val entry = CachedSessionStream(
            url = row.streamUrl,
            headers = deserializeHeaders(row.headersJson),
            sourceKind = kind,
            resolvedAt = row.resolvedAt,
            expiresAt = row.expiresAt
        )
        return if (isCacheEntryUsable(entry, now)) {
            sessionCache[targetId] = entry
            entry
        } else {
            null
        }
    }

    private fun isCacheEntryUsable(entry: CachedSessionStream, now: Long): Boolean {
        if (entry.url.isBlank() || isRecentlyFailed(entry.url)) return false
        if (entry.expiresAt <= now) return false
        if ((now - entry.resolvedAt) >= SESSION_FRESHNESS_MS) return false
        val expiry = M3uParser.parseWmsAuthSignExpiry(entry.url, now)
        if (expiry.isExpired) return false
        if (expiry.isSignedToken && expiry.minutesRemaining <= 3) return false
        return true
    }

    private fun cacheResolvedStream(
        targetId: String,
        url: String,
        headers: Map<String, String>,
        sourceKind: StreamSourceKind
    ) {
        val now = System.currentTimeMillis()
        val expiryInfo = M3uParser.parseWmsAuthSignExpiry(url, now)
        val computedExpiresAt = if (expiryInfo.isSignedToken && !expiryInfo.isExpired && expiryInfo.minutesRemaining > 0) {
            now + (expiryInfo.minutesRemaining * 60_000L).coerceAtMost(SESSION_FRESHNESS_MS)
        } else {
            now + SESSION_FRESHNESS_MS
        }

        sessionCache[targetId] = CachedSessionStream(
            url = url,
            headers = headers,
            sourceKind = sourceKind,
            resolvedAt = now,
            expiresAt = computedExpiresAt
        )

        val entity = StreamCacheEntity(
            targetId = targetId,
            streamUrl = url,
            headersJson = serializeHeaders(headers),
            sourceKindKey = sourceKind.name,
            resolvedAt = now,
            expiresAt = computedExpiresAt
        )
        engineScope.launch {
            runCatching { streamCacheDao.upsert(entity) }
        }
    }

    private fun serializeHeaders(headers: Map<String, String>): String {
        val obj = JSONObject()
        headers.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun deserializeHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return defaultTamashaHeaders()
        return runCatching {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotBlank()) map[k] = v
            }
            map
        }.getOrDefault(defaultTamashaHeaders())
    }

    private fun getTamashaCookie(streamUrl: String): String? = runCatching {
        val baseCookie = CookieManager.getInstance().getCookie(TAMASHA_BASE_URL)
        val streamCookie = CookieManager.getInstance().getCookie(streamUrl)
        listOfNotNull(baseCookie, streamCookie)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun sanitizeSniffedHeaders(raw: Map<String, String>): Map<String, String> = buildMap {
        val allowedExplicit = setOf(
            "referer", "origin", "user-agent", "cookie",
            "authorization", "accept", "accept-language"
        )
        raw.forEach { (k, v) ->
            val lower = k.trim().lowercase(Locale.ROOT)
            if (lower.isNotBlank() && v.isNotBlank() &&
                (lower in allowedExplicit || lower.startsWith("x-"))
            ) {
                put(k.trim(), v.trim())
            }
        }
    }

    private suspend fun sniffMediaWithHiddenWebView(
        pageUrl: String,
        channel: ChannelEntity,
        isVodPage: Boolean,
        timeoutMs: Long
    ): SniffedMedia? {
        val bestResult = CompletableDeferred<SniffedMedia?>()
        var fallbackChunk: SniffedMedia? = null
        var graceJob: Job? = null

        val task = HiddenFetcherTask(
            taskId = System.nanoTime(),
            pageUrl = pageUrl,
            channelStreamKey = channel.tamashaStreamKey,
            isVodPage = isVodPage,
            onCaptured = { rawUrl, capturedHeaders, isMaster ->
                val cleaned = rawUrl.trim()
                val valid = if (isVodPage) isTargetVodStream(cleaned) else isTargetLiveM3u8(cleaned, channel)
                if (valid && !isRecentlyFailed(cleaned)) {
                    val candidate = SniffedMedia(cleaned, capturedHeaders)
                    if (isMaster || (isVodPage && cleaned.substringBefore('?').endsWith(".mp4", true))) {
                        graceJob?.cancel()
                        if (!bestResult.isCompleted) bestResult.complete(candidate)
                    } else if (fallbackChunk == null) {
                        fallbackChunk = candidate
                        if (graceJob == null) {
                            graceJob = engineScope.launch {
                                delay(MASTER_PLAYLIST_GRACE_MS)
                                if (!bestResult.isCompleted) {
                                    bestResult.complete(fallbackChunk)
                                }
                            }
                        }
                    }
                }
            }
        )

        _activeFetcherTask.value = task
        return try {
            withTimeoutOrNull(timeoutMs) {
                bestResult.await()
            } ?: fallbackChunk
        } finally {
            graceJob?.cancel()
            if (_activeFetcherTask.value?.taskId == task.taskId) {
                _activeFetcherTask.value = null
            }
        }
    }

    private suspend fun ensureTamashaCatalogIndex(force: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && catalogLoadedAt > 0L && (now - catalogLoadedAt) < 25 * 60_000L && discoveredSlugsByKey.isNotEmpty()) {
            return@withContext
        }
        runCatching {
            val html = fetchText(indexHttpClient, "$TAMASHA_BASE_URL/live-tv", defaultTamashaHeaders())
                ?: fetchText(indexHttpClient, "$TAMASHA_BASE_URL/live", defaultTamashaHeaders())
                ?: return@withContext
            catalogLoadedAt = now

            buildIdRegex.find(html)?.groupValues?.getOrNull(1)?.let { nextBuildId = it }
            indexSlugsAndStreams(html)

            nextBuildId?.let { bid ->
                val catalogJson = fetchText(
                    indexHttpClient,
                    "$TAMASHA_BASE_URL/_next/data/$bid/live-tv.json",
                    defaultTamashaHeaders()
                )
                if (!catalogJson.isNullOrBlank()) {
                    indexSlugsAndStreams(catalogJson)
                }
            }
        }
    }

    private fun indexSlugsAndStreams(payload: String) {
        val ignored = setOf("live-tv", "movies", "dramas", "login", "search", "privacy-policy", "terms", "sports")

        slugHrefRegex.findAll(payload).forEach { match ->
            val rawSlug = match.groupValues[1].lowercase(Locale.ROOT).trimStart('/')
            val leaf = rawSlug.substringAfterLast('/')
            if (rawSlug !in ignored && leaf !in ignored) {
                val norm = M3uParser.normalizeChannelKey(leaf.replace("-live", ""))
                if (norm.length >= 3) {
                    discoveredSlugsByKey[norm] = rawSlug
                }
            }
        }

        jsonSlugRegex.findAll(payload).forEach { match ->
            val slug = match.groupValues[1].lowercase(Locale.ROOT).trimStart('/')
            if (slug !in ignored) {
                val norm = M3uParser.normalizeChannelKey(slug.replace("-live", ""))
                if (norm.length >= 3 && !discoveredSlugsByKey.containsKey(norm)) {
                    discoveredSlugsByKey[norm] = slug
                }
            }
        }

        extractMediaCandidates(payload, allowMp4 = false).forEach { url ->
            extractStreamKey(url)?.let { key ->
                discoveredTamashaStreamsByKey[key.lowercase(Locale.ROOT)] = url
            }
        }
    }

    private suspend fun extractMediaViaHttpAndNextData(
        slug: String,
        channel: ChannelEntity,
        allowMp4: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val cleanSlug = slug.trimStart('/')
        val headers = defaultTamashaHeaders()
        nextBuildId?.let { bid ->
            val json = fetchText(fastHttpClient, "$TAMASHA_BASE_URL/_next/data/$bid/$cleanSlug.json", headers)
            if (!json.isNullOrBlank()) {
                pickBestMedia(extractMediaCandidates(json, allowMp4), channel)?.let { return@withContext it }
            }
        }
        val html = fetchText(fastHttpClient, "$TAMASHA_BASE_URL/$cleanSlug", headers) ?: return@withContext null
        buildIdRegex.find(html)?.groupValues?.getOrNull(1)?.let { bid ->
            if (nextBuildId == null) {
                nextBuildId = bid
                val json = fetchText(fastHttpClient, "$TAMASHA_BASE_URL/_next/data/$bid/$cleanSlug.json", headers)
                if (!json.isNullOrBlank()) {
                    pickBestMedia(extractMediaCandidates(json, allowMp4), channel)?.let { return@withContext it }
                }
            }
        }
        pickBestMedia(extractMediaCandidates(html, allowMp4), channel)
    }

    private suspend fun tryFetchPublicLiveM3u8Parallel(channel: ChannelEntity): PublicStreamCandidate? =
        withContext(Dispatchers.IO) {
            val neutralHeaders = mapOf("User-Agent" to TAMASHA_USER_AGENT, "Accept" to "*/*")

            val candidates = mutableListOf<PublicStreamCandidate>()
            if (channel.publicFallbackUrl.isNotBlank() && !isRecentlyFailed(channel.publicFallbackUrl)) {
                candidates.add(PublicStreamCandidate(channel.publicFallbackUrl, neutralHeaders))
            }

            ensurePublicM3uIndex()
            findMatchingPublicStreams(channel)
                .filterNot { isRecentlyFailed(it.streamUrl) }
                .take(5)
                .forEach { parsed ->
                    candidates.add(PublicStreamCandidate(parsed.streamUrl, buildPublicHeaders(parsed)))
                }

            if (candidates.isEmpty()) return@withContext null

            val winner = CompletableDeferred<PublicStreamCandidate?>()
            coroutineScope {
                val jobs = candidates.map { candidate ->
                    launch {
                        if (winner.isCompleted) return@launch
                        if (verifyHlsReachable(candidate.url, candidate.headers) == HlsCheckResult.VALID) {
                            winner.complete(candidate)
                        } else {
                            markStreamUrlFailed(candidate.url)
                        }
                    }
                }
                launch {
                    jobs.forEach { it.join() }
                    if (!winner.isCompleted) winner.complete(null)
                }
                val result = winner.await()
                coroutineContext.cancelChildren()
                result
            }
        }

    private fun buildPublicHeaders(parsed: ParsedM3uChannel): Map<String, String> = buildMap {
        put("User-Agent", parsed.userAgent.ifBlank { TAMASHA_USER_AGENT })
        put("Accept", "*/*")
        if (parsed.referrer.isNotBlank()) {
            put("Referer", parsed.referrer)
            runCatching {
                val uri = Uri.parse(parsed.referrer)
                val origin = "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
                if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                    put("Origin", origin)
                }
            }
        } else if (parsed.streamUrl.contains("tamashaweb.com", ignoreCase = true)) {
            put("Referer", TAMASHA_REFERER)
            put("Origin", TAMASHA_ORIGIN)
        }
    }

    private suspend fun ensurePublicM3uIndex() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (publicIndexLoadedAt > 0L && (now - publicIndexLoadedAt) < 45 * 60_000L && publicLiveChannels.isNotEmpty()) {
            return@withContext
        }
        val headers = mapOf("User-Agent" to TAMASHA_USER_AGENT)
        val collected = coroutineScope {
            listOf(IPTV_PK_M3U, IPTV_URD_M3U, IPTV_INT_M3U).map { listUrl ->
                async {
                    val text = fetchText(indexHttpClient, listUrl, headers)
                    if (!text.isNullOrBlank()) M3uParser.parsePlaylist(text) else emptyList()
                }
            }.awaitAll().flatten()
        }
        if (collected.isNotEmpty()) {
            publicLiveChannels = collected.distinctBy { it.streamUrl }
            publicIndexLoadedAt = now
        }
    }

    private fun findMatchingPublicStreams(channel: ChannelEntity): List<ParsedM3uChannel> {
        val targetNorm = M3uParser.normalizeChannelKey(channel.name)
        val aliases = buildPublicAliases(channel.id, targetNorm)

        return publicLiveChannels
            .mapNotNull { item ->
                val itemNorm = M3uParser.normalizeChannelKey(item.name)
                val score = when {
                    itemNorm == targetNorm -> 100
                    aliases.any { it == itemNorm } -> 95
                    aliases.any { alias -> alias.length >= 4 && (itemNorm.contains(alias) || alias.contains(itemNorm)) } -> 75
                    targetNorm.length >= 4 && itemNorm.contains(targetNorm) -> 65
                    else -> 0
                }
                if (score > 0) score to item else null
            }
            .sortedByDescending { (score, item) ->
                val globalBonus = if (item.streamUrl.contains("tamashaweb.com", ignoreCase = true)) 0 else 10
                score + globalBonus
            }
            .map { it.second }
    }

    private fun buildPublicAliases(channelId: String, normName: String): List<String> = when (channelId) {
        "green_tv" -> listOf("greenentertainment", "greentv")
        "geo_entertainment" -> listOf("geoentertainment", "geotv", "harpalgeo")
        "geo_news" -> listOf("geonews", "geonewspk")
        "ary_digital" -> listOf("arydigital", "arydigitalasia")
        "ary_news" -> listOf("arynews", "arynewspk")
        "dawn_news" -> listOf("dawnnews", "dawnnewstv")
        "sun_news" -> listOf("sunonews", "sunonewshd", "sunnews")
        "ptv_news" -> listOf("ptvnews")
        "paigham_tv" -> listOf("paighamtv", "paigham")
        "ptv_sports" -> listOf("ptvsports", "ptvsportshd")
        "ptv_home" -> listOf("ptvhome")
        "samaa_news" -> listOf("samaatv", "samaanews", "samaa")
        "cgtn_documentary" -> listOf("cgtndocumentary", "cgtndoc")
        "discovery_hd" -> listOf("discoveryhd", "discoverychannel", "discovery")
        "hum_tv" -> listOf("humtv", "humtvpk")
        "hum_masala" -> listOf("hummasala", "masalatv")
        "aaj_news" -> listOf("aajnews", "aajtv")
        "al_jazeera" -> listOf("aljazeeraenglish", "aljazeera")
        "ary_zindagi" -> listOf("aryzindagi")
        "express_entertainment" -> listOf("expressentertainment")
        "hum_sitary" -> listOf("humsitaray", "humsitary")
        "cnn_news" -> listOf("cnninternational", "cnnnews", "cnn")
        "aplus" -> listOf("aplus", "aplustv", "aplusentertainment")
        else -> listOf(normName)
    }

    /**
     * Verifies BOTH HLS (.m3u8) and progressive MP4 (.mp4) VOD streams quickly against HTTP 403/401/404.
     */
    private suspend fun verifyPlayableMediaUrl(url: String, headers: Map<String, String>): HlsCheckResult {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        if (path.endsWith(".mp4")) {
            return verifyMp4Reachable(url, headers)
        }
        return verifyHlsReachable(url, headers)
    }

    private suspend fun verifyMp4Reachable(url: String, headers: Map<String, String>): HlsCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-2047")
                    .get()
                headers.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true)) reqBuilder.header(k, v)
                }
                fastHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code in setOf(401, 403, 404, 410, 451)) {
                        return@use HlsCheckResult.FORBIDDEN_OR_DEAD
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        return@use HlsCheckResult.NETWORK_INDETERMINATE
                    }
                    val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                    if (ct.contains("text/html") || ct.contains("application/json")) {
                        return@use HlsCheckResult.FORBIDDEN_OR_DEAD
                    }
                    HlsCheckResult.VALID
                }
            }.getOrDefault(HlsCheckResult.NETWORK_INDETERMINATE)
        }

    /**
     * Fast 2-level HLS verification using pooled low-latency probe client:
     * 1. Manifest responds 200 OK with valid #EXTM3U content.
     * 2. First child playlist/segment probe catches CDN 403/401/404 before ExoPlayer opens.
     */
    private suspend fun verifyHlsReachable(url: String, headers: Map<String, String>): HlsCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                fastHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code in setOf(401, 403, 404, 410, 451)) {
                        return@use HlsCheckResult.FORBIDDEN_OR_DEAD
                    }
                    if (!resp.isSuccessful) return@use HlsCheckResult.NETWORK_INDETERMINATE

                    val finalUrl = resp.request.url
                    val snippet = resp.body?.source()?.let { src ->
                        src.request(4096)
                        src.buffer.clone().readUtf8(minOf(src.buffer.size, 4096L))
                    }.orEmpty()

                    if (!snippet.contains("#EXTM3U", ignoreCase = true) &&
                        !snippet.contains("#EXT-X-", ignoreCase = true)
                    ) {
                        return@use HlsCheckResult.FORBIDDEN_OR_DEAD
                    }

                    val firstChildLine = snippet.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

                    if (firstChildLine != null) {
                        val childUrl = finalUrl.resolve(firstChildLine)?.toString()
                        if (!childUrl.isNullOrBlank()) {
                            val childReqBuilder = Request.Builder()
                                .url(childUrl)
                                .header("Range", "bytes=0-511")
                                .get()
                            headers.forEach { (k, v) ->
                                if (!k.equals("Range", ignoreCase = true)) {
                                    childReqBuilder.header(k, v)
                                }
                            }
                            val childStatus = runCatching {
                                fastHttpClient.newCall(childReqBuilder.build()).execute().use { childResp ->
                                    if (childResp.code in setOf(401, 403, 404, 410, 451)) {
                                        HlsCheckResult.FORBIDDEN_OR_DEAD
                                    } else if (childResp.isSuccessful || childResp.code == 206) {
                                        HlsCheckResult.VALID
                                    } else {
                                        HlsCheckResult.NETWORK_INDETERMINATE
                                    }
                                }
                            }.getOrDefault(HlsCheckResult.NETWORK_INDETERMINATE)

                            if (childStatus == HlsCheckResult.FORBIDDEN_OR_DEAD) {
                                return@use HlsCheckResult.FORBIDDEN_OR_DEAD
                            }
                        }
                    }

                    HlsCheckResult.VALID
                }
            }.getOrDefault(HlsCheckResult.NETWORK_INDETERMINATE)
        }

    private fun fetchText(client: OkHttpClient, url: String, headers: Map<String, String>): String? {
        return runCatching {
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun findInCatalogStreams(channel: ChannelEntity): String? {
        val key = channel.tamashaStreamKey.lowercase(Locale.ROOT)
        if (key.isNotBlank()) {
            discoveredTamashaStreamsByKey[key]?.let { return it }
            val core = key.removePrefix("vsat-").removeSuffix("-abr").replace("-", "").replace("_", "")
            discoveredTamashaStreamsByKey.entries.firstOrNull { (k, _) ->
                k.replace("-", "").replace("_", "").contains(core)
            }?.value?.let { return it }
        }
        return null
    }

    private fun findBestSlugs(channel: ChannelEntity): List<String> {
        val slugs = LinkedHashSet<String>()
        val normName = M3uParser.normalizeChannelKey(channel.name)
        discoveredSlugsByKey[normName]?.let { slugs.add(it) }
        discoveredSlugsByKey.entries.firstOrNull { (k, _) ->
            k.contains(normName) || normName.contains(k)
        }?.value?.let { slugs.add(it) }
        slugs.addAll(channel.allCandidateSlugs())
        return slugs.toList()
    }

    private fun extractMediaCandidates(raw: String, allowMp4: Boolean): List<String> {
        val found = LinkedHashSet<String>()
        m3u8RawRegex.findAll(raw).forEach { match ->
            val cleaned = cleanExtractedUrl(match.value)
            if (M3uParser.isValidStreamUrl(cleaned)) found.add(cleaned)
        }
        m3u8EncodedRegex.findAll(raw).forEach { match ->
            runCatching {
                val decoded = URLDecoder.decode(match.value, "UTF-8").trim()
                if (M3uParser.isValidStreamUrl(decoded)) found.add(decoded)
            }
        }
        if (allowMp4) {
            mp4RawRegex.findAll(raw).forEach { match ->
                val cleaned = cleanExtractedUrl(match.value)
                if (isTargetVodStream(cleaned)) found.add(cleaned)
            }
        }
        return found.toList()
    }

    private fun cleanExtractedUrl(raw: String): String = raw
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim()

    private fun pickBestMedia(candidates: List<String>, channel: ChannelEntity): String? {
        if (candidates.isEmpty()) return null
        val key = channel.tamashaStreamKey.lowercase(Locale.ROOT)
        return candidates
            .filterNot { isRecentlyFailed(it) }
            .sortedByDescending { url ->
                var score = 0
                val lower = url.lowercase(Locale.ROOT)
                if (lower.contains(".m3u8")) score += 25
                if (lower.contains("wmsauthsign")) score += 50
                if (lower.contains("tamashaweb.com")) score += 30
                if (key.isNotBlank() && lower.contains(key)) score += 40
                if (lower.contains("playlist.m3u8") || lower.contains("master.m3u8")) score += 20
                if (lower.contains("chunks")) score += 10
                score
            }
            .firstOrNull()
    }

    private fun extractStreamKey(url: String): String? {
        val marker = "/jazzauth/"
        val idx = url.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val after = url.substring(idx + marker.length)
        val slash = after.indexOf('/')
        return if (slash > 0) after.substring(0, slash) else null
    }

    private fun isTargetVodStream(url: String): Boolean {
        if (!M3uParser.isValidStreamUrl(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("imasdk") ||
            lower.contains("adserver") ||
            lower.contains("vast") ||
            lower.contains("preview") ||
            lower.contains("promo") ||
            lower.contains("trailer")
        ) {
            return false
        }
        val path = lower.substringBefore('?')
        return lower.contains(".m3u8") || path.endsWith(".mp4")
    }

    private fun isTargetLiveM3u8(url: String, channel: ChannelEntity): Boolean {
        if (!M3uParser.isValidStreamUrl(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.contains(".m3u8")) return false
        if (lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("imasdk") ||
            lower.contains("adserver") ||
            lower.contains("vast")
        ) {
            return false
        }
        val uriHost = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return uriHost.contains("tamashaweb") ||
            lower.contains("wmsauthsign") ||
            lower.contains("jazzauth") ||
            (channel.tamashaStreamKey.isNotBlank() && lower.contains(channel.tamashaStreamKey.lowercase(Locale.ROOT))) ||
            lower.contains("chunks") ||
            lower.contains("playlist.m3u8") ||
            lower.contains("master.m3u8") ||
            lower.contains("index.m3u8")
    }
}
