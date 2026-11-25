package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.player.HiddenCatalogHarvestTask
import com.example.player.HiddenFetcherTask
import com.example.player.TamashaPluginEngine
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-safe, memory-lightweight invisible Chromium stream fetcher used ONLY in the background
 * to sniff freshly signed .m3u8 / .mp4 URLs + request headers from tamashaweb.com and hand them
 * directly to native Media3 ExoPlayer.
 *
 * Implements full onRenderProcessGone() recovery + software layer rendering so Chromium never
 * contends with ExoPlayer hardware video surfaces or crashes the host app process.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun HiddenTamashaFetcherHost(
    task: HiddenFetcherTask?,
    modifier: Modifier = Modifier
) {
    if (task == null) return

    val context = LocalContext.current
    val isDead = remember(task.taskId) { AtomicBoolean(false) }
    var rendererCrashed by remember(task.taskId) { mutableStateOf(false) }

    if (rendererCrashed) return

    val webView = remember(task.taskId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Software layer prevents GPU surface contention with ExoPlayer on emulators & low-RAM devices
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            }
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> false }

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = TamashaPluginEngine.TAMASHA_USER_AGENT
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onM3u8Found(url: String?) {
                        if (isDead.get()) return
                        if (!url.isNullOrBlank() && isPlayableStreamUrl(url, task.isVodPage)) {
                            val isMaster = isMasterPlaylistUrl(url)
                            task.onCaptured(url, emptyMap(), isMaster)
                        }
                    }
                },
                "TamashaM3u8Bridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    isDead.set(true)
                    rendererCrashed = true
                    safeDestroyWebView(view, "TamashaM3u8Bridge")
                    // Return true so Android never kills the host app process
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (isDead.get()) return empty204Response()
                    val reqUrl = request?.url?.toString().orEmpty()
                    val lower = reqUrl.lowercase(Locale.ROOT)

                    // 1. Capture any live .m3u8 (or VOD .mp4) request together with exact browser headers
                    if (isPlayableStreamUrl(lower, task.isVodPage)) {
                        val headers = request?.requestHeaders.orEmpty()
                        val isMaster = isMasterPlaylistUrl(lower)
                        task.onCaptured(reqUrl, headers, isMaster)
                        if (lower.substringBefore('?').endsWith(".mp4")) {
                            return empty204Response()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    // 2. Block heavy media chunks, images, fonts, and ad/analytics beacons
                    if (shouldBlockBandwidthHeavyResource(lower, allowMp4Sniff = task.isVodPage, blockCss = false)) {
                        return empty204Response()
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!isDead.get()) {
                        runCatching { view?.evaluateJavascript(EARLY_SNIFFER_JS, null) }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isDead.get()) {
                        runCatching {
                            view?.evaluateJavascript(EARLY_SNIFFER_JS, null)
                            view?.evaluateJavascript(POLL_RESOURCES_JS, null)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(task.taskId) {
        if (!isDead.get()) {
            runCatching {
                webView.loadUrl(task.pageUrl, mapOf("Referer" to TamashaPluginEngine.TAMASHA_REFERER))
            }
        }
        val polls = if (task.isVodPage) 52 else 34
        for (step in 0 until polls) {
            delay(240L)
            if (isDead.get()) break
            runCatching { webView.evaluateJavascript(POLL_RESOURCES_JS, null) }
            if (task.isVodPage && (step == 5 || step == 12 || step == 22) && !isDead.get()) {
                runCatching { webView.evaluateJavascript(VOD_CLICK_FIRST_FREE_EPISODE_JS, null) }
            }
        }
    }

    DisposableEffect(task.taskId) {
        onDispose {
            if (isDead.compareAndSet(false, true)) {
                safeDestroyWebView(webView, "TamashaM3u8Bridge")
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 320.dp, height = 180.dp)
            .alpha(0f)
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.size(width = 320.dp, height = 180.dp)
        )
    }
}

/**
 * Crash-safe, ultra-lightweight invisible background Chromium harvester visiting:
 * - https://tamashaweb.com/movie
 * - https://tamashaweb.com/?filter=tvshows
 * - https://tamashaweb.com/
 * - https://tamashaweb.com/live
 * Uses 1dp software layer + CSS blocking + deduplicated JS bridge payloads + full
 * onRenderProcessGone() handling to keep RAM low and prevent Chromium renderer kills.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun HiddenTamashaCatalogHarvesterHost(
    task: HiddenCatalogHarvestTask?,
    modifier: Modifier = Modifier
) {
    if (task == null) return

    val context = LocalContext.current
    val currentRouteRef = remember(task.taskId) { mutableStateOf("movie") }
    val isDead = remember(task.taskId) { AtomicBoolean(false) }
    val completedOnce = remember(task.taskId) { AtomicBoolean(false) }
    var rendererCrashed by remember(task.taskId) { mutableStateOf(false) }

    fun finishSafely() {
        if (completedOnce.compareAndSet(false, true)) {
            task.onCompleted()
        }
    }

    if (rendererCrashed) return

    val webView = remember(task.taskId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Software layer + 1dp footprint avoids allocating heavy GPU textures on background routes
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            }
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> false }

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = TamashaPluginEngine.TAMASHA_USER_AGENT
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onApiJson(jsonText: String?) {
                        if (isDead.get()) return
                        if (!jsonText.isNullOrBlank() && jsonText.length in 41..1_500_000) {
                            task.onApiJson(currentRouteRef.value, jsonText)
                        }
                    }

                    @JavascriptInterface
                    fun onDomCards(cardsJson: String?) {
                        if (isDead.get()) return
                        if (!cardsJson.isNullOrBlank() && cardsJson.startsWith("[")) {
                            task.onDomCards(currentRouteRef.value, cardsJson)
                        }
                    }
                },
                "TamashaCatalogBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    isDead.set(true)
                    rendererCrashed = true
                    safeDestroyWebView(view, "TamashaCatalogBridge")
                    finishSafely()
                    // Return true so Android never crashes the app when Chromium renderer exits
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (isDead.get()) return empty204Response()
                    val lower = request?.url?.toString().orEmpty().lowercase(Locale.ROOT)
                    if (shouldBlockBandwidthHeavyResource(lower, allowMp4Sniff = false, blockCss = true) ||
                        lower.contains(".m3u8")
                    ) {
                        return empty204Response()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!isDead.get()) {
                        runCatching { view?.evaluateJavascript(CATALOG_API_HOOK_JS, null) }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isDead.get()) {
                        runCatching {
                            view?.evaluateJavascript(CATALOG_API_HOOK_JS, null)
                            view?.evaluateJavascript(CATALOG_DOM_SCRAPE_JS, null)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(task.taskId) {
        try {
            for ((routeHint, url) in task.routes) {
                if (isDead.get()) break
                currentRouteRef.value = routeHint
                runCatching {
                    webView.loadUrl(url, mapOf("Referer" to TamashaPluginEngine.TAMASHA_REFERER))
                }
                delay(2_000L)
                if (isDead.get()) break
                runCatching { webView.evaluateJavascript(CATALOG_DOM_SCRAPE_JS, null) }

                // Memory-friendly 2-step scroll to hydrate lazy carousels without blowing V8 heap
                val scrollSteps = if (routeHint == "movie" || routeHint == "tvshows") {
                    listOf(1200, 2800)
                } else {
                    listOf(1200)
                }
                for (y in scrollSteps) {
                    if (isDead.get()) break
                    runCatching { webView.evaluateJavascript("window.scrollTo(0, $y);", null) }
                    delay(750L)
                    if (isDead.get()) break
                    runCatching { webView.evaluateJavascript(CATALOG_DOM_SCRAPE_JS, null) }
                }
                if (!isDead.get()) {
                    task.onRouteFinished(routeHint)
                }
            }
        } finally {
            finishSafely()
        }
    }

    DisposableEffect(task.taskId) {
        onDispose {
            if (isDead.compareAndSet(false, true)) {
                safeDestroyWebView(webView, "TamashaCatalogBridge")
            }
            finishSafely()
        }
    }

    Box(
        modifier = modifier
            .size(1.dp)
            .alpha(0f)
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.size(1.dp)
        )
    }
}

private fun safeDestroyWebView(webView: WebView?, jsInterfaceName: String) {
    if (webView == null) return
    runCatching {
        webView.stopLoading()
        webView.onPause()
        webView.webViewClient = WebViewClient()
        webView.removeJavascriptInterface(jsInterfaceName)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun empty204Response(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    204,
    "No Content",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

private fun isPlayableStreamUrl(url: String, isVodPage: Boolean): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    if (lower.contains(".m3u8")) return true
    if (isVodPage) {
        val pathOnly = lower.substringBefore('?')
        if (pathOnly.endsWith(".mp4") &&
            !lower.contains("doubleclick") &&
            !lower.contains("googlesyndication") &&
            !lower.contains("imasdk") &&
            !lower.contains("adserver") &&
            !lower.contains("preview") &&
            !lower.contains("promo") &&
            !lower.contains("trailer")
        ) {
            return true
        }
    }
    return false
}

private fun isMasterPlaylistUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    return lower.contains("playlist.m3u8") ||
        lower.contains("master.m3u8") ||
        lower.contains("index.m3u8")
}

private fun shouldBlockBandwidthHeavyResource(
    lowerUrl: String,
    allowMp4Sniff: Boolean,
    blockCss: Boolean
): Boolean {
    val pathOnly = lowerUrl.substringBefore('?')
    return pathOnly.endsWith(".ts") ||
        pathOnly.endsWith(".m4s") ||
        (!allowMp4Sniff && pathOnly.endsWith(".mp4")) ||
        pathOnly.endsWith(".aac") ||
        pathOnly.endsWith(".png") ||
        pathOnly.endsWith(".jpg") ||
        pathOnly.endsWith(".jpeg") ||
        pathOnly.endsWith(".webp") ||
        pathOnly.endsWith(".gif") ||
        pathOnly.endsWith(".svg") ||
        (blockCss && pathOnly.endsWith(".css")) ||
        pathOnly.endsWith(".woff") ||
        pathOnly.endsWith(".woff2") ||
        pathOnly.endsWith(".ttf") ||
        lowerUrl.contains("doubleclick.net") ||
        lowerUrl.contains("googlesyndication.com") ||
        lowerUrl.contains("googletagmanager.com") ||
        lowerUrl.contains("google-analytics.com") ||
        lowerUrl.contains("facebook.net") ||
        lowerUrl.contains("clarity.ms") ||
        lowerUrl.contains("hotjar.com")
}

private val EARLY_SNIFFER_JS = """
    (function() {
        try {
            function unescapeAndReport(txt) {
                if (typeof txt !== 'string' || !txt || txt.length > 800000) return;
                var cleaned = txt.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
                var matches = cleaned.match(/https?:\/\/[^"'\s<>\\]+\.(?:m3u8|mp4)(?:[^"'\s<>\\]*)/gi);
                if (matches && matches.length) {
                    var limit = Math.min(matches.length, 16);
                    for (var i = 0; i < limit; i++) {
                        report(matches[i]);
                    }
                }
            }
            function report(u) {
                if (typeof u === 'string') {
                    var low = u.toLowerCase();
                    if (low.indexOf('.m3u8') !== -1 || low.indexOf('.mp4') !== -1) {
                        var normalized = u.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
                        if (window.TamashaM3u8Bridge) window.TamashaM3u8Bridge.onM3u8Found(normalized);
                    }
                }
            }
            if (!window.__zaidM3u8Hooked) {
                window.__zaidM3u8Hooked = true;

                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    try {
                        var u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        report(u);
                    } catch(e) {}
                    return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                            report(resp.url);
                            var ct = (resp.headers && resp.headers.get('content-type')) || '';
                            if (ct.indexOf('json') !== -1 || ct.indexOf('mpegurl') !== -1 || (resp.url && resp.url.indexOf('api') !== -1)) {
                                resp.clone().text().then(unescapeAndReport).catch(function(){});
                            }
                        } catch(e) {}
                        return resp;
                    });
                };

                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    report(url);
                    this.addEventListener('load', function() {
                        try {
                            if (this.responseType === '' || this.responseType === 'text') {
                                unescapeAndReport(this.responseText || '');
                            }
                        } catch(e) {}
                    });
                    return origOpen.apply(this, arguments);
                };

                var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (srcDesc && srcDesc.set) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        set: function(val) {
                            report(val);
                            try { this.muted = true; this.volume = 0; } catch(e) {}
                            return srcDesc.set.call(this, val);
                        },
                        get: function() {
                            return srcDesc.get ? srcDesc.get.call(this) : '';
                        },
                        configurable: true
                    });
                }

                var origSetAttr = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if (name === 'src' && typeof value === 'string') {
                        report(value);
                    }
                    return origSetAttr.apply(this, arguments);
                };
            }
        } catch(e) {}
    })();
""".trimIndent()

private val POLL_RESOURCES_JS = """
    (function() {
        try {
            function report(u) {
                if (typeof u === 'string') {
                    var low = u.toLowerCase();
                    if (low.indexOf('.m3u8') !== -1 || low.indexOf('.mp4') !== -1) {
                        var normalized = u.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
                        if (window.TamashaM3u8Bridge) window.TamashaM3u8Bridge.onM3u8Found(normalized);
                    }
                }
            }
            if (window.performance && performance.getEntriesByType) {
                var entries = performance.getEntriesByType('resource') || [];
                for (var i = 0; i < entries.length; i++) {
                    report(entries[i].name);
                }
            }
            if (!window.__zaidNextScanned) {
                window.__zaidNextScanned = true;
                var nextEl = document.getElementById('__NEXT_DATA__');
                if (nextEl && nextEl.textContent) {
                    var cleaned = nextEl.textContent.replace(/\\\//g, '/').replace(/\\u0026/g, '&');
                    var m = cleaned.match(/https?:\/\/[^"'\s<>\\]+\.(?:m3u8|mp4)(?:[^"'\s<>\\]*)/gi);
                    if (m && m.length) m.slice(0, 16).forEach(report);
                }
            }
            var vids = document.querySelectorAll('video, audio, source');
            vids.forEach(function(v) {
                if (v.src) report(v.src);
                if (v.currentSrc) report(v.currentSrc);
                try { v.muted = true; v.volume = 0; } catch(e) {}
                if (v.play && v.paused) {
                    try { v.play().catch(function(){}); } catch(e) {}
                }
            });
            var btns = document.querySelectorAll('button, [role="button"], .vjs-big-play-button, .jw-icon-playback');
            btns.forEach(function(b) {
                var t = ((b.innerText || '') + ' ' + (b.className || '') + ' ' + (b.getAttribute('aria-label') || '')).toLowerCase();
                if (!/\b(pro|subscribe|login|sign in|upgrade)\b/i.test(t)) {
                    if (t.indexOf('play') !== -1 || t.indexOf('watch') !== -1 || t.indexOf('start') !== -1) {
                        try { b.click(); } catch(e) {}
                    }
                }
            });
        } catch(e) {}
    })();
""".trimIndent()

private val VOD_CLICK_FIRST_FREE_EPISODE_JS = """
    (function() {
        try {
            if (window.__zaidClickedVodCount && window.__zaidClickedVodCount >= 2) return;
            window.__zaidClickedVodCount = (window.__zaidClickedVodCount || 0) + 1;

            var proRe = /\b(pro|premium|vip|paid|locked|subscribe|upgrade)\b/i;

            // Priority 1: Direct "Watch Now" / "Watch Free" / "Play" CTA button
            var ctas = document.querySelectorAll('button, a[href], [role="button"]');
            for (var i = 0; i < ctas.length; i++) {
                var el = ctas[i];
                var txt = (el.innerText || el.getAttribute('aria-label') || '').trim();
                var cls = (el.className || '').toString();
                if (proRe.test(txt) || proRe.test(cls)) continue;
                if (/^(watch now|watch free|play now|play|start watching|watch movie|watch episode)/i.test(txt)) {
                    el.click();
                    return;
                }
            }

            // Priority 2: First FREE Episode card or link on a Drama/TV Show series page
            var candidates = document.querySelectorAll('a[href], [class*="episode"], [class*="Episode"], [data-episode], [role="link"]');
            for (var j = 0; j < candidates.length; j++) {
                var ep = candidates[j];
                var href = (ep.getAttribute('href') || '').toLowerCase();
                var txt2 = (ep.innerText || '').trim();
                var cls2 = (ep.className || '').toString();
                if (proRe.test(txt2) || proRe.test(cls2)) continue;
                if (href.indexOf('episode') !== -1 || href.indexOf('watch') !== -1 ||
                    href.indexOf('ep-') !== -1 || href.indexOf('video') !== -1 ||
                    /\b(episode\s*1|ep\.?\s*1|ep\.?\s*01|full movie)\b/i.test(txt2)) {
                    ep.click();
                    return;
                }
            }
        } catch(e) {}
    })();
""".trimIndent()

private val CATALOG_API_HOOK_JS = """
    (function() {
        try {
            function forwardJson(txt) {
                if (typeof txt === 'string' && txt.length > 40 && txt.length < 1400000 && window.TamashaCatalogBridge) {
                    var trimmed = txt.trim();
                    if (trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[') {
                        window.TamashaCatalogBridge.onApiJson(trimmed);
                    }
                }
            }
            if (!window.__zaidCatalogHooked) {
                window.__zaidCatalogHooked = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                            var ct = (resp.headers && resp.headers.get('content-type')) || '';
                            if (ct.indexOf('json') !== -1 || (resp.url && resp.url.indexOf('api') !== -1)) {
                                resp.clone().text().then(forwardJson).catch(function(){});
                            }
                        } catch(e) {}
                        return resp;
                    });
                };
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function() {
                    this.addEventListener('load', function() {
                        try {
                            if (this.responseType === '' || this.responseType === 'text') {
                                forwardJson(this.responseText || '');
                            }
                        } catch(e) {}
                    });
                    return origOpen.apply(this, arguments);
                };
            }
        } catch(e) {}
    })();
""".trimIndent()

private val CATALOG_DOM_SCRAPE_JS = """
    (function() {
        try {
            if (!window.TamashaCatalogBridge) return;
            // Forward __NEXT_DATA__ only once per page to avoid repeated huge JNI payloads
            if (!window.__zaidNextDataSent) {
                var nextEl = document.getElementById('__NEXT_DATA__');
                if (nextEl && nextEl.textContent && nextEl.textContent.length < 1400000) {
                    window.__zaidNextDataSent = true;
                    window.TamashaCatalogBridge.onApiJson(nextEl.textContent);
                }
            }

            var proRe = /\b(pro|vip|paid|premium|locked|crown)\b/i;
            var cards = [];
            var seen = window.__zaidSeenCards || (window.__zaidSeenCards = {});

            function extractImgSrc(el) {
                var img = el.tagName === 'IMG' ? el : el.querySelector('img');
                if (!img) return '';
                var imgSrc = img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '';
                if ((!imgSrc || imgSrc.indexOf('data:') === 0) && img.getAttribute('srcset')) {
                    var parts = img.getAttribute('srcset').split(',');
                    if (parts.length) imgSrc = parts[parts.length - 1].trim().split(' ')[0];
                }
                if (imgSrc && imgSrc.indexOf('data:') !== 0) return imgSrc;
                return '';
            }

            function findReactData(el) {
                var result = { href: '', title: '', id: '', streamUrl: '', isPro: false };
                try {
                    var cur = el;
                    for (var depth = 0; depth < 3 && cur; depth++) {
                        var keys = Object.keys(cur);
                        for (var k = 0; k < keys.length; k++) {
                            var kn = keys[k];
                            if (kn.indexOf('__reactProps') === 0) {
                                var props = cur[kn];
                                if (!props || typeof props !== 'object') continue;
                                var candidate = props.item || props.data || props.movie || props.show || props.drama || props.content || props.video || props;
                                if (candidate && typeof candidate === 'object') {
                                    if (!result.href) result.href = candidate.seo_url || candidate.slug || candidate.web_url || candidate.href || candidate.to || '';
                                    if (!result.title) result.title = candidate.title || candidate.name || candidate.show_name || candidate.movie_name || '';
                                    if (!result.id && candidate.id) result.id = String(candidate.id);
                                    if (!result.streamUrl) result.streamUrl = candidate.stream_url || candidate.hls_url || candidate.m3u8_url || candidate.vod_url || '';
                                    if (candidate.is_pro === true || candidate.isPro === true || candidate.is_paid === true || candidate.isPaid === true || candidate.is_premium === true) {
                                        result.isPro = true;
                                    }
                                }
                            }
                        }
                        cur = cur.parentElement;
                    }
                } catch(e) {}
                return result;
            }

            function findSectionHeading(el) {
                var parent = el.closest('section, [class*="carousel"], [class*="slider"], [class*="section"], [class*="swiper"]');
                if (parent) {
                    var h = parent.querySelector('h1, h2, h3, h4');
                    if (h && h.innerText) return h.innerText.trim().split('\n')[0];
                }
                return '';
            }

            function checkProBadge(el) {
                var badges = el.querySelectorAll('span, img');
                var maxCheck = Math.min(badges.length, 8);
                for (var b = 0; b < maxCheck; b++) {
                    var bt = ((badges[b].innerText || '') + ' ' + (badges[b].getAttribute('alt') || '')).trim();
                    if (bt.length <= 24 && proRe.test(bt)) return true;
                }
                return false;
            }

            function pushCard(el, rawHref) {
                if (cards.length >= 220) return;
                var imgSrc = extractImgSrc(el);
                if (!imgSrc) return;
                var reactMeta = findReactData(el);
                var href = rawHref || el.getAttribute('data-href') || el.getAttribute('data-slug') || reactMeta.href || '';
                var streamUrl = reactMeta.streamUrl || '';
                if (!href && !streamUrl) return;
                if (href === '/' || href.indexOf('javascript:') === 0 || href.indexOf('mailto:') === 0) return;

                var img = el.tagName === 'IMG' ? el : el.querySelector('img');
                var cardText = (el.innerText || '').trim();
                var title = (reactMeta.title || (img && img.getAttribute('alt')) || el.getAttribute('title') || cardText.split('\n')[0] || '').trim();
                var isPro = reactMeta.isPro || checkProBadge(el);
                var sectionHeading = findSectionHeading(el);

                var key = href + '|' + title;
                if (seen[key]) return;
                seen[key] = true;

                cards.push({
                    href: href,
                    id: reactMeta.id || '',
                    title: title,
                    img: imgSrc,
                    section: sectionHeading,
                    streamUrl: streamUrl,
                    isPro: isPro
                });
            }

            // 1. Standard <a> cards
            var anchors = document.querySelectorAll('a[href]');
            var maxAnchors = Math.min(anchors.length, 260);
            for (var i = 0; i < maxAnchors; i++) {
                pushCard(anchors[i], anchors[i].getAttribute('href') || '');
            }

            // 2. React clickable poster images (walk up to nearest clickable card parent — much faster & lighter than wildcard class selectors)
            var imgs = document.querySelectorAll('img');
            var maxImgs = Math.min(imgs.length, 220);
            for (var j = 0; j < maxImgs; j++) {
                var imgEl = imgs[j];
                if (imgEl.closest('a[href]')) continue;
                var holder = imgEl.closest('[role="button"], [data-slug], [data-href], article, li') || imgEl.parentElement;
                if (holder) pushCard(holder, '');
            }

            if (cards.length > 0) {
                window.TamashaCatalogBridge.onDomCards(JSON.stringify(cards));
            }
        } catch(e) {}
    })();
""".trimIndent()
