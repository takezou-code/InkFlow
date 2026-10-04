package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.ShapeMd

/**
 * Desktop-only pieces of the glass material.
 *
 * The material itself — the tokens, the sheen + rim dressing, [fauxGlassPanel] and
 * [glassSidePanel] — now lives in `:shared` (`ui/Glass.kt`), which is the tablet's
 * own code rather than a transcription of it. That file also documents what the
 * material is: the cue order, why the rim is a 0.75dp hairline, why there is no
 * `Modifier.shadow()`, and why nothing animates while idle.
 *
 * What stayed here is the part that is genuinely the desktop's: the backdrop, and
 * the two components whose signatures are driven by desktop concerns.
 *
 * `bubbleGlass` and `pressableGlass` also exist on the tablet, under the same
 * names and with different bodies — see the note in `:shared`'s `Glass.kt`. These
 * are the desktop's, not reduced copies of the tablet's.
 */

/**
 * The background glass needs in order to read as glass: something to be
 * translucent *against*.
 *
 * Without a varied backdrop a translucent panel has nothing to reveal, and the
 * material collapses into a flat tinted rectangle — which is exactly what happens
 * when glass ships over a solid fill. Two very soft, very large radial glows on
 * the brand hues give the panels an actual gradient to distort, so they pick up
 * a visible colour shift across the window the way the tablet's does.
 *
 * Deliberately not animated. The tablet team measured multiple glass surfaces
 * driving a full-window repaint every frame and lost more than half the frame
 * rate; the motion budget goes to the press response instead.
 */
@Composable
fun Modifier.auroraBackdrop(isDark: Boolean): Modifier {
    val glow = if (isDark) 0.16f else 0.10f
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val width = 1600f
    val height = 1000f
    // Corners, expressed as fractions so the glows stay anchored to the window
    // rather than to a fixed pixel offset that would drift with the window size.
    fun corner(fx: Float, fy: Float) = androidx.compose.ui.geometry.Offset(width * fx, height * fy)

    return this
        .background(
            Brush.linearGradient(
                // Slightly lifted toward the bottom so the top bar's glass has a
                // brighter field above it than the reading surface below.
                0f to MaterialTheme.colorScheme.background,
                0.55f to MaterialTheme.colorScheme.background,
                1f to MaterialTheme.colorScheme.surface
            )
        )
        .background(
            Brush.radialGradient(
                colors = listOf(primary.copy(alpha = glow), Color.Transparent),
                center = corner(0.14f, 0.04f),
                radius = 1400f
            )
        )
        .background(
            Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = glow * 0.8f), Color.Transparent),
                center = corner(0.94f, 0.96f),
                radius = 1200f
            )
        )
}

// `bubbleGlass` used to live here as a two-line wrapper over `fauxGlassPanel` at a
// 50% corner. Now that the tablet's glass system is in `:shared` there are two
// `bubbleGlass` on the classpath — this one and the tablet's, which is a considered
// high-coverage floater for sitting on paper (85% white / 90% navy, 1dp gradient
// rim, plus a deep hairline in light mode because a white rim is invisible on white
// paper). Two same-named components with different bodies is the situation
// `:shared/ui/Glass.kt` documents; rather than reconcile them, the wrapper is
// deleted and the desktop uses the real one. The wrapper was strictly less capable.

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