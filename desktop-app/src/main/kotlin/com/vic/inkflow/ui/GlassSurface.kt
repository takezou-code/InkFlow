package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
// Tokens live in `com.vic.inkflow.ui` (Theme.kt) — the desktop has no separate
// `ui.theme` package the way Android does.

/**
 * The tablet's liquid-glass material, ported to the desktop.
 *
 * ## What is and is not portable
 *
 * The tablet (`app/.../ui/GlassSurface.kt`) has two paths. The primary one is
 * built on `dev.chrisbanes.haze`, which does real backdrop blur through
 * Android's `RenderEffect`. That is an Android platform API with no counterpart
 * in Compose Desktop, and emulating a true Gaussian backdrop blur per frame in
 * software would be far too slow to ship.
 *
 * So this port is the tablet's **faux** path — the one the tablet itself falls
 * back to on devices where blur is unavailable — with its numbers taken verbatim
 * from `theme/Color.kt`. That is deliberate: the tablet team tuned those alphas
 * by eye against the real material, so reusing them keeps the two apps looking
 * like the same product rather than like two apps that both happen to be glassy.
 *
 * The cues that carry the material, in the order they matter:
 *
 *  1. a translucent tint (not an opaque surface) so the backdrop shows through;
 *  2. a vertical sheen — bright at the top, dark at the bottom — so it reads as a
 *     lit pane with thickness rather than a flat wash;
 *  3. a hairline rim that is bright at the top edge and nearly invisible
 *     elsewhere, standing in for the highlight along a curved glass lip.
 *
 * Two values are worth keeping and one is worth not keeping:
 *
 *  - The rim is 0.75dp and low-alpha on purpose. A thick rim with a bright line
 *    all the way around is the 2020 filter-UI look, not glass.
 *  - The light-mode extra hairline (`Color(0xFF0F172A)` at 10%) keeps the shape
 *    legible against a pale document page; without it the panel disappears.
 *  - There is no `Modifier.shadow()` anywhere. Elevation is carried by the rim
 *    plus the inner bottom gradient. The tablet team found shadows left visible
 *    glyph outlines behind text on some GPUs; there is no reason to invite that
 *    here.
 *
 * No idle animation: the tablet team measured it costing 60 -> 30-42fps with
 * several glass surfaces on screen. The press response in [glassClickable] is
 * where the motion belongs.
 */

/** Glass colour tokens, copied from the tablet's `theme/Color.kt`. */
object GlassTokens {
    val VeilLight = GlassVeilLight
    val VeilDark = GlassVeilDark
    val TintLight = GlassTintLight
    val TintDark = GlassTintDark

    /** Icons/text sitting on glass should use this, never a hardcoded colour. */
    fun contentColor(isDark: Boolean): Color = if (isDark) Color.White else PaperInkColor
}

/**
 * The shared dressing: thin sheen + top rim.
 *
 * Split out from [fauxGlassPanel] because the tablet uses the same treatment on
 * its dialogs and pressable chips, and having one definition is the entire point
 * of moving this material into a shared file.
 */
@Composable
fun Modifier.glassDressing(
    isDark: Boolean,
    shape: Shape,
    specular: Boolean = true
): Modifier {
    var m = this
        // Sheen: bright at the top edge, clear through the middle, slightly dark
        // at the bottom so the panel gains apparent thickness.
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.07f else 0.08f),
                    0.06f to Color.White.copy(alpha = if (isDark) 0.02f else 0.02f),
                    0.35f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.08f else 0.05f)
                )
            ),
            shape = shape
        )
    if (specular) {
        // Rim: bright hairline at the top, fading fast. This is the single cue
        // that most makes a flat rectangle read as a curved surface.
        m = m.border(
            border = BorderStroke(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.18f to Color.White.copy(alpha = 0.06f),
                        0.72f to Color.White.copy(alpha = 0.02f),
                        1f to Color.White.copy(alpha = 0.05f)
                    )
                )
            ),
            shape = shape
        )
        if (!isDark) {
            // Pale hairline for definition against a white document page.
            m = m.border(
                border = BorderStroke(
                    width = 0.5.dp,
                    color = Color(0xFF0F172A).copy(alpha = 0.10f)
                ),
                shape = shape
            )
        }
    }
    return m
}

/**
 * The main glass surface. Drop-in for the old hand-tuned `glassPanel`.
 *
 * @param specular false for large quiet panels where a rim would only add noise
 *   (e.g. a full-height sidebar); true for cards, dialogs and chips.
 */
@Composable
fun Modifier.fauxGlassPanel(
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier = this
    .clip(shape)
    .background(
        color = if (isDark) GlassTintDark else GlassTintLight,
        shape = shape
    )
    .glassDressing(isDark, shape, specular)

/** Circular glass — avatar wells, round icon buttons, FABs. */
@Composable
fun Modifier.bubbleGlass(
    isDark: Boolean,
    specular: Boolean = true
): Modifier = fauxGlassPanel(isDark, RoundedCornerShape(50), specular)

/**
 * Glass that reacts to press.
 *
 * The sweep is the app's only idle-free glass motion, and it exists because the
 * press is exactly when the user is already looking at the surface.
 */
@Composable
fun Modifier.pressableGlass(
    isDark: Boolean,
    shape: Shape = ShapeMd,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return fauxGlassPanel(isDark, shape)
        .background(
            color = Color.White.copy(alpha = if (pressed) 0.10f else 0f),
            shape = shape
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Kept for call sites that predate the port. Now a thin alias over the shared
 * material so no screen keeps its own private approximation.
 */
@Composable
fun Modifier.glassPanel(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp)
): Modifier = fauxGlassPanel(InkThemeState.darkMode, shape)