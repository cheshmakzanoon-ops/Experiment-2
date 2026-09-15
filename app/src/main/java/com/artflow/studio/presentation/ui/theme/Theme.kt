package com.artflow.studio.presentation.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark color scheme for ArtFlow - optimized for creative work
 */
private val DarkColorScheme = darkColorScheme(
    primary = ArtFlowPrimary,
    onPrimary = Color.White,
    primaryContainer = ArtFlowPrimaryVariant,
    onPrimaryContainer = Color.White,
    
    secondary = ArtFlowSecondary,
    onSecondary = Color.White,
    secondaryContainer = ArtFlowSecondaryVariant,
    onSecondaryContainer = Color.White,
    
    tertiary = ArtFlowSecondary,
    onTertiary = Color.White,
    tertiaryContainer = ArtFlowSecondaryVariant,
    onTertiaryContainer = Color.White,
    
    background = ArtFlowBackground,
    onBackground = TextPrimary,
    
    surface = ArtFlowSurface,
    onSurface = TextPrimary,
    surfaceVariant = ArtFlowSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    
    error = Error,
    onError = Color.White,
    
    outline = Color.Gray
)

/**
 * Light color scheme for ArtFlow
 */
private val LightColorScheme = lightColorScheme(
    primary = ArtFlowPrimary,
    onPrimary = Color.White,
    primaryContainer = ArtFlowPrimaryLight,
    onPrimaryContainer = Color.Black,
    
    secondary = ArtFlowSecondary,
    onSecondary = Color.White,
    secondaryContainer = ArtFlowSecondaryVariant,
    onSecondaryContainer = Color.White,
    
    tertiary = ArtFlowSecondary,
    onTertiary = Color.White,
    tertiaryContainer = ArtFlowSecondaryVariant,
    onTertiaryContainer = Color.White,
    
    background = CanvasWhite,
    onBackground = Color.Black,
    
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.LightGray,
    onSurfaceVariant = Color.DarkGray,
    
    error = Error,
    onError = Color.White,
    
    outline = Color.Gray
)

/**
 * ArtFlow Theme composable
 * Provides Material 3 theming with custom colors and typography
 */
@Composable
fun ArtFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic colors for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
