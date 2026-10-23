package com.vic.inkflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vic.inkflow.agent.LocalModelConfig
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.sync.LocalSyncManager
import com.vic.inkflow.sync.ProposalQueue
import com.vic.inkflow.ui.AuroraBackground
import com.vic.inkflow.ui.AgentPanel
import com.vic.inkflow.ui.BackdropTheme
import com.vic.inkflow.ui.PdfViewer
import com.vic.inkflow.ui.InkFlowTheme
import com.vic.inkflow.ui.InkThemeState
import com.vic.inkflow.ui.LibraryView
import com.vic.inkflow.ui.SettingsViewModel
import com.vic.inkflow.ui.theme.ShapeXl
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.chromeGlass
import com.vic.inkflow.ui.glassClickable
import com.vic.inkflow.ui.glassContentColor
import com.vic.inkflow.ui.glassFieldColors
import com.vic.inkflow.ui.glassSelectionPill
import com.vic.inkflow.ui.GlassOptionChip
import com.vic.inkflow.ui.kit.DeskDivider
import com.vic.inkflow.ui.kit.DeskScreen
import com.vic.inkflow.ui.kit.DeskSectionLabel
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.probe.PenProbeScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

import mu.KotlinLogging
import java.io.File
import java.util.Properties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // ── Local model (the AI agent's engine) ────────────────────────────────
    //
    // The model server is local and the user starts it themselves, so these
    // are just connection details. The API key is optional: llama.cpp and
    // LM Studio ignore it unless the server was started with one.
    //
    // They live in the same properties file as the pairing code because they
    // are the same kind of thing: local settings that never leave the
    // machine and that the user can delete by deleting one file.

    private val KEY_MODEL_BASE_URL = "model.baseUrl"
    private val KEY_MODEL_NAME = "model.name"
    private val KEY_MODEL_API_KEY = "model.apiKey"
    private val KEY_MODEL_TEMPERATURE = "model.temperature"
    private val KEY_MODEL_SEND_IMAGES = "model.sendImages"

    var modelBaseUrl: String
        get() = load().getProperty(KEY_MODEL_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "http://localhost:8080"
        set(value) {
            val props = load()
            props.setProperty(KEY_MODEL_BASE_URL, value.trim())
            save(props)
        }

    var modelName: String
        get() = load().getProperty(KEY_MODEL_NAME)?.trim().orEmpty()
        set(value) {
            val props = load()
            props.setProperty(KEY_MODEL_NAME, value.trim())
            save(props)
        }

    var modelApiKey: String
        get() = load().getProperty(KEY_MODEL_API_KEY)?.trim().orEmpty()
        set(value) {
            val props = load()
            props.setProperty(KEY_MODEL_API_KEY, value.trim())
            save(props)
        }

    /** `null` means "let the server decide". */
    var modelTemperature: Double?
        get() = load().getProperty(KEY_MODEL_TEMPERATURE)?.trim()?.toDoubleOrNull()
        set(value) {
            val props = load()
            if (value == null) props.remove(KEY_MODEL_TEMPERATURE)
            else props.setProperty(KEY_MODEL_TEMPERATURE, value.toString())
            save(props)
        }

    /** Whether page images are sent to the model (only if it can see them). */
    var modelSendImages: Boolean
        get() = load().getProperty(KEY_MODEL_SEND_IMAGES)?.toBooleanStrictOrNull() ?: true
        set(value) {
            val props = load()
            props.setProperty(KEY_MODEL_SEND_IMAGES, value.toString())
            save(props)
        }

    /** The provider configuration the agent uses. */
    fun localModelConfig(): LocalModelConfig = LocalModelConfig(
        baseUrl = modelBaseUrl,
        model = modelName,
        apiKey = modelApiKey,
        temperature = modelTemperature
    )
}

@Composable
fun App(uiPreview: String? = null) {
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
            InkFlowApp(uiPreview)
        }
    }
}

