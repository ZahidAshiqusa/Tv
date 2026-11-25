package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localization.AppStrings
import com.example.ui.ChannelUpdateProgress
import com.example.ui.UpdateStage
import com.example.ui.theme.AuroraBlue
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.CallEmerald
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Full-screen blocking first-launch popup shown on initial install.
 * Nothing else (no channel list, no navigation bar) is visible until the user taps
 * "Download Icons & Refresh Channel Links" and the download + link refresh completes.
 */
@Composable
fun FirstLaunchSetupPopup(
    strings: AppStrings,
    updateProgress: ChannelUpdateProgress,
    onStartDownload: () -> Unit,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to AuroraBlue.copy(alpha = 0.82f),
                        0.45f to CinemaBackground,
                        1.0f to Color(0xFF030711)
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("first_launch_setup_popup")
    ) {
        // Subtle language switch pill in top-end corner
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(14.dp))
                .background(CinemaSurfaceHigh.copy(alpha = 0.85f))
                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable(onClick = onToggleLanguage)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("first_launch_language_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = "Switch language",
                tint = ElectricCyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = strings.languageToggleLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Centered focused setup popup card
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            CinemaSurfaceHigh.copy(alpha = 0.96f),
                            CinemaSurface.copy(alpha = 0.98f)
                        )
                    )
                )
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            ElectricCyan.copy(alpha = 0.55f),
                            ElectricBlue.copy(alpha = 0.4f),
                            CallEmerald.copy(alpha = 0.45f)
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glowing App Emblem
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(ElectricCyan.copy(alpha = 0.25f), ElectricBlue.copy(alpha = 0.3f))
                        )
                    )
                    .border(1.5.dp, ElectricCyan.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LiveTv,
                    contentDescription = "Zaid Live TV",
                    tint = ElectricCyan,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = strings.firstLaunchTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.firstLaunchSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedContent(
                targetState = updateProgress.isUpdating,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "first_launch_action_state"
            ) { isUpdating ->
                if (!isUpdating) {
                    Button(
                        onClick = onStartDownload,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = CinemaBackground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .testTag("first_launch_download_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = strings.firstLaunchButton,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                } else {
                    val stageBase = when (updateProgress.stage) {
                        UpdateStage.CATALOG -> 0.08f
                        UpdateStage.ICONS -> 0.28f
                        UpdateStage.LINKS -> 0.65f
                        UpdateStage.IDLE -> 1.0f
                    }
                    val stageWeight = when (updateProgress.stage) {
                        UpdateStage.CATALOG -> 0.20f
                        UpdateStage.ICONS -> 0.37f
                        UpdateStage.LINKS -> 0.35f
                        UpdateStage.IDLE -> 0f
                    }
                    val subFraction = if (updateProgress.total > 0) {
                        (updateProgress.completed.toFloat() / updateProgress.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0.4f
                    }
                    val targetFraction = (stageBase + stageWeight * subFraction).coerceIn(0.05f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetFraction,
                        animationSpec = tween(durationMillis = 320),
                        label = "setup_progress"
                    )
                    val percent = (animatedProgress * 100).toInt().coerceIn(1, 100)

                    val statusText = when (updateProgress.stage) {
                        UpdateStage.CATALOG -> strings.updatingCatalogStatus
                        UpdateStage.ICONS -> strings.updatingIconsStatus
                        UpdateStage.LINKS -> strings.updatingLinksStatus
                        UpdateStage.IDLE -> strings.buffering
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CinemaBackground.copy(alpha = 0.65f))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 18.dp)
                            .testTag("first_launch_progress_box"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = ElectricCyan,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$percent%",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = ElectricCyan
                            )
                        }

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            color = ElectricCyan,
                            trackColor = CinemaSurfaceHigh,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )

                        if (updateProgress.total > 1) {
                            Text(
                                text = "${updateProgress.completed} / ${updateProgress.total}",
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
