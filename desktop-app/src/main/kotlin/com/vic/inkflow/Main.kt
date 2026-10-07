package com.vic.inkflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vic.inkflow.sync.ProposalQueue
import com.vic.inkflow.ui.AuroraBackground
import com.vic.inkflow.ui.BackdropTheme
import com.vic.inkflow.ui.AiAssistantPanel
import com.vic.inkflow.ui.PdfViewer
import com.vic.inkflow.ui.InkFlowTheme
import com.vic.inkflow.ui.InkThemeState
import com.vic.inkflow.ui.LibraryView
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl
import com.vic.inkflow.ui.bubbleGlass
import com.vic.inkflow.ui.auroraBackdrop
import com.vic.inkflow.ui.glassSidePanel
import com.vic.inkflow.ui.pressableGlass
import com.vic.inkflow.ui.fauxGlassPanel
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

import mu.KotlinLogging
import java.io.File
import java.util.Properties

private val logger = KotlinLogging.logger {}

/** Shared app-data locations (single source of truth for UI + sync). */
object AppPaths {
    val dir: String = System.getProperty("user.home") + File.separator + ".inkflow"
    val dbPath: String = dir + File.separator + "inkflow.db"
    val settingsPath: String = dir + File.separator + "desktop.properties"
}

/**
 * The tablet's pairing code, persisted between launches.
 *
 * Stored in a plain properties file rather than the database on purpose: this is
 * a *local client credential*, not mirrored content. If it went through the sync
 * mirror it would be a credential the desktop wrote into its own DB, which is a
 * much harder thing to reason about than one text file the user can delete.
 *
 * `lastKnownInstanceId` is the same idea — the desktop needs it to tell "the user
 * deleted a document on the tablet" apart from "the tablet was reinstalled", and
 * losing it across restarts would make every missing document look like a deletion.
 */
object DesktopSettings {
    private val KEY_PAIRING_CODE = "tablet.pairingCode"
    private val KEY_INSTANCE_ID = "tablet.lastKnownInstanceId"
    private val KEY_DARK_MODE = "ui.darkMode"
    private val KEY_BACKDROP = "ui.backdropTheme"

    fun load(): Properties {
        val props = Properties()
        val f = File(AppPaths.settingsPath)
        if (f.exists()) {
            runCatching { f.inputStream().use { props.load(it) } }
        }
        return props
    }

    fun save(props: Properties) {
        runCatching {
            val f = File(AppPaths.settingsPath)
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "InkFlow desktop local settings") }
        }
    }

    var pairingCode: String?
        get() = load().getProperty(KEY_PAIRING_CODE)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val props = load()
            if (value.isNullOrBlank()) props.remove(KEY_PAIRING_CODE) else props.setProperty(KEY_PAIRING_CODE, value.trim())
            save(props)
        }

    var lastKnownInstanceId: String?
        get() = load().getProperty(KEY_INSTANCE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val props = load()
            if (value.isNullOrBlank()) props.remove(KEY_INSTANCE_ID) else props.setProperty(KEY_INSTANCE_ID, value.trim())
            save(props)
        }

    /**
     * Persisted theme choice.
     *
     * Both settings went back to dark on every launch, which made the pickers look
     * broken rather than unsaved. Read and write go through the same Properties file
     * as the pairing code, so there is one place that can fail and one file to
     * inspect when it does.
     */
    var darkMode: Boolean
        get() = load().getProperty(KEY_DARK_MODE)?.toBooleanStrictOrNull() ?: true
        set(value) {
            val props = load()
            props.setProperty(KEY_DARK_MODE, value.toString())
            save(props)
        }

    var backdropThemeName: String
        get() = load().getProperty(KEY_BACKDROP) ?: "SOFT"
        set(value) {
            val props = load()
            props.setProperty(KEY_BACKDROP, value)
            save(props)
        }
}

