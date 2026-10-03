package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.GlassTintDark
import com.vic.inkflow.ui.theme.GlassTintLight
import com.vic.inkflow.ui.theme.GlassVeilDark
import com.vic.inkflow.ui.theme.GlassVeilLight
import com.vic.inkflow.ui.theme.PaperInkColor
import com.vic.inkflow.ui.theme.ShapeLg

/**
 * The portable half of the glass material.
 *
 * ## Why this file exists
 *
 * The tablet's `GlassSurface.kt` has two paths. The primary one is built on
 * `dev.chrisbanes.haze`, which does real backdrop blur through Android's
 * `RenderEffect`. That is an Android platform API with no counterpart in Compose
 * Desktop, and emulating a true Gaussian backdrop blur per frame in software
 * would be far too slow to ship. So that path stays in `:app`.
 *
 * What is here is the tablet's **faux** path — the one the tablet itself falls
 * back to where blur is unavailable — plus the `rim` axis the desktop needed. It
 * lives in `:shared` so both apps run the same numbers instead of one of them
 * carrying a transcription of the other. The desktop used to hold a 282-line hand
 * port described in its own comments as "ported from the tablet"; keeping a copy
 * is how `GlassVeilDark` ended up at 45% on the desktop while the tablet had
 * already moved it to 40%.
 *
 * ## What is deliberately NOT here
 *
 * `glassPanel`, `bubbleGlass` and `pressableGlass` share their names across the
 * two apps but are *different components*, so they stay in their own modules
 * rather than being forced into a common signature that fits neither:
 *
 *  - the tablet's `glassPanel` requires a `HazeState` and a `HazeInput`;
 *  - the tablet's `bubbleGlass` is a high-coverage floater for sitting on paper
 *    (85% white / 90% navy, 1dp rim), whereas the desktop's is merely
 *    `fauxGlassPanel` at a 50% corner radius;
 *  - the tablet's `pressableGlass` drives `LocalQuietMode` and a one-shot haptic,
 *    with a different parameter list again.
 *
 * Reconciling those is a design task, not a move. Until it is done each app owns
 * its own, and the genuinely shared dressing underneath stays here so at least
 * the substrate cannot drift.
 *
 * ## The cues that carry the material, in the order they matter
 *
 *  1. a translucent tint (not an opaque surface) so the backdrop shows through;
 *  2. a vertical sheen — bright at the top, dark at the bottom — so it reads as a
 *     lit pane with thickness rather than a flat wash;
 *  3. a hairline rim bright along one edge and nearly invisible elsewhere,
 *     standing in for the highlight along a curved glass lip.
 *
 * Two values worth keeping and one worth not keeping:
 *
 *  - The rim is 0.75dp and low-alpha on purpose. A thick rim with a bright line
 *    all the way around is the 2020 filter-UI look, not glass.
 *  - The light-mode extra hairline (`Color(0xFF0F172A)` at 10%) keeps the shape
 *    legible against a pale document page; without it the panel disappears.
 *  - There is no `Modifier.shadow()` anywhere. The tablet team diagnosed this
 *    over nine rounds: with a shadow every surface showed a white outline box
 *    behind text on the Xiaomi tablet's GPU, and removing it removed the box
 *    every time — border, sheen and blur were all innocent. Elevation is carried
 *    by the rim plus the inner bottom gradient.
 *
 * No idle animation: the tablet team measured several glass surfaces each driving
 * a full-window repaint every frame, costing 60 -> 30-42fps. The motion budget
 * belongs to the press response.
 */

/** Glass colour tokens, for call sites that want the values without the shapes. */
object GlassTokens {
    val VeilLight = GlassVeilLight
    val VeilDark = GlassVeilDark
    val TintLight = GlassTintLight
    val TintDark = GlassTintDark

    /** Icons/text sitting on glass should use this, never a hardcoded colour. */
    fun contentColor(isDark: Boolean): Color = if (isDark) Color.White else PaperInkColor
}

/**
 * Which edge of a glass surface carries the specular highlight.
 *
 * `Top` for anything horizontal (the light source is above). `Leading` for a
 * full-height strip, where the visible boundary is the one facing the content —
 * a top-edge highlight on a rail is stranded off-screen where nobody looks.
 */
enum class RimEdge { Top, Leading }

/**
 * The shared dressing: thin sheen + rim.
 *
 * Split out from [fauxGlassPanel] because the tablet applies the same treatment to
 * its dialogs and pressable chips, and one definition is the entire point of
 * moving this material into a shared file.
 *
 * @param rim which edge carries the highlight. A glass pane is lit from above, so
 *   a horizontal surface (top bar, dialog, card) reads brightest along its top
 *   edge — but for a full-height strip (nav rail, side panel) that top edge is
 *   off-screen and the edge that actually matters is the vertical one facing the
 *   content. Using the vertical gradient on a rail puts a bright line across the
 *   very top and nothing along the edge you actually see, which is why this is a
 *   parameter and not a constant.
 */
@Composable
fun Modifier.glassDressing(
    isDark: Boolean,
    shape: Shape,
    specular: Boolean = true,
    rim: RimEdge = RimEdge.Top
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
        // The rim keeps only a hairline of light along one edge: that is what
        // defines the boundary. Real "reacts to the light" behaviour belongs to
        // haze's specular on the tablet's primary path; here we only add a nearly
        // invisible bottom edge to tidy the shape, and must not compete with it.
        val stops = arrayOf(
            0f to Color.White.copy(alpha = 0.34f),
            0.18f to Color.White.copy(alpha = 0.06f),
            0.72f to Color.White.copy(alpha = 0.02f),
            1f to Color.White.copy(alpha = 0.05f)
        )
        m = m.border(
            border = BorderStroke(
                width = 0.75.dp,
                brush = when (rim) {
                    RimEdge.Top -> Brush.verticalGradient(*stops)
                    RimEdge.Leading -> Brush.horizontalGradient(*stops)
                }
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
 * The main glass surface.
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

/**
 * A full-height strip of chrome: the nav rail, the AI side panel.
 *
 * Differs from [fauxGlassPanel] only in where the rim goes (see [RimEdge]) and in
 * being square-cornered, so it butts cleanly against the window edge and against
 * the content column without a rounded notch at the bottom.
 */
@Composable
fun Modifier.glassSidePanel(isDark: Boolean): Modifier =
    this
        .clip(RectangleShape)
        .background(
            color = if (isDark) GlassTintDark else GlassTintLight,
            shape = RectangleShape
        )
        .glassDressing(isDark, RectangleShape, specular = true, rim = RimEdge.Leading)