package com.vic.inkflow.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.TrashRetention
import com.vic.inkflow.data.TrashedDocument
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl

/**
 * The trash tab.
 *
 * Deliberately a **list**, not the adaptive grid: every row carries two actions
 * (restore / delete permanently) and a retention countdown, which do not fit a
 * 180dp card. The grid/list toggle is only shown on the home tab for the same
 * reason.
 *
 * Trash is local-only — sync and backup never see these documents (the DAO hides
 * `deletedAt IS NOT NULL` from every manifest query), so there is no "restore
 * from another device" affordance to design for.
 */
@Composable
internal fun LibraryTrashView(
    trashed: List<TrashedDocument>,
    searchQuery: String,
    retentionDays: Int,
    docViewModel: DocumentViewModel,
    context: Context,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onPurgeAll: () -> Unit,
    isDarkTheme: Boolean,
    brandGradient: Brush,
    modifier: Modifier = Modifier
) {
    val visible = remember(trashed, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) trashed
        else trashed.filter { it.document.displayName.contains(q, ignoreCase = true) }
    }
    // One clock per trash snapshot: recomputing per row makes the countdown jitter.
    val now = remember(trashed) { System.currentTimeMillis() }

    var pendingPurge by remember { mutableStateOf<TrashedDocument?>(null) }
    var showPurgeAll by remember { mutableStateOf(false) }

    if (trashed.isEmpty()) {
        TrashEmptyState(
            modifier = modifier.fillMaxSize(),
            brandGradient = brandGradient,
            isDarkTheme = isDarkTheme
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "垃圾桶",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (retentionDays == 0) {
                        "${trashed.size} 份・不會自動清除"
                    } else {
                        "${trashed.size} 份・保留 $retentionDays 天"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trashed.isNotEmpty()) {
                TextButton(onClick = { showPurgeAll = true }) {
                    Text("清空垃圾桶", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "垃圾桶裡沒有符合「${searchQuery.trim()}」的筆記",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(visible, key = { it.document.uri }) { item ->
                val coverBitmap by docViewModel
                    .getDocumentThumbnail(context, item.document.uri)
                    .collectAsState()
                TrashRow(
                    item = item,
                    coverBitmap = coverBitmap,
                    retentionDays = retentionDays,
                    now = now,
                    onRestore = { onRestore(item.document.uri) },
                    onPurge = { pendingPurge = item },
                    isDarkTheme = isDarkTheme
                )
            }
        }
    }

    AnimatedDialog(visible = pendingPurge != null) {
        val target = pendingPurge
        if (target != null) {
            GlassDialog(
                onDismissRequest = { pendingPurge = null },
                isDark = isDarkTheme,
                title = { Text("永久刪除") },
                text = {
                    Text("「${target.document.displayName}」的筆跡、標註與原始檔會一併刪除，無法復原。")
                },
                confirmText = "永久刪除",
                onConfirm = {
                    pendingPurge = null
                    onPurge(target.document.uri)
                },
                confirmColor = MaterialTheme.colorScheme.error
            )
        }
    }

    AnimatedDialog(visible = showPurgeAll) {
        GlassDialog(
            onDismissRequest = { showPurgeAll = false },
            isDark = isDarkTheme,
            title = { Text("清空垃圾桶") },
            text = { Text("垃圾桶裡的 ${trashed.size} 份筆記會被永久刪除，無法復原。") },
            confirmText = "全部刪除",
            onConfirm = {
                showPurgeAll = false
                onPurgeAll()
            },
            confirmColor = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun TrashRow(
    item: TrashedDocument,
    coverBitmap: android.graphics.Bitmap?,
    retentionDays: Int,
    now: Long,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    isDarkTheme: Boolean
) {
    val veil = Color.White.copy(alpha = if (isDarkTheme) 0.10f else 0.35f)
    val deletedOn = remember(item.deletedAt) {
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(item.deletedAt))
    }
    val daysLeft = TrashRetention.daysLeft(item.deletedAt, now, retentionDays)
    val countdown = when {
        daysLeft == null -> "不會自動清除"
        daysLeft <= 0 -> "今天到期"
        else -> "剩 $daysLeft 天"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeLg)
            .glassCardSurface(isDarkTheme, ShapeLg, null)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 88.dp)
                .clip(ShapeSm)
                .background(veil),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = coverBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.document.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "刪除於 $deletedOn・$countdown",
                style = MaterialTheme.typography.bodySmall,
                color = if (daysLeft != null && daysLeft <= 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(
                        Icons.Outlined.RestoreFromTrash,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("還原")
                }
                TextButton(onClick = onPurge, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("永久刪除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TrashEmptyState(
    modifier: Modifier,
    brandGradient: Brush,
    isDarkTheme: Boolean
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 文字一律壓在玻璃面板上：背景可能是用戶的壁紙（亮→暗都有），
        // 裸文字在深色桌面上會讀不出來（同 LibraryEmptyState 的處理）。
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fauxGlassPanel(isDarkTheme, ShapeXl),
            shape = ShapeXl,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(brandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = Color.White.copy(alpha = 0.92f)
                    )
                }
                Text(
                    text = "垃圾桶是空的",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "刪除的筆記會先放在這裡，保留期內都能還原。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

