package app.marlboroadvance.mpvex.shiroikuma

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified

/**
 * Builds the live Material scheme and typography from [ShiroikumaUiPrefs], so every knob on the
 * 白い熊 mpv拡張 UI page takes effect the moment it is moved.
 *
 * Two rules keep the black-yellow look clean, and both are easy to undo by accident:
 *  - every `*Container` role is a FLAT surface colour, never a low-alpha accent — alpha yellow
 *    composited over black reads as olive;
 *  - [ColorScheme.surfaceTint] is transparent, so Material's tonal-elevation overlay never pulls a
 *    surface back toward the accent.
 *
 * Alpha yellow IS used for outlines and the seekbar track: as a stroke over black that is the
 * intended dim-yellow divider, not a muddy fill.
 */
fun shiroikumaColorScheme(p: ShiroikumaUiPrefs): ColorScheme {
    val background = Color(p.background)
    val surface = Color(p.surface)
    val text = Color(p.text)
    val textSecondary = Color(p.textSecondary)
    val accent = Color(p.accent)
    val border = Color(p.border)
    val divider = Color(p.divider)
    val error = Color(p.errorColor)
    val onAccent = accent.contrastingOnColor()

    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = surface,
        onSecondaryContainer = text,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = surface,
        onTertiaryContainer = text,
        error = error,
        onError = background,
        errorContainer = surface,
        onErrorContainer = error,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        outline = border,
        outlineVariant = divider,
        scrim = Color(0xFF000000),
        inverseSurface = text,
        inverseOnSurface = background,
        inversePrimary = background,
        surfaceTint = Color.Transparent,
        surfaceDim = background,
        surfaceBright = surface,
        surfaceContainerLowest = background,
        surfaceContainerLow = background,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
    )
}

/**
 * Applies the page's font, weight and scale on top of the app's base typography. A null
 * [ShiroikumaUiStore.fontFamily] leaves each style's own family alone; weight 0 leaves each style's
 * own weight alone, so "App default / 0 / 100%" is a true no-op.
 */
fun shiroikumaTypography(base: Typography, p: ShiroikumaUiPrefs): Typography {
    val family = ShiroikumaUiStore.fontFamily(p.fontFileName)
    val weight = p.fontWeight.takeIf { it in 100..900 }?.let(::FontWeight)
    val scale = p.fontScalePct / 100f
    if (family == null && weight == null && scale == 1f) return base

    fun restyle(style: TextStyle) = style.copy(
        fontFamily = family ?: style.fontFamily,
        fontWeight = weight ?: style.fontWeight,
        fontSize = if (style.fontSize.isSpecified) style.fontSize * scale else style.fontSize,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
    )

    return Typography(
        displayLarge = restyle(base.displayLarge),
        displayMedium = restyle(base.displayMedium),
        displaySmall = restyle(base.displaySmall),
        headlineLarge = restyle(base.headlineLarge),
        headlineMedium = restyle(base.headlineMedium),
        headlineSmall = restyle(base.headlineSmall),
        titleLarge = restyle(base.titleLarge),
        titleMedium = restyle(base.titleMedium),
        titleSmall = restyle(base.titleSmall),
        bodyLarge = restyle(base.bodyLarge),
        bodyMedium = restyle(base.bodyMedium),
        bodySmall = restyle(base.bodySmall),
        labelLarge = restyle(base.labelLarge),
        labelMedium = restyle(base.labelMedium),
        labelSmall = restyle(base.labelSmall),
    )
}

/** Black or white, whichever reads better on [this] — used for onPrimary against the accent. */
fun Color.contrastingOnColor(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.6f) Color(0xFF000000) else Color(0xFFFFFFFF)
}

/** The font family the page itself should render body text in. */
fun ShiroikumaUiPrefs.family(): FontFamily? = ShiroikumaUiStore.fontFamily(fontFileName)
