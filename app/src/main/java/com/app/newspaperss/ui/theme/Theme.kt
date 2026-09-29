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

private val light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    background = Newsprint, onBackground = Ink,
    surface = Newsprint, onSurface = Ink,
    surfaceVariant = Color(0xFFECE7DA), onSurfaceVariant = Color(0xFF4A4740),
)

private val dark = darkColorScheme(
    primary = Color(0xFFE8A594), onPrimary = Color(0xFF3B0D02),
    background = Color(0xFF161514), onBackground = Color(0xFFE9E4D8),
    surface = Color(0xFF161514), onSurface = Color(0xFFE9E4D8),
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
