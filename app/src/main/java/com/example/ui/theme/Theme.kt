package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.domain.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = EmeraldOnPrimaryDark,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldOnContainerDark,
    secondary = CyanSecondaryDark,
    onSecondary = CyanOnSecondaryDark,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = CyanOnContainerDark,
    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = Color(0xFF151C2A),
    surfaceContainerHigh = ObsidianSurfaceVariant,
    surfaceContainerHighest = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = EmeraldOnPrimaryLight,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = EmeraldOnContainerLight,
    secondary = CyanSecondaryLight,
    onSecondary = CyanOnSecondaryLight,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = CyanOnContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    background = StudioBackground,
    onBackground = TextPrimaryLight,
    surface = StudioSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = StudioSurface,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = StudioSurfaceVariant,
    surfaceContainerHighest = StudioSurfaceHigh,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Immutable
data class StatusColors(
    val connected: Color,
    val tunnel: Color,
    val warning: Color,
    val disconnected: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        connected = EmeraldPrimaryDark,
        tunnel = CyanSecondaryDark,
        warning = AmberTertiaryDark,
        disconnected = TextSecondaryDark,
    )
}

@Composable
fun OpenCodeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val statusColors = if (darkTheme) {
        StatusColors(
            connected = EmeraldPrimaryDark,
            tunnel = CyanSecondaryDark,
            warning = AmberTertiaryDark,
            disconnected = TextSecondaryDark,
        )
    } else {
        StatusColors(
            connected = EmeraldPrimaryLight,
            tunnel = CyanSecondaryLight,
            warning = AmberTertiaryLight,
            disconnected = TextSecondaryLight,
        )
    }

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenCodeTypography,
            shapes = OpenCodeShapes,
            content = content,
        )
    }
}