@Composable
fun App() {
    InkFlowTheme {
        // The backdrop has to sit *behind* everything, including the reading
        // surface, so it is applied at the root rather than per-panel. A
        // translucent panel with a flat colour behind it has nothing to reveal
        // and the whole glass material reads as a flat tint.
        //
        // This is the tablet's own `AuroraBackground` (`:shared`), not a
        // desktop imitation. It used to be a hand-rolled three-layer gradient
        // whose "corners" were fractions of a hard-coded 1600x1000 rather than
        // the real window size — so the glow anchoring drifted as the window
        // resized, contradicting the comment right above it. The real one derives
        // every position from the actual constraints.
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraBackground(
                isDarkTheme = InkThemeState.darkMode,
                // 12 orbs is what the tablet's library uses; the editor drops to 5
                // because paper covers most of the window there.
                orbCount = 12,
                theme = InkThemeState.backdropTheme,
                modifier = Modifier.fillMaxSize()
            )
            InkFlowApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkFlowApp() {
    val databaseManager = remember { DatabaseManager(AppPaths.dbPath).also { it.connect() } }
    // v5: one outbox shared by the editor (records) and the sync loop (sends).
    // Shared rather than owned by either side so the queue survives both: the editor
    // writes without knowing about sockets, and the sync loop sends without knowing
    // about gestures.
    val proposalQueue = remember { ProposalQueue(databaseManager) }
    val syncManager = remember {
        LocalSyncManager(databaseManager, AppPaths.dir, psk = DesktopSettings.pairingCode, proposalQueue = proposalQueue)
    }

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
    var pairingCode by remember { mutableStateOf(DesktopSettings.pairingCode ?: "") }

    LaunchedEffect(Unit) {
        while (true) {
            peerCount = syncManager.devices.count { System.currentTimeMillis() - it.lastSeenMillis < 90_000 }
            peerNames = syncManager.devices.joinToString("、") { it.deviceName }
            if (!isSyncing && syncManager.isSyncing) isSyncing = true
            if (isSyncing && !syncManager.isSyncing) {
                syncManager.lastSyncResult?.let { r ->
                    // Pending proposals and conflict notes come from the outbox, not
                    // the result: they describe what did NOT travel, which is exactly
                    // what the user needs to see when the counts look fine.
                    val pending = proposalQueue.pendingOpCount()
                    val notes = proposalQueue.drainNotices()
                    lastSyncSummary = "文件 ${r.documentsUpdated} · 筆跡 ${r.strokesPulled} · " +
                        "文字 ${r.textsPulled} · PDF ${r.filesTransferred} · 衝突保留 ${r.conflictsSkipped}" +
                        (if (r.proposalsAccepted > 0) " · 已送出 ${r.proposalsAccepted}" else "") +
                        (if (r.proposalConflicts > 0) " · 提案衝突 ${r.proposalConflicts}" else "") +
                        (if (pending > 0) " · 待送出 $pending" else "") +
                        (if (r.errors.isNotEmpty()) " · 錯誤 ${r.errors.size}" else "") +
                        notes.joinToString("") { " · $it" }
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
            // Glass, not a solid M3 surfaceVariant fill. The bar floats over the
            // reading surface, so it has to be translucent or the page stops
            // running under it and the layout reads as two stacked documents.
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.fauxGlassPanel(
                isDark = InkThemeState.darkMode,
                shape = RectangleShape,
                // Full-height bar: a rim down both long edges is noise, so only the
                // bottom hairline matters and the sheen carries the rest.
                specular = false
            ),
            actions = {
                // Sync status chip. Connected state reads as "armed" so it breaks
                // from the neutral glass; disconnected stays glass.
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .then(
                            if (peerCount > 0) {
                                Modifier.clip(ShapeSm).background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                )
                            } else {
                                Modifier.bubbleGlass(InkThemeState.darkMode)
                                    .padding(0.dp)
                            }
                        )
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
                                val pending = proposalQueue.pendingOpCount()
                                val notes = proposalQueue.drainNotices()
                                lastSyncSummary = "手動同步：文件 ${result.documentsUpdated} · 筆跡 ${result.strokesPulled} · " +
                                    "文字 ${result.textsPulled} · PDF ${result.filesTransferred} · 衝突保留 ${result.conflictsSkipped}" +
                                    (if (result.proposalsAccepted > 0) " · 已送出 ${result.proposalsAccepted}" else "") +
                                    (if (result.proposalConflicts > 0) " · 提案衝突 ${result.proposalConflicts}" else "") +
                                    (if (pending > 0) " · 待送出 $pending" else "") +
                                    (if (result.errors.isNotEmpty()) " · 錯誤 ${result.errors.size}" else "") +
                                    notes.joinToString("") { " · $it" }
                                isSyncing = false
                                libraryRefresh++
                            }
                        }
                    },
                    enabled = !isSyncing
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "立即同步")
                }
                // Dark mode toggle. Writes through to disk on every change: the
                // alternative is a setting that appears to work and then forgets,
                // which is worse than not having it.
                IconButton(
                    onClick = {
                        val next = !InkThemeState.darkMode
                        InkThemeState.darkMode = next
                        DesktopSettings.darkMode = next
                    }
                ) {
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
                modifier = Modifier
                    .width(88.dp)
                    // Chrome, not content: the rail floats over whatever is to its
                    // right, so a solid M3 fill here makes the layout read as two
                    // separate apps bolted together. Leading rim, because on a
                    // full-height strip that is the only edge you ever see.
                    .glassSidePanel(InkThemeState.darkMode),
                containerColor = Color.Transparent,
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
                        lastSyncSummary = lastSyncSummary,
                        pairingCode = pairingCode,
                        onPairingCodeChange = { code ->
                            pairingCode = code
                            syncManager.setPairingCode(code)
                            // Persist on every keystroke rather than on blur: the user
                            // has no "save" affordance here, and losing the code on a
                            // crash would mean re-reading it off the tablet screen.
                            DesktopSettings.pairingCode = code
                        }
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
                          // PdfManager.mirroredDir() is the same directory — hard-coding a second
                          // spelling here would let an import land somewhere the
                          // viewer never looks, and the document would open as
                          // "file not found".
                          mirrorRoot = com.vic.inkflow.util.PdfManager.mirroredDir().absolutePath,
                          onLibraryChanged = { libraryRefresh++ },
                          modifier = Modifier.fillMaxSize()
                      )
                    else -> Row(Modifier.fillMaxSize()) {
                        PdfViewer(
                            documentUri = selectedDocument!!,
                            pageIndex = currentPageIndex,
                            databaseManager = databaseManager,
                            onPageChange = { currentPageIndex = it },
                              // Ink is opt-in (see the parameter's doc). Local edits are
                              // filed into the v5 proposal queue and pushed for tablet
                              // arbitration on the next sync; without a tablet they stay
                              // local until one appears.
                              editable = true,
                              onInkChanged = { libraryRefresh++ },
                              proposalQueue = proposalQueue,
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
private fun SettingsView(
    peerNames: String,
    lastSyncSummary: String?,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit
) {
    // Settings is chrome, so it gets the glass panel — but the text inside stays
    // on an opaque run. A translucent panel behind small type is the fastest way
    // to make a setting screen unreadable.
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp)
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeXl)
                .fauxGlassPanel(isDark = InkThemeState.darkMode, shape = ShapeXl)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingRow("主題模式", if (InkThemeState.darkMode) "深色（閱讀預設）" else "淺色")

            // Backdrop intensity. The tablet picks this in its own settings screen;
            // the desktop had no control because it had no real backdrop to tune.
            // Now that it runs the tablet's AuroraBackground, the four presets mean
            // something here too — and being able to flip between them is how you
            // judge whether the backdrop is the thing making a screen look wrong.
            Text(
                "背景",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackdropTheme.entries.forEach { t ->
                    val selected = InkThemeState.backdropTheme == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .fauxGlassPanel(
                                isDark = InkThemeState.darkMode,
                                shape = RoundedCornerShape(50),
                                specular = !selected
                            )
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                else Color.Transparent
                            )
                            // pressableGlass, not a bare clickable: it is the shared
                            // press entry, so these chips get the same sweep and scale
                            // as every other control in the app.
                            .pressableGlass(
                                isDark = InkThemeState.darkMode,
                                shape = RoundedCornerShape(50),
                                onClick = {
                                    InkThemeState.backdropTheme = t
                                    DesktopSettings.backdropThemeName = t.name
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            InkThemeState.backdropLabels[t] ?: t.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            SettingRow("資料目錄", AppPaths.dir)
            SettingRow("資料庫", AppPaths.dbPath)
            SettingRow("已連線設備", if (peerNames.isNotBlank()) peerNames else "無（請確認平板與電腦同一 Wi-Fi，且兩端均開啟 InkFlow）")
            SettingRow("上次同步", lastSyncSummary ?: "尚無記錄")

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Text(
                "平板配對碼",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "在平板「設定 → 電腦同步」開啟同步伺服器後，把畫面上的 8 位數配對碼填在這裡。" +
                    "留空則不驗證——同一個 Wi-Fi 上任何人都能讀走你的全部文件，所以建議填。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = onPairingCodeChange,
                    singleLine = true,
                    label = { Text("配對碼") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(min = 200.dp)
                )
                // Live validation: the tablet's code is exactly 8 digits, so a
                // non-conforming value can only be a typo. Saying so here beats a
                // handshake rejection on the next sync with no explanation.
                val normalised = pairingCode.trim()
                val looksRight = normalised.isEmpty() ||
                    (normalised.length == 8 && normalised.all { it.isDigit() })
                Text(
                    when {
                        normalised.isEmpty() -> "未設定"
                        looksRight -> "已設定"
                        else -> "應該是 8 位數字"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (looksRight) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Text(
                "本機不同步雲端：所有數據僅在局域網內傳輸。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

fun main() {
    // The data dir has to exist before anything logs into it (logback.xml writes to
    // %USERPROFILE%/.inkflow) and before the database opens.
    File(AppPaths.dir).mkdirs()

    // Same artwork the EXE carries, but loaded from a PNG rather than the .ico:
    // ImageIO ships no ICO reader, so `ImageIO.read` on the packaged icon would
    // return null and the taskbar would silently fall back to Compose's default.
    val icon: Painter? = loadWindowIcon()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "InkFlow",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            icon = icon
        ) {
            App()
        }
    }
}

/**
 * Loads the window icon from the classpath.
 *
 * Two things this has to work around:
 *
 *  - The `.ico` in the same folder is what jpackage embeds in the EXE, but
 *    ImageIO ships no ICO reader, so `ImageIO.read` on it returns null and the
 *    taskbar silently falls back to Compose's default. Hence the PNG twin.
 *  - `Window(icon = ...)` wants a `Painter`, not an `ImageBitmap`.
 *
 * Not `@Composable`: `main()` is not a composable function, so `remember` is not
 * available there, and an icon that never changes has nothing to recompose.
 */
private fun loadWindowIcon(): Painter? {
    val bytes = object {}.javaClass.getResourceAsStream("/inkflow.png")?.use { it.readBytes() }
        ?: run {
            logger.warn { "inkflow.png is not on the classpath; the taskbar will show a default icon" }
            return null
        }
    return try {
        val awt = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        BitmapPainter(awt.toComposeImageBitmap())
    } catch (e: Exception) {
        logger.warn(e) { "window icon could not be decoded; the taskbar will show a default" }
        null
    }
}
