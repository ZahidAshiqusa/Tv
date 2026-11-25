package com.example.player

import com.example.data.M3uParser
import com.example.data.local.ChannelEntity
import com.example.data.local.VodItemEntity
import com.example.data.model.ChannelCategory
import com.example.data.model.VodSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CatalogSyncResult(
    val discoveredFreeChannels: List<ChannelEntity>,
    val hdLogosByNormalizedName: Map<String, String>,
    val discoveredFreeVodItems: List<VodItemEntity>
)

data class HiddenCatalogHarvestTask(
    val taskId: Long,
    val routes: List<Pair<String, String>>, // (routeHint, fullUrl)
    val onApiJson: (routeHint: String, jsonText: String) -> Unit,
    val onDomCards: (routeHint: String, cardsJson: String) -> Unit,
    val onRouteFinished: (routeHint: String) -> Unit,
    val onCompleted: () -> Unit
)

/**
 * Real-Time TamashaWeb.com Free Catalog Harvester.
 * Fetches ALL real, live, FREE Movies (from https://tamashaweb.com/movie),
 * Dramas & TV Shows (from https://tamashaweb.com/?filter=tvshows),
 * Highlights & Wrestling (from https://tamashaweb.com/),
 * and Live TV Channels + authentic original broadcast logos (from https://tamashaweb.com/live + iptv-org API).
 * Strictly excludes Pro, Paid, Premium, VIP, or subscription-locked items.
 */
class TamashaCatalogFetcher {

