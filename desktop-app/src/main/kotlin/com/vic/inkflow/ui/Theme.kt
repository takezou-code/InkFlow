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
import com.vic.inkflow.ui.theme.InkDarkScheme
import com.vic.inkflow.ui.theme.InkLightScheme
import com.vic.inkflow.ui.theme.PaperInkColor
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl

/**
 * The product's palette comes from `:shared` — the tablet's own `InkDarkScheme` /
 * `InkLightScheme` — rather than from a set invented here.
 *
 * This file used to declare a private `InkColors` whose comment claimed the palette
 * was "shared with the Android tablet build, so the two halves of the product read
 * as one app rather than two". Comparing them, the desktop had drifted into a
 * different *hue family*, not merely a different shade: its background ramp was a
 * blue navy (`Night0` #070B14 … `Night3` #1D2942) where the tablet uses a
 * near-neutral charcoal (#0B0F1E / #18181B / #27272A), and its accent was
 * `Indigo500` #5B6CFF against the tablet's `BrandIndigo` #6366F1. Every surface,
 * card and backdrop was built on that difference.
 *
 * Copying the tablet's numbers across would have fixed today's pixels and
 * reinstated tomorrow's drift — the same failure the second copy of `GlassVeilDark`
 * caused. So the schemes themselves are shared and this file has no palette left to
 * own. See the note on `InkDarkScheme` in `:shared`.
 *
 * The corner radii (ShapeSm/Md/Lg/Xl) and glass tokens (GlassVeil*, GlassTint*,
 * PaperInkColor) are likewise imported from `:shared`; those were copied in under a
 * comment claiming they were "copied verbatim from the tablet's theme/Color.kt",
 * and two of them had already drifted.
 */

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

private val LightInkScheme: ColorScheme = InkLightScheme

private val DarkInkScheme: ColorScheme = InkDarkScheme

/**
 * Corner radii for Material components — the tablet's scale, not a second one.
 *
 * These used to be 6/10/14/18/24dp, a set of their own, while the glass material in
 * `:shared` was already rounding panels with `ShapeLg` (24dp). That put two different
 * corner radii in a single window: a glass panel at 24dp next to an M3 card at 18dp,
 * with no seam between them. On the tablet both come from the same four tokens, which
 * is why its surfaces read as one material.
 *
 * The mapping below is the tablet's exactly, including `extraSmall` and `small`
 * sharing `ShapeSm` — that is not a typo, it is what makes small M3 controls (chips,
 * text-field outlines, menu items) land on the same radius as the shared scale rather
 * than inventing a fifth size.
 */
val InkShapes = Shapes(
    extraSmall = ShapeSm,
    small = ShapeSm,
    medium = ShapeMd,
    large = ShapeLg,
    extraLarge = ShapeXl
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

    /**
     * Backdrop intensity, using the tablet's four presets.
     *
     * SOFT is pixel-identical to the tablet's own default, so it is the right
     * starting point: if the desktop looks wrong here, the cause is not the
     * backdrop tuning.
     */
    var backdropTheme by mutableStateOf(BackdropTheme.SOFT)

    /** Human-facing labels, in the tablet's terms. */
    val backdropLabels: Map<BackdropTheme, String> = mapOf(
        BackdropTheme.SOFT to "柔光",
        BackdropTheme.VIVID to "熾霞",
        BackdropTheme.NIGHT to "墨夜",
        BackdropTheme.CLEAN to "素"
    )
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
