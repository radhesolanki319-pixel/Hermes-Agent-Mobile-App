package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// AMOLED "black + colourful" identity (user-chosen, 2026-11-17): pure black surfaces,
// violet primary, pink secondary, green tertiary, with gradient brushes for hero elements.
// PocketOrange keeps its historic name (164 call sites) but now holds the violet primary.
val PocketOrange = Color(0xFF8B5CF6)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF34D399)
val PocketBackground = Color(0xFF000000)
val PocketSurface = Color(0xFF0B0B10)
val PocketSurfaceVariant = Color(0xFF15151D)
val PocketOutline = Color(0xFF23232D)

val PocketGradientPrimary: Brush = Brush.linearGradient(
    listOf(Color(0xFF7C3AED), Color(0xFFDB2777)),
)
val PocketGradientUserBubble: Brush = Brush.linearGradient(
    listOf(Color(0xFF7C3AED), Color(0xFF2563EB)),
)
val PocketGradientRainbow: Brush = Brush.horizontalGradient(
    listOf(Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFF59E0B)),
)
val PocketGradientTeal: Brush = Brush.linearGradient(
    listOf(Color(0xFF0D9488), Color(0xFF2563EB)),
)

private val DarkColors = darkColorScheme(
    primary = PocketOrange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2E1065),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFEC4899),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3B1226),
    onSecondaryContainer = Color(0xFFFBCFE8),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF022C22),
    background = PocketBackground,
    onBackground = Color(0xFFF4F4F8),
    surface = PocketSurface,
    onSurface = Color(0xFFF4F4F8),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = Color(0xFFA6A6B8),
    outline = PocketOutline,
    outlineVariant = Color(0xFF2E2E3B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD85A20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE0D2),
    onPrimaryContainer = Color(0xFF451A08),
    secondary = Color(0xFF3366CC),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1B8A5A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEFF5),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFD8DEE4),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
