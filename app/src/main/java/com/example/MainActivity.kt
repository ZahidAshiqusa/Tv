package com.example

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localization.AppLanguage
import com.example.ui.MainViewModel
import com.example.ui.ZaidLiveTvScreen
import com.example.ui.theme.ZaidLiveTvTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_PLAY) {
                viewModel.playerController.togglePlayPause()
                updateAutoPipParams()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_TOGGLE_PLAY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val hiddenFetcherTask by viewModel.hiddenFetcherTask.collectAsStateWithLifecycle()
            val hiddenCatalogTask by viewModel.hiddenCatalogTask.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.isFullscreen) {
                applyFullscreenMode(uiState.isFullscreen)
            }

            // Keep Android 12+ auto-PiP parameters synced whenever a channel/video is open
            LaunchedEffect(
                uiState.activePlayback?.id,
                uiState.playerState.isPlaying,
                uiState.preferences.initialChannelsUpdated
            ) {
                updateAutoPipParams()
            }

            ZaidLiveTvTheme(isUrdu = uiState.preferences.language == AppLanguage.URDU) {
                ZaidLiveTvScreen(
                    uiState = uiState,
                    hiddenFetcherTask = hiddenFetcherTask,
                    hiddenCatalogTask = hiddenCatalogTask,
                    exoPlayer = viewModel.playerController.exoPlayer,
                    onSelectDestination = viewModel::selectDestination,
                    onSelectShowsSubFilter = viewModel::setShowsSubFilter,
                    onSelectWrestlingSubFilter = viewModel::setWrestlingSubFilter,
                    onSelectChannel = viewModel::selectChannel,
                    onSelectVodItem = viewModel::selectVodItem,
                    onClosePlayer = viewModel::closePlayer,
                    onCategoryChange = viewModel::setCategory,
                    onSearchChange = viewModel::setSearchQuery,
                    onToggleFavoritesFilter = viewModel::toggleFavoritesFilter,
                    onToggleChannelFavorite = viewModel::toggleChannelFavorite,
                    onToggleVodFavorite = viewModel::toggleVodFavorite,
                    onToggleLanguage = viewModel::toggleLanguage,
                    onSelectLanguage = viewModel::setLanguage,
                    onUpdateAllChannels = viewModel::updateAllChannelsAndIcons,
                    onRefreshCatalog = viewModel::refreshCatalogNow,
                    onTogglePlayPause = viewModel.playerController::togglePlayPause,
                    onToggleMute = viewModel.playerController::toggleMute,
                    onSelectQuality = viewModel::selectQuality,
                    onToggleFullscreen = { viewModel.setFullscreen(!uiState.isFullscreen) },
                    onEnterPip = { enterLivePipMode(showErrorToast = true) },
                    onAutoRefreshStream = viewModel::refreshActiveStreamNow,
                    onOpenDeveloperSheet = viewModel::openDeveloperSheet,
                    onConsumeSnackbar = viewModel::consumeSnackbar
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    private fun applyFullscreenMode(fullscreen: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun shouldAutoEnterPip(): Boolean {
        val state = viewModel.uiState.value
        return state.preferences.initialChannelsUpdated && state.activePlayback != null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val isPlaying = viewModel.playerController.state.value.isPlaying
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_PIP_TOGGLE,
            Intent(ACTION_PIP_TOGGLE_PLAY).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (isPlaying) "Pause" else "Play"
        val playPauseAction = RemoteAction(
            Icon.createWithResource(this, iconRes),
            label,
            label,
            playPauseIntent
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(playPauseAction))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updateAutoPipParams() {
        if (!supportsPip()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setPictureInPictureParams(buildPipParams(autoEnter = shouldAutoEnterPip()))
            }
        }
    }

    private fun enterLivePipMode(showErrorToast: Boolean = false) {
        if (!supportsPip()) {
            if (showErrorToast) {
                Toast.makeText(this, "Mini popup player is not supported on this device", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(buildPipParams(autoEnter = true))
            }.onFailure {
                if (showErrorToast) {
                    Toast.makeText(this, "Mini popup player is not available right now", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Automatically launch floating mini popup player when user minimizes or presses Home
        // while any live channel, drama, movie, or wrestling video is open
        if (shouldAutoEnterPip() && !isInPictureInPictureMode) {
            enterLivePipMode(showErrorToast = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setInPipMode(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            updateAutoPipParams()
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep video playing continuously inside the floating PiP mini viewer;
        // pause only when the user actually closes the PiP popup or leaves without active PiP
        if (!isInPictureInPictureMode) {
            viewModel.playerController.pauseForBackground()
        }
    }

    companion object {
        private const val ACTION_PIP_TOGGLE_PLAY = "com.example.zaidlivetv.ACTION_PIP_TOGGLE_PLAY"
        private const val REQUEST_CODE_PIP_TOGGLE = 9101
    }
}
