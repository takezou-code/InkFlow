package com.vic.inkflow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.vic.inkflow.ui.theme.InkDarkScheme
import com.vic.inkflow.ui.theme.InkLightScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Does text stay readable when the theme is toggled?
 *
 * "The colours come from `MaterialTheme.colorScheme`" only proves the text *changes*.
 * It says nothing about whether it is still legible in the mode it switched into — and
 * that is the failure a palette migration actually produces: two schemes that both look
 * plausible on their own, and one unreadable combination.
 *
 * So the claims worth testing are per-role contrast, in both modes, against the
 * surfaces those roles are actually used on.
 */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun check(name: String, scheme: androidx.compose.material3.ColorScheme) {
        // Body and heading text sits on `surface`; the window background behind the
        // glass sits on `background`. Both are checked because panels float over both.
        val onSurface = contrast(scheme.onSurface, scheme.surface)
        assertTrue(
            onSurface >= 4.5f,
            "$name: onSurface/surface contrast $onSurface is below 4.5:1"
        )

        val onBackground = contrast(scheme.onBackground, scheme.background)
        assertTrue(
            onBackground >= 4.5f,
            "$name: onBackground/background contrast $onBackground is below 4.5:1"
        )

        // Secondary text (sync status, captions, timestamps). WCAG allows 3:1 for text
        // that is not the primary reading content, and this role is used that way.
        val onVariant = contrast(scheme.onSurfaceVariant, scheme.surface)
        assertTrue(
            onVariant >= 3.0f,
            "$name: onSurfaceVariant/surface contrast $onVariant is below 3:1"
        )

        // Accent text and the selected-tool pill. Only ever used at 12sp+ and never as
        // the sole carrier of meaning, so 3:1 is the bar.
        val accent = contrast(scheme.primary, scheme.surface)
        assertTrue(
            accent >= 3.0f,
            "$name: primary/surface contrast $accent is below 3:1"
        )
    }

@Test
    fun `dark scheme text is readable on its own surfaces`() = check("dark", InkDarkScheme)

    @Test
    fun `light scheme text is readable on its own surfaces`() = check("light", InkLightScheme)

    @Test
    fun `every text role actually changes between the two schemes`() {
        // If a role were identical in both, the toggle would leave that text stranded
        // on the wrong contrast — invisible in a screenshot diff, obvious to a user.
        listOf(
            "onSurface" to (InkDarkScheme.onSurface to InkLightScheme.onSurface),
            "onBackground" to (InkDarkScheme.onBackground to InkLightScheme.onBackground),
            "onSurfaceVariant" to
                (InkDarkScheme.onSurfaceVariant to InkLightScheme.onSurfaceVariant),
            "surface" to (InkDarkScheme.surface to InkLightScheme.surface),
            "background" to (InkDarkScheme.background to InkLightScheme.background)
        ).forEach { (role, pair) ->
            assertNotEquals(pair.first, pair.second, "$role is identical in both schemes")
        }
    }

    @Test
    fun `the shared schemes are the tablet's values, not the desktop's old palette`() {
        // The desktop used to carry its own blue-navy palette. These four are the
        // load-bearing proofs that it is now consuming the tablet's.
        assertEquals(Color(0xFF6366F1), InkDarkScheme.primary)
        assertEquals(Color(0xFF8B5CF6), InkDarkScheme.secondary)
        assertEquals(Color(0xFF0B0F1E), InkDarkScheme.background)
        assertEquals(Color(0xFF18181B), InkDarkScheme.surface)
    }
}