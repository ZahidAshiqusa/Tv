package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsCricket
import androidx.compose.material.icons.rounded.SportsMma
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.data.local.ChannelEntity
import com.example.data.local.VodItemEntity
import com.example.data.model.ChannelCategory
import com.example.data.model.VideoQuality
import com.example.localization.AppLanguage
import com.example.localization.AppStrings
import com.example.localization.stringsFor
import com.example.player.HiddenCatalogHarvestTask
import com.example.player.HiddenFetcherTask
import com.example.ui.components.ChannelGrid
import com.example.ui.components.DeveloperInfoSheet
import com.example.ui.components.FirstLaunchSetupPopup
import com.example.ui.components.HiddenTamashaCatalogHarvesterHost
import com.example.ui.components.HiddenTamashaFetcherHost
import com.example.ui.components.LivePlayerView
import com.example.ui.components.SettingsScreen
import com.example.ui.components.VodCatalogGrid
import com.example.ui.theme.AuroraBlue
import com.example.ui.theme.CallEmerald
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.FavoriteRose
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UrduCleanFontFamily

private data class NavTabSpec(
    val destination: AppDestination,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun ZaidLiveTvScreen(
    uiState: MainUiState,
    hiddenFetcherTask: HiddenFetcherTask?,
    hiddenCatalogTask: HiddenCatalogHarvestTask?,
    exoPlayer: ExoPlayer,
    onSelectDestination: (AppDestination) -> Unit,
    onSelectShowsSubFilter: (ShowsSubFilter) -> Unit,
    onSelectWrestlingSubFilter: (WrestlingSubFilter) -> Unit,
    onSelectChannel: (ChannelEntity) -> Unit,
    onSelectVodItem: (VodItemEntity) -> Unit,
    onClosePlayer: () -> Unit,
    onCategoryChange: (ChannelCategory) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onToggleChannelFavorite: (ChannelEntity) -> Unit,
    onToggleVodFavorite: (VodItemEntity) -> Unit,
    onToggleLanguage: () -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onUpdateAllChannels: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectQuality: (VideoQuality) -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onAutoRefreshStream: () -> Unit,
    onOpenDeveloperSheet: (Boolean) -> Unit,
    onConsumeSnackbar: () -> Unit
) {
    val isUrdu = uiState.preferences.language == AppLanguage.URDU
    val strings = remember(uiState.preferences.language) { stringsFor(uiState.preferences.language) }
    val snackbarHostState = remember { SnackbarHostState() }

    val navTabs = remember(strings) {
        listOf(
            NavTabSpec(AppDestination.CHANNELS, strings.navChannels, Icons.Rounded.LiveTv, "nav_tab_channels"),
            NavTabSpec(AppDestination.SHOWS, strings.navShows, Icons.Rounded.Tv, "nav_tab_shows"),
            NavTabSpec(AppDestination.MOVIES, strings.navMovies, Icons.Rounded.Movie, "nav_tab_movies"),
            NavTabSpec(AppDestination.WRESTLING, strings.navWrestling, Icons.Rounded.SportsMma, "nav_tab_wrestling"),
            NavTabSpec(AppDestination.SETTINGS, strings.navSettings, Icons.Rounded.Settings, "nav_tab_settings")
        )
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onConsumeSnackbar()
        }
    }

    BackHandler(enabled = uiState.isFullscreen) {
        onToggleFullscreen()
    }

    BackHandler(enabled = !uiState.isFullscreen && uiState.destination != AppDestination.CHANNELS) {
        onSelectDestination(AppDestination.CHANNELS)
    }

    val showUpdateBanner = uiState.destination != AppDestination.SETTINGS &&
        uiState.updateProgress.isUpdating

    Box(modifier = Modifier.fillMaxSize()) {
        // Invisible background fetchers placed behind the UI (only ONE Chromium WebView at a time to prevent renderer OOM)
        HiddenTamashaFetcherHost(task = hiddenFetcherTask)
        if (hiddenFetcherTask == null) {
            HiddenTamashaCatalogHarvesterHost(task = hiddenCatalogTask)
        }

        // Wait cleanly for persisted setup flag before drawing anything (prevents flash of channel list)
        if (!uiState.isPreferencesLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBackground)
            )
            return@Box
        }

        // Full-screen blocking First-Launch Setup Popup:
        // Nothing else is visible until user taps "Download Icons & Refresh Channel Links" and loading completes
        if (!uiState.preferences.initialChannelsUpdated) {
            FirstLaunchSetupPopup(
                strings = strings,
                updateProgress = uiState.updateProgress,
                onStartDownload = onUpdateAllChannels,
                onToggleLanguage = onToggleLanguage,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        // Fullscreen or PiP Mode — Pure edge-to-edge native player
        val activePlayback = uiState.activePlayback
        if ((uiState.isFullscreen || uiState.isInPipMode) && activePlayback != null) {
            LivePlayerView(
                exoPlayer = exoPlayer,
                playbackTitle = activePlayback.displayTitle(isUrdu),
                isLiveBroadcast = activePlayback is ActivePlaybackItem.LiveChannel,
                strings = strings,
                playerState = uiState.playerState,
                selectedQuality = uiState.preferences.preferredQuality,
                isFullscreen = uiState.isFullscreen,
                isInPipMode = uiState.isInPipMode,
                onTogglePlayPause = onTogglePlayPause,
                onToggleMute = onToggleMute,
                onSelectQuality = onSelectQuality,
                onToggleFullscreen = onToggleFullscreen,
                onEnterPip = onEnterPip,
                onAutoRefreshStream = onAutoRefreshStream,
                onClosePlayer = {
                    if (uiState.isFullscreen) onToggleFullscreen() else onClosePlayer()
                },
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedScreen = maxWidth >= 800.dp

            Scaffold(
                containerColor = CinemaBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = CinemaSurfaceHigh,
                            contentColor = TextPrimary,
                            actionColor = ElectricCyan,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                },
                bottomBar = {
                    if (!isExpandedScreen) {
                        ZaidBottomNavigationBar(
                            tabs = navTabs,
                            currentDestination = uiState.destination,
                            onSelectDestination = onSelectDestination
                        )
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to AuroraBlue.copy(alpha = 0.55f),
                                    0.28f to CinemaBackground,
                                    1.0f to CinemaBackground
                                )
                            )
                        )
                        .padding(innerPadding)
                ) {
                    if (isExpandedScreen) {
                        ZaidNavigationRail(
                            tabs = navTabs,
                            currentDestination = uiState.destination,
                            onSelectDestination = onSelectDestination
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding()
                    ) {
                        CinemaTopHeader(
                            isUrdu = isUrdu,
                            strings = strings,
                            isUpdating = uiState.updateProgress.isUpdating,
                            onUpdateAllChannels = onUpdateAllChannels,
                            onToggleLanguage = onToggleLanguage
                        )

                        // First-Time Setup / Live Progress Update Card at the top
                        AnimatedVisibility(
                            visible = showUpdateBanner,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            FirstTimeUpdateBanner(
                                strings = strings,
                                updateProgress = uiState.updateProgress,
                                onUpdateClick = onUpdateAllChannels,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        if (isExpandedScreen && uiState.destination != AppDestination.SETTINGS) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1.05f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (activePlayback != null) {
                                        Surface(
                                            shape = RoundedCornerShape(22.dp),
                                            color = Color.Black,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    1.2.dp,
                                                    ElectricCyan.copy(alpha = 0.45f),
                                                    RoundedCornerShape(22.dp)
                                                )
                                        ) {
                                            LivePlayerView(
                                                exoPlayer = exoPlayer,
                                                playbackTitle = activePlayback.displayTitle(isUrdu),
                                                isLiveBroadcast = activePlayback is ActivePlaybackItem.LiveChannel,
                                                strings = strings,
                                                playerState = uiState.playerState,
                                                selectedQuality = uiState.preferences.preferredQuality,
                                                isFullscreen = false,
                                                isInPipMode = false,
                                                onTogglePlayPause = onTogglePlayPause,
                                                onToggleMute = onToggleMute,
                                                onSelectQuality = onSelectQuality,
                                                onToggleFullscreen = onToggleFullscreen,
                                                onEnterPip = onEnterPip,
                                                onAutoRefreshStream = onAutoRefreshStream,
                                                onClosePlayer = onClosePlayer
                                            )
                                        }
                                    } else {
                                        IdlePlayerPromptCard(strings = strings)
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .fillMaxHeight()
                                ) {
                                    DestinationContentBody(
                                        uiState = uiState,
                                        isUrdu = isUrdu,
                                        strings = strings,
                                        onCategoryChange = onCategoryChange,
                                        onSelectShowsSubFilter = onSelectShowsSubFilter,
                                        onSelectWrestlingSubFilter = onSelectWrestlingSubFilter,
                                        onSearchChange = onSearchChange,
                                        onToggleFavoritesFilter = onToggleFavoritesFilter,
                                        onSelectChannel = onSelectChannel,
                                        onToggleChannelFavorite = onToggleChannelFavorite,
                                        onSelectVodItem = onSelectVodItem,
                                        onToggleVodFavorite = onToggleVodFavorite,
                                        onSelectLanguage = onSelectLanguage,
                                        onSelectQuality = onSelectQuality,
                                        onUpdateAllChannels = onUpdateAllChannels,
                                        onRefreshCatalog = onRefreshCatalog
                                    )
                                }
                            }
                        } else {
                            AnimatedVisibility(
                                visible = activePlayback != null && uiState.destination != AppDestination.SETTINGS,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                activePlayback?.let { active ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .border(
                                                1.2.dp,
                                                ElectricCyan.copy(alpha = 0.45f),
                                                RoundedCornerShape(20.dp)
                                            )
                                    ) {
                                        LivePlayerView(
                                            exoPlayer = exoPlayer,
                                            playbackTitle = active.displayTitle(isUrdu),
                                            isLiveBroadcast = active is ActivePlaybackItem.LiveChannel,
                                            strings = strings,
                                            playerState = uiState.playerState,
                                            selectedQuality = uiState.preferences.preferredQuality,
                                            isFullscreen = false,
                                            isInPipMode = false,
                                            onTogglePlayPause = onTogglePlayPause,
                                            onToggleMute = onToggleMute,
                                            onSelectQuality = onSelectQuality,
                                            onToggleFullscreen = onToggleFullscreen,
                                            onEnterPip = onEnterPip,
                                            onAutoRefreshStream = onAutoRefreshStream,
                                            onClosePlayer = onClosePlayer
                                        )
                                    }
                                }
                            }

                            DestinationContentBody(
                                uiState = uiState,
                                isUrdu = isUrdu,
                                strings = strings,
                                onCategoryChange = onCategoryChange,
                                onSelectShowsSubFilter = onSelectShowsSubFilter,
                                onSelectWrestlingSubFilter = onSelectWrestlingSubFilter,
                                onSearchChange = onSearchChange,
                                onToggleFavoritesFilter = onToggleFavoritesFilter,
                                onSelectChannel = onSelectChannel,
                                onToggleChannelFavorite = onToggleChannelFavorite,
                                onSelectVodItem = onSelectVodItem,
                                onToggleVodFavorite = onToggleVodFavorite,
                                onSelectLanguage = onSelectLanguage,
                                onSelectQuality = onSelectQuality,
                                onUpdateAllChannels = onUpdateAllChannels,
                                onRefreshCatalog = onRefreshCatalog
                            )
                        }
                    }
                }
            }
        }

        if (uiState.showDeveloperSheet) {
            DeveloperInfoSheet(
                strings = strings,
                onDismiss = { onOpenDeveloperSheet(false) }
            )
        }
    }
}

