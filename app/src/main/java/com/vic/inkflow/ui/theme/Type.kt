package com.vic.inkflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vic.inkflow.R

/**
 * Inter variable font (OFL), bundled offline. One file drives every weight
 * through FontVariation so headings and body share the same family with
 * consistent metrics.
 */
val InterFamily = FontFamily(
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))
    )
)

private val DisplayFamily = InterFamily
private val BodyFamily = InterFamily

// The scale itself lives in `:shared` (see `inkTypography`). It used to be spelled out
// here in full, which meant two apps could drift on type — and the desktop, having
// no Inter at all, had already drifted. Only the part that is genuinely Android —
// turning R.font into a FontFamily via FontVariation — stays on this side.
val Typography = inkTypography(InterFamily)