@Composable
fun InkFlowApp(uiPreview: String? = null) {
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
    var showSettings by remember { mutableStateOf(uiPreview == "settings") }
    // The AI panel is a reader companion: it works on the page in front of the
    // user, so it only makes sense with a document open.
    var showAgent by remember { mutableStateOf(uiPreview == "ai") }
    // The bridge between the panel and the reader's page state.
    val noteLanding = remember { com.vic.inkflow.agent.NoteLandingHandle() }
    var currentPageIndex by remember { mutableStateOf(0) }

    // `--ui=settings|reader|ai` opens straight onto a screen with the most
    // recently opened document. It exists so the UI can be looked at without a
    // human driving the mouse: a screenshot script cannot click a window that a
    // fullscreen app is covering, and a screen you cannot see is a screen you
    // cannot judge. No effect on a normal launch.
    LaunchedEffect(uiPreview) {
        if (uiPreview == "reader" || uiPreview == "ai") {
            selectedDocument = runCatching {
                databaseManager.getAllDocuments().firstOrNull()?.uri
            }.getOrNull()
        }
    }

    // ── Sync state (polled so the indicator tracks background auto-sync) ────
    var isSyncing by remember { mutableStateOf(false) }
    var libraryRefresh by remember { mutableStateOf(0) }
    var lastSyncSummary by remember { mutableStateOf<String?>(null) }
    var peerCount by remember { mutableStateOf(0) }
    var peerNames by remember { mutableStateOf("") }


    // Write-through sync state (see triggerEditSync below): a filed proposal
    // must not wait for the next 28s poll tick. Declared before the poll loop
    // so the loop can fire an armed request when a running pass ends.
    val scope = rememberCoroutineScope()
    var editSyncArmed by remember { mutableStateOf(false) }
    var editSyncJob by remember { mutableStateOf<Job?>(null) }

    // Manual sync, shared by every entry point. Defined before the poll loop so
    // the loop can fire an armed write-through request when a pass ends; the
    // summary string is built once here so triggers cannot drift apart.
    fun requestManualSync() {
        if (!isSyncing) {
            isSyncing = true
            syncManager.requestSyncNow { result ->
                val pending = proposalQueue.pendingOpCount()
                val notes = proposalQueue.drainNotices()
                lastSyncSummary = "手動同步：文件 ${result.documentsUpdated} · 筆跡 ${result.strokesPulled} · " +
                    "文字 ${result.textsPulled} · 分類 ${result.foldersSynced} · PDF ${result.filesTransferred} · 未變更 ${result.unchangedSkipped}" +
                    (if (result.proposalsAccepted > 0) " · 已送出 ${result.proposalsAccepted}" else "") +
                    (if (result.proposalConflicts > 0) " · 提案衝突 ${result.proposalConflicts}" else "") +
                    (if (pending > 0) " · 待送出 $pending" else "") +
                    (if (result.errors.isNotEmpty()) " · 錯誤 ${result.errors.size}" else "") +
                    notes.joinToString("") { " · $it" }
                isSyncing = false
                libraryRefresh++
            }
        }
    }

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
                        "文字 ${r.textsPulled} · 分類 ${r.foldersSynced} · PDF ${r.filesTransferred} · 未變更 ${r.unchangedSkipped}" +
                        (if (r.proposalsAccepted > 0) " · 已送出 ${r.proposalsAccepted}" else "") +
                        (if (r.proposalConflicts > 0) " · 提案衝突 ${r.proposalConflicts}" else "") +
                        (if (pending > 0) " · 待送出 $pending" else "") +
                        (if (r.errors.isNotEmpty()) " · 錯誤 ${r.errors.size}" else "") +
                        notes.joinToString("") { " · $it" }
                }
                isSyncing = false
                libraryRefresh++
            }
            // A write-through request armed while the pass was running fires now:
            // the finished pass may have moved past the edited file already.
            if (editSyncArmed) {
                editSyncArmed = false
                requestManualSync()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Strokes reset the 1.5s quiet timer, so one burst costs one pass; if a pass
    // is already running the request stays armed and the poll loop above fires
    // it when that pass ends.
    fun triggerEditSync() {
        editSyncArmed = true
        editSyncJob?.cancel()
        editSyncJob = scope.launch {
            delay(1500)
            if (!isSyncing) {
                editSyncArmed = false
                requestManualSync()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // No top app bar: it cost a full row of vertical space on every screen for
        // controls that all have better homes now — Library/Sync/Settings and the
        // theme toggle live in the dock, the peer count rides the Sync icon, the
        // sync detail lives in the library pill and settings, and the document
        // title rides the reader toolbar where the document actually is.

        // ── Body: tablet-style floating dock + content (+ AI panel in reader) ──
        // The old M3 NavigationRail read as a second app bolted to the side.
        // The tablet uses a floating 76dp glass capsule with a brand dot and
        // self-drawn rail items, so the desktop does the same. Destinations stay
        // desktop ones (Library/Sync/Settings); only the material changes.
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                    .width(76.dp)
                    .chromeGlass(isDark = InkThemeState.darkMode, shape = ShapeMd)
                    .padding(vertical = 8.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                // The real app artwork (same PNG the window icon and the EXE use),
                // not a gradient dot with a letter: one mark everywhere.
                BrandIcon(modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                DesktopRailItem(
                    selected = selectedDocument == null && !showSettings,
                    onClick = { selectedDocument = null; showSettings = false },
                    icon = Icons.Outlined.Home,
                    label = "文件庫"
                )
                DesktopRailItem(
                    selected = false,
                    busy = isSyncing || syncManager.isSyncing,
                    badgeCount = peerCount,
                    onClick = { requestManualSync() },
                    icon = Icons.Outlined.Sync,
                    label = "同步"
                )
                DesktopRailItem(
                    selected = showSettings,
                    onClick = { showSettings = true; selectedDocument = null },
                    icon = Icons.Outlined.Settings,
                    label = "設定"
                )
                // The AI panel only exists for a document — it turns the page in
                // front of the user into a study note.
                if (selectedDocument != null) {
                    DesktopRailItem(
                        selected = showAgent,
                        onClick = { showAgent = !showAgent },
                        icon = Icons.Filled.AutoAwesome,
                        label = "AI"
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val next = !InkThemeState.darkMode
                    InkThemeState.darkMode = next
                    DesktopSettings.darkMode = next
                }) {
                    Icon(
                        imageVector = if (InkThemeState.darkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = "切換主題",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Peer presence as words, not just a coloured dot: colour-only
                // status is invisible to anyone who cannot distinguish it.
                Text(
                    if (peerCount > 0) "$peerCount 在線" else "離線",
                    color = if (peerCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Screen transitions speak the tablet's language: the incoming screen
            // slides in from the right and fades in, the outgoing slides a
            // quarter-width out to the left and fades — the same spec as the
            // tablet's NavHost (Motion SLOW). The old plain crossfade was wrong
            // for two reasons: a pure blend of two heavy screens reads as a
            // muddy smear rather than navigation, and with no direction the user
            // cannot tell whether they went deeper or back out.
            AnimatedContent(
                targetState = showSettings to selectedDocument,
                transitionSpec = {
                    (slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(Motion.DURATION_SLOW)
                    ) + fadeIn(
                        animationSpec = tween(Motion.DURATION_SLOW)
                    )) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { -it / 4 },
                        animationSpec = tween(Motion.DURATION_SLOW)
                    ) + fadeOut(
                        animationSpec = tween(Motion.DURATION_SLOW)
                    ))
                },
                label = "ScreenSwitch",
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { screen ->
                val (settingsOpen, doc) = screen
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        settingsOpen -> {
                            val settingsVm = remember { SettingsViewModel() }
                            SettingsView(
                                viewModel = settingsVm,
                                peerNames = peerNames,
                                lastSyncSummary = lastSyncSummary,
                                onPairingCodeChange = { code ->
                                    syncManager.setPairingCode(code)
                                }
                            )
                        }
                        doc == null -> {
                            val docRepo: com.vic.inkflow.data.JdbcDocumentRepository = org.koin.core.context.GlobalContext.get().get()
                            val folderRepo: com.vic.inkflow.data.JdbcFolderRepository = org.koin.core.context.GlobalContext.get().get()
                            val scope = rememberCoroutineScope()
                            val libraryVm = remember(databaseManager) {
                                com.vic.inkflow.ui.LibraryViewModel(
                                    documentRepository = docRepo,
                                    folderRepository = folderRepo,
                                    scope = scope
                                )
                            }
                            LibraryView(
                                viewModel = libraryVm,
                                syncStatusText = lastSyncSummary
                                    ?: if (peerCount > 0) "已連接：$peerNames"
                                       else "未偵測到平板（等待局域網發現）",
                                onDocumentSelected = { uri ->
                                    selectedDocument = uri
                                    currentPageIndex = 0
                                },
                                mirrorRoot = com.vic.inkflow.util.PdfManager.mirroredDir().absolutePath,
                                onLibraryChanged = { libraryRefresh++ },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> Row(Modifier.fillMaxSize()) {
                            // Display name from the database, not the URI: synced URIs
                            // are opaque keys (not file paths), so chopping the URI
                            // showed garbage in the toolbar title.
                            val displayName = remember(doc) {
                                runCatching { databaseManager.getDocument(doc)?.displayName }
                                    .getOrNull()?.takeIf { it.isNotBlank() }
                                    ?: doc.substringAfterLast('/').substringAfterLast('\\')
                            }
                            PdfViewer(
                                documentUri = doc,
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
                                onLocalEdit = { triggerEditSync() },
                                // The same token drives the library: every sync pass and
                                // every local edit bumps it, so the reader reloads its
                                // page from the database instead of showing stale ink.
                                refreshToken = libraryRefresh,
                                documentTitle = displayName,
                                noteLanding = noteLanding,
                                // `weight`, not `fillMaxSize`: in a Row the reader
                                // used to take the whole width and squeeze the AI
                                // panel to zero, so the panel never actually
                                // appeared next to the page.
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            if (showAgent) {
                                AgentPanel(
                                    documentUri = doc,
                                    pageIndex = currentPageIndex,
                                    landing = noteLanding,
                                    onToggleCollapsed = { showAgent = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandIcon(modifier: Modifier = Modifier) {
    // Loaded from the jar like the window icon: `desktopApp/src/main/resources`
    // ships the same PNG the taskbar and the packaged EXE carry.
    val painter = remember {
        runCatching {
            val bytes = object {}.javaClass.getResourceAsStream("/inkflow.png")?.use { it.readBytes() }
                ?: return@runCatching null
            val awt = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                ?: return@runCatching null
            BitmapPainter(awt.toComposeImageBitmap())
        }.getOrNull()
    }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = "InkFlow",
            modifier = modifier.clip(CircleShape)
        )
    } else {
        // Artwork missing must never blank the dock: fall back to the brand tint.
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "I",
                style = MaterialTheme.typography.titleMedium,
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

@Composable
private fun DesktopRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    busy: Boolean = false,
    badgeCount: Int = 0
) {
    // Tablet GlassRailItem in desktop material: selected state rides the shared
    // selection pill, press rides glassClickable, and the icon/label follow the
    // same selected-primary / unselected-muted language. Desktop has no Haze, so
    // the pill is the faux shared one, not the tablet's Haze-backed panel.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .then(if (selected) Modifier.glassSelectionPill(CircleShape) else Modifier)
            .glassClickable(onClick = onClick, shape = CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = if (badgeCount > 0) "$label（$badgeCount 台在線）" else label,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Peer count rides the icon: the top bar that used to carry
                // "N 台設備在線" is gone, and the number has to live somewhere
                // that is visible on every screen.
                if (badgeCount > 0) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(horizontal = 3.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsView(
    viewModel: SettingsViewModel,
    peerNames: String,
    lastSyncSummary: String?,
    onPairingCodeChange: (String) -> Unit
) {
    // Settings used to be a 28dp-padded column that could not scroll, so on a
    // short window the last rows (the pairing code, the privacy note) simply fell
    // off the bottom with no way to reach them. It now sits on the same scaffold
    // as every other screen — title and actions in the header, one scrolling body
    // with a bounded reading width — and the section cards are the only chrome.
    val state by viewModel.state.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .chromeGlass(isDark = InkThemeState.darkMode, shape = ShapeXl)
                    .padding(DeskSpace.lg),
                verticalArrangement = Arrangement.spacedBy(DeskSpace.sm)
            ) {
                DeskSectionLabel("外觀")
                SettingRow("主題模式", if (InkThemeState.darkMode) "深色（閱讀預設）" else "淺色")

                // Backdrop intensity. The tablet picks this in its own settings
                // screen; the desktop had no control because it had no real
                // backdrop to tune. Now that it runs the tablet's
                // AuroraBackground, the four presets mean something here too.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DeskSpace.sm)
                ) {
                    Text(
                        "背景",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.width(140.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs)) {
                        BackdropTheme.entries.forEach { t ->
                            GlassOptionChip(
                                text = t.label,
                                selected = InkThemeState.backdropTheme == t,
                                onClick = {
                                    InkThemeState.backdropTheme = t
                                    DesktopSettings.backdropThemeName = t.name
                                },
                                isDark = InkThemeState.darkMode
                            )
                        }
                    }
                }

                DeskDivider(Modifier.padding(vertical = DeskSpace.xs))

                DeskSectionLabel("本機資料")
                SettingRow("資料目錄", AppPaths.dir)
                SettingRow("資料庫", AppPaths.dbPath)

                DeskDivider(Modifier.padding(vertical = DeskSpace.xs))

                DeskSectionLabel("平板同步")
                SettingRow(
                    "已連線設備",
                    if (peerNames.isNotBlank()) peerNames
                    else "無（請確認平板與電腦同一 Wi-Fi，且兩端均開啟 InkFlow）"
                )
                SettingRow("上次同步", lastSyncSummary ?: "尚無記錄")

                Spacer(Modifier.height(DeskSpace.xxs))

                Text("平板配對碼", style = MaterialTheme.typography.titleSmall)
                Text(
                    "在平板「設定 → 電腦同步」開啟同步伺服器後，把畫面上的 8 位數配對碼填在這裡。" +
                        "留空則不驗證——同一個 Wi-Fi 上任何人都能讀走你的全部文件，所以建議填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DeskSpace.sm)
                ) {
                    DeskTextField(
                        value = state.pairingCode,
                        onValueChange = { code ->
                            viewModel.onEvent(SettingsViewModel.SettingsEvent.SetPairingCode(code))
                            onPairingCodeChange(code)
                        },
                        placeholder = "8 位數字",
                        modifier = Modifier.width(200.dp)
                    )
                    // Live validation: the tablet's code is exactly 8 digits, so
                    // a non-conforming value can only be a typo. Saying so here
                    // beats a handshake rejection with no explanation.
                    val normalised = state.pairingCode.trim()
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
            }

            Spacer(Modifier.height(DeskSpace.md))
            Text(
                "本機不同步雲端：所有數據僅在局域網內傳輸。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(DeskSpace.lg))
        }
    }

@Composable
private fun SettingRow(label: String, value: String) {
    // Label column is fixed so the values line up down the card; a
    // `SpaceBetween` row made every value start wherever its label happened to
    // end, which reads as a ragged table.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(140.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

fun main(args: Array<String>) {
    // The data dir has to exist before anything logs into it (logback.xml writes to
    // %USERPROFILE%/.inkflow) and before the database opens.
    File(AppPaths.dir).mkdirs()

    // Same artwork the EXE carries, but loaded from a PNG rather than the .ico:
    // ImageIO ships no ICO reader, so `ImageIO.read` on the packaged icon would
    // return null and the taskbar would silently fall back to Compose's default.
    val icon: Painter? = loadWindowIcon()

    // A0.1 / B2.0 probes (TEMPORARY): `--probe=pen` and `--probe=llm` swap the
    // whole app for the probe window. They run inside the same window/runtime as
    // the product, so the numbers they report are the numbers the packaged app
    // produces — which is the only measurement worth acting on. The packaged EXE
    // matters here specifically: a trimmed jpackage runtime can lack a module
    // (`java.net.http`) that the full development JDK has. See `probe/PenProbe.kt`
    // and `probe/LlmProbe.kt`.
    val probe = args.firstOrNull { it.startsWith("--probe=") }
        ?.substringAfter("=")
        ?.trim()
        ?.lowercase()

    fun probeArg(name: String): String? = args
        .firstOrNull { it.startsWith("--$name=") }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // Where the model server listens is the user's choice, so both addresses are
    // tried unless one is given. `--llm-runs` trades wall-clock time for a
    // tighter estimate of the structured-output pass rate.
    val llmOptions = com.vic.inkflow.probe.LlmProbeOptions(
        urls = probeArg("llm-url")?.let { listOf(it) }
            ?: com.vic.inkflow.probe.LlmProbeOptions().urls,
        model = probeArg("llm-model"),
        imagePath = probeArg("llm-image"),
        runs = probeArg("llm-runs")?.toIntOrNull() ?: 5
    )

    // Start Koin (Milestone 6): the repository graph is resolved from here.
    org.koin.core.context.startKoin {
        modules(com.vic.inkflow.appModule)
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = when (probe) {
                "pen" -> "InkFlow — pen probe"
                "llm" -> "InkFlow — LLM probe"
                else -> "InkFlow"
            },
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            icon = icon
        ) {
            when (probe) {
                "pen" -> PenProbeScreen()
                "llm" -> com.vic.inkflow.probe.LlmProbeScreen(llmOptions)
                else -> App(uiPreview = probeArg("ui"))
            }
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
