package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChannelRepository
import com.example.data.M3uParser
import com.example.data.local.ChannelEntity
import com.example.data.local.UserPreferences
import com.example.data.local.VodItemEntity
import com.example.data.model.ChannelCategory
import com.example.data.model.VideoQuality
import com.example.data.model.VodSection
import com.example.localization.AppLanguage
import com.example.localization.stringsFor
import com.example.player.HiddenCatalogHarvestTask
import com.example.player.HiddenFetcherTask
import com.example.player.LivePlayerController
import com.example.player.PlaybackStatus
import com.example.player.PlayerUiState
import com.example.player.StreamSourceKind
import com.example.player.TamashaCatalogFetcher
import com.example.player.TamashaPluginEngine
import com.example.player.TamashaResolveResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppDestination {
    CHANNELS,
    SHOWS,
    MOVIES,
    WRESTLING,
    SETTINGS
}

enum class ShowsSubFilter {
    ALL,
    DRAMAS,
    TV_SHOWS
}

enum class WrestlingSubFilter {
    ALL,
    WRESTLING,
    HIGHLIGHTS
}

enum class UpdateStage {
    IDLE,
    CATALOG,
    ICONS,
    LINKS
}

data class ChannelUpdateProgress(
    val isUpdating: Boolean = false,
    val stage: UpdateStage = UpdateStage.IDLE,
    val completed: Int = 0,
    val total: Int = 0
)

sealed interface ActivePlaybackItem {
    val id: String
    fun displayTitle(isUrdu: Boolean): String

    data class LiveChannel(val channel: ChannelEntity) : ActivePlaybackItem {
        override val id: String get() = channel.id
        override fun displayTitle(isUrdu: Boolean): String = channel.displayName(isUrdu)
    }

    data class VodShow(val item: VodItemEntity) : ActivePlaybackItem {
        override val id: String get() = item.id
        override fun displayTitle(isUrdu: Boolean): String = item.displayTitle(isUrdu)
    }
}

