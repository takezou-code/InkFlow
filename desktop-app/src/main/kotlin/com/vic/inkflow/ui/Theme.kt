package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import com.vic.inkflow.ui.theme.GlassTintDark
import com.vic.inkflow.ui.theme.inkTypography
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
import com.vic.inkflow.DesktopSettings

/**
 * The product's palette comes from `:shared`  ...  the tablet's own `InkDarkScheme` /
 * `InkLightScheme`  ...  rather than from a set invented here.
 *
 * This file used to declare a private `InkColors` whose comment claimed the palette
 * was "shared with the Android tablet build, so the two halves of the product read
 * as one app rather than two". Comparing them, the desktop had drifted into a
 * different *hue family*, not merely a different shade: its background ramp was a
 * blue navy (`Night0` #070B14  ...  `Night3` #1D2942) where the tablet uses a
 * near-neutral charcoal (#0B0F1E / #18181B / #27272A), and its accent was
 * `Indigo500` #5B6CFF against the tablet's `BrandIndigo` #6366F1. Every surface,
 * card and backdrop was built on that difference.
 *
 * Copying the tablet's numbers across would have fixed today's pixels and
 * reinstated tomorrow's drift  ...  the same failure the second copy of `GlassVeilDark`
 * caused. So the schemes themselves are shared and this file has no palette left to
 * own. See the note on `InkDarkScheme` in `:shared`.
 *
 * The corner radii (ShapeSm/Md/Lg/Xl) and glass tokens (GlassVeil*, GlassTint*,
 * PaperInkColor) are likewise imported from `:shared`; those were copied in under a
 * comment claiming they were "copied verbatim from the tablet's theme/Color.kt",
 * and two of them had already drifted.
 */

// Corner radii (ShapeSm/Md/Lg/Xl) and the glass colour tokens  ...  GlassVeil*,
// GlassTint*, PaperInkColor  ...  are imported from `:shared` rather than restated
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
 * Corner radii for Material components  ...  the tablet's scale, not a second one.
 *
 * These used to be 6/10/14/18/24dp, a set of their own, while the glass material in
 * `:shared` was already rounding panels with `ShapeLg` (24dp). That put two different
 * corner radii in a single window: a glass panel at 24dp next to an M3 card at 18dp,
 * with no seam between them. On the tablet both come from the same four tokens, which
 * is why its surfaces read as one material.
 *
 * The mapping below is the tablet's exactly, including `extraSmall` and `small`
 * sharing `ShapeSm`  ...  that is not a typo, it is what makes small M3 controls (chips,
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

/**
 * Inter, loaded the way Compose Desktop actually allows.
 *
 * `Font(path =  ... )` and `Font(file =  ... )` do not exist on this target  ...  checked against
 * the artifact rather than assumed: `androidx.compose.ui.text.font.FontKt` in
 * **ui-text-desktop** 1.12.1 declares only `Font(resId: Int,  ... )`. The desktop entry
 * point is a different symbol, `FontFamily_desktopKt.FontFamily(path: String)`, which
 * resolves the font from the jar's resources. Hence the file living at
 * `desktop-app/src/main/resources/fonts/inter_variable.ttf`  ...  same 856 KB binary the
 * tablet ships.
 *
 * The path form has no `variationSettings`, so the five Inter weights are selected by
 * synthesis rather than by axis. The *scale*  ...  sizes, weights, line heights, tracking  ... 
 * is the shared `:shared` `inkTypography` either way, and that is the part that has to
 * match for the two apps to read as one product.
 */
@OptIn(ExperimentalTextApi::class)
private val InterFamily = FontFamily("fonts/inter_variable.ttf")

val InkTypography = inkTypography(InterFamily)

/**
 * Global theme state.
 *
 * Reads are seeded from disk so a restart keeps the user's choice. Reads are also
 * defensive: the settings file is hand-editable and can be truncated, and failing
 * to open the app over a theme preference is a bad trade.
 */
object InkThemeState {
    /** Dark by default, per the reading-scenario spec. */
    var darkMode by mutableStateOf(
        runCatching { DesktopSettings.darkMode }.getOrNull() ?: true
    )

    /**
     * Backdrop intensity, using the tablet's four presets.
     *
     * SOFT is pixel-identical to the tablet's own default, so it is the right
     * starting point: if the desktop looks wrong here, the cause is not the
     * backdrop tuning.
     */
    /**
     * Backdrop intensity.
     *
     * Seeded from disk so the choice survives a restart. It used to be a plain
     * `mutableStateOf(SOFT)`, which silently reset the user's choice every launch
     * and made the setting look broken rather than unsaved. Reads are guarded
     * because a settings file can be truncated or hand-edited, and a theme picker
     * is not worth failing startup over.
     */
    var backdropTheme by mutableStateOf(
        runCatching { BackdropTheme.valueOf(DesktopSettings.backdropThemeName) }
            .getOrNull() ?: BackdropTheme.SOFT
    )

/** Human-facing labels, in the tablet's terms. */
    val backdropLabels: Map<BackdropTheme, String> = mapOf(
        BackdropTheme.SOFT to "柔和",
        BackdropTheme.VIVID to "鮮明",
        BackdropTheme.NIGHT to "夜色",
        BackdropTheme.CLEAN to "純淨"
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
 * The one chrome material for the whole desktop window.
 *
 * Near-opaque tinted surface plus the shared dressing (sheen + rim), so every
 * floating container — toolbar, dock, cards, panels — reads at exactly the same
 * black-glass depth. Translucent glass over busy content fails both layers, and
 * five slightly different translucencies read as five different apps; one
 * material, one depth, everywhere.
 */
@Composable
fun Modifier.chromeGlass(isDark: Boolean, shape: Shape, specular: Boolean = true): Modifier = this
    .clip(shape)
    .background(
        MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.88f else 0.92f),
        shape
    )
    .glassDressing(isDark, shape, specular)

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
