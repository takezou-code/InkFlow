package com.vic.inkflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The product's colour schemes, in one place.
 *
 * ## Why this moved out of the tablet's `Theme.kt`
 *
 * The desktop used to carry its own `InkColors` object and its own
 * `lightColorScheme`/`darkColorScheme`, under a comment claiming the palette was
 * "shared with the Android tablet build, so the two halves of the product read as
 * one app rather than two". It was not shared. Comparing the two:
 *
 * | role        | tablet            | desktop (before) |
 * |-------------|-------------------|------------------|
 * | primary     | `BrandIndigo` #6366F1 | `Indigo500` #5B6CFF |
 * | secondary   | `BrandPurple` #8B5CF6 | `Violet400` #A78BFA |
 * | background  | #0B0F1E (near-neutral) | `Night0` #070B14 (blue navy) |
 * | surface     | #18181B (zinc)    | `Night1` #0D1424   |
 * | surfaceVariant | #27272A       | `Night2` #141D33   |
 *
 * So the two apps did not merely differ in shade, they differed in hue family: the
 * tablet is a near-neutral charcoal with indigo accents, the desktop a blue navy.
 * Every surface, every card and the aurora backdrop were built on that difference,
 * which is a large part of why the desktop read as a different product rather than
 * the same one.
 *
 * Copying the tablet's numbers into the desktop would fix today's pixels and
 * reinstate tomorrow's drift — which is exactly what happened with the glass tokens
 * two commits ago, and what the second copy of `GlassVeilDark` cost. So the schemes
 * themselves live here and both apps consume them.
 *
 * The values are the tablet's, unchanged.
 */
val InkDarkScheme: ColorScheme = darkColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = BrandPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF06B6D4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = Color(0xFF0B0F1E),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF27272A),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = BrandIndigo,
    inverseSurface = Color(0xFFF8FAFC),
    inverseOnSurface = Color(0xFF18181B),
    inversePrimary = Color(0xFF818CF8),
    surfaceDim = Color(0xFF0B0F1E),
    surfaceBright = Color(0xFF27272A),
    surfaceContainerLowest = Color(0xFF0B0F1E),
    surfaceContainerLow = Color(0xFF18181B),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF27272A),
    surfaceContainerHighest = Color(0xFF3F3F46),
    outline = Slate600,
    outlineVariant = Slate700,
    scrim = Color(0xFF000000),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
)

val InkLightScheme: ColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = BrandPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF06B6D4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceTint = BrandIndigo,
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF818CF8),
    surfaceDim = Color(0xFFF1F5F9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    scrim = Color(0xFF000000),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
)