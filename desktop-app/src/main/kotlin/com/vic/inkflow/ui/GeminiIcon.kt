package com.vic.inkflow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The tablet's own Gemini sparkle, ported 1:1 from
 * `app/src/main/res/drawable/ic_gemini.xml`.
 *
 * Same 24dp viewport, same single path — only the packaging changes, because
 * desktop Compose cannot read Android vector XML. Fill stays black here; the
 * caller tints it (primary when the AI panel is open, glass content otherwise),
 * exactly like the tablet's toolbar button.
 */
val GeminiSparkle: ImageVector by lazy {
    ImageVector.Builder(
        name = "GeminiSparkle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(
            "M11.04,19.32Q12,21.51 12,24q0,-2.49 0.93,-4.68 0.96,-2.19 2.58,-3.81t3.81,-2.55Q21.51,12 24,12q-2.49,0 -4.68,-0.93a12.3,12.3 0 0,1 -3.81,-2.58 12.3,12.3 0 0,1 -2.58,-3.81Q12,2.49 12,0q0,2.49 -0.96,4.68 -0.93,2.19 -2.55,3.81a12.3,12.3 0 0,1 -3.81,2.58Q2.49,12 0,12q2.49,0 4.68,0.96 2.19,0.93 3.81,2.55t2.55,3.81"
        ),
        fill = SolidColor(Color.Black)
    ).build()
}
