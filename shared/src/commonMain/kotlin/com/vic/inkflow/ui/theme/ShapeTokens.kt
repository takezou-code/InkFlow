package com.vic.inkflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Unified corner-radius scale. All custom surfaces should use these tokens
 * instead of ad-hoc RoundedCornerShape values so radii stay consistent:
 *
 *   ShapeSm (12) — inputs, chips, small controls
 *   ShapeMd (16) — cards, thumbnails, list tiles
 *   ShapeLg (24) — dialogs, hero panels, document cards
 *   ShapeXl (32) — stage shells, large decorative containers
 */
val ShapeSm = RoundedCornerShape(12.dp)
val ShapeMd = RoundedCornerShape(16.dp)
val ShapeLg = RoundedCornerShape(24.dp)
val ShapeXl = RoundedCornerShape(32.dp)
