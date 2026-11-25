package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SportsMma
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VideoQuality
import com.example.data.model.VodSection
import com.example.localization.AppLanguage
import com.example.localization.AppStrings
import com.example.ui.ChannelUpdateProgress
import com.example.ui.MainUiState
import com.example.ui.UpdateStage
import com.example.ui.theme.CallEmerald
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UrduCleanFontFamily

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    strings: AppStrings,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectQuality: (VideoQuality) -> Unit,
    onUpdateAllChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val channelsCount = uiState.allChannels.size
    val showsCount = uiState.allVodItems.count { it.section == VodSection.DRAMA || it.section == VodSection.TV_SHOW }
    val moviesCount = uiState.allVodItems.count { it.section == VodSection.MOVIE }
    val wrestlingCount = uiState.allVodItems.count {
        it.section == VodSection.WRESTLING || it.section == VodSection.HIGHLIGHTS
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = strings.settingsTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )

        // 1. Dedicated Developer Details Card (Zaid Ashiq + WhatsApp + Direct Call)
        DeveloperFooterCard(strings = strings)

        // 2. Full Channels, Free Shows, Movies, Wrestling & HD Icons Update Card
        SettingsUpdateCard(
            strings = strings,
            updateProgress = uiState.updateProgress,
            onUpdateClick = onUpdateAllChannels
        )

        // 3. Free Library Overview Stats
        SettingsSectionCard(
            title = strings.settingsLibraryTitle,
            icon = Icons.Rounded.LiveTv
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryStatTile(
                    icon = Icons.Rounded.LiveTv,
                    count = channelsCount,
                    label = strings.navChannels,
                    modifier = Modifier.weight(1f)
                )
                LibraryStatTile(
                    icon = Icons.Rounded.Tv,
                    count = showsCount,
                    label = strings.navShows,
                    modifier = Modifier.weight(1f)
                )
                LibraryStatTile(
                    icon = Icons.Rounded.Movie,
                    count = moviesCount,
                    label = strings.navMovies,
                    modifier = Modifier.weight(1f)
                )
                LibraryStatTile(
                    icon = Icons.Rounded.SportsMma,
                    count = wrestlingCount,
                    label = strings.navWrestling,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Language Selector (English <-> Crisp Offline iPhone-Style Urdu)
        SettingsSectionCard(
            title = strings.settingsLanguageTitle,
            subtitle = strings.settingsLanguageSubtitle,
            icon = Icons.Rounded.Language
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LanguageOptionButton(
                    label = "English",
                    isUrduLabel = false,
                    selected = uiState.preferences.language == AppLanguage.ENGLISH,
                    onClick = { onSelectLanguage(AppLanguage.ENGLISH) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_lang_english")
                )
                LanguageOptionButton(
                    label = "اردو",
                    isUrduLabel = true,
                    selected = uiState.preferences.language == AppLanguage.URDU,
                    onClick = { onSelectLanguage(AppLanguage.URDU) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_lang_urdu")
                )
            }
        }

        // 5. Default Video Quality Selector
        SettingsSectionCard(
            title = strings.settingsQualityTitle,
            icon = Icons.Rounded.HighQuality
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.entries.forEach { quality ->
                    val isSelected = uiState.preferences.preferredQuality == quality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) ElectricBlue.copy(alpha = 0.24f) else CinemaBackground)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) ElectricCyan else CinemaBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectQuality(quality) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("settings_quality_${quality.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = quality.title(strings),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) ElectricCyan else TextPrimary
                            )
                            Text(
                                text = quality.subtitle(strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(ElectricCyan.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsUpdateCard(
    strings: AppStrings,
    updateProgress: ChannelUpdateProgress,
    onUpdateClick: () -> Unit
) {
    val totalSafe = updateProgress.total.coerceAtLeast(1)
    val stageFraction = (updateProgress.completed.toFloat() / totalSafe.toFloat()).coerceIn(0f, 1f)
    val overallProgress = when (updateProgress.stage) {
        UpdateStage.CATALOG -> 0.18f
        UpdateStage.ICONS -> 0.2f + stageFraction * 0.4f
        UpdateStage.LINKS -> 0.6f + stageFraction * 0.4f
        UpdateStage.IDLE -> 0f
    }

    SettingsSectionCard(
        title = strings.updateBannerTitle,
        subtitle = strings.updateBannerSubtitle,
        icon = Icons.Rounded.CloudDownload
    ) {
        if (!updateProgress.isUpdating) {
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
                    .testTag("settings_update_all_button")
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    color = CallEmerald,
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

@Composable
private fun LibraryStatTile(
    icon: ImageVector,
    count: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CinemaBackground)
            .border(1.dp, CinemaBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = count.toString(),
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LanguageOptionButton(
    label: String,
    isUrduLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(ElectricCyan, ElectricBlue))
                } else {
                    Brush.horizontalGradient(listOf(CinemaBackground, CinemaBackground))
                }
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else CinemaBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = if (isUrduLabel) UrduCleanFontFamily else OutfitFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (selected) CinemaBackground else TextPrimary
        )
    }
}
