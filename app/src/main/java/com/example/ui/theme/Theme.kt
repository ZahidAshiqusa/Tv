package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val ZaidDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = CinemaBackground,
    primaryContainer = ElectricBlueDeep,
    onPrimaryContainer = TextPrimary,
    secondary = ElectricBlue,
    onSecondary = TextPrimary,
    secondaryContainer = CinemaSurfaceHigh,
    onSecondaryContainer = TextPrimary,
    tertiary = LiveCrimson,
    onTertiary = TextPrimary,
    background = CinemaBackground,
    onBackground = TextPrimary,
    surface = CinemaSurface,
    onSurface = TextPrimary,
    surfaceVariant = CinemaSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = CinemaBackground,
    surfaceContainerLow = CinemaSurface,
    surfaceContainer = CinemaSurface,
    surfaceContainerHigh = CinemaSurfaceHigh,
    surfaceContainerHighest = CinemaSurfaceHighest,
    outline = CinemaBorder,
    outlineVariant = CinemaSurfaceHigh,
    error = LiveCrimson,
    onError = TextPrimary
)

private val ZaidShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ZaidLiveTvTheme(
    isUrdu: Boolean = false,
    content: @Composable () -> Unit
) {
    val typography = remember(isUrdu) { createZaidTypography(isUrdu) }
    val layoutDirection = if (isUrdu) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = ZaidDarkColorScheme,
            typography = typography,
            shapes = ZaidShapes,
            content = content
        )
    }
}
