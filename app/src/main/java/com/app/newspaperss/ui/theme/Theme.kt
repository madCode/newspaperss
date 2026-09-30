package com.app.newspaperss.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val Ink = Color(0xFF1C1B1A)
private val Newsprint = Color(0xFFF7F4EC)
private val Accent = Color(0xFF8C2F1B)

// Every container role is set explicitly: Material's defaults are lavender,
// which fights the newsprint palette.
private val light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFF3D9CF), onPrimaryContainer = Color(0xFF3B0D02),
    secondary = Color(0xFF5E5A50), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DDC8), onSecondaryContainer = Ink,
    background = Newsprint, onBackground = Ink,
    surface = Newsprint, onSurface = Ink,
    surfaceVariant = Color(0xFFECE7DA), onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFF3EFE4),
    surfaceContainer = Color(0xFFEFEADD),
    surfaceContainerHigh = Color(0xFFEAE4D5),
    surfaceContainerHighest = Color(0xFFE4DDCC),
    // Dark enough to see on e-ink: outlined buttons and dividers use it, and at the old #D3CCBC an
    // outlined button on a card was a 1.2:1 line, so it read as plain text.
    outline = Color(0xFF8A8477), outlineVariant = Color(0xFF948D80),
)

private val dark = darkColorScheme(
    primary = Color(0xFFE8A594), onPrimary = Color(0xFF3B0D02),
    primaryContainer = Color(0xFF5C2112), onPrimaryContainer = Color(0xFFF3D9CF),
    secondary = Color(0xFFCFC6B4), onSecondary = Color(0xFF2B2821),
    secondaryContainer = Color(0xFF3D3930), onSecondaryContainer = Color(0xFFE9E4D8),
    background = Color(0xFF161514), onBackground = Color(0xFFE9E4D8),
    surface = Color(0xFF161514), onSurface = Color(0xFFE9E4D8),
    surfaceVariant = Color(0xFF2E2C28), onSurfaceVariant = Color(0xFFCBC5B8),
    surfaceContainerLowest = Color(0xFF100F0E),
    surfaceContainerLow = Color(0xFF1C1B19),
    surfaceContainer = Color(0xFF211F1D),
    surfaceContainerHigh = Color(0xFF2B2926),
    surfaceContainerHighest = Color(0xFF363330),
    outline = Color(0xFF948E82), outlineVariant = Color(0xFF7A746A),
)

// Serif display and titles give the app its newspaper feel; body text stays sans for UI legibility.
private val typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun NewspaperssTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = typography, content = content)
}
