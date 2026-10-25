package com.vic.inkflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern Windows 11 style button variants for desktop app.
 */
@Composable
fun ModernIconButton(
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp
) {
    val isDark = InkThemeState.darkMode
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val backgroundColor = when {
        !enabled -> onSurfaceVariant.copy(alpha = 0.08f)
        selected -> primary.copy(alpha = 0.18f)
        else -> surface.copy(alpha = 0f)
    }
    val borderColor = when {
        !enabled -> Color.Transparent
        selected -> primary.copy(alpha = 0.5f)
        else -> onSurfaceVariant.copy(alpha = 0.15f)
    }
    val iconColor = when {
        !enabled -> onSurfaceVariant.copy(alpha = 0.35f)
        selected -> primary
        else -> onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor, shape)
            .border(if (selected || !enabled) 0.dp else 1.dp, borderColor, shape)
            .clickable(enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material.Icon(
            icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun ModernChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val isDark = InkThemeState.darkMode
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val bg = when {
        !enabled -> onSurfaceVariant.copy(alpha = 0.06f)
        selected -> primary.copy(alpha = 0.14f)
        else -> surface.copy(alpha = 0.9f)
    }
    val borderCol = when {
        !enabled -> Color.Transparent
        selected -> primary.copy(alpha = 0.4f)
        else -> onSurfaceVariant.copy(alpha = 0.12f)
    }
    val txtCol = when {
        !enabled -> onSurfaceVariant.copy(alpha = 0.38f)
        selected -> primary
        else -> onSurface
    }

    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .height(32.dp)
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderCol, shape)
            .clickable(enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leadingIcon?.let {
                androidx.compose.material.Icon(
                    it,
                    contentDescription = "",
                    tint = txtCol,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = text,
                color = txtCol,
                fontSize = 13.sp,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}