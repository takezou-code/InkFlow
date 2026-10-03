package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.ui.theme.GlassTintDark
import com.vic.inkflow.ui.theme.GlassTintLight
import com.vic.inkflow.ui.theme.GlassVeilDark
import com.vic.inkflow.ui.theme.GlassVeilLight
import com.vic.inkflow.ui.theme.PaperInkColor
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl

/**
 * Palette shared with the Android tablet build, so the two halves of the product
 * read as one app rather than two.
 *
 * The desktop used to be a generic M3 slate/blue scheme. The tablet is deep navy
 * with indigo-violet accents, so that is what this mirrors: dark is the default
 * because the desktop is a reading client and the page should dominate.
 */
private object InkColors {
    // Deep navy backgrounds (tablet "墨夜" family)
    val Night0 = Color(0xFF070B14)
    val Night1 = Color(0xFF0D1424)
    val Night2 = Color(0xFF141D33)
    val Night3 = Color(0xFF1D2942)

    // Indigo / violet accents (tablet BrandIndigo family)
    val Indigo200 = Color(0xFFB7C4FF)
    val Indigo300 = Color(0xFF93A3FF)
    val Indigo400 = Color(0xFF7488FF)
    val Indigo500 = Color(0xFF5B6CFF)
    val Indigo700 = Color(0xFF3A45C4)
    val Violet400 = Color(0xFFA78BFA)

    // Light surfaces
    val Paper0 = Color(0xFFF7F8FC)
    val Paper1 = Color(0xFFFFFFFF)
    val PaperEdge = Color(0xFFE3E7F0)
    val Ink = Color(0xFF121826)
    val InkSoft = Color(0xFF5A6579)
}

// Corner radii (ShapeSm/Md/Lg/Xl) and the glass colour tokens — GlassVeil*,
// GlassTint*, PaperInkColor — are imported from `:shared` rather than restated
// here.
//
// They used to be copied in, under a comment claiming they were "copied verbatim
// from the tablet's theme/Color.kt". Two of them had already drifted, which is
// exactly the failure mode a second copy invites:
//
//   GlassVeilDark  desktop 0x730F172A (45%)  vs  tablet 0x660F172A (40%)
//   PaperInkColor  desktop 0xFF121826       vs  tablet 0xFF1E293B
//
// The tablet had tuned both by eye against its real blur-based glass and recorded
// why in Color.kt; the desktop kept the superseded numbers, so its glass was
// reading heavier and darker than the product it was imitating. Importing makes
// that class of drift impossible rather than merely noticed.

private val LightInkScheme: ColorScheme = lightColorScheme(
    primary = InkColors.Indigo700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF1B2170),
    secondary = InkColors.Violet400,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE6FF),
    onSecondaryContainer = Color(0xFF2A1B52),
    tertiary = Color(0xFF2BA6A0),
    background = InkColors.Paper0,
    onBackground = InkColors.Ink,
    surface = InkColors.Paper1,
    onSurface = InkColors.Ink,
    surfaceVariant = Color(0xFFEFF1F7),
    onSurfaceVariant = InkColors.InkSoft,
    outline = Color(0xFFC3CAD8),
    outlineVariant = Color(0xFFE3E7F0)
)

private val DarkInkScheme: ColorScheme = darkColorScheme(
    primary = InkColors.Indigo300,
    onPrimary = Color(0xFF10163A),
    primaryContainer = InkColors.Indigo700,
    onPrimaryContainer = Color(0xFFE0E5FF),
    secondary = InkColors.Violet400,
    onSecondary = Color(0xFF1E1233),
    secondaryContainer = Color(0xFF3B2D63),
    onSecondaryContainer = Color(0xFFEBE1FF),
    tertiary = Color(0xFF6FD8CF),
    background = InkColors.Night0,
    onBackground = Color(0xFFE8ECF6),
    surface = InkColors.Night1,
    onSurface = Color(0xFFE8ECF6),
    surfaceVariant = InkColors.Night2,
    onSurfaceVariant = Color(0xFFB6C0D6),
    outline = Color(0xFF3A4763),
    outlineVariant = Color(0xFF232F49)
)

/** Document cards use 12dp rounded corners; pills are fully rounded. */
val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val base = Typography()

val InkTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium)
)

/** Global dark-mode toggle (defaults to dark, per the reading-scenario spec). */
object InkThemeState {
    var darkMode by mutableStateOf(true)
}

/** The aurora-like accent used for brand marks and the empty-state glyph. */
val AccentGradient: Brush
    @Composable get() = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary
        )
    )

/**
 * Filled pill used for chips, the sync button and the page navigator.
 *
 * A selected pill deliberately breaks from the glass material: it needs to read
 * as "armed", so it takes a tinted fill and a brighter rim instead of the neutral
 * translucent veil.
 */
@Composable
fun Modifier.glassPill(
    selected: Boolean = false,
    alpha: Float = 0.55f
): Modifier {
    val shape = RoundedCornerShape(50)
    val fill = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = alpha)
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    }
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, border, shape)
}

/** Decorative full-bleed background: vertical night gradient plus a soft accent bloom. */
@Composable
fun BoxScope.auroraBackdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        // Two offset blooms give the same "light behind glass" read as the tablet's
        // aurora scenes without shipping a shader.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            androidx.compose.ui.graphics.Color.Transparent
                        ),
                        radius = 900f
                    )
                )
        )
        content()
    }
}

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = InkThemeState.darkMode,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkInkScheme else LightInkScheme,
        typography = InkTypography,
        shapes = InkShapes,
        content = content
    )
}
