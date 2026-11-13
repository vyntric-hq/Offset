/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyntric.offset.R

/** Colour tokens. Neutral grays, one accent used sparingly. */
@Immutable
data class OffsetColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val destructive: Color,
    val success: Color,
    val warning: Color,
    val fill: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val chrome: Color,
    val isDark: Boolean,
)

val Accent = Color(0xFF0A84FF)

private val LightColors = OffsetColors(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    accent = Accent,
    onAccent = Color.White,
    destructive = Color(0xFFFF3B30),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    fill = Color(0xFFE9E9EB),
    segmentTrack = Color(0x1F767680),
    segmentThumb = Color(0xFFFFFFFF),
    chrome = Color(0xF7F9F9F9),
    isDark = false,
)

private val DarkColors = OffsetColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceRaised = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    accent = Accent,
    onAccent = Color.White,
    destructive = Color(0xFFFF453A),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    fill = Color(0xFF39393D),
    segmentTrack = Color(0x3D767680),
    segmentThumb = Color(0xFF636366),
    chrome = Color(0xF7161616),
    isDark = true,
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Type scale: 17sp body, large bold titles. */
@Immutable
data class OffsetType(
    val largeTitle: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.2.sp),
    val title: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    val headline: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp),
    val callout: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp),
    val subhead: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    val footnote: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    val caption: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp),
    val mono: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum"),
)

/** Spacing and shape tokens. */
object Dimens {
    val gutter = 16.dp
    val groupCorner = 12.dp
    val rowMinHeight = 48.dp
    val sectionGap = 24.dp
    val hairline = 0.5.dp
    val sheetCorner = 14.dp
    val buttonHeight = 50.dp
}

val LocalOffsetColors = staticCompositionLocalOf { LightColors }
val LocalOffsetType = staticCompositionLocalOf { OffsetType() }
val LocalHapticsEnabled = staticCompositionLocalOf { true }

object OffsetTheme {
    val colors: OffsetColors @Composable get() = LocalOffsetColors.current
    val type: OffsetType @Composable get() = LocalOffsetType.current
}

@Composable
fun OffsetTheme(dark: Boolean, haptics: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val type = OffsetType()
    // Material 3 components underneath get the same neutral palette and Inter, never the default purple.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent,
            background = colors.background, onBackground = colors.label, surface = colors.surface,
            onSurface = colors.label, surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.secondaryLabel,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceRaised, surfaceContainerLow = colors.surface,
            surfaceContainerLowest = colors.background, surfaceContainerHighest = colors.surfaceRaised,
            outline = colors.separator, outlineVariant = colors.separator, error = colors.destructive,
            primaryContainer = colors.accent, onPrimaryContainer = colors.onAccent, secondaryContainer = colors.fill,
            onSecondaryContainer = colors.label, tertiary = colors.accent,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent,
            background = colors.background, onBackground = colors.label, surface = colors.surface,
            onSurface = colors.label, surfaceVariant = colors.background, onSurfaceVariant = colors.secondaryLabel,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.surface, surfaceContainerLow = colors.surface,
            surfaceContainerLowest = colors.surface, surfaceContainerHighest = colors.surface,
            outline = colors.separator, outlineVariant = colors.separator, error = colors.destructive,
            primaryContainer = colors.accent, onPrimaryContainer = colors.onAccent, secondaryContainer = colors.fill,
            onSecondaryContainer = colors.label, tertiary = colors.accent,
        )
    }
    val typography = Typography(
        bodyLarge = type.body, bodyMedium = type.subhead, bodySmall = type.footnote,
        titleLarge = type.title, titleMedium = type.headline, titleSmall = type.subhead,
        labelLarge = type.headline, labelMedium = type.footnote, labelSmall = type.caption,
        headlineLarge = type.largeTitle, headlineMedium = type.title, headlineSmall = type.title,
    )
    CompositionLocalProvider(
        LocalOffsetColors provides colors,
        LocalOffsetType provides type,
        LocalHapticsEnabled provides haptics,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
