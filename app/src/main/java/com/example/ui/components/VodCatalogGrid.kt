package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.LogoCacheManager
import com.example.data.local.VodItemEntity
import com.example.localization.AppStrings
import com.example.ui.theme.CallEmerald
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.FavoriteRose
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun VodCatalogGrid(
    items: List<VodItemEntity>,
    activePlaybackId: String?,
    isUrdu: Boolean,
    showFavoritesOnly: Boolean,
    strings: AppStrings,
    onItemClick: (VodItemEntity) -> Unit,
    onToggleFavorite: (VodItemEntity) -> Unit,
    isLoadingCatalog: Boolean = false,
    hasSearchQuery: Boolean = false,
    onRefreshCatalog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier
            .fillMaxSize()
            .testTag("vod_catalog_grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VodEmptyState(
                    isFavoritesEmpty = showFavoritesOnly,
                    isLoadingCatalog = isLoadingCatalog,
                    hasSearchQuery = hasSearchQuery,
                    strings = strings,
                    onRefreshCatalog = onRefreshCatalog
                )
            }
        } else {
            items(
                items = items,
                key = { it.id }
            ) { item ->
                VodPosterCard(
                    item = item,
                    isActive = item.id == activePlaybackId,
                    isUrdu = isUrdu,
                    strings = strings,
                    onClick = { onItemClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) }
                )
            }
        }
    }
}

@Composable
private fun VodPosterCard(
    item: VodItemEntity,
    isActive: Boolean,
    isUrdu: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { LogoCacheManager.getImageLoader(context) }
    val shape = RoundedCornerShape(20.dp)
    val accent = Color(item.accentHex)

    val bundledPosterRes = remember(item.id, item.sectionKey) {
        BundledMediaRegistry.vodThumbnailRes(item.id, item.section)
    }
    val verifiedRemotePoster = remember(item.posterUrl, item.fallbackPosterUrl) {
        when {
            BundledMediaRegistry.isRealRemoteAssetUrl(item.posterUrl) -> item.posterUrl
            BundledMediaRegistry.isRealRemoteAssetUrl(item.fallbackPosterUrl) -> item.fallbackPosterUrl
            else -> ""
        }
    }
    var remoteFailed by remember(item.id, verifiedRemotePoster) { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = if (isActive) CinemaSurfaceHigh else CinemaSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (isActive) 1.8.dp else 1.dp,
                brush = if (isActive) {
                    Brush.linearGradient(listOf(ElectricCyan, ElectricBlue))
                } else {
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.38f), CinemaBorder))
                },
                shape = shape
            )
            .clickable(onClick = onClick)
            .testTag("vod_card_${item.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Full-Bleed 16:9 Cinema Poster / Thumbnail Stage (100% Offline Bundled Ready on Install)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(CinemaSurfaceHigh)
            ) {
                if (verifiedRemotePoster.isNotBlank() && !remoteFailed) {
                    SubcomposeAsyncImage(
                        model = remember(verifiedRemotePoster) {
                            ImageRequest.Builder(context)
                                .data(verifiedRemotePoster)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .crossfade(true)
                                .build()
                        },
                        imageLoader = imageLoader,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Image(
                                painter = painterResource(id = bundledPosterRes),
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        },
                        error = {
                            remoteFailed = true
                            Image(
                                painter = painterResource(id = bundledPosterRes),
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    )
                } else {
                    Image(
                        painter = painterResource(id = bundledPosterRes),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Cinema vignette scrim for crisp FREE badge & heart readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.35f),
                                0.55f to Color.Transparent,
                                1.0f to CinemaSurface.copy(alpha = 0.88f)
                            )
                        )
                )

                // FREE Badge (Top Start)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CallEmerald.copy(alpha = 0.94f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = strings.freeBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF041C12)
                    )
                }

                // Favorite Heart (Top End)
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(36.dp)
                        .testTag("favorite_vod_${item.id}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(CinemaBackground.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite ${item.title}",
                            tint = if (item.isFavorite) FavoriteRose else TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Title + Section Meta + Watch Now Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.displayTitle(isUrdu),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) ElectricCyan else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = item.subtitleMeta.ifBlank { item.section.label(strings) },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (isActive) {
                                Brush.horizontalGradient(listOf(LiveCrimson, LiveCrimson.copy(alpha = 0.85f)))
                            } else {
                                Brush.horizontalGradient(
                                    listOf(ElectricBlue.copy(alpha = 0.3f), ElectricCyan.copy(alpha = 0.2f))
                                )
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isActive) Color.Transparent else ElectricCyan.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(11.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (isActive) Color.White else ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isActive) strings.playingNow else strings.watchNow,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.White else TextPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun VodEmptyState(
    isFavoritesEmpty: Boolean,
    isLoadingCatalog: Boolean,
    hasSearchQuery: Boolean,
    strings: AppStrings,
    onRefreshCatalog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            !isFavoritesEmpty && !hasSearchQuery && isLoadingCatalog -> {
                CircularProgressIndicator(
                    color = ElectricCyan,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = strings.loadingCatalogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = strings.loadingCatalogSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            !isFavoritesEmpty && !hasSearchQuery -> {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(CinemaSurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = strings.loadingCatalogSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onRefreshCatalog,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = CinemaBackground
                    ),
                    modifier = Modifier.testTag("reload_vod_catalog_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.loadNowButton,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(CinemaSurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavoritesEmpty) Icons.Rounded.FavoriteBorder else Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = if (isFavoritesEmpty) strings.emptyFavoritesTitle else strings.emptySearchTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (isFavoritesEmpty) strings.emptyFavoritesSubtitle else strings.emptySearchSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