@Composable
private fun DestinationContentBody(
    uiState: MainUiState,
    isUrdu: Boolean,
    strings: AppStrings,
    onCategoryChange: (ChannelCategory) -> Unit,
    onSelectShowsSubFilter: (ShowsSubFilter) -> Unit,
    onSelectWrestlingSubFilter: (WrestlingSubFilter) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onSelectChannel: (ChannelEntity) -> Unit,
    onToggleChannelFavorite: (ChannelEntity) -> Unit,
    onSelectVodItem: (VodItemEntity) -> Unit,
    onToggleVodFavorite: (VodItemEntity) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectQuality: (VideoQuality) -> Unit,
    onUpdateAllChannels: () -> Unit,
    onRefreshCatalog: () -> Unit
) {
    val hasSearch = uiState.searchQuery.trim().isNotEmpty()
    when (uiState.destination) {
        AppDestination.CHANNELS -> {
            SearchAndCategoryControls(
                searchQuery = uiState.searchQuery,
                selectedCategory = uiState.selectedCategory,
                showFavoritesOnly = uiState.showFavoritesOnly,
                channelCount = uiState.filteredChannels.size,
                strings = strings,
                onSearchChange = onSearchChange,
                onCategoryChange = onCategoryChange,
                onToggleFavorites = onToggleFavoritesFilter
            )
            ChannelGrid(
                channels = uiState.filteredChannels,
                activeChannelId = uiState.activePlayback?.id,
                isUrdu = isUrdu,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onChannelClick = onSelectChannel,
                onToggleFavorite = onToggleChannelFavorite
            )
        }

        AppDestination.SHOWS -> {
            VodSearchBar(
                searchQuery = uiState.searchQuery,
                placeholder = strings.searchShowsPlaceholder,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onSearchChange = onSearchChange,
                onToggleFavorites = onToggleFavoritesFilter
            )
            ShowsSubFilterRow(
                selectedFilter = uiState.showsSubFilter,
                showFavoritesOnly = uiState.showFavoritesOnly,
                itemCount = uiState.filteredShows.size,
                strings = strings,
                onSelectFilter = onSelectShowsSubFilter
            )
            VodCatalogGrid(
                items = uiState.filteredShows,
                activePlaybackId = uiState.activePlayback?.id,
                isUrdu = isUrdu,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onItemClick = onSelectVodItem,
                onToggleFavorite = onToggleVodFavorite,
                isLoadingCatalog = uiState.isCatalogSyncing,
                hasSearchQuery = hasSearch,
                onRefreshCatalog = onRefreshCatalog
            )
        }

        AppDestination.MOVIES -> {
            VodSearchBar(
                searchQuery = uiState.searchQuery,
                placeholder = strings.searchMoviesPlaceholder,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onSearchChange = onSearchChange,
                onToggleFavorites = onToggleFavoritesFilter
            )
            SectionHeaderMetaRow(
                title = strings.sectionMovies,
                count = uiState.filteredMovies.size,
                strings = strings
            )
            VodCatalogGrid(
                items = uiState.filteredMovies,
                activePlaybackId = uiState.activePlayback?.id,
                isUrdu = isUrdu,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onItemClick = onSelectVodItem,
                onToggleFavorite = onToggleVodFavorite,
                isLoadingCatalog = uiState.isCatalogSyncing,
                hasSearchQuery = hasSearch,
                onRefreshCatalog = onRefreshCatalog
            )
        }

        AppDestination.WRESTLING -> {
            VodSearchBar(
                searchQuery = uiState.searchQuery,
                placeholder = strings.searchWrestlingPlaceholder,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onSearchChange = onSearchChange,
                onToggleFavorites = onToggleFavoritesFilter
            )
            WrestlingSubFilterRow(
                selectedFilter = uiState.wrestlingSubFilter,
                showFavoritesOnly = uiState.showFavoritesOnly,
                itemCount = uiState.filteredWrestling.size,
                strings = strings,
                onSelectFilter = onSelectWrestlingSubFilter
            )
            VodCatalogGrid(
                items = uiState.filteredWrestling,
                activePlaybackId = uiState.activePlayback?.id,
                isUrdu = isUrdu,
                showFavoritesOnly = uiState.showFavoritesOnly,
                strings = strings,
                onItemClick = onSelectVodItem,
                onToggleFavorite = onToggleVodFavorite,
                isLoadingCatalog = uiState.isCatalogSyncing,
                hasSearchQuery = hasSearch,
                onRefreshCatalog = onRefreshCatalog
            )
        }

        AppDestination.SETTINGS -> {
            SettingsScreen(
                uiState = uiState,
                strings = strings,
                onSelectLanguage = onSelectLanguage,
                onSelectQuality = onSelectQuality,
                onUpdateAllChannels = onUpdateAllChannels
            )
        }
    }
}

