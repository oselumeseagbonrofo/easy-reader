package com.ebookreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ebookreader.app.domain.model.ReaderTheme

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF8C4A27),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCCB),
    onPrimaryContainer = Color(0xFF321200),
    secondary = Color(0xFF6F5B4E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADECE),
    onSecondaryContainer = Color(0xFF271910),
    tertiary = Color(0xFF5D6237),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E7B1),
    onTertiaryContainer = Color(0xFF1A1E00),
    background = Color(0xFFFAF6F0),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF2DFD3),
    onSurfaceVariant = Color(0xFF51443C),
    outline = Color(0xFF83746B)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB690),
    onPrimary = Color(0xFF532203),
    primaryContainer = Color(0xFF6F3615),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFDDC1B1),
    onSecondary = Color(0xFF3E2D22),
    secondaryContainer = Color(0xFF564337),
    onSecondaryContainer = Color(0xFFFADECE),
    tertiary = Color(0xFFC6CB97),
    onTertiary = Color(0xFF2F330D),
    tertiaryContainer = Color(0xFF454A21),
    onTertiaryContainer = Color(0xFFE2E7B1),
    background = Color(0xFF161311),
    onBackground = Color(0xFFEDE0DA),
    surface = Color(0xFF201B18),
    onSurface = Color(0xFFEDE0DA),
    surfaceVariant = Color(0xFF51443C),
    onSurfaceVariant = Color(0xFFD5C3B8),
    outline = Color(0xFF9E8E83)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Immutable
data class ReaderPalette(
    val background: Color,
    val onBackground: Color,
    val subtleText: Color,
    val hudContainer: Color,
    val onHudContainer: Color,
    val accent: Color,
    val searchHighlight: Color,
    val activeSearchHighlight: Color,
    val isDark: Boolean
)

fun readerPaletteFor(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.PAPER -> ReaderPalette(
        background = Color(0xFFFAF8F4),
        onBackground = Color(0xFF221E1B),
        subtleText = Color(0xFF797067),
        hudContainer = Color(0xF5FFFDF9),
        onHudContainer = Color(0xFF201A17),
        accent = Color(0xFF8C4A27),
        searchHighlight = Color(0x66FACC15),
        activeSearchHighlight = Color(0xB3F59E0B),
        isDark = false
    )
    ReaderTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF3EAD6),
        onBackground = Color(0xFF3A2E21),
        subtleText = Color(0xFF7C6954),
        hudContainer = Color(0xF5FAF2E1),
        onHudContainer = Color(0xFF362A1E),
        accent = Color(0xFF9A4D22),
        searchHighlight = Color(0x66EAB308),
        activeSearchHighlight = Color(0xB3D97706),
        isDark = false
    )
    ReaderTheme.DARK -> ReaderPalette(
        background = Color(0xFF191B20),
        onBackground = Color(0xFFE4E4E7),
        subtleText = Color(0xFF9CA3AF),
        hudContainer = Color(0xF224272F),
        onHudContainer = Color(0xFFF3F4F6),
        accent = Color(0xFFFBBF24),
        searchHighlight = Color(0x55FACC15),
        activeSearchHighlight = Color(0x99F59E0B),
        isDark = true
    )
    ReaderTheme.AMOLED -> ReaderPalette(
        background = Color(0xFF000000),
        onBackground = Color(0xFFD4D4D8),
        subtleText = Color(0xFF71717A),
        hudContainer = Color(0xF2141417),
        onHudContainer = Color(0xFFE4E4E7),
        accent = Color(0xFFF59E0B),
        searchHighlight = Color(0x55EAB308),
        activeSearchHighlight = Color(0x99F59E0B),
        isDark = true
    )
}

@Composable
fun EasyReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
