package com.vic.inkflow

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.sync.LocalSyncManager
import com.vic.inkflow.ui.AiAssistantPanel
import com.vic.inkflow.ui.InkFlowTheme
import com.vic.inkflow.ui.InkThemeState
import com.vic.inkflow.ui.LibraryView
import com.vic.inkflow.ui.PdfViewerWithPdfBox
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/** Shared app-data locations (single source of truth for UI + sync). */
object AppPaths {
    val dir: String = System.getProperty("user.home") + File.separator + ".inkflow"
    val dbPath: String = dir + File.separator + "inkflow.db"
}

@Composable
fun App() {
    InkFlowTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            InkFlowApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkFlowApp() {
    val databaseManager = remember { DatabaseManager(AppPaths.dbPath).also { it.connect() } }
    val syncManager = remember { LocalSyncManager(databaseManager, AppPaths.dir) }

    LaunchedEffect(Unit) {
        File(AppPaths.dir).mkdirs()
        syncManager.startListening()
        logger.info { "Local sync service started; data dir: ${AppPaths.dir}" }
    }
    DisposableEffect(Unit) {
        onDispose {
            syncManager.stopListening()
            databaseManager.disconnect()
        }
    }

    // ── Navigation & view state ─────────────────────────────────────────────
    var selectedDocument by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var currentPageIndex by remember { mutableStateOf(0) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var aiPanelCollapsed by remember { mutableStateOf(true) }

    // ── Sync state (polled so the indicator tracks background auto-sync) ────
    var isSyncing by remember { mutableStateOf(false) }
    var libraryRefresh by remember { mutableStateOf(0) }
    var lastSyncSummary by remember { mutableStateOf<String?>(null) }
    var peerCount by remember { mutableStateOf(0) }
    var peerNames by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            peerCount = syncManager.devices.count { System.currentTimeMillis() - it.lastSeenMillis < 90_000 }
            peerNames = syncManager.devices.joinToString("、") { it.deviceName }
            if (!isSyncing && syncManager.isSyncing) isSyncing = true
            if (isSyncing && !syncManager.isSyncing) {
                syncManager.lastSyncResult?.let { r ->
                    lastSyncSummary = "文件 ${r.documentsUpdated} · 筆跡 ${r.strokesPulled} · " +
                        "PDF ${r.filesTransferred} · 衝突保留 ${r.conflictsSkipped}" +
                        (if (r.errors.isNotEmpty()) " · 錯誤 ${r.errors.size}" else "")
                }
                isSyncing = false
                libraryRefresh++
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top App Bar ──────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("InkFlow", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        selectedDocument?.substringAfterLast('/')?.substringAfterLast('\\')
                            ?: "AI 輔助閱讀 · 文件管理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            actions = {
                // Sync status chip
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (peerCount > 0) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        if (isSyncing || syncManager.isSyncing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (peerCount > 0) Icons.Default.Sync else Icons.Default.SyncDisabled,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (peerCount > 0) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                isSyncing || syncManager.isSyncing -> "同步中…"
                                peerCount > 0 -> "$peerCount 台設備在線"
                                else -> "離線"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                // Manual sync trigger
                IconButton(
                    onClick = {
                        if (!isSyncing) {
                            isSyncing = true
                            syncManager.requestSyncNow { result ->
                                lastSyncSummary = "手動同步：文件 ${result.documentsUpdated} · 筆跡 ${result.strokesPulled} · " +
                                    "PDF ${result.filesTransferred} · 衝突保留 ${result.conflictsSkipped}" +
                                    (if (result.errors.isNotEmpty()) " · 錯誤 ${result.errors.size}" else "")
                                isSyncing = false
                                libraryRefresh++
                            }
                        }
                    },
                    enabled = !isSyncing
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "立即同步")
                }
                // Dark mode toggle
                IconButton(onClick = { InkThemeState.darkMode = !InkThemeState.darkMode }) {
                    Text(if (InkThemeState.darkMode) "🌙" else "☀️")
                }
                // Back to library
                if (selectedDocument != null || showSettings) {
                    IconButton(onClick = { selectedDocument = null; showSettings = false }) {
                        Icon(Icons.Default.Home, contentDescription = "返回文件庫")
                    }
                }
            }
        )

        // ── Body: Navigation rail + content (+ AI panel in reader) ──────────
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.width(84.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                header = { Spacer(Modifier.height(8.dp)) }
            ) {
                NavigationRailItem(
                    selected = selectedDocument == null && !showSettings,
                    onClick = { selectedDocument = null; showSettings = false },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("文件庫") }
                )
                NavigationRailItem(
                    selected = false,
                    onClick = { /* manual sync */
                        if (!isSyncing) { isSyncing = true; syncManager.requestSyncNow { isSyncing = false; libraryRefresh++ } }
                    },
                    icon = {
                        if (isSyncing || syncManager.isSyncing)
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else
                            Icon(Icons.Default.Sync, contentDescription = null)
                    },
                    label = { Text("同步") }
                )
                NavigationRailItem(
                    selected = showSettings,
                    onClick = { showSettings = true; selectedDocument = null },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("設定") }
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (peerCount > 0) "● $peerCount" else "○",
                        color = if (peerCount > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when {
                    showSettings -> SettingsView(
                        peerNames = peerNames,
                        lastSyncSummary = lastSyncSummary
                    )
                    selectedDocument == null -> LibraryView(
                        databaseManager = databaseManager,
                        refreshToken = libraryRefresh,
                        syncStatusText = lastSyncSummary
                            ?: if (peerCount > 0) "已連接：$peerNames"
                               else "未偵測到平板（等待局域網發現）",
                        selectedFolderId = selectedFolderId,
                        onFolderSelected = { selectedFolderId = it },
                        onDocumentSelected = { uri ->
                            selectedDocument = uri
                            currentPageIndex = 0
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Row(Modifier.fillMaxSize()) {
                        PdfViewerWithPdfBox(
                            documentUri = selectedDocument!!,
                            pageIndex = currentPageIndex,
                            databaseManager = databaseManager,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AiAssistantPanel(
                            documentUri = selectedDocument!!,
                            collapsed = aiPanelCollapsed,
                            onToggleCollapsed = { aiPanelCollapsed = !aiPanelCollapsed }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsView(peerNames: String, lastSyncSummary: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider()
        SettingRow("主題模式", if (InkThemeState.darkMode) "深色（閱讀預設）" else "淺色")
        SettingRow("資料目錄", AppPaths.dir)
        SettingRow("資料庫", AppPaths.dbPath)
        SettingRow("已連線設備", if (peerNames.isNotBlank()) peerNames else "無（請確認平板與電腦同一 Wi-Fi，且兩端均開啟 InkFlow）")
        SettingRow("上次同步", lastSyncSummary ?: "尚無記錄")
        Text(
            "本機不同步雲端：所有數據僅在局域網內傳輸。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp)
        )
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "InkFlow",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App()
    }
}