@Composable
private fun ZaidBottomNavigationBar(
    tabs: List<NavTabSpec>,
    currentDestination: AppDestination,
    onSelectDestination: (AppDestination) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = CinemaBorder.copy(alpha = 0.8f))
        NavigationBar(
            containerColor = CinemaSurface,
            contentColor = TextPrimary,
            windowInsets = WindowInsets.navigationBars,
            tonalElevation = 0.dp
        ) {
            tabs.forEach { tab ->
                val selected = currentDestination == tab.destination
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectDestination(tab.destination) },
                    modifier = Modifier.testTag(tab.testTag),
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = ElectricBlue.copy(alpha = 0.28f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun ZaidNavigationRail(
    tabs: List<NavTabSpec>,
    currentDestination: AppDestination,
    onSelectDestination: (AppDestination) -> Unit
) {
    NavigationRail(
        containerColor = CinemaSurface,
        contentColor = TextPrimary,
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .border(width = 1.dp, color = CinemaBorder.copy(alpha = 0.7f))
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        tabs.forEach { tab ->
            val selected = currentDestination == tab.destination
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectDestination(tab.destination) },
                modifier = Modifier.testTag(tab.testTag),
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = ElectricCyan,
                    selectedTextColor = ElectricCyan,
                    indicatorColor = ElectricBlue.copy(alpha = 0.28f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun CinemaTopHeader(
    isUrdu: Boolean,
    strings: AppStrings,
    isUpdating: Boolean,
    onUpdateAllChannels: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    val spinTransition = rememberInfiniteTransition(label = "sync_spin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_angle"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_zaid_tv_emblem),
            contentDescription = "Zaid Live TV Logo",
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(listOf(ElectricCyan, ElectricBlue)),
                    shape = RoundedCornerShape(14.dp)
                )
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isUrdu) "زید لائیو ٹی وی" else "Zaid Live TV",
                fontFamily = if (isUrdu) UrduCleanFontFamily else OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                lineHeight = if (isUrdu) 26.sp else 23.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = strings.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // One-Tap Full Update Button (Channels, Shows, Movies, Wrestling & HD Icons)
        IconButton(
            onClick = onUpdateAllChannels,
            enabled = !isUpdating,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(CinemaSurfaceHigh)
                .border(
                    width = 1.dp,
                    color = if (isUpdating) CallEmerald else CinemaBorder,
                    shape = RoundedCornerShape(13.dp)
                )
                .testTag("header_update_channels_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = strings.updateHeaderButton,
                tint = if (isUpdating) CallEmerald else ElectricCyan,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (isUpdating) spinAngle else 0f)
            )
        }

        // Crisp Language Toggle Pill (English <-> iPhone-Style Urdu RTL)
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(CinemaSurfaceHigh)
                .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
                .clickable(onClick = onToggleLanguage)
                .padding(horizontal = 12.dp)
                .testTag("language_toggle_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = "Switch language",
                tint = ElectricCyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = strings.languageToggleLabel,
                fontFamily = if (strings.languageToggleLabel == "اردو") UrduCleanFontFamily else OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun FirstTimeUpdateBanner(
    strings: AppStrings,
    updateProgress: ChannelUpdateProgress,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    val totalSafe = updateProgress.total.coerceAtLeast(1)
    val stageFraction = (updateProgress.completed.toFloat() / totalSafe.toFloat()).coerceIn(0f, 1f)
    val overallProgress = when (updateProgress.stage) {
        UpdateStage.CATALOG -> 0.16f
        UpdateStage.ICONS -> 0.2f + stageFraction * 0.4f
        UpdateStage.LINKS -> 0.6f + stageFraction * 0.4f
        UpdateStage.IDLE -> 0f
    }

    Surface(
        shape = shape,
        color = CinemaSurface,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(ElectricCyan.copy(alpha = 0.65f), CallEmerald.copy(alpha = 0.55f))
                ),
                shape = shape
            )
            .testTag("first_time_update_banner")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            ElectricBlue.copy(alpha = 0.24f),
                            CallEmerald.copy(alpha = 0.14f),
                            CinemaSurface
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(ElectricCyan, ElectricBlue))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            tint = CinemaBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = CallEmerald,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = strings.updateBannerBadge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = CallEmerald
                            )
                        }
                        Text(
                            text = strings.updateBannerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                if (!updateProgress.isUpdating) {
                    Text(
                        text = strings.updateBannerSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = onUpdateClick,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = CinemaBackground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("update_channels_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.updateNowButton,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    val statusText = when (updateProgress.stage) {
                        UpdateStage.CATALOG -> strings.updatingCatalogStatus
                        UpdateStage.ICONS -> strings.updatingIconsStatus
                        UpdateStage.LINKS -> strings.updatingLinksStatus
                        UpdateStage.IDLE -> strings.buffering
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (updateProgress.stage != UpdateStage.CATALOG) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${updateProgress.completed} / $totalSafe",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = ElectricCyan
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { overallProgress.coerceIn(0.08f, 1f) },
                        color = ElectricCyan,
                        trackColor = CinemaSurfaceHigh,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun VodSearchBar(
    searchQuery: String,
    placeholder: String,
    showFavoritesOnly: Boolean,
    strings: AppStrings,
    onSearchChange: (String) -> Unit,
    onToggleFavorites: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.testTag("clear_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear search",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = CinemaBorder,
                focusedContainerColor = CinemaSurface,
                unfocusedContainerColor = CinemaSurface,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = ElectricCyan
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("channel_search_input")
        )

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (showFavoritesOnly) FavoriteRose.copy(alpha = 0.22f) else CinemaSurface)
                .border(
                    width = 1.dp,
                    color = if (showFavoritesOnly) FavoriteRose else CinemaBorder,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable(onClick = onToggleFavorites)
                .testTag("favorites_filter_toggle"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (showFavoritesOnly) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = strings.favoritesLabel,
                tint = if (showFavoritesOnly) FavoriteRose else TextSecondary,
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

@Composable
private fun ShowsSubFilterRow(
    selectedFilter: ShowsSubFilter,
    showFavoritesOnly: Boolean,
    itemCount: Int,
    strings: AppStrings,
    onSelectFilter: (ShowsSubFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pills = listOf(
            Triple(ShowsSubFilter.ALL, strings.allCategories, Icons.Rounded.GridView),
            Triple(ShowsSubFilter.DRAMAS, strings.sectionDramas, Icons.Rounded.TheaterComedy),
            Triple(ShowsSubFilter.TV_SHOWS, strings.sectionTvShows, Icons.Rounded.Tv)
        )
        pills.forEach { (filter, label, icon) ->
            val isSelected = selectedFilter == filter && !showFavoritesOnly
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSelected) {
                            Brush.horizontalGradient(listOf(ElectricCyan, ElectricBlue))
                        } else {
                            Brush.horizontalGradient(listOf(CinemaSurface, CinemaSurface))
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Color.Transparent else CinemaBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectFilter(filter) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("shows_filter_${filter.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) CinemaBackground else ElectricCyan.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) CinemaBackground else TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$itemCount ${strings.itemsCountSuffix}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun WrestlingSubFilterRow(
    selectedFilter: WrestlingSubFilter,
    showFavoritesOnly: Boolean,
    itemCount: Int,
    strings: AppStrings,
    onSelectFilter: (WrestlingSubFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pills = listOf(
            Triple(WrestlingSubFilter.ALL, strings.allCategories, Icons.Rounded.GridView),
            Triple(WrestlingSubFilter.WRESTLING, strings.sectionWrestling, Icons.Rounded.SportsMma),
            Triple(WrestlingSubFilter.HIGHLIGHTS, strings.sectionHighlights, Icons.Rounded.SportsCricket)
        )
        pills.forEach { (filter, label, icon) ->
            val isSelected = selectedFilter == filter && !showFavoritesOnly
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSelected) {
                            Brush.horizontalGradient(listOf(ElectricCyan, ElectricBlue))
                        } else {
                            Brush.horizontalGradient(listOf(CinemaSurface, CinemaSurface))
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Color.Transparent else CinemaBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectFilter(filter) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("wrestling_filter_${filter.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) CinemaBackground else ElectricCyan.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) CinemaBackground else TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$itemCount ${strings.itemsCountSuffix}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun SectionHeaderMetaRow(
    title: String,
    count: Int,
    strings: AppStrings
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CallEmerald.copy(alpha = 0.18f))
                    .border(1.dp, CallEmerald.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = strings.freeBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = CallEmerald
                )
            }
        }
        Text(
            text = "$count ${strings.itemsCountSuffix}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun SearchAndCategoryControls(
    searchQuery: String,
    selectedCategory: ChannelCategory,
    showFavoritesOnly: Boolean,
    channelCount: Int,
    strings: AppStrings,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (ChannelCategory) -> Unit,
    onToggleFavorites: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VodSearchBar(
            searchQuery = searchQuery,
            placeholder = strings.searchPlaceholder,
            showFavoritesOnly = showFavoritesOnly,
            strings = strings,
            onSearchChange = onSearchChange,
            onToggleFavorites = onToggleFavorites
        )

        // Easy-to-Recognize Icon + Label Category Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelCategory.entries.forEach { category ->
                val isSelected = selectedCategory == category && !showFavoritesOnly
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) {
                                Brush.horizontalGradient(listOf(ElectricCyan, ElectricBlue))
                            } else {
                                Brush.horizontalGradient(listOf(CinemaSurface, CinemaSurface))
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color.Transparent else CinemaBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onCategoryChange(category) }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .testTag("category_tab_${category.storageKey.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = categoryIcon(category),
                        contentDescription = null,
                        tint = if (isSelected) CinemaBackground else ElectricCyan.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = category.label(strings),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) CinemaBackground else TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "$channelCount ${strings.channelsCountSuffix}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

private fun categoryIcon(category: ChannelCategory): ImageVector = when (category) {
    ChannelCategory.ALL -> Icons.Rounded.GridView
    ChannelCategory.NEWS -> Icons.Rounded.Newspaper
    ChannelCategory.SPORTS -> Icons.Rounded.SportsCricket
    ChannelCategory.ENTERTAINMENT -> Icons.Rounded.TheaterComedy
    ChannelCategory.ISLAMIC -> Icons.Rounded.MenuBook
    ChannelCategory.MOVIES -> Icons.Rounded.Movie
}

@Composable
private fun IdlePlayerPromptCard(strings: AppStrings) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CinemaSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CinemaBorder, RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(ElectricCyan.copy(alpha = 0.25f), ElectricBlue.copy(alpha = 0.35f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LiveTv,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = strings.tapChannelPrompt,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = strings.tapChannelSubprompt,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
