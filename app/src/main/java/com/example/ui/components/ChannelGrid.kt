package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ChannelEntity
import com.example.localization.AppStrings
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
fun ChannelGrid(
    channels: List<ChannelEntity>,
    activeChannelId: String?,
    isUrdu: Boolean,
    showFavoritesOnly: Boolean,
    strings: AppStrings,
    onChannelClick: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 158.dp),
        modifier = modifier
            .fillMaxSize()
            .testTag("channel_grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (channels.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyChannelState(
                    isFavoritesEmpty = showFavoritesOnly,
                    strings = strings
                )
            }
        } else {
            items(
                items = channels,
                key = { it.id }
            ) { channel ->
                ChannelCard(
                    channel = channel,
                    isActive = channel.id == activeChannelId,
                    isUrdu = isUrdu,
                    strings = strings,
                    onClick = { onChannelClick(channel) },
                    onToggleFavorite = { onToggleFavorite(channel) }
                )
            }
        }

        // Dedicated Developer Footer Card at the bottom of the catalog
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(modifier = Modifier.height(8.dp))
            DeveloperFooterCard(
                strings = strings,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ChannelCard(
    channel: ChannelEntity,
    isActive: Boolean,
    isUrdu: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val channelAccent = Color(channel.accentHex)
    val borderBrush = if (isActive) {
        Brush.linearGradient(listOf(ElectricCyan, ElectricBlue))
    } else {
        Brush.linearGradient(
            listOf(
                channelAccent.copy(alpha = 0.28f),
                CinemaBorder.copy(alpha = 0.7f)
            )
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (isActive) 1.8.dp else 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .clickable(onClick = onClick)
            .testTag("channel_card_${channel.id}"),
        shape = shape,
        color = if (isActive) CinemaSurfaceHigh else CinemaSurface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = if (isActive) {
                            listOf(
                                ElectricCyan.copy(alpha = 0.18f),
                                CinemaSurfaceHigh
                            )
                        } else {
                            listOf(
                                channelAccent.copy(alpha = 0.11f),
                                CinemaSurface
                            )
                        }
                    )
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Row: Category Badge + Large Touch Favorite Heart
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) ElectricCyan.copy(alpha = 0.2f)
                                else CinemaBackground.copy(alpha = 0.65f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = channel.category.label(strings),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) ElectricCyan else TextSecondary,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("favorite_channel_${channel.id}")
                    ) {
                        Icon(
                            imageVector = if (channel.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Toggle favorite for ${channel.name}",
                            tint = if (channel.isFavorite) FavoriteRose else TextSecondary.copy(alpha = 0.75f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // Studio-Framed Cached High-Res Channel Logo
                Box(contentAlignment = Alignment.BottomEnd) {
                    ChannelLogo(
                        channel = channel,
                        size = 68.dp
                    )
                    if (isActive) {
                        PlayingEqualizerBadge()
                    }
                }

                // Clear, Easy-to-Read Channel Name
                Text(
                    text = channel.displayName(isUrdu),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) ElectricCyan else TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // Clean Action Pill: "Watch Live" / "NOW PLAYING"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isActive) {
                                Brush.horizontalGradient(listOf(LiveCrimson, LiveCrimson.copy(alpha = 0.85f)))
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        ElectricBlue.copy(alpha = 0.28f),
                                        ElectricCyan.copy(alpha = 0.18f)
                                    )
                                )
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isActive) Color.Transparent else ElectricCyan.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.playingNow,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = strings.watchLive,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayingEqualizerBadge() {
    val transition = rememberInfiniteTransition(label = "eq")
    val bar1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "eq1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse),
        label = "eq2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(510), RepeatMode.Reverse),
        label = "eq3"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LiveCrimson)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { heightFraction ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((10f * heightFraction).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun EmptyChannelState(
    isFavoritesEmpty: Boolean,
    strings: AppStrings
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
