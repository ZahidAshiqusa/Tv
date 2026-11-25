package com.example.data

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.ChannelEntity
import com.example.data.local.PreferencesManager
import com.example.data.local.StreamCacheEntity
import com.example.data.local.UserPreferences
import com.example.data.local.VodItemEntity
import com.example.data.model.VideoQuality
import com.example.localization.AppLanguage
import com.example.player.CatalogSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

class ChannelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val channelDao = db.channelDao()
    private val vodDao = db.vodDao()
    private val streamCacheDao = db.streamCacheDao()
    private val preferencesManager = PreferencesManager(appContext)

    val channelsFlow: Flow<List<ChannelEntity>> = channelDao.observeAllChannels()
    val vodItemsFlow: Flow<List<VodItemEntity>> = vodDao.observeAllVodItems()
    val preferencesFlow: Flow<UserPreferences> = preferencesManager.preferencesFlow

    suspend fun ensureSeededAndPreCacheLogos() = withContext(Dispatchers.IO) {
        if (channelDao.getChannelCount() == 0) {
            channelDao.upsertAll(SeedChannels.all())
        }
        val allChannels = channelDao.getAllChannelsSnapshot()
        val prefs = preferencesManager.preferencesFlow.first()
        if (!prefs.logosPreCachedOnce) {
            LogoCacheManager.precacheAllLogos(appContext, allChannels)
            preferencesManager.markLogosPreCached()
        }
    }

    suspend fun getAllChannelsSnapshot(): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (channelDao.getChannelCount() == 0) {
            channelDao.upsertAll(SeedChannels.all())
        }
        channelDao.getAllChannelsSnapshot()
    }

    suspend fun getAllVodSnapshot(): List<VodItemEntity> = withContext(Dispatchers.IO) {
        vodDao.getAllVodSnapshot()
    }

    // --- Fast SQLite Stream Cache Accessors ---
    suspend fun getAllValidCachedStreams(now: Long): List<StreamCacheEntity> = withContext(Dispatchers.IO) {
        streamCacheDao.pruneExpired(now)
        streamCacheDao.getAllValid(now)
    }

    suspend fun saveCachedStream(entry: StreamCacheEntity) = withContext(Dispatchers.IO) {
        streamCacheDao.upsert(entry)
    }

    suspend fun deleteCachedStreamByTarget(targetId: String) = withContext(Dispatchers.IO) {
        streamCacheDao.deleteByTargetId(targetId)
    }

    suspend fun deleteCachedStreamByUrl(url: String) = withContext(Dispatchers.IO) {
        streamCacheDao.deleteByUrl(url)
    }

    /**
     * Merges freshly fetched free Tamasha channels, 100% real HD broadcast icons (Tamasha CDN +
     * iptv-org official database), and ONLY real live free VOD items from tamashaweb.com into Room
     * while preserving user favorites.
     */
    suspend fun mergeCatalogSync(
        sync: CatalogSyncResult,
        replaceVodList: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val existingChannels = channelDao.getAllChannelsSnapshot()
        val existingNormNames = existingChannels.associateBy { M3uParser.normalizeChannelKey(it.name) }

        // 1. Upgrade existing channels with authentic original broadcast logos
        existingChannels.forEach { ch ->
            val hdLogo = findBestOriginalLogo(ch, sync.hdLogosByNormalizedName)
            if (!hdLogo.isNullOrBlank() && hdLogo != ch.logoUrl) {
                val keepExistingTamasha = ch.logoUrl.contains("tamashaweb.com", ignoreCase = true) &&
                    !hdLogo.contains("tamashaweb.com", ignoreCase = true)
                if (!keepExistingTamasha) {
                    channelDao.updateLogoUrl(ch.id, hdLogo)
                }
            }
        }

        // 2. Insert all newly discovered FREE live channels from Tamasha
        val newFreeChannels = sync.discoveredFreeChannels.filter { discovered ->
            val norm = M3uParser.normalizeChannelKey(discovered.name)
            !existingNormNames.containsKey(norm) &&
                existingChannels.none { it.tamashaSlug.equals(discovered.tamashaSlug, ignoreCase = true) }
        }
        if (newFreeChannels.isNotEmpty()) {
            channelDao.insertIfAbsent(newFreeChannels)
        }

        // 3. Merge/Replace VOD catalog with ONLY real live FREE Dramas, TV Shows, Movies, Wrestling & Highlights
        //    actually present on tamashaweb.com (preserving user favorites by ID or slug)
        if (sync.discoveredFreeVodItems.isNotEmpty()) {
            val prevVod = vodDao.getAllVodSnapshot()
            val favIds = prevVod.filter { it.isFavorite }.map { it.id }.toSet()
            val favSlugs = prevVod.filter { it.isFavorite && it.pageSlug.isNotBlank() }
                .map { it.pageSlug.lowercase(Locale.ROOT) }.toSet()

            val mergedVod = sync.discoveredFreeVodItems.map { item ->
                val isFav = item.id in favIds ||
                    (item.pageSlug.isNotBlank() && item.pageSlug.lowercase(Locale.ROOT) in favSlugs)
                if (isFav) item.copy(isFavorite = true) else item
            }
            if (replaceVodList) {
                vodDao.clearAll()
            }
            vodDao.upsertAll(mergedVod)
        }

        // Silently pre-cache any newly upgraded original channel logos into Coil disk cache
        val refreshedChannels = channelDao.getAllChannelsSnapshot()
        LogoCacheManager.precacheAllLogos(appContext, refreshedChannels)
    }

    private fun findBestOriginalLogo(
        channel: ChannelEntity,
        logosByNorm: Map<String, String>
    ): String? {
        if (logosByNorm.isEmpty()) return null
        val norm = M3uParser.normalizeChannelKey(channel.name)
        val keysToTry = buildList {
            add(norm)
            addAll(logoAliasesFor(channel.id))
            add(M3uParser.normalizeChannelKey(channel.tamashaSlug.removeSuffix("-live")))
        }.distinct().filter { it.length >= 3 }

        for (key in keysToTry) {
            logosByNorm[key]?.let { return it }
        }
        for (key in keysToTry) {
            if (key.length < 4) continue
            logosByNorm.entries.firstOrNull { (k, _) ->
                k.length >= 4 && (k.contains(key) || key.contains(k))
            }?.value?.let { return it }
        }
        return null
    }

    private fun logoAliasesFor(channelId: String): List<String> = when (channelId) {
        "green_tv" -> listOf("greenentertainment", "greentv")
        "geo_entertainment" -> listOf("geoentertainment", "harpalgeo", "geotv")
        "geo_news" -> listOf("geonews", "geonewspk")
        "ary_digital" -> listOf("arydigital", "arydigitalasia")
        "ary_news" -> listOf("arynews", "arynewspk")
        "dawn_news" -> listOf("dawnnews", "dawnnewstv")
        "ptv_sports" -> listOf("ptvsports", "ptvsportshd")
        "ten_sports" -> listOf("tensports", "tensportspk", "tensportshd")
        "geo_super" -> listOf("geosuper", "geosuperpk")
        "hum_tv" -> listOf("humtv", "humtvpk")
        "samaa_news" -> listOf("samaatv", "samaanews", "samaa")
        "express_news" -> listOf("expressnews", "expressnewspk")
        "dunya_news" -> listOf("dunyanews", "dunyanewspk")
        "hum_news" -> listOf("humnews", "humnewspk")
        "bol_news" -> listOf("bolnews", "bolnewshd")
        "news_92" -> listOf("92news", "92newshd", "92newshdplus")
        "sun_news" -> listOf("sunonews", "sunonewshd", "sunnews")
        "aaj_news" -> listOf("aajnews", "aajtv")
        "ptv_news" -> listOf("ptvnews")
        "ptv_home" -> listOf("ptvhome")
        "ary_zindagi" -> listOf("aryzindagi")
        "express_entertainment" -> listOf("expressentertainment")
        "hum_sitary" -> listOf("humsitaray", "humsitary")
        "hum_masala" -> listOf("hummasala", "masalatv")
        "aplus" -> listOf("aplus", "aplustv", "aplusentertainment")
        "paigham_tv" -> listOf("paighamtv", "paigham")
        "ary_qtv" -> listOf("aryqtv", "qtv")
        "madani_channel" -> listOf("madanichannel", "madanichannelurdu")
        "al_jazeera" -> listOf("aljazeeraenglish", "aljazeera")
        "cnn_news" -> listOf("cnninternational", "cnn", "cnnnews")
        "cgtn_documentary" -> listOf("cgtndocumentary", "cgtndoc")
        "discovery_hd" -> listOf("discoverychannel", "discoveryhd", "discovery")
        else -> emptyList()
    }

    suspend fun precacheAllLogosWithProgress(
        channels: List<ChannelEntity>,
        vodItems: List<VodItemEntity>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) {
        LogoCacheManager.precacheAllMediaWithProgress(appContext, channels, vodItems, onProgress)
    }

    suspend fun markInitialChannelsUpdated() {
        preferencesManager.markInitialChannelsUpdated()
    }

    suspend fun toggleFavorite(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        channelDao.setFavorite(channel.id, !channel.isFavorite)
    }

    suspend fun toggleVodFavorite(item: VodItemEntity) = withContext(Dispatchers.IO) {
        vodDao.setFavorite(item.id, !item.isFavorite)
    }

    suspend fun saveResolvedStream(channelId: String, freshStreamUrl: String) = withContext(Dispatchers.IO) {
        channelDao.updateResolvedStream(channelId, freshStreamUrl, System.currentTimeMillis())
    }

    suspend fun saveResolvedVodStream(vodId: String, freshStreamUrl: String) = withContext(Dispatchers.IO) {
        vodDao.updateResolvedStream(vodId, freshStreamUrl, System.currentTimeMillis())
    }

    suspend fun setLanguage(language: AppLanguage) = preferencesManager.setLanguage(language)

    suspend fun setPreferredQuality(quality: VideoQuality) = preferencesManager.setPreferredQuality(quality)
}
