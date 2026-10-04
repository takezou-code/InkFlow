package com.vic.inkflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The product's type scale, built over a caller-supplied font family.
 *
 * ## Why the family is a parameter and not loaded here
 *
 * The two platforms have genuinely incompatible font APIs, and there is no shared
 * entry point. Verified against the actual CMP 1.12.1 artifacts rather than assumed:
 *
 * - `androidx.compose.ui.text.font.FontKt` in **ui-text-desktop** declares only
 *   `Font(resId: Int, …)`. There is no `Font(path, …)` and no `Font(file, …)` — the
 *   cross-platform overloads simply do not exist on that target.
 * - The desktop way in is a different symbol entirely:
 *   `androidx.compose.ui.text.font.FontFamily_desktopKt` exposes
 *   `FontFamily(path: String)` and `EmbeddedFontFamily(path: String)`, neither of
 *   which exists off the JVM.
 * - Android's way is `Font(resId = R.font.inter_variable, …, variationSettings = …)`.
 *
 * Trying to bridge that with `expect`/`actual` looks appealing and does not work:
 * `:shared`'s commonMain resolves Compose to the Android artifact, so writing
 * `Font(path, …)` there fails with "None of the following candidates is
 * applicable", and the desktop `actual` still only sees the `resId` overloads.
 *
 * So the split is drawn where the platform boundary actually is. The **scale** —
 * sizes, weights, line heights, tracking — is the part that has to be identical or
 * the two apps stop looking like one product, and that lives here. Only the
 * mechanical act of turning a font file into a `FontFamily` is left to each caller.
 *
 * The desktop used stock M3 `Typography()` with five ad-hoc overrides and no Inter at
 * all, which is why its headings had different proportions from the tablet's.
 *
 * Values are the tablet's, unchanged.
 */
fun inkTypography(inter: FontFamily): Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Bold,
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp
    ),
    labelMedium = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = inter, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp
    )
)