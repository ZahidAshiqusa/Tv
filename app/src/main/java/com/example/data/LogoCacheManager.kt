package com.example.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.local.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permanent offline-first logo cache powered by Coil.
 * Ignores short CDN Cache-Control headers so channel icons download once and load
 * instantaneously (0ms network delay) even on weak internet or offline.
 */
object LogoCacheManager {

    @Volatile
    private var imageLoader: ImageLoader? = null
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getImageLoader(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: buildImageLoader(context.applicationContext).also {
                imageLoader = it
            }
        }
    }

    private fun buildImageLoader(context: Context): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host.lowercase()
                val builder = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                if (host.contains("tamashaweb.com")) {
                    builder.header("Referer", "https://tamashaweb.com/")
                    builder.header("Origin", "https://tamashaweb.com")
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .respectCacheHeaders(false) // Keep logos cached permanently on disk
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.filesDir, "zaid_channel_logo_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L) // 150 MB permanent storage in filesDir
                    .build()
            }
            .crossfade(160)
            .build()
    }

    /**
     * Silently pre-downloads and permanently caches all channel logos into filesDir.
     */
    fun precacheAllLogos(context: Context, channels: List<ChannelEntity>, onComplete: () -> Unit = {}) {
        val appContext = context.applicationContext
        val loader = getImageLoader(appContext)
        cacheScope.launch {
            channels.forEach { channel ->
                if (channel.logoUrl.isNotBlank()) {
                    val request = ImageRequest.Builder(appContext)
                        .data(channel.logoUrl)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build()
                    loader.enqueue(request)
                }
                if (channel.fallbackLogoUrl.isNotBlank()) {
                    val fallbackRequest = ImageRequest.Builder(appContext)
                        .data(channel.fallbackLogoUrl)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build()
                    loader.enqueue(fallbackRequest)
                }
            }
            onComplete()
        }
    }

    /**
     * Actively downloads and permanently stores every channel's icon (primary + fallback)
     * in filesDir disk cache with live progress callbacks for the first-time/manual update flow.
     */
    suspend fun precacheAllLogosWithProgress(
        context: Context,
        channels: List<ChannelEntity>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val loader = getImageLoader(appContext)
        val total = channels.size.coerceAtLeast(1)
        val completed = AtomicInteger(0)
        onProgress(0, total)

        channels.chunked(6).forEach { batch ->
            coroutineScope {
                batch.map { channel ->
                    async {
                        runCatching {
                            if (channel.logoUrl.isNotBlank()) {
                                loader.execute(
                                    ImageRequest.Builder(appContext)
                                        .data(channel.logoUrl)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                )
                            }
                            if (channel.fallbackLogoUrl.isNotBlank()) {
                                loader.execute(
                                    ImageRequest.Builder(appContext)
                                        .data(channel.fallbackLogoUrl)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                )
                            }
                        }
                        onProgress(completed.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun precacheAllMediaWithProgress(
        context: Context,
        channels: List<ChannelEntity>,
        vodItems: List<com.example.data.local.VodItemEntity>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val loader = getImageLoader(appContext)
        val urlPairs = channels.map { it.logoUrl to it.fallbackLogoUrl } +
            vodItems.map { it.posterUrl to it.fallbackPosterUrl }
        val total = urlPairs.size.coerceAtLeast(1)
        val completed = AtomicInteger(0)
        onProgress(0, total)

        urlPairs.chunked(6).forEach { batch ->
            coroutineScope {
                batch.map { (primary, fallback) ->
                    async {
                        runCatching {
                            if (primary.isNotBlank()) {
                                loader.execute(
                                    ImageRequest.Builder(appContext)
                                        .data(primary)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                )
                            }
                            if (fallback.isNotBlank()) {
                                loader.execute(
                                    ImageRequest.Builder(appContext)
                                        .data(fallback)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                )
                            }
                        }
                        onProgress(completed.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
        }
    }
}