data class MainUiState(
    val destination: AppDestination = AppDestination.CHANNELS,
    val allChannels: List<ChannelEntity> = emptyList(),
    val filteredChannels: List<ChannelEntity> = emptyList(),
    val allVodItems: List<VodItemEntity> = emptyList(),
    val filteredShows: List<VodItemEntity> = emptyList(),
    val filteredMovies: List<VodItemEntity> = emptyList(),
    val filteredWrestling: List<VodItemEntity> = emptyList(),
    val selectedCategory: ChannelCategory = ChannelCategory.ALL,
    val showsSubFilter: ShowsSubFilter = ShowsSubFilter.ALL,
    val wrestlingSubFilter: WrestlingSubFilter = WrestlingSubFilter.ALL,
    val searchQuery: String = "",
    val showFavoritesOnly: Boolean = false,
    val activePlayback: ActivePlaybackItem? = null,
    val preferences: UserPreferences = UserPreferences(),
    val isPreferencesLoaded: Boolean = false,
    val playerState: PlayerUiState = PlayerUiState(),
    val updateProgress: ChannelUpdateProgress = ChannelUpdateProgress(),
    val isCatalogSyncing: Boolean = false,
    val snackbarMessage: String? = null,
    val showDeveloperSheet: Boolean = false,
    val isFullscreen: Boolean = false,
    val isInPipMode: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChannelRepository(application)
    private val tamashaPlugin = TamashaPluginEngine(application)
    private val catalogFetcher = TamashaCatalogFetcher()

    val hiddenFetcherTask: StateFlow<HiddenFetcherTask?> = tamashaPlugin.activeFetcherTask
    val hiddenCatalogTask: StateFlow<HiddenCatalogHarvestTask?> = catalogFetcher.activeHarvestTask

    val playerController = LivePlayerController(
        context = application,
        onStreamNeedsM3u8Refresh = { failedUrl, httpStatusCode ->
            handleAutomaticRefreshOnError(failedUrl, httpStatusCode)
        }
    )

    private data class NavFilterState(
        val destination: AppDestination = AppDestination.CHANNELS,
        val category: ChannelCategory = ChannelCategory.ALL,
        val showsSubFilter: ShowsSubFilter = ShowsSubFilter.ALL,
        val wrestlingSubFilter: WrestlingSubFilter = WrestlingSubFilter.ALL,
        val query: String = "",
        val favoritesOnly: Boolean = false,
        val activeChannelId: String? = null,
        val activeVodId: String? = null
    )

    private data class ModalState(
        val snackbarMessage: String? = null,
        val showDeveloperSheet: Boolean = false,
        val isFullscreen: Boolean = false,
        val isInPipMode: Boolean = false,
        val isCatalogSyncing: Boolean = false,
        val updateProgress: ChannelUpdateProgress = ChannelUpdateProgress()
    )

    private val navFilterState = MutableStateFlow(NavFilterState())
    private val modalState = MutableStateFlow(ModalState())

    private var resolveJob: Job? = null
    private var updateJob: Job? = null
    private var catalogSyncJob: Job? = null
    private var errorRecoveryStage = 0

    private val catalogFlow = combine(
        repository.channelsFlow,
        repository.vodItemsFlow
    ) { channels, vod -> channels to vod }

    val uiState: StateFlow<MainUiState> = combine(
        catalogFlow,
        repository.preferencesFlow,
        navFilterState,
        playerController.state,
        modalState
    ) { (channels, vodItems), prefs, nav, player, modals ->
        val queryTrimmed = nav.query.trim()

        val filteredChannels = channels.filter { ch ->
            val categoryMatches = nav.category == ChannelCategory.ALL || ch.category == nav.category
            val favoriteMatches = !nav.favoritesOnly || ch.isFavorite
            val queryMatches = queryTrimmed.isEmpty() ||
                ch.name.contains(queryTrimmed, ignoreCase = true) ||
                ch.urduName.contains(queryTrimmed, ignoreCase = true) ||
                ch.categoryKey.contains(queryTrimmed, ignoreCase = true)
            categoryMatches && favoriteMatches && queryMatches
        }

        val filteredShows = vodItems.filter { item ->
            val inShowsTab = when (nav.showsSubFilter) {
                ShowsSubFilter.ALL -> item.section == VodSection.DRAMA || item.section == VodSection.TV_SHOW
                ShowsSubFilter.DRAMAS -> item.section == VodSection.DRAMA
                ShowsSubFilter.TV_SHOWS -> item.section == VodSection.TV_SHOW
            }
            val favMatch = !nav.favoritesOnly || item.isFavorite
            val qMatch = queryTrimmed.isEmpty() ||
                item.title.contains(queryTrimmed, ignoreCase = true) ||
                item.urduTitle.contains(queryTrimmed, ignoreCase = true) ||
                item.subtitleMeta.contains(queryTrimmed, ignoreCase = true)
            inShowsTab && favMatch && qMatch
        }

        val filteredMovies = vodItems.filter { item ->
            item.section == VodSection.MOVIE &&
                (!nav.favoritesOnly || item.isFavorite) &&
                (queryTrimmed.isEmpty() ||
                    item.title.contains(queryTrimmed, ignoreCase = true) ||
                    item.urduTitle.contains(queryTrimmed, ignoreCase = true) ||
                    item.subtitleMeta.contains(queryTrimmed, ignoreCase = true))
        }

        val filteredWrestling = vodItems.filter { item ->
            val inWrestlingTab = when (nav.wrestlingSubFilter) {
                WrestlingSubFilter.ALL -> item.section == VodSection.WRESTLING || item.section == VodSection.HIGHLIGHTS
                WrestlingSubFilter.WRESTLING -> item.section == VodSection.WRESTLING
                WrestlingSubFilter.HIGHLIGHTS -> item.section == VodSection.HIGHLIGHTS
            }
            val favMatch = !nav.favoritesOnly || item.isFavorite
            val qMatch = queryTrimmed.isEmpty() ||
                item.title.contains(queryTrimmed, ignoreCase = true) ||
                item.urduTitle.contains(queryTrimmed, ignoreCase = true) ||
                item.subtitleMeta.contains(queryTrimmed, ignoreCase = true)
            inWrestlingTab && favMatch && qMatch
        }

        val activePlayback: ActivePlaybackItem? = when {
            nav.activeChannelId != null -> channels.firstOrNull { it.id == nav.activeChannelId }
                ?.let { ActivePlaybackItem.LiveChannel(it) }
            nav.activeVodId != null -> vodItems.firstOrNull { it.id == nav.activeVodId }
                ?.let { ActivePlaybackItem.VodShow(it) }
            else -> null
        }

        MainUiState(
            destination = nav.destination,
            allChannels = channels,
            filteredChannels = filteredChannels,
            allVodItems = vodItems,
            filteredShows = filteredShows,
            filteredMovies = filteredMovies,
            filteredWrestling = filteredWrestling,
            selectedCategory = nav.category,
            showsSubFilter = nav.showsSubFilter,
            wrestlingSubFilter = nav.wrestlingSubFilter,
            searchQuery = nav.query,
            showFavoritesOnly = nav.favoritesOnly,
            activePlayback = activePlayback,
            preferences = prefs,
            isPreferencesLoaded = true,
            playerState = player,
            updateProgress = modals.updateProgress,
            isCatalogSyncing = modals.isCatalogSyncing,
            snackbarMessage = modals.snackbarMessage,
            showDeveloperSheet = modals.showDeveloperSheet,
            isFullscreen = modals.isFullscreen,
            isInPipMode = modals.isInPipMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(isPreferencesLoaded = false)
    )

    init {
        viewModelScope.launch {
            repository.ensureSeededAndPreCacheLogos()
            val prefs = repository.preferencesFlow.first()
            if (prefs.initialChannelsUpdated) {
                val seededChannels = repository.getAllChannelsSnapshot()
                // Warm top popular channels into SQLite stream_cache for instant tap playback
                tamashaPlugin.prewarmInBackground(seededChannels)
                // Quietly refresh live catalog & HD icons on returning launches
                delay(400L)
                syncCatalogFromTamasha()
            }
        }
        startProactiveTokenRenewalLoop()
    }

    private fun syncCatalogFromTamasha() {
        if (catalogSyncJob?.isActive == true) return
        catalogSyncJob = viewModelScope.launch {
            modalState.update { it.copy(isCatalogSyncing = true) }
            try {
                runCatching {
                    val sync = catalogFetcher.fetchAllFreeCatalog(
                        useHeadlessBrowser = true,
                        onPartialSync = { partial ->
                            repository.mergeCatalogSync(partial, replaceVodList = false)
                        }
                    )
                    repository.mergeCatalogSync(sync, replaceVodList = true)
                }
            } finally {
                modalState.update { it.copy(isCatalogSyncing = false) }
            }
        }
    }

    fun refreshCatalogNow() {
        syncCatalogFromTamasha()
    }

    private fun startProactiveTokenRenewalLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(45_000L)
                val now = System.currentTimeMillis()
                val state = uiState.value
                val active = state.activePlayback
                if (active is ActivePlaybackItem.LiveChannel && state.playerState.status is PlaybackStatus.Playing) {
                    val ch = active.channel
                    val expiry = M3uParser.parseWmsAuthSignExpiry(ch.streamUrl, now)
                    if (expiry.isSignedToken && (expiry.isExpired || expiry.minutesRemaining <= 8)) {
                        tamashaPlugin.invalidateChannelCache(ch.id)
                        when (val result = tamashaPlugin.fetchLiveM3u8(ch, forceRefresh = true)) {
                            is TamashaResolveResult.FreshHlsStream -> {
                                repository.saveResolvedStream(ch.id, result.streamUrl)
                                if (uiState.value.activePlayback?.id == ch.id) {
                                    playerController.playStream(
                                        url = result.streamUrl,
                                        headers = result.headers,
                                        sourceKind = result.sourceKind,
                                        quality = uiState.value.preferences.preferredQuality
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    /**
     * Full First-Time / On-Demand Update:
     * 1. Fetches ALL real free live channels, authentic original HD icons, free Dramas, TV Shows,
     *    Movies, Wrestling & Highlights from tamashaweb.com + iptv-org official database
     * 2. Permanently disk-caches all original channel icons & posters
     * 3. Parallel-refreshes live stream links directly into SQLite stream_cache
     */
    fun updateAllChannelsAndIcons() {
        if (modalState.value.updateProgress.isUpdating) return

        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            // Phase 1: Fetch all free channels, real HD logos, Dramas, TV Shows, Movies, Wrestling & Highlights
            modalState.update {
                it.copy(
                    isCatalogSyncing = true,
                    updateProgress = ChannelUpdateProgress(
                        isUpdating = true,
                        stage = UpdateStage.CATALOG,
                        completed = 0,
                        total = 1
                    )
                )
            }
            runCatching {
                val sync = catalogFetcher.fetchAllFreeCatalog(
                    useHeadlessBrowser = true,
                    onPartialSync = { partial ->
                        repository.mergeCatalogSync(partial, replaceVodList = false)
                    }
                )
                repository.mergeCatalogSync(sync, replaceVodList = true)
            }
            modalState.update { it.copy(isCatalogSyncing = false) }

            val channels = repository.getAllChannelsSnapshot()
            val vodItems = repository.getAllVodSnapshot()

            // Phase 2: Permanently cache all original HD icons & thumbnails
            val mediaTotal = (channels.size + vodItems.size).coerceAtLeast(1)
            modalState.update {
                it.copy(
                    updateProgress = ChannelUpdateProgress(
                        isUpdating = true,
                        stage = UpdateStage.ICONS,
                        completed = 0,
                        total = mediaTotal
                    )
                )
            }
            repository.precacheAllLogosWithProgress(channels, vodItems) { done, count ->
                modalState.update {
                    it.copy(
                        updateProgress = ChannelUpdateProgress(
                            isUpdating = true,
                            stage = UpdateStage.ICONS,
                            completed = done,
                            total = count
                        )
                    )
                }
            }

            // Phase 3: Parallel-refresh live stream links directly into SQLite stream_cache
            val channelTotal = channels.size.coerceAtLeast(1)
            modalState.update {
                it.copy(
                    updateProgress = ChannelUpdateProgress(
                        isUpdating = true,
                        stage = UpdateStage.LINKS,
                        completed = 0,
                        total = channelTotal
                    )
                )
            }
            tamashaPlugin.bulkRefreshChannelLinks(
                channels = channels,
                onChannelResolved = { channelId, freshUrl ->
                    repository.saveResolvedStream(channelId, freshUrl)
                },
                onProgress = { done, count ->
                    modalState.update {
                        it.copy(
                            updateProgress = ChannelUpdateProgress(
                                isUpdating = true,
                                stage = UpdateStage.LINKS,
                                completed = done,
                                total = count
                            )
                        )
                    }
                }
            )

            repository.markInitialChannelsUpdated()
            val strings = stringsFor(uiState.value.preferences.language)
            modalState.update {
                it.copy(
                    updateProgress = ChannelUpdateProgress(isUpdating = false, stage = UpdateStage.IDLE),
                    snackbarMessage = strings.updateCompleteMessage
                )
            }
        }
    }

    fun selectDestination(destination: AppDestination) {
        navFilterState.update {
            it.copy(
                destination = destination,
                query = "",
                favoritesOnly = false
            )
        }
        // If user opens a VOD tab and the real live catalog hasn't finished yet, kick off sync
        if (destination in setOf(AppDestination.SHOWS, AppDestination.MOVIES, AppDestination.WRESTLING) &&
            uiState.value.allVodItems.isEmpty() && !modalState.value.isCatalogSyncing
        ) {
            syncCatalogFromTamasha()
        }
    }

    fun setShowsSubFilter(filter: ShowsSubFilter) {
        navFilterState.update { it.copy(showsSubFilter = filter, favoritesOnly = false) }
    }

    fun setWrestlingSubFilter(filter: WrestlingSubFilter) {
        navFilterState.update { it.copy(wrestlingSubFilter = filter, favoritesOnly = false) }
    }

    fun selectChannel(channel: ChannelEntity) {
        errorRecoveryStage = 0
        navFilterState.update { it.copy(activeChannelId = channel.id, activeVodId = null) }
        fetchAndPlayChannel(channel = channel, forceRefresh = false, preferPublicFallback = false)

        // Quietly warm adjacent channels into SQLite stream_cache for instant channel zapping
        val list = uiState.value.filteredChannels
        val idx = list.indexOfFirst { it.id == channel.id }
        if (idx >= 0) {
            val neighbors = listOfNotNull(list.getOrNull(idx + 1), list.getOrNull(idx + 2))
            if (neighbors.isNotEmpty()) {
                tamashaPlugin.prewarmInBackground(neighbors)
            }
        }
    }

    fun selectVodItem(item: VodItemEntity) {
        errorRecoveryStage = 0
        navFilterState.update { it.copy(activeVodId = item.id, activeChannelId = null) }
        fetchAndPlayVod(item = item, forceRefresh = false)
    }

    fun refreshActiveStreamNow() {
        val active = uiState.value.activePlayback ?: return
        errorRecoveryStage = 0
        tamashaPlugin.invalidateChannelCache(active.id)
        when (active) {
            is ActivePlaybackItem.LiveChannel -> {
                fetchAndPlayChannel(channel = active.channel, forceRefresh = true, preferPublicFallback = false)
            }
            is ActivePlaybackItem.VodShow -> {
                fetchAndPlayVod(item = active.item, forceRefresh = true)
            }
        }
    }

    private fun fetchAndPlayChannel(
        channel: ChannelEntity,
        forceRefresh: Boolean,
        preferPublicFallback: Boolean
    ) {
        resolveJob?.cancel()
        playerController.setResolvingState()

        resolveJob = viewModelScope.launch {
            val result = tamashaPlugin.fetchLiveM3u8(
                channel = channel,
                forceRefresh = forceRefresh,
                preferPublicFallback = preferPublicFallback
            )
            if (navFilterState.value.activeChannelId != channel.id) return@launch

            when (result) {
                is TamashaResolveResult.FreshHlsStream -> {
                    repository.saveResolvedStream(channel.id, result.streamUrl)
                    playerController.playStream(
                        url = result.streamUrl,
                        headers = result.headers,
                        sourceKind = result.sourceKind,
                        quality = uiState.value.preferences.preferredQuality
                    )
                }
                is TamashaResolveResult.Failure -> {
                    playerController.setErrorState(result.reason)
                }
            }
        }
    }

    private fun fetchAndPlayVod(
        item: VodItemEntity,
        forceRefresh: Boolean
    ) {
        resolveJob?.cancel()
        playerController.setResolvingState()

        resolveJob = viewModelScope.launch {
            val result = tamashaPlugin.fetchVodLiveStream(
                item = item,
                forceRefresh = forceRefresh
            )
            if (navFilterState.value.activeVodId != item.id) return@launch

            when (result) {
                is TamashaResolveResult.FreshHlsStream -> {
                    repository.saveResolvedVodStream(item.id, result.streamUrl)
                    playerController.playStream(
                        url = result.streamUrl,
                        headers = result.headers,
                        sourceKind = result.sourceKind,
                        quality = uiState.value.preferences.preferredQuality,
                        isLive = false
                    )
                }
                is TamashaResolveResult.Failure -> {
                    playerController.setErrorState(result.reason)
                }
            }
        }
    }

    private fun handleAutomaticRefreshOnError(
        failedUrl: String?,
        httpStatusCode: Int?
    ) {
        val active = uiState.value.activePlayback ?: return
        if (!failedUrl.isNullOrBlank() &&
            (httpStatusCode == null || httpStatusCode in setOf(401, 403, 404, 410, 451))
        ) {
            tamashaPlugin.markStreamUrlFailed(failedUrl)
        }

        when (active) {
            is ActivePlaybackItem.LiveChannel -> {
                val activeSource = uiState.value.playerState.activeSourceKind
                when (errorRecoveryStage) {
                    0 -> {
                        errorRecoveryStage = 1
                        tamashaPlugin.invalidateChannelCache(active.id)
                        val usePublic = activeSource == StreamSourceKind.PUBLIC_HLS
                        fetchAndPlayChannel(active.channel, forceRefresh = true, preferPublicFallback = usePublic)
                    }
                    1 -> {
                        errorRecoveryStage = 2
                        tamashaPlugin.invalidateChannelCache(active.id)
                        fetchAndPlayChannel(active.channel, forceRefresh = true, preferPublicFallback = true)
                    }
                    else -> {
                        val strings = stringsFor(uiState.value.preferences.language)
                        playerController.setErrorState(strings.streamErrorSubtitle)
                    }
                }
            }
            is ActivePlaybackItem.VodShow -> {
                if (errorRecoveryStage == 0) {
                    errorRecoveryStage = 1
                    tamashaPlugin.invalidateChannelCache(active.id)
                    fetchAndPlayVod(active.item, forceRefresh = true)
                } else {
                    val strings = stringsFor(uiState.value.preferences.language)
                    playerController.setErrorState(strings.streamErrorSubtitle)
                }
            }
        }
    }

    fun closePlayer() {
        resolveJob?.cancel()
        navFilterState.update { it.copy(activeChannelId = null, activeVodId = null) }
        modalState.update { it.copy(isFullscreen = false) }
        playerController.stopAndClear()
    }

    fun setCategory(category: ChannelCategory) {
        navFilterState.update { it.copy(category = category, favoritesOnly = false) }
    }

    fun setSearchQuery(query: String) {
        navFilterState.update { it.copy(query = query) }
    }

    fun toggleFavoritesFilter() {
        navFilterState.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun toggleChannelFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(channel)
        }
    }

    fun toggleVodFavorite(item: VodItemEntity) {
        viewModelScope.launch {
            repository.toggleVodFavorite(item)
        }
    }

    fun toggleLanguage() {
        val next = if (uiState.value.preferences.language == AppLanguage.ENGLISH) {
            AppLanguage.URDU
        } else {
            AppLanguage.ENGLISH
        }
        viewModelScope.launch {
            repository.setLanguage(next)
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            repository.setLanguage(language)
        }
    }

    fun selectQuality(quality: VideoQuality) {
        viewModelScope.launch {
            repository.setPreferredQuality(quality)
        }
        playerController.applyQuality(quality)
    }

    fun setFullscreen(fullscreen: Boolean) {
        modalState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun setInPipMode(inPip: Boolean) {
        modalState.update { it.copy(isInPipMode = inPip) }
    }

    fun openDeveloperSheet(show: Boolean) {
        modalState.update { it.copy(showDeveloperSheet = show) }
    }

    fun consumeSnackbar() {
        modalState.update { it.copy(snackbarMessage = null) }
    }

    override fun onCleared() {
        resolveJob?.cancel()
        updateJob?.cancel()
        catalogSyncJob?.cancel()
        tamashaPlugin.release()
        playerController.release()
        super.onCleared()
    }
}
