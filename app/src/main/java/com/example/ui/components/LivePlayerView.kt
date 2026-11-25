package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.VideoQuality
import com.example.localization.AppStrings
import com.example.player.PlaybackStatus
import com.example.player.PlayerUiState
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HudPillBackground
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.PlayerScrim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class SwipeGestureType { NONE, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class)
@Composable
fun LivePlayerView(
    exoPlayer: ExoPlayer,
    playbackTitle: String,
    isLiveBroadcast: Boolean,
    strings: AppStrings,
    playerState: PlayerUiState,
    selectedQuality: VideoQuality,
    isFullscreen: Boolean,
    isInPipMode: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectQuality: (VideoQuality) -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onAutoRefreshStream: () -> Unit,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var playerSize by remember { mutableStateOf(IntSize.Zero) }

    // Swipe-only brightness (left half) & volume (right half) gesture state
    var activeGesture by remember { mutableStateOf(SwipeGestureType.NONE) }
    var brightnessLevel by remember {
        val currentWinBright = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (currentWinBright in 0f..1f) currentWinBright else 0.55f)
    }
    var volumeLevel by remember {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        mutableFloatStateOf(curVol.toFloat() / maxVol.toFloat())
    }

    // VOD Timeline Position & Duration (Dramas, Movies, Shows, Wrestling & Highlights)
    var vodPositionMs by remember { mutableLongStateOf(0L) }
    var vodDurationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(isLiveBroadcast, playerState.status, controlsVisible) {
        if (!isLiveBroadcast) {
            while (true) {
                if (!isScrubbing) {
                    vodPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    vodDurationMs = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                }
                delay(450L)
            }
        }
    }

    // Auto-hide controls after 3.5 seconds while actively playing
    LaunchedEffect(controlsVisible, playerState.status, showQualityDialog, isScrubbing) {
        if (controlsVisible && playerState.status is PlaybackStatus.Playing && !showQualityDialog && !isScrubbing) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(activeGesture) {
        if (activeGesture != SwipeGestureType.NONE) {
            delay(1_400L)
            activeGesture = SwipeGestureType.NONE
        }
    }

    val containerModifier = if (isFullscreen || isInPipMode) {
        modifier
            .fillMaxSize()
            .background(Color.Black)
    } else {
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    }

    Box(
        modifier = containerModifier
            .testTag("live_video_player_container")
            .onSizeChanged { playerSize = it }
            .pointerInput(isInPipMode) {
                if (isInPipMode) return@pointerInput
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { onTogglePlayPause() }
                )
            }
            .pointerInput(isInPipMode, playerSize) {
                if (isInPipMode) return@pointerInput
                var gestureSide = SwipeGestureType.NONE
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val midpoint = (playerSize.width / 2f).coerceAtLeast(1f)
                        gestureSide = if (offset.x < midpoint) {
                            val winBright = activity?.window?.attributes?.screenBrightness ?: -1f
                            if (winBright in 0f..1f) brightnessLevel = winBright
                            SwipeGestureType.BRIGHTNESS
                        } else {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            volumeLevel = curVol.toFloat() / maxVol.toFloat()
                            SwipeGestureType.VOLUME
                        }
                        activeGesture = gestureSide
                    },
                    onDragEnd = { gestureSide = SwipeGestureType.NONE },
                    onDragCancel = { gestureSide = SwipeGestureType.NONE },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val heightPx = playerSize.height.coerceAtLeast(240).toFloat()
                        val delta = (-dragAmount / (heightPx * 0.78f))
                        when (gestureSide) {
                            SwipeGestureType.BRIGHTNESS -> {
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0.05f, 1.0f)
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = brightnessLevel
                                    win.attributes = lp
                                }
                                activeGesture = SwipeGestureType.BRIGHTNESS
                            }
                            SwipeGestureType.VOLUME -> {
                                volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val targetIndex = (volumeLevel * maxVol).roundToInt().coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
                                activeGesture = SwipeGestureType.VOLUME
                            }
                            SwipeGestureType.NONE -> Unit
                        }
                    }
                )
            }
    ) {
        // Pure Native Media3 ExoPlayer Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isInPipMode) return@Box

        // Clean Professional Loading Overlay (No technical jargon)
        if (playerState.status is PlaybackStatus.Resolving ||
            playerState.status is PlaybackStatus.Buffering ||
            playerState.status is PlaybackStatus.Reconnecting
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBackground.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(CinemaSurface.copy(alpha = 0.85f))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text(
                        text = when (playerState.status) {
                            is PlaybackStatus.Resolving -> strings.resolvingStream
                            is PlaybackStatus.Reconnecting -> strings.reconnecting
                            else -> strings.buffering
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Friendly Retry Overlay (No technical jargon)
        if (playerState.status is PlaybackStatus.Error) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBackground.copy(alpha = 0.92f))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SignalWifiStatusbarConnectedNoInternet4,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = strings.streamErrorTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = strings.streamErrorSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onAutoRefreshStream,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = CinemaBackground
                        ),
                        modifier = Modifier.testTag("player_retry_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.retryButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Swipe Gesture HUD Pill (Brightness Left, Volume Right)
        AnimatedVisibility(
            visible = activeGesture != SwipeGestureType.NONE,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isBrightness = activeGesture == SwipeGestureType.BRIGHTNESS
            val level = if (isBrightness) brightnessLevel else volumeLevel
            val percent = (level * 100f).roundToInt().coerceIn(0, 100)

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = HudPillBackground,
                modifier = Modifier
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .testTag(if (isBrightness) "brightness_hud" else "volume_hud")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = when {
                            isBrightness -> Icons.Rounded.Brightness6
                            percent == 0 -> Icons.Rounded.VolumeOff
                            else -> Icons.Rounded.VolumeUp
                        },
                        contentDescription = if (isBrightness) strings.brightnessLabel else strings.volumeLabel,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(116.dp)
                        ) {
                            Text(
                                text = if (isBrightness) strings.brightnessLabel else strings.volumeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "$percent%",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = ElectricCyan
                            )
                        }
                        LinearProgressIndicator(
                            progress = { level },
                            color = ElectricCyan,
                            trackColor = CinemaSurfaceHigh,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier
                                .width(116.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        // Cinema Player Control Overlay (Auto-Hiding; always hidden in mini PiP popup view)
        AnimatedVisibility(
            visible = !isInPipMode && controlsVisible && playerState.status !is PlaybackStatus.Error,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to PlayerScrim,
                            0.32f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1.0f to PlayerScrim
                        )
                    )
            ) {
                // Top Bar: LIVE + Channel Name + Clean Refresh Pill + Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LivePulseBadge(label = if (isLiveBroadcast) strings.liveBadge else strings.freeBadge)

                    Text(
                        text = playbackTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CinemaSurfaceHigh.copy(alpha = 0.8f))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .clickable { onAutoRefreshStream() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("player_refresh_stream_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh live broadcast",
                            tint = ElectricCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = strings.refreshStreamBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = onClosePlayer,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("close_player_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = strings.closeButton,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Center Play / Pause (+ 10s Rewind/Forward for Movies & Dramas)
                if (playerState.status !is PlaybackStatus.Resolving &&
                    playerState.status !is PlaybackStatus.Buffering &&
                    playerState.status !is PlaybackStatus.Reconnecting
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (!isLiveBroadcast) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(CinemaBackground.copy(alpha = 0.62f))
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), CircleShape)
                                    .clickable {
                                        val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                        exoPlayer.seekTo(target)
                                        vodPositionMs = target
                                    }
                                    .testTag("player_rewind_10_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay10,
                                    contentDescription = "Rewind 10 seconds",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(CinemaBackground.copy(alpha = 0.68f))
                                .border(1.5.dp, ElectricCyan.copy(alpha = 0.7f), CircleShape)
                                .clickable { onTogglePlayPause() }
                                .testTag("player_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play or pause video",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        if (!isLiveBroadcast) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(CinemaBackground.copy(alpha = 0.62f))
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), CircleShape)
                                    .clickable {
                                        val dur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                        val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                        exoPlayer.seekTo(target)
                                        vodPositionMs = target
                                    }
                                    .testTag("player_forward_10_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forward10,
                                    contentDescription = "Forward 10 seconds",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Control Area: Optional VOD Seek Bar + Swipe Hint + Mute + Quality + PiP + Fullscreen
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    if (!isLiveBroadcast && vodDurationMs > 0L) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = formatVodTime(vodPositionMs),
                                fontFamily = OutfitFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Slider(
                                value = vodPositionMs.toFloat().coerceIn(0f, vodDurationMs.toFloat()),
                                onValueChange = { nextPos ->
                                    isScrubbing = true
                                    vodPositionMs = nextPos.toLong()
                                },
                                onValueChangeFinished = {
                                    exoPlayer.seekTo(vodPositionMs)
                                    isScrubbing = false
                                },
                                valueRange = 0f..vodDurationMs.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = ElectricCyan,
                                    activeTrackColor = ElectricCyan,
                                    inactiveTrackColor = TextPrimary.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .testTag("vod_seek_slider")
                            )
                            Text(
                                text = formatVodTime(vodDurationMs),
                                fontFamily = OutfitFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.swipeHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 6.dp)
                        )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("player_mute_button")
                        ) {
                            Icon(
                                imageVector = if (playerState.isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                contentDescription = "Mute or unmute audio",
                                tint = if (playerState.isMuted) LiveCrimson else TextPrimary,
                                modifier = Modifier.size(21.dp)
                            )
                        }

                        val qualityText = remember(selectedQuality, playerState.activeVideoHeightPx) {
                            if (selectedQuality == VideoQuality.AUTO && playerState.activeVideoHeightPx > 0) {
                                "${playerState.activeVideoHeightPx}p"
                            } else {
                                selectedQuality.badgeLabel
                            }
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaSurfaceHigh.copy(alpha = 0.88f))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable { showQualityDialog = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("player_quality_settings_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Video quality settings",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = qualityText,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = onEnterPip,
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureInPictureAlt,
                                contentDescription = "Picture-in-Picture mode",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("player_fullscreen_button")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = "Toggle fullscreen",
                                tint = ElectricCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        QualityPickerDialog(
            strings = strings,
            selectedQuality = selectedQuality,
            activeVideoHeightPx = playerState.activeVideoHeightPx,
            onDismiss = { showQualityDialog = false },
            onSelectQuality = { quality ->
                onSelectQuality(quality)
                showQualityDialog = false
            }
        )
    }
}

@Composable
private fun LivePulseBadge(label: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_dot_alpha"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LiveCrimson.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(Color.White)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun QualityPickerDialog(
    strings: AppStrings,
    selectedQuality: VideoQuality,
    activeVideoHeightPx: Int,
    onDismiss: () -> Unit,
    onSelectQuality: (VideoQuality) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CinemaSurface,
        titleContentColor = TextPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.HighQuality,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Text(
                    text = strings.qualityTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.entries.forEach { quality ->
                    val isSelected = selectedQuality == quality
                    val suffix = if (quality == VideoQuality.AUTO && activeVideoHeightPx > 0) {
                        " (${activeVideoHeightPx}p)"
                    } else {
                        ""
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) ElectricBlue.copy(alpha = 0.25f) else CinemaBackground)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) ElectricCyan else CinemaBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectQuality(quality) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("quality_option_${quality.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${quality.title(strings)}$suffix",
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closeButton, color = ElectricCyan)
            }
        }
    )
}

private fun formatVodTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