    private val harvestMutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeHarvestTask = MutableStateFlow<HiddenCatalogHarvestTask?>(null)
    val activeHarvestTask: StateFlow<HiddenCatalogHarvestTask?> = _activeHarvestTask.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .cookieJar(InMemoryStreamCookieJar())
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val nextDataRegex = Regex(
        """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val buildIdRegex = Regex(""""buildId"\s*:\s*"([^"]+)"""")
    private val paidWordRegex = Regex("""\b(pro|paid|premium|vip|gold|locked|subscribe|subscription)\b""", RegexOption.IGNORE_CASE)

    private val accentPalette = listOf(
        0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444,
        0xFF8B5CF6, 0xFFEC4899, 0xFF2563EB, 0xFF06B6D4
    )

    private val ignoredBasePaths = setOf(
        "", "/", "home", "live", "live-tv", "movie", "movies", "shows", "tv-shows", "dramas",
        "sports", "wrestling", "highlights", "shots", "login", "signup", "register",
        "search", "profile", "account", "settings", "subscribe", "subscription",
        "packages", "pricing", "pro", "premium", "privacy-policy", "terms",
        "terms-and-conditions", "about", "contact", "faq", "help", "watchlist", "more"
    )

    suspend fun fetchAllFreeCatalog(
        useHeadlessBrowser: Boolean = true,
        onPartialSync: (suspend (CatalogSyncResult) -> Unit)? = null
    ): CatalogSyncResult = harvestMutex.withLock {
        withContext(Dispatchers.IO) {
            val headers = mapOf(
                "Referer" to TamashaPluginEngine.TAMASHA_REFERER,
                "Origin" to TamashaPluginEngine.TAMASHA_ORIGIN,
                "User-Agent" to TamashaPluginEngine.TAMASHA_USER_AGENT,
                "Accept" to "text/html,application/json,*/*"
            )

            val hdLogos = ConcurrentHashMap<String, String>()
            val discoveredChannels = ConcurrentHashMap<String, ChannelEntity>()
            val discoveredVod = ConcurrentHashMap<String, VodItemEntity>()

            fun snapshotResult(): CatalogSyncResult {
                val enrichedChannels = discoveredChannels.values
                    .sortedBy { it.sortOrder }
                    .map { ch ->
                        if (ch.logoUrl.isBlank()) {
                            val norm = M3uParser.normalizeChannelKey(ch.name)
                            val matchedLogo = hdLogos[norm]
                                ?: hdLogos.entries.firstOrNull { (k, _) ->
                                    k.length >= 4 && (k.contains(norm) || norm.contains(k))
                                }?.value
                                ?: ""
                            ch.copy(logoUrl = matchedLogo)
                        } else ch
                    }

                val cleanVod = discoveredVod.values
                    .filter { it.pageSlug.isNotBlank() || it.streamUrl.isNotBlank() }
                    .sortedWith(compareBy<VodItemEntity> { it.sortOrder }.thenBy { it.title })

                return CatalogSyncResult(
                    discoveredFreeChannels = enrichedChannels,
                    hdLogosByNormalizedName = hdLogos.toMap(),
                    discoveredFreeVodItems = cleanVod
                )
            }

            // 1. Parallel OkHttp fetch of Tamasha's exact primary pages + official broadcast logos
            var buildId: String? = null
            coroutineScope {
                val iptvLogosJob = async { fetchOfficialBroadcastLogos(hdLogos) }

                val primaryPages = listOf(
                    "movie" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/movie",
                    "tvshows" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/?filter=tvshows",
                    "home" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/",
                    "live" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/live"
                )

                val pageJobs = primaryPages.map { (routeHint, url) ->
                    async {
                        val html = fetchText(url, headers) ?: return@async null
                        val foundBid = buildIdRegex.find(html)?.groupValues?.getOrNull(1)
                        extractNextDataJson(html)?.let { root ->
                            ingestJsonTree(root, routeHint, discoveredChannels, hdLogos, discoveredVod)
                        }
                        foundBid
                    }
                }

                buildId = pageJobs.awaitAll().firstOrNull { !it.isNullOrBlank() }

                // Fetch Next.js JSON data routes in parallel if buildId was found
                buildId?.let { bid ->
                    val nextRoutes = listOf("movie", "movies", "shows", "dramas", "live", "live-tv", "sports")
                    nextRoutes.map { route ->
                        async {
                            val jsonPayload = fetchText(
                                "${TamashaPluginEngine.TAMASHA_BASE_URL}/_next/data/$bid/$route.json",
                                headers
                            )
                            val rootObj = jsonPayload?.let { runCatching { JSONObject(it) }.getOrNull() }
                            if (rootObj != null) {
                                val hint = when (route) {
                                    "movie", "movies" -> "movie"
                                    "shows", "dramas" -> "tvshows"
                                    "live", "live-tv" -> "live"
                                    else -> route
                                }
                                ingestJsonTree(rootObj, hint, discoveredChannels, hdLogos, discoveredVod)
                            }
                        }
                    }.awaitAll()
                }

                iptvLogosJob.await()
            }

            // Emit fast OkHttp results immediately (original channel logos + SSR VOD items)
            if (onPartialSync != null && (discoveredVod.isNotEmpty() || hdLogos.isNotEmpty())) {
                runCatching { onPartialSync(snapshotResult()) }
            }

            // 2. Headless Chromium WebView Harvester on the exact live pages:
            //    - https://tamashaweb.com/movie
            //    - https://tamashaweb.com/?filter=tvshows
            //    - https://tamashaweb.com/
            //    - https://tamashaweb.com/live
            //    Streams live results incrementally after each route finishes!
            if (useHeadlessBrowser) {
                harvestViaHiddenWebView(
                    channelsOut = discoveredChannels,
                    hdLogosOut = hdLogos,
                    vodOut = discoveredVod,
                    onRouteDone = {
                        if (onPartialSync != null) {
                            callbackScope.launch {
                                runCatching { onPartialSync(snapshotResult()) }
                            }
                        }
                    }
                )
            }

            snapshotResult()
        }
    }

    private suspend fun harvestViaHiddenWebView(
        channelsOut: ConcurrentHashMap<String, ChannelEntity>,
        hdLogosOut: ConcurrentHashMap<String, String>,
        vodOut: ConcurrentHashMap<String, VodItemEntity>,
        onRouteDone: (routeHint: String) -> Unit
    ) {
        val finished = CompletableDeferred<Unit>()
        val routes = listOf(
            "movie" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/movie",
            "tvshows" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/?filter=tvshows",
            "home" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/",
            "live" to "${TamashaPluginEngine.TAMASHA_BASE_URL}/live"
        )

        val task = HiddenCatalogHarvestTask(
            taskId = System.nanoTime(),
            routes = routes,
            onApiJson = { routeHint, jsonText ->
                runCatching {
                    val trimmed = jsonText.trim()
                    when {
                        trimmed.startsWith("{") -> {
                            ingestJsonTree(JSONObject(trimmed), routeHint, channelsOut, hdLogosOut, vodOut)
                        }
                        trimmed.startsWith("[") -> {
                            ingestJsonTree(JSONArray(trimmed), routeHint, channelsOut, hdLogosOut, vodOut)
                        }
                    }
                }
            },
            onDomCards = { routeHint, cardsJson ->
                runCatching {
                    val array = JSONArray(cardsJson)
                    for (i in 0 until array.length()) {
                        val card = array.optJSONObject(i) ?: continue
                        ingestDomCard(card, routeHint, channelsOut, hdLogosOut, vodOut)
                    }
                }
            },
            onRouteFinished = { routeHint ->
                onRouteDone(routeHint)
            },
            onCompleted = {
                if (!finished.isCompleted) finished.complete(Unit)
            }
        )

        _activeHarvestTask.value = task
        try {
            withTimeoutOrNull(34_000L) {
                finished.await()
            }
        } finally {
            if (_activeHarvestTask.value?.taskId == task.taskId) {
                _activeHarvestTask.value = null
            }
        }
    }

    private fun ingestDomCard(
        card: JSONObject,
        routeHint: String,
        channelsOut: ConcurrentHashMap<String, ChannelEntity>,
        hdLogosOut: ConcurrentHashMap<String, String>,
        vodOut: ConcurrentHashMap<String, VodItemEntity>
    ) {
        if (card.optBoolean("isPro", false)) return

        val rawHref = card.optString("href", "").trim()
        val slug = normalizeSlug(rawHref)
        val directStream = card.optString("streamUrl", "").trim()
            .takeIf { it.startsWith("http", true) && (it.contains(".m3u8", true) || it.contains(".mp4", true)) }
            .orEmpty()

        val basePath = slug.substringBefore('?').lowercase(Locale.ROOT)
        val hasQueryId = slug.contains("?") && (slug.contains("id=", true) || slug.contains("v=", true) || slug.contains("slug=", true))
        if ((slug.isBlank() || (basePath in ignoredBasePaths && !hasQueryId)) && directStream.isBlank()) return

        val imgUrl = normalizeAssetUrl(card.optString("img", "").trim())
        val rawTitle = card.optString("title", "").trim()
        val sectionHeading = card.optString("section", "").trim()

        val title = rawTitle.ifBlank { deriveTitleFromSlug(slug) }
        if (title.length !in 2..90) return

        val combined = "$sectionHeading $slug $title".lowercase(Locale.ROOT)
        if (combined.contains("upgrade to pro") || combined.contains("get pro") ||
            combined.contains("premium plan") || combined.contains("tamasha pro") ||
            combined.contains("subscribe now")
        ) {
            return
        }

        val isChannel = routeHint == "live" ||
            basePath.endsWith("-live") ||
            basePath.startsWith("live-tv/") ||
            basePath.startsWith("live/") ||
            imgUrl.contains("/channels/", ignoreCase = true) ||
            sectionHeading.contains("live tv", ignoreCase = true) ||
            sectionHeading.contains("live channels", ignoreCase = true)

        if (isChannel) {
            val norm = M3uParser.normalizeChannelKey(title)
            if (norm.length >= 2) {
                if (imgUrl.startsWith("http", ignoreCase = true)) {
                    hdLogosOut[norm] = imgUrl
                }
                channelsOut.putIfAbsent(
                    norm,
                    ChannelEntity(
                        id = "tamasha_$norm",
                        name = title,
                        urduName = title,
                        streamUrl = directStream,
                        tamashaSlug = slug,
                        tamashaAltSlugs = basePath.removeSuffix("-live"),
                        tamashaStreamKey = extractStreamKey(directStream) ?: "",
                        categoryKey = M3uParser.detectCategory(title, sectionHeading).storageKey,
                        logoUrl = imgUrl,
                        fallbackLogoUrl = "",
                        accentHex = accentPalette[Math.abs(norm.hashCode()) % accentPalette.size],
                        sortOrder = 100 + channelsOut.size
                    )
                )
            }
            return
        }

        if (!imgUrl.startsWith("http", ignoreCase = true)) return

        val section = classifyVodSection(routeHint, sectionHeading, slug, title)
        val norm = M3uParser.normalizeChannelKey(title)
        val key = "${section.storageKey}_$norm"
        if (norm.length >= 2 && vodOut.size < 400) {
            val metaLabel = sectionHeading.takeIf { it.isNotBlank() && it.length <= 28 }
            val meta = listOf(metaLabel, "Free").filterNotNull().joinToString(" • ")
            val altSlugs = buildCandidateAltSlugs(slug, section, card.optString("id", ""))
            val candidate = VodItemEntity(
                id = "vod_$key",
                title = title,
                urduTitle = title,
                sectionKey = section.storageKey,
                pageSlug = slug,
                altSlugs = altSlugs,
                streamUrl = directStream,
                posterUrl = imgUrl,
                fallbackPosterUrl = "",
                subtitleMeta = meta,
                accentHex = accentPalette[Math.abs(norm.hashCode()) % accentPalette.size],
                sortOrder = 10 + vodOut.size
            )
            vodOut.merge(key, candidate) { existing, incoming ->
                existing.copy(
                    streamUrl = existing.streamUrl.ifBlank { incoming.streamUrl },
                    posterUrl = if (incoming.posterUrl.contains("canvas.tamashaweb.com", true)) {
                        incoming.posterUrl
                    } else {
                        existing.posterUrl.ifBlank { incoming.posterUrl }
                    },
                    pageSlug = existing.pageSlug.ifBlank { incoming.pageSlug },
                    altSlugs = mergeAltSlugs(existing.altSlugs, incoming.altSlugs)
                )
            }
        }
    }

    private fun ingestJsonTree(
        node: Any?,
        routeHint: String,
        channelsOut: ConcurrentHashMap<String, ChannelEntity>,
        hdLogosOut: ConcurrentHashMap<String, String>,
        vodOut: ConcurrentHashMap<String, VodItemEntity>,
        parentSectionHint: String = ""
    ) {
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    ingestJsonTree(node.opt(i), routeHint, channelsOut, hdLogosOut, vodOut, parentSectionHint)
                }
            }
            is JSONObject -> {
                val hasChildrenList = node.has("items") || node.has("data") ||
                    node.has("contents") || node.has("list") || node.has("videos") ||
                    node.has("movies") || node.has("shows") || node.has("dramas") ||
                    node.has("episodes") || node.has("channels") || node.has("results")

                val containerHeading = firstNonBlank(
                    node,
                    "section_name", "sectionTitle", "carousel_title", "rail_title",
                    "category_title", "heading", "row_title", "railName", "categoryName"
                ).ifBlank {
                    if (hasChildrenList) firstNonBlank(node, "title", "name") else ""
                }.ifBlank { parentSectionHint }

                val title = firstNonBlank(
                    node,
                    "channel_name", "show_name", "movie_name", "drama_name", "series_name",
                    "video_title", "content_title", "episode_title", "title", "name", "display_name"
                )
                val rawSlug = firstNonBlank(
                    node,
                    "slug", "seo_url", "seo_slug", "channel_slug", "web_slug", "url_slug",
                    "share_url", "shareUrl", "web_url", "webUrl", "deep_link", "deepLink", "link", "href"
                )
                val itemId = firstNonBlank(
                    node,
                    "content_id", "vod_id", "movie_id", "series_id", "show_id", "episode_id", "id"
                )
                val slug = normalizeSlug(rawSlug)
                val image = normalizeAssetUrl(extractBestImage(node))
                val streamUrl = extractBestStreamUrl(node)

                val genre = firstNonBlank(
                    node,
                    "genre", "category", "category_name", "content_type",
                    "type", "sub_category", "program_type"
                )

                val basePath = slug.substringBefore('?').lowercase(Locale.ROOT)
                val looksLikeChannel = title.length in 2..52 &&
                    (slug.isNotBlank() || streamUrl.isNotBlank()) &&
                    (node.has("channel_name") || node.has("channel_logo") || node.has("channel_id") ||
                        image.contains("/channels/", ignoreCase = true) ||
                        basePath.endsWith("-live") ||
                        streamUrl.contains("/jazzauth/", ignoreCase = true) ||
                        genre.contains("live tv", ignoreCase = true))

                if (looksLikeChannel && !isPaidOrProItem(node)) {
                    val norm = M3uParser.normalizeChannelKey(title)
                    if (norm.length >= 2) {
                        if (image.startsWith("http", ignoreCase = true)) {
                            hdLogosOut[norm] = image
                        }
                        val cleanSlug = slug.ifBlank {
                            title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-live"
                        }
                        val category = M3uParser.detectCategory(title, "$genre $containerHeading")
                        val streamKey = extractStreamKey(streamUrl) ?: ""
                        channelsOut.putIfAbsent(
                            norm,
                            ChannelEntity(
                                id = "tamasha_$norm",
                                name = title,
                                urduName = title,
                                streamUrl = streamUrl,
                                tamashaSlug = cleanSlug,
                                tamashaAltSlugs = cleanSlug.removeSuffix("-live"),
                                tamashaStreamKey = streamKey,
                                categoryKey = category.storageKey,
                                logoUrl = image,
                                fallbackLogoUrl = "",
                                accentHex = accentPalette[Math.abs(norm.hashCode()) % accentPalette.size],
                                sortOrder = 100 + channelsOut.size
                            )
                        )
                    }
                } else {
                    val hasQueryId = slug.contains("?") && (slug.contains("id=", true) || slug.contains("v=", true))
                    val validSlug = slug.isNotBlank() && (basePath !in ignoredBasePaths || hasQueryId)
                    val derivedSlug = if (!validSlug && itemId.isNotBlank() && title.length in 2..90 && image.startsWith("http", true)) {
                        val safeTitleSlug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        when (routeHint) {
                            "movie" -> "movie/$safeTitleSlug"
                            else -> safeTitleSlug
                        }
                    } else slug

                    val looksLikeVod = !looksLikeChannel &&
                        !hasChildrenList &&
                        title.length in 2..90 &&
                        (derivedSlug.isNotBlank() || streamUrl.isNotBlank()) &&
                        image.startsWith("http", ignoreCase = true)

                    if (looksLikeVod && !isPaidOrProItem(node)) {
                        val section = classifyVodSection(routeHint, "$containerHeading $genre", derivedSlug, title)
                        val norm = M3uParser.normalizeChannelKey(title)
                        val key = "${section.storageKey}_$norm"
                        if (norm.length >= 2 && vodOut.size < 400) {
                            val metaLabel = genre.ifBlank { containerHeading }.takeIf { it.isNotBlank() && it.length <= 28 }
                            val meta = listOf(metaLabel, "Free").filterNotNull().joinToString(" • ")
                            val altSlugs = buildCandidateAltSlugs(derivedSlug, section, itemId)
                            val item = VodItemEntity(
                                id = "vod_$key",
                                title = title,
                                urduTitle = title,
                                sectionKey = section.storageKey,
                                pageSlug = derivedSlug,
                                altSlugs = altSlugs,
                                streamUrl = streamUrl,
                                posterUrl = image,
                                fallbackPosterUrl = "",
                                subtitleMeta = meta,
                                accentHex = accentPalette[Math.abs(norm.hashCode()) % accentPalette.size],
                                sortOrder = 10 + vodOut.size
                            )
                            vodOut.merge(key, item) { existing, incoming ->
                                existing.copy(
                                    streamUrl = existing.streamUrl.ifBlank { incoming.streamUrl },
                                    posterUrl = if (incoming.posterUrl.contains("canvas.tamashaweb.com", true)) {
                                        incoming.posterUrl
                                    } else {
                                        existing.posterUrl.ifBlank { incoming.posterUrl }
                                    },
                                    pageSlug = if (existing.pageSlug.isBlank() || existing.pageSlug.none { it == '/' } && incoming.pageSlug.contains('/')) {
                                        incoming.pageSlug
                                    } else {
                                        existing.pageSlug
                                    },
                                    altSlugs = mergeAltSlugs(existing.altSlugs, incoming.altSlugs)
                                )
                            }
                        }
                    }
                }

                // Always traverse child properties (do not drop entire subtrees due to user-level flags)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val keyName = keys.next()
                    if (keyName == "user" || keyName == "subscription_plans" || keyName == "packages") continue
                    ingestJsonTree(node.opt(keyName), routeHint, channelsOut, hdLogosOut, vodOut, containerHeading)
                }
            }
        }
    }

    private fun extractBestImage(obj: JSONObject): String {
        val imageKeys = listOf(
            "channel_logo", "logo", "logo_url", "channel_icon",
            "landscape_image", "horizontal_poster", "banner_image", "thumbnail_url", "thumb_url",
            "thumbnail", "thumb", "poster_url", "vertical_poster", "portrait_image",
            "poster", "poster_img", "cover_image", "cover", "banner", "banner_img",
            "artwork", "image_url", "img_url", "image", "img", "picture", "icon"
        )
        for (k in imageKeys) {
            val raw = obj.opt(k) ?: continue
            when (raw) {
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isNotBlank() && !trimmed.equals("null", true) &&
                        (trimmed.startsWith("http", true) || trimmed.startsWith("//") || trimmed.startsWith("/"))
                    ) {
                        return trimmed
                    }
                }
                is JSONObject -> {
                    val nested = firstNonBlank(raw, "landscape", "horizontal", "url", "src", "large", "medium", "portrait", "original")
                    if (nested.isNotBlank()) return nested
                }
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        val el = raw.opt(i)
                        if (el is String && el.startsWith("http", true)) return el.trim()
                        if (el is JSONObject) {
                            val nested = firstNonBlank(el, "url", "src", "image")
                            if (nested.isNotBlank()) return nested
                        }
                    }
                }
            }
        }
        return ""
    }

    private fun extractBestStreamUrl(obj: JSONObject): String {
        val streamKeys = listOf(
            "stream_url", "streamUrl", "hls_url", "hlsUrl", "m3u8_url", "m3u8Url", "m3u8",
            "live_url", "liveUrl", "vod_url", "vodUrl", "video_url", "videoUrl",
            "playback_url", "playbackUrl", "play_url", "playUrl", "media_url", "mediaUrl",
            "cdn_url", "cdnUrl", "signed_url", "signedUrl", "episode_url", "url"
        )
        for (k in streamKeys) {
            val candidate = obj.optString(k, "").replace("\\/", "/").replace("\\u0026", "&").trim()
            if (candidate.startsWith("http", ignoreCase = true) &&
                (candidate.contains(".m3u8", ignoreCase = true) || candidate.contains(".mp4", ignoreCase = true))
            ) {
                return candidate
            }
        }
        return ""
    }

    private fun buildCandidateAltSlugs(primarySlug: String, section: VodSection, itemId: String): String {
        val alts = LinkedHashSet<String>()
        val clean = primarySlug.trim().trim('/')
        val leaf = clean.substringAfterLast('/').substringBefore('?')
        if (leaf.isNotBlank() && leaf != clean) {
            alts.add(leaf)
        }
        if (clean.isNotBlank() && !clean.contains('/') && !clean.contains('?')) {
            when (section) {
                VodSection.MOVIE -> {
                    alts.add("movie/$clean")
                    alts.add("movies/$clean")
                }
                VodSection.DRAMA, VodSection.TV_SHOW -> {
                    alts.add("shows/$clean")
                    alts.add("dramas/$clean")
                    alts.add("watch/$clean")
                }
                VodSection.WRESTLING, VodSection.HIGHLIGHTS -> {
                    alts.add("sports/$clean")
                    alts.add("watch/$clean")
                }
            }
        }
        if (itemId.isNotBlank() && itemId.all { it.isDigit() }) {
            when (section) {
                VodSection.MOVIE -> alts.add("movie?id=$itemId")
                else -> alts.add("watch?id=$itemId")
            }
        }
        alts.remove(primarySlug)
        return alts.take(4).joinToString(",")
    }

    private fun mergeAltSlugs(a: String, b: String): String {
        return (a.split(',') + b.split(','))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .joinToString(",")
    }

    private fun classifyVodSection(
        routeHint: String,
        metaHint: String,
        slug: String,
        title: String
    ): VodSection {
        val combined = "$metaHint $slug $title".lowercase(Locale.ROOT)
        return when {
            routeHint == "movie" ||
                slug.startsWith("movie/", true) ||
                slug.startsWith("movies/", true) ||
                combined.contains("movie") || combined.contains("film") ||
                combined.contains("telefilm") || combined.contains("cinema") -> VodSection.MOVIE

            combined.contains("wrestl") || combined.contains("wwe") ||
                combined.contains("smackdown") || combined.contains("raw") ||
                combined.contains("nxt") || combined.contains("aew") ||
                combined.contains("mma") || combined.contains("ufc") ||
                combined.contains("naiza") || combined.contains("fight") -> VodSection.WRESTLING

            combined.contains("highlight") || combined.contains("match") ||
                combined.contains("innings") || combined.contains("wickets") ||
                combined.contains("sixes") || combined.contains("psl") ||
                combined.contains("cricket") || combined.contains("world cup") ||
                combined.contains("t20") || combined.contains("odi") ||
                combined.contains("goals") -> VodSection.HIGHLIGHTS

            combined.contains("talk show") || combined.contains("morning show") ||
                combined.contains("game show") || combined.contains("comedy show") ||
                combined.contains("mazaq") || combined.contains("hasna") ||
                combined.contains("jeeto") || combined.contains("khabar") ||
                combined.contains("cooking") || combined.contains("recipe") ||
                combined.contains("podcast") || combined.contains("reality") -> VodSection.TV_SHOW

            routeHint == "tvshows" ||
                combined.contains("drama") || combined.contains("serial") ||
                combined.contains("shots") || combined.contains("episode") ||
                combined.contains("season") || combined.contains("series") -> VodSection.DRAMA

            else -> VodSection.TV_SHOW
        }
    }

    /**
     * Strictly rejects any Pro, Paid, Premium, VIP, or subscription-locked item while avoiding
     * false positives on words like "program", "production", or "profile".
     */
    private fun isPaidOrProItem(obj: JSONObject): Boolean {
        val booleanPaidKeys = listOf(
            "is_paid", "isPaid", "is_pro", "isPro", "is_premium", "isPremium",
            "is_locked", "isLocked", "is_vip", "isVip", "requires_subscription",
            "is_subscription_required", "paid", "premium", "pro", "is_exclusive_pro",
            "subscription_required", "locked"
        )
        for (key in booleanPaidKeys) {
            if (obj.has(key)) {
                val value = obj.opt(key)
                if (value == true || value == 1 ||
                    value?.toString()?.equals("true", true) == true ||
                    value?.toString() == "1"
                ) {
                    return true
                }
            }
        }

        listOf("is_free", "isFree", "free").forEach { key ->
            if (obj.has(key)) {
                val v = obj.opt(key)
                if (v == false || v == 0 ||
                    v?.toString()?.equals("false", true) == true ||
                    v?.toString() == "0"
                ) {
                    return true
                }
            }
        }

        val price = obj.optDouble("price", 0.0)
        if (!price.isNaN() && price > 0.0) return true

        val tagFields = listOf(
            "badge", "tag", "access_type", "plan_type", "package_type",
            "tier", "label", "subscription_type", "monetization", "ribbon"
        )
        for (field in tagFields) {
            val text = obj.optString(field, "").trim()
            if (text.isNotBlank() && paidWordRegex.containsMatchIn(text)) {
                return true
            }
        }
        return false
    }

    private fun extractNextDataJson(html: String): JSONObject? {
        val raw = nextDataRegex.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * Preserves query parameters (e.g., watch?id=12345 or movie?id=456) while stripping #fragments
     * and tracking parameters so Tamasha watch URLs never lose their content ID!
     */
    private fun normalizeSlug(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            if (!s.contains("tamashaweb.com", ignoreCase = true)) return ""
            s = s.substringAfter("tamashaweb.com", "")
        }
        s = s.substringBefore('#').trim().trimStart('/')
        val pathPart = s.substringBefore('?').trimEnd('/')
        val queryPart = s.substringAfter('?', "")
        if (queryPart.isBlank()) return pathPart
        val cleanParams = queryPart.split('&')
            .filter { p ->
                val lower = p.lowercase(Locale.ROOT)
                p.isNotBlank() && !lower.startsWith("utm_") && !lower.startsWith("fbclid")
            }
            .joinToString("&")
        return if (cleanParams.isNotBlank()) "$pathPart?$cleanParams" else pathPart
    }

    private fun deriveTitleFromSlug(slug: String): String {
        val leaf = slug.substringBefore('?').substringAfterLast('/')
        return leaf.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val raw = obj.opt(k) ?: continue
            if (raw is JSONObject || raw is JSONArray) continue
            val v = raw.toString().trim()
            if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) {
                return v
            }
        }
        return ""
    }

    private fun normalizeAssetUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val clean = raw.replace("\\/", "/").trim()
        return when {
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "https://canvas.tamashaweb.com$clean"
            else -> ""
        }
    }

    private fun extractStreamKey(url: String): String? {
        val marker = "/jazzauth/"
        val idx = url.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val after = url.substring(idx + marker.length)
        val slash = after.indexOf('/')
        return if (slash > 0) after.substring(0, slash) else null
    }

    /**
     * Fetches 100% authentic original broadcaster PNG/WebP logos from iptv-org live M3Us
     * and the official iptv-org database API (channels.json + logos.json).
     */
    private suspend fun fetchOfficialBroadcastLogos(
        hdLogosOut: ConcurrentHashMap<String, String>
    ) = coroutineScope {
        val uaHeaders = mapOf("User-Agent" to TamashaPluginEngine.TAMASHA_USER_AGENT)
        val m3uUrls = listOf(
            "https://iptv-org.github.io/iptv/countries/pk.m3u",
            "https://iptv-org.github.io/iptv/languages/urd.m3u",
            "https://iptv-org.github.io/iptv/categories/news.m3u",
            "https://iptv-org.github.io/iptv/categories/sports.m3u",
            "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
            "https://iptv-org.github.io/iptv/categories/religious.m3u",
            "https://iptv-org.github.io/iptv/categories/documentary.m3u"
        )

        val m3uJobs = m3uUrls.map { m3uUrl ->
            async {
                val raw = fetchText(m3uUrl, uaHeaders) ?: return@async
                M3uParser.parsePlaylist(raw).forEach { parsed ->
                    val norm = M3uParser.normalizeChannelKey(parsed.name)
                    val logo = parsed.logoUrl.trim()
                    if (norm.length >= 3 && isDecodableRasterLogo(logo)) {
                        hdLogosOut.putIfAbsent(norm, logo)
                    }
                }
            }
        }

        val apiJob = async {
            runCatching {
                val channelsRaw = fetchText("https://iptv-org.github.io/api/channels.json", uaHeaders)
                val logosRaw = fetchText("https://iptv-org.github.io/api/logos.json", uaHeaders)
                if (channelsRaw.isNullOrBlank() || logosRaw.isNullOrBlank()) return@runCatching

                val targetCountries = setOf("PK", "QA", "US", "CN", "GB", "AE")
                val idToNames = HashMap<String, List<String>>(1024)
                val channelsArr = JSONArray(channelsRaw)
                for (i in 0 until channelsArr.length()) {
                    val obj = channelsArr.optJSONObject(i) ?: continue
                    val country = obj.optString("country", "").uppercase(Locale.ROOT)
                    val id = obj.optString("id", "").trim()
                    if (id.isBlank()) continue
                    if (country !in targetCountries && !id.endsWith(".pk", ignoreCase = true)) continue

                    val names = mutableListOf<String>()
                    val mainName = obj.optString("name", "").trim()
                    if (mainName.isNotBlank()) names.add(mainName)
                    val altArr = obj.optJSONArray("alt_names")
                    if (altArr != null) {
                        for (j in 0 until altArr.length()) {
                            val alt = altArr.optString(j, "").trim()
                            if (alt.isNotBlank()) names.add(alt)
                        }
                    }
                    val idStem = id.substringBefore('.').trim()
                    if (idStem.isNotBlank()) names.add(idStem)
                    if (names.isNotEmpty()) {
                        idToNames[id] = names
                    }
                }

                val bestLogoById = HashMap<String, Pair<Int, String>>(1024)
                val logosArr = JSONArray(logosRaw)
                for (i in 0 until logosArr.length()) {
                    val obj = logosArr.optJSONObject(i) ?: continue
                    val channelId = obj.optString("channel", "").trim()
                    if (!idToNames.containsKey(channelId)) continue

                    val url = obj.optString("url", "").trim()
                    if (!isDecodableRasterLogo(url)) continue
                    val format = obj.optString("format", "").uppercase(Locale.ROOT)
                    if (format == "SVG") continue

                    val width = obj.optInt("width", 0)
                    val height = obj.optInt("height", 0)
                    val inUseBonus = if (obj.optBoolean("in_use", true)) 10000 else 0
                    val pngBonus = if (format == "PNG" || url.endsWith(".png", true)) 500 else 0
                    val score = inUseBonus + pngBonus + minOf(width.coerceAtLeast(height), 1200)

                    val prev = bestLogoById[channelId]
                    if (prev == null || score > prev.first) {
                        bestLogoById[channelId] = score to url
                    }
                }

                bestLogoById.forEach { (channelId, pair) ->
                    val logoUrl = pair.second
                    idToNames[channelId]?.forEach { rawName ->
                        val norm = M3uParser.normalizeChannelKey(rawName)
                        if (norm.length >= 3) {
                            val existing = hdLogosOut[norm]
                            if (existing.isNullOrBlank() || !existing.contains("tamashaweb.com", ignoreCase = true)) {
                                hdLogosOut[norm] = logoUrl
                            }
                        }
                    }
                }
            }
        }

        m3uJobs.awaitAll()
        apiJob.await()
    }

    private fun isDecodableRasterLogo(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return false
        }
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return !path.endsWith(".svg") && !path.endsWith(".svgz")
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val req = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> req.header(k, v) }
            httpClient.newCall(req.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
