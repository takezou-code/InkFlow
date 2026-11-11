package com.vic.inkflow.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.sync.SyncIdentityStore
import com.vic.inkflow.sync.TabletSyncService
import com.vic.inkflow.ui.backdrop.BackdropScene
import com.vic.inkflow.ui.backdrop.DuneScene
import com.vic.inkflow.ui.backdrop.backdropScenes
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.util.AutoBackupScheduler
import com.vic.inkflow.util.BackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 設定頁版面規則（改版後維持這四條，新增一列照抄）：
 *
 *  1. 內容收在 820dp 的中央直欄。平板橫向原本是滿版，控制項被拉成超寬一條，
 *     校正方框甚至長成整個螢幕大的正方形——這是「看起來亂」的根源，不是顏色。
 *  2. 一節＝標題（加一條細線收尾）＋一張 faux 玻璃卡。卡內每一列固定
 *     左右 20dp／上下 12～14dp，控制項一律靠右或撐滿，不再有 16/8/12 混用。
 *  3. 選項列一律 [GlassSegmentedBar]，下拉一律 [GlassMenu]；不用 M3 OutlinedTextField，
 *     它在設定頁上又高又重，跟分段條擺在一起像兩套語言。
 *  4. 說明文字只留一段，其餘改成標題／副標的字階差異；重複的按鈕（兩個「歸零」、
 *     兩個「新增顏色」）直接刪掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSettingsScreen(
    prefs: SharedPreferences,
    onNavigateBack: () -> Unit,
    currentThemeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onPowerSaverChanged: (Boolean) -> Unit = {},
    backdropTheme: BackdropTheme = BackdropTheme.SOFT,
    onBackdropThemeChanged: (BackdropTheme) -> Unit = {},
    backdropKind: BackdropKind = BackdropKind.ORB,
    onBackdropKindChanged: (BackdropKind) -> Unit = {},
    backdropScene: BackdropScene = DuneScene,
    onBackdropSceneChanged: (BackdropScene) -> Unit = {},
    backdropImageUri: android.net.Uri? = null,
    onBackdropImageChanged: (android.net.Uri?) -> Unit = {},
    /** 目前的重點色；AppNav 是唯一來源，這裡只負責顯示與回報。 */
    accentColor: Color = com.vic.inkflow.ui.theme.DefaultAccentColor,
    onAccentChanged: (Color) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    // 對話框自判深淺
    val isDarkSettings = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var defaultInputMode by remember { mutableStateOf(prefs.getString("default_input_mode", InputMode.FREE.name) ?: InputMode.FREE.name) }
    var defaultQuickSwipe by remember { mutableStateOf(prefs.getBoolean("default_quick_swipe_eraser_enabled", false)) }
    var defaultAutoSwitchToPenAfterErase by remember { mutableStateOf(prefs.getBoolean("default_auto_switch_to_pen_after_erase", false)) }
    var defaultStrokeSpeedSensitivity by remember { mutableFloatStateOf(prefs.getFloat("default_stroke_speed_sensitivity", 1f)) }
    // 粗細靈敏度（全域）：0=鈍（跟隨慢、穩）→1=靈（跟隨快、活），預設 0.33
    var defaultWidthResponsiveness by remember { mutableFloatStateOf(prefs.getFloat("default_width_responsiveness", 0.33f)) }
    // 手指觸控落筆校正（全域，副廠電容筆專用）：開關＋XY 偏移 dp
    var touchCalEnabled by remember { mutableStateOf(prefs.getBoolean("default_touch_cal_enabled", false)) }
    var touchCalDxDp by remember { mutableFloatStateOf(prefs.getFloat("default_touch_cal_dx_dp", 0f)) }
    var touchCalDyDp by remember { mutableFloatStateOf(prefs.getFloat("default_touch_cal_dy_dp", 0f)) }
    fun persistTouchCal() {
        prefs.edit()
            .putBoolean("default_touch_cal_enabled", touchCalEnabled)
            .putFloat("default_touch_cal_dx_dp", touchCalDxDp)
            .putFloat("default_touch_cal_dy_dp", touchCalDyDp)
            .apply()
    }
    var defaultPenColor by remember { mutableIntStateOf(prefs.getInt("default_pen_color", 0xFF000000.toInt())) }
    var defaultHighlighterColor by remember { mutableIntStateOf(prefs.getInt("default_highlighter_color", 0xFFFFC700.toInt())) }
    var defaultPenWidth by remember { mutableFloatStateOf(prefs.getFloat("default_pen_width", 4f)) }
    var defaultHighlighterWidth by remember { mutableFloatStateOf(prefs.getFloat("default_highlighter_width", 8f)) }
    var defaultBackground by remember { mutableStateOf(prefs.getString("default_paper_background", PageBackground.BLANK.name) ?: PageBackground.BLANK.name) }
    // 舊 prefs 若存著已退役的 PALM_REJECTION，一次洗成 STYLUS_ONLY（跟編輯器實際行為一致）
    LaunchedEffect(Unit) {
        if (defaultInputMode == InputMode.PALM_REJECTION.name) {
            defaultInputMode = InputMode.STYLUS_ONLY.name
            prefs.edit().putString("default_input_mode", InputMode.STYLUS_ONLY.name).apply()
        }
    }

    // --- AI 來源（可多選）-----------------------------------------------------
    // 只有列在清單裡的 AI 會出現在工具列切換；至少保留一個，否則使用者會把自己鎖死。
    var enabledAiState by remember {
        mutableStateOf(parseEnabledProviders(prefs.getString(KEY_AI_ENABLED_PROVIDERS, null)))
    }
    // 自訂網址編輯中的 provider（null＝沒開對話框）。同一家 AI 常有國際版／中國版
    // 兩個入口，哪個登得進去是網站政策，不該逼使用者等我們出包才能改。
    var aiUrlEditing by remember { mutableStateOf<AiProvider?>(null) }
    var aiUrlInput by remember { mutableStateOf("") }
    // 這份畫面看得到的自訂網址（存 prefs，改完重讀一次觸發重畫）。
    var aiCustomUrls by remember { mutableStateOf(readAiCustomUrls(prefs)) }
    // 自訂 AI 的名稱（使用者自己取，預設「自訂 AI」）。
    var aiCustomNameState by remember {
        mutableStateOf(aiCustomDisplayName(prefs.getString(KEY_AI_CUSTOM_NAME, null)))
    }
    var aiNameInput by remember { mutableStateOf("") }
    // 診斷紀錄存 prefs，改動（清除）後 +1 觸發重畫。
    var aiProbeVersion by remember { mutableIntStateOf(0) }
    // 流水帳檢視（一次性讀進 state：檔案不大，且看完就關）。
    var aiDiagVisible by remember { mutableStateOf(false) }
    var aiDiagText by remember { mutableStateOf("") }

    /**
     * 勾選／取消一家 AI。
     *
     * 抽成一支是因為「至少留一家」與寫回 prefs 的規則只能有一份；
     * 六家固定列與自訂列若各寫一次，遲早有一邊漏掉保護。
     */
    fun applyAiEnabled(provider: AiProvider, on: Boolean) {
        val next = if (on) {
            AiProvider.entries.filter { it in enabledAiState || it == provider }
        } else {
            enabledAiState.filter { it != provider }
        }
        if (next.isEmpty()) {
            android.widget.Toast.makeText(
                context,
                "至少要保留一個 AI",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            enabledAiState = next
            prefs.edit()
                .putString(KEY_AI_ENABLED_PROVIDERS, encodeEnabledProviders(next))
                .apply()
            AiDiag.log(context, provider, "CFG", "enabled=${encodeEnabledProviders(next)}")
        }
    }

    var showPenColorPicker by remember { mutableStateOf(false) }
    var showHighlighterColorPicker by remember { mutableStateOf(false) }
    var showAccentPicker by remember { mutableStateOf(false) }

    // --- 外觀：靜模式／背景 ---------------------------------------------------
    var powerSaver by remember { mutableStateOf(prefs.getBoolean(KEY_POWER_SAVER, false)) }
    var backdropKindState by remember { mutableStateOf(backdropKind) }
    var backdropThemeState by remember { mutableStateOf(backdropTheme) }
    var backdropSceneState by remember { mutableStateOf(backdropScene) }
    // 選圖：SAF 單選圖片＋持久權限，不拷貝（無孤兒檔）
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.edit().putString("backdrop_image", uri.toString()).apply()
            onBackdropImageChanged(uri)
        }
    }

    // --- 備份與還原 -----------------------------------------------------------
    var isBackupBusy by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var confirmRestoreFile by remember { mutableStateOf<java.io.File?>(null) }
    var showAllBackups by remember { mutableStateOf(false) }
    var confirmClearLegacy by remember { mutableStateOf(false) }

    var autoEnabled by remember { mutableStateOf(AutoBackupScheduler.isEnabled(context)) }
    var publicKeep by remember { mutableIntStateOf(AutoBackupScheduler.publicKeep(context)) }
    var publicLine by remember { mutableStateOf("讀取中…") }
    var allBackups by remember { mutableStateOf(emptyList<AutoBackupScheduler.BackupItem>()) }
    var isRefreshing by remember { mutableStateOf(false) }
    // 新安裝抓不到公開備份，幾乎都是缺「所有檔案存取」權限：
    // 沒它就讀不到 文件/InkFlow，列表永遠是空的。每次刷新順手更新狀態。
    var hasAllFilesAccess by remember {
        mutableStateOf(android.os.Environment.isExternalStorageManager())
    }
    fun lineFor(target: String): String {
        val backupPrefs = BackupManager.backupPrefs(context)
        val runMs = backupPrefs.getLong(AutoBackupScheduler.lastRunKey(target), 0L)
        if (runMs == 0L) return "尚未執行過"
        val run = SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(Date(runMs))
        val st = backupPrefs.getString(AutoBackupScheduler.lastStatusKey(target), null)
        return if (st.isNullOrEmpty()) "上次 $run" else "上次 $run｜$st"
    }
    // 手動重新整理跟 8 秒輪詢走同一套載入，檔案讀寫走 IO 線程，絕不卡主線程
    val reloadBackups: suspend () -> Unit = {
        val pub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            lineFor(AutoBackupScheduler.TARGET_PUBLIC)
        }
        val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AutoBackupScheduler.listAllBackups(context)
        }
        publicLine = pub
        allBackups = files
        hasAllFilesAccess = android.os.Environment.isExternalStorageManager()
    }
    // 背景任務完成後自動刷新狀態，不用手動按
    LaunchedEffect(autoEnabled) {
        while (true) {
            reloadBackups()
            kotlinx.coroutines.delay(8000)
        }
    }

    // --- 電腦同步 -------------------------------------------------------------
    // 只在使用者開著這個畫面時才輪詢，離開畫面 composition 就停：
    // 這不是背景輪詢，而是讓「6 小時額度用完、服務自己收掉」這件事
    // 在畫面上如實可見（否則開關會卡在「開」而使用者無從關閉）。
    var syncRunning by remember { mutableStateOf(TabletSyncService.isRunning) }
    var syncError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            syncRunning = TabletSyncService.isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

    // --- 預設調色盤 -----------------------------------------------------------
    // 預設墨水色票住在 shared 的 theme 層（hex 色碼只准在那裡，見 ink doctor）
    val paletteDefaults = remember {
        com.vic.inkflow.ui.theme.DefaultInkPalette
    }
    val defaultPaletteCsv = prefs.getString("default_recent_colors", null)
    val paletteInitial = remember(defaultPaletteCsv) {
        (defaultPaletteCsv?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }
            ?: paletteDefaults).toMutableList()
    }
    val paletteState = remember(defaultPaletteCsv) {
        mutableStateListOf<Int>().apply { addAll(paletteInitial) }
    }
    var showPaletteColorPicker by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun persistPalette() {
        prefs.edit().putString("default_recent_colors", paletteState.joinToString(",")).apply()
    }

    fun openPalettePicker(index: Int, colorInt: Int) {
        showPaletteColorPicker = index to colorInt
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(isDark = isDarkSettings, onNavigateBack = onNavigateBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // ===== 外觀 ================================================
                SettingsSection("外觀", isDarkSettings) {
                    SettingsChoiceBlock(
                        title = "主題",
                        options = ThemeMode.values().map { it.settingsLabel() },
                        selectedIndex = ThemeMode.values().indexOf(currentThemeMode).coerceAtLeast(0),
                        isDark = isDarkSettings
                    ) { idx ->
                        val mode = ThemeMode.values()[idx]
                        onThemeModeChanged(mode)
                        prefs.edit().putString("theme_mode", mode.name).apply()
                    }

                    SettingsAccentBlock(
                        accent = accentColor,
                        isDark = isDarkSettings,
                        onSelect = onAccentChanged,
                        onCustom = { showAccentPicker = true }
                    )

                    // 流光／靜總開關：靜＝玻璃降假、動畫凍結、幀率 60、暫停自動備份，功能不變
                    SettingsSwitchRow(
                        title = "靜模式",
                        subtitle = "玻璃降假、動畫凍結、幀率降 60、暫停自動備份；電量低於 20% 會自動切進來（手動改過就不再自動接管）",
                        checked = powerSaver,
                        onCheckedChange = {
                            powerSaver = it
                            prefs.edit()
                                .putBoolean(KEY_POWER_SAVER, it)
                                .putBoolean("power_saver_manual", true)
                                .putBoolean("power_saver_auto", false)
                                .apply()
                            onPowerSaverChanged(it)
                        }
                    )

                    // 背景三類：泡泡／特效／圖片；靜模式下動的全凍結首幀
                    SettingsChoiceBlock(
                        title = "背景",
                        options = BackdropKind.values().map { it.label },
                        selectedIndex = BackdropKind.values().indexOf(backdropKindState).coerceAtLeast(0),
                        isDark = isDarkSettings,
                        hint = "泡泡與特效是動態背景，靜模式會停在第一幀。"
                    ) { idx ->
                        val k = BackdropKind.values()[idx]
                        backdropKindState = k
                        prefs.edit().putString("backdrop_kind", k.name).apply()
                        onBackdropKindChanged(k)
                    }
                    if (backdropKindState == BackdropKind.ORB) {
                        SettingsChoiceBlock(
                            title = "泡泡配色",
                            options = BackdropTheme.values().map { it.label },
                            selectedIndex = BackdropTheme.values().indexOf(backdropThemeState).coerceAtLeast(0),
                            isDark = isDarkSettings
                        ) { idx ->
                            val t = BackdropTheme.values()[idx]
                            backdropThemeState = t
                            prefs.edit().putString("backdrop_theme", t.name).apply()
                            onBackdropThemeChanged(t)
                        }
                    }
                    if (backdropKindState == BackdropKind.SCENE) {
                        // 場景清單來自 :shared 註冊表：加場景不用改這一頁
                        SettingsChoiceBlock(
                            title = "特效場景",
                            options = backdropScenes.map { it.label },
                            selectedIndex = backdropScenes.indexOfFirst { it.id == backdropSceneState.id }.coerceAtLeast(0),
                            isDark = isDarkSettings
                        ) { idx ->
                            val s = backdropScenes[idx]
                            backdropSceneState = s
                            prefs.edit().putString("backdrop_scene", s.id).apply()
                            onBackdropSceneChanged(s)
                        }
                    }
                    if (backdropKindState == BackdropKind.IMAGE) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (backdropImageUri == null) "尚未選擇圖片" else "已套用自訂圖片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            SettingsPillButton(
                                text = if (backdropImageUri == null) "選擇圖片" else "更換圖片",
                                isDark = isDarkSettings,
                                onClick = { pickImage.launch(arrayOf("image/*")) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        GlassTextButton(
                            text = "恢復預設背景",
                            onClick = {
                                backdropKindState = BackdropKind.ORB
                                backdropThemeState = BackdropTheme.SOFT
                                prefs.edit()
                                    .putString("backdrop_kind", BackdropKind.ORB.name)
                                    .putString("backdrop_theme", BackdropTheme.SOFT.name)
                                    .apply()
                                onBackdropKindChanged(BackdropKind.ORB)
                                onBackdropThemeChanged(BackdropTheme.SOFT)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ===== 書寫 ================================================
                SettingsSection("書寫", isDarkSettings) {
                    SettingsChoiceBlock(
                        title = "觸控模式",
                        // 只剩 2 種活路：PALM_REJECTION 已併入觸控筆模式（cycleInputMode＋VM init 同調），
                        // 選單不再列出；舊 prefs 若還存著它，顯示為觸控筆模式（跟編輯器實際行為一致）。
                        options = listOf("均可畫", "僅觸控筆"),
                        selectedIndex = if (InputMode.valueOf(defaultInputMode) == InputMode.FREE) 0 else 1,
                        isDark = isDarkSettings,
                        hint = if (InputMode.valueOf(defaultInputMode) == InputMode.FREE) {
                            "手指與觸控筆都能書寫。"
                        } else {
                            "只接受觸控筆，手指用來平移畫布。"
                        }
                    ) { idx ->
                        val mode = if (idx == 0) InputMode.FREE else InputMode.STYLUS_ONLY
                        defaultInputMode = mode.name
                        prefs.edit().putString("default_input_mode", mode.name).apply()
                    }

                    SettingsSwitchRow(
                        title = "快劃橡皮擦",
                        subtitle = "使用畫筆在螢幕上快速來回滑動來觸發橡皮擦",
                        checked = defaultQuickSwipe,
                        onCheckedChange = {
                            defaultQuickSwipe = it
                            prefs.edit().putBoolean("default_quick_swipe_eraser_enabled", it).apply()
                        }
                    )

                    SettingsSwitchRow(
                        title = "擦除後自動切回畫筆",
                        subtitle = "橡皮擦有擦到內容後，自動切換回畫筆工具",
                        checked = defaultAutoSwitchToPenAfterErase,
                        onCheckedChange = {
                            defaultAutoSwitchToPenAfterErase = it
                            prefs.edit().putBoolean("default_auto_switch_to_pen_after_erase", it).apply()
                        }
                    )

                    SettingsSliderRow(
                        title = "提筆變細強度",
                        subtitle = "手指或無壓感筆有效",
                        value = defaultStrokeSpeedSensitivity,
                        range = 0.5f..2.0f
                    ) {
                        defaultStrokeSpeedSensitivity = it
                        prefs.edit().putFloat("default_stroke_speed_sensitivity", it).apply()
                    }

                    SettingsSliderRow(
                        title = "粗細跟手速度",
                        subtitle = "慢＝鈍而穩　快＝靈活",
                        value = defaultWidthResponsiveness,
                        range = 0f..1f
                    ) {
                        defaultWidthResponsiveness = it
                        prefs.edit().putFloat("default_width_responsiveness", it).apply()
                    }

                    SettingsSwitchRow(
                        title = "手指觸控落筆校正",
                        subtitle = "副廠電容筆走手指通道、墨水偏離筆尖時，用搖桿把落筆點拉回筆尖；僅手指模式生效",
                        checked = touchCalEnabled,
                        onCheckedChange = {
                            touchCalEnabled = it
                            persistTouchCal()
                        }
                    )
                    if (touchCalEnabled) {
                        TouchCalibrationBlock(
                            dxDp = touchCalDxDp,
                            dyDp = touchCalDyDp,
                            onOffsetChange = { dx, dy ->
                                touchCalDxDp = dx
                                touchCalDyDp = dy
                                persistTouchCal()
                            }
                        )
                    }
                }

                // ===== 筆刷 ================================================
                SettingsSection("筆刷", isDarkSettings) {
                    SettingsGroupLabel("畫筆")
                    SettingsColorRow("顏色", Color(defaultPenColor)) { showPenColorPicker = true }
                    SettingsSliderRow("粗細", defaultPenWidth, 1f..20f) {
                        defaultPenWidth = it
                        prefs.edit().putFloat("default_pen_width", it).apply()
                    }

                    SettingsGroupLabel("螢光筆")
                    SettingsColorRow("顏色", Color(defaultHighlighterColor)) { showHighlighterColorPicker = true }
                    SettingsSliderRow("粗細", defaultHighlighterWidth, 4f..40f) {
                        defaultHighlighterWidth = it
                        prefs.edit().putFloat("default_highlighter_width", it).apply()
                    }

                    SettingsGroupLabel("調色盤")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "新文件的起始顏色，所有文件共用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.size(12.dp))
                            Box(
                                modifier = Modifier
                                    .fauxGlassPanel(isDarkSettings, CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${paletteState.size} 色",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            paletteState.forEachIndexed { idx, colorInt ->
                                PaletteColorTile(
                                    color = Color(colorInt),
                                    isDark = isDarkSettings,
                                    onClick = { openPalettePicker(idx, colorInt) },
                                    onDelete = {
                                        if (paletteState.size > 1) {
                                            paletteState.removeAt(idx)
                                            persistPalette()
                                        }
                                    }
                                )
                            }

                            PaletteAddTile(
                                isDark = isDarkSettings,
                                onClick = {
                                    openPalettePicker(
                                        paletteState.size,
                                        paletteState.lastOrNull() ?: paletteDefaults.first()
                                    )
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            GlassTextButton(
                                text = "重設預設",
                                onClick = {
                                    paletteState.clear()
                                    paletteState.addAll(paletteDefaults)
                                    persistPalette()
                                }
                            )
                        }
                    }
                }

                // ===== 紙張 ================================================
                SettingsSection("紙張", isDarkSettings) {
                    SettingsChoiceBlock(
                        title = "新文件底圖",
                        options = PageBackground.values().map { it.settingsLabel() },
                        selectedIndex = PageBackground.values().indexOf(PageBackground.valueOf(defaultBackground)).coerceAtLeast(0),
                        isDark = isDarkSettings
                    ) { idx ->
                        val bg = PageBackground.values()[idx]
                        defaultBackground = bg.name
                        prefs.edit().putString("default_paper_background", bg.name).apply()
                    }
                }

                // ===== AI 來源 ============================================
                SettingsSection("AI 來源", isDarkSettings) {
                    SettingsHint("勾選要出現在工具列切換的 AI；未勾選的不會被切到。至少保留一個。")
                    // 自訂 AI 另外一節（它有名字欄位，列在這裡會擠壞版面）。
                    AiProvider.entries.filter { it != AiProvider.CUSTOM }.forEach { provider ->
                        val isOn = provider in enabledAiState
                        val customUrl = aiCustomUrls[provider]
                        val startUrl = provider.effectiveStartUrl(customUrl)
                        SettingsSwitchRow(
                            title = provider.label,
                            // 副標＝能力＋（自訂網址時）實際會開的站，使用者一眼看出改到哪。
                            subtitle = aiCapabilitySummary(provider) + if (customUrl.isNullOrBlank()) {
                                ""
                            } else {
                                "・${urlHost(startUrl) ?: startUrl}"
                            },
                            checked = isOn,
                            trailing = {
                                SettingsPillButton(
                                    text = "網址",
                                    isDark = isDarkSettings,
                                    onClick = {
                                        // 預填目前實際用的網址，方便只改路徑／改回預設。
                                        aiUrlInput = startUrl
                                        aiUrlEditing = provider
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            },
                            onCheckedChange = { on -> applyAiEnabled(provider, on) }
                        )
                        // 已知的網站限制先講清楚，比讓使用者自己撞牆好。
                        aiProviderNote(provider)?.let { SettingsCaption(it) }
                    }
                }

                // ===== 自訂 AI（你的 AI）=====================================
                // 排在六家後面：新 AI 一直出，這裡填名稱＋網址就能用，不必等 App 發版。
                SettingsSection("自訂 AI", isDarkSettings) {
                    SettingsHint("填名稱與完整網址（https://…），就會出現在工具列切換。送圖可用，匯入尚未實測。")
                    val customUrl = aiCustomUrls[AiProvider.CUSTOM]
                    val customHost = customUrl?.let { urlHost(it) ?: it }
                    SettingsSwitchRow(
                        title = aiCustomNameState,
                        subtitle = if (customHost.isNullOrBlank()) "還沒設定網址" else customHost,
                        checked = AiProvider.CUSTOM in enabledAiState,
                        trailing = {
                            SettingsPillButton(
                                text = "設定",
                                isDark = isDarkSettings,
                                onClick = {
                                    aiNameInput = aiCustomNameState
                                    aiUrlInput = customUrl ?: "https://"
                                    aiUrlEditing = AiProvider.CUSTOM
                                },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        },
                        onCheckedChange = { on ->
                            // 沒網址就打開只會得到空白面板，先擋下來並指路。
                            if (on && normalizeCustomAiUrl(customUrl) == null) {
                                android.widget.Toast.makeText(
                                    context,
                                    "請先按「設定」填一個網址",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                applyAiEnabled(AiProvider.CUSTOM, on)
                            }
                        }
                    )
                }

                // ===== AI 診斷 ============================================
                // 探針（PROBE3）在每次開站時自動量，結果存 prefs；完整流水帳落檔
                // （filesDir/ai_diag.log）。兩份理由不同：設定頁看「現在能不能用」，
                // 檔案看「剛剛那一次到底卡在哪一步」。
                SettingsSection("AI 診斷", isDarkSettings) {
                    SettingsHint("每開一次 AI 會自動量一次網頁結構。開過某家之後回來這裡，就看得到它能不能送圖、能不能匯入。")
                    aiProbeVersion // 清除後重畫
                    val records = AiProvider.entries.mapNotNull { p ->
                        decodeProbeRecord(prefs.getString(aiProbeKey(p), null))?.let { p to it }
                    }
                    if (records.isEmpty()) {
                        SettingsCaption("還沒有資料：到編輯器切到某家 AI，等頁面開好，再回來這裡。")
                    } else {
                        records.forEach { (p, rec) -> SettingsProbeRow(p, rec) }
                    }
                    // 流水帳：這裡才是「整段流程」的證據（開站→送圖→匯入→錯誤）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "記錄檔 ${AiDiag.sizeInfo(context)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        SettingsPillButton(
                            text = "看記錄",
                            isDark = isDarkSettings,
                            onClick = {
                                aiDiagText = AiDiag.readTail(context, 400)
                                    .ifBlank { "還沒有記錄。先到編輯器開一次 AI。" }
                                aiDiagVisible = true
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        SettingsPillButton(
                            text = "清除",
                            isDark = isDarkSettings,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                AiDiag.clear(context)
                                prefs.edit().apply {
                                    AiProvider.entries.forEach { remove(aiProbeKey(it)) }
                                }.apply()
                                aiProbeVersion++
                            }
                        )
                    }
                }

                // ===== 備份與還原 ==========================================
                SettingsSection("備份與還原", isDarkSettings) {
                    SettingsHint("每天自動備份到「文件/InkFlow」，只保留你選的份數；換機時直接一鍵還原。")

                    SettingsSwitchRow(
                        title = "自動備份",
                        subtitle = "每天檢查一次；資料沒變不寫檔，低電量、低儲存或缺資料夾權限會跳過",
                        checked = autoEnabled,
                        onCheckedChange = {
                            autoEnabled = it
                            AutoBackupScheduler.setEnabled(context, it)
                        }
                    )

                    SettingsChoiceBlock(
                        title = "保留份數",
                        options = AutoBackupScheduler.PUBLIC_KEEP_CHOICES.map { "$it 份" },
                        selectedIndex = AutoBackupScheduler.PUBLIC_KEEP_CHOICES
                            .indexOf(publicKeep).coerceAtLeast(0),
                        isDark = isDarkSettings,
                        hint = "目前 $publicKeep 份；調少會立刻刪掉最舊的自動備份，手動匯出的檔案不受影響。"
                    ) { idx ->
                        val keep = AutoBackupScheduler.PUBLIC_KEEP_CHOICES[idx]
                        if (keep != publicKeep) {
                            publicKeep = keep
                            AutoBackupScheduler.setPublicKeep(context, keep)
                            coroutineScope.launch { reloadBackups() }
                        }
                    }

                    SettingsStatusRow("公開", publicLine)

                    if (allBackups.isNotEmpty()) {
                        val legacyCount = allBackups.count { it.source == "舊私有" }
                        val visible = if (showAllBackups) allBackups else allBackups.take(4)
                        SettingsCaption("最近的備份（${allBackups.size}）")
                        visible.forEach { item ->
                            BackupRow(
                                item = item,
                                isDark = isDarkSettings,
                                isBusy = isBackupBusy,
                                isLatest = item == allBackups.firstOrNull(),
                                onRestore = { confirmRestoreFile = item.file }
                            )
                        }
                        if (allBackups.size > 4 || legacyCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (allBackups.size > 4) {
                                    GlassTextButton(
                                        text = if (showAllBackups) "收起" else "顯示全部 ${allBackups.size} 份",
                                        onClick = { showAllBackups = !showAllBackups }
                                    )
                                }
                                if (legacyCount > 0) {
                                    GlassTextButton(
                                        text = "清除舊私有（$legacyCount）",
                                        onClick = { confirmClearLegacy = true },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 新安裝看不到公開備份時，先授權再重新整理（缺權限列表永遠是空的）
                    if (!hasAllFilesAccess) {
                        SettingsHint(
                            "尚未授權「所有檔案存取」，備份寫不進 文件/InkFlow，也讀不到既有備份。",
                            isError = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!hasAllFilesAccess) {
                            SettingsPillButton(
                                text = "授權資料夾",
                                isDark = isDarkSettings,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    runCatching {
                                        val intent = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                }
                            )
                        }
                        SettingsPillButton(
                            text = "立即備份",
                            isDark = isDarkSettings,
                            enabled = hasAllFilesAccess && !isBackupBusy && !isRefreshing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                backupStatus = "已排入立即備份，完成後會自動更新狀態。"
                                AutoBackupScheduler.runOnce(context)
                            }
                        )
                        SettingsPillButton(
                            text = if (isRefreshing) "整理中…" else "重新整理",
                            isDark = isDarkSettings,
                            enabled = !isBackupBusy && !isRefreshing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    try { reloadBackups() } finally { isRefreshing = false }
                                }
                            }
                        )
                    }

                    backupStatus?.let {
                        SettingsHint(it)
                    }
                }

                // ===== 電腦同步 ============================================
                SettingsSection("電腦同步", isDarkSettings) {
                    SettingsHint("在同一個 Wi‑Fi 下，讓電腦把文件、PDF 與筆跡拉過去。平板是唯一真相，電腦只存鏡像。")

                    SettingsSwitchRow(
                        title = "同步伺服器",
                        subtitle = if (syncRunning) {
                            "正在監聽，等待電腦連線"
                        } else {
                            "關閉時電腦會顯示「找不到平板」。開啟後會出現一個不發聲的常駐通知。"
                        },
                        checked = syncRunning,
                        onCheckedChange = { want ->
                            if (want) {
                                syncError = TabletSyncService.start(context)
                                // start() 只是「已送出」，socket 是異步綁的；下一次輪詢
                                // 才會變成 true，所以這裡先不樂觀地把開關打開。
                                syncError?.let { syncRunning = false }
                            } else {
                                TabletSyncService.stop(context)
                                syncRunning = false
                                syncError = null
                            }
                        }
                    )

                    syncError?.let {
                        SettingsHint("無法啟動：$it", isError = true)
                    }

                    if (syncRunning) {
                        val psk = remember { SyncIdentityStore.psk(context) }
                        val lanIp = remember { localLanIpv4() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "配對碼",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                psk,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                buildString {
                                    if (lanIp != null) append("這台平板在區域網的位址：$lanIp\n")
                                    append("在電腦端輸入這組配對碼後按同步。配對碼固定不變，除非清除 App 資料。")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 一鍵還原自動備份：先確認（會覆蓋目前全部資料），驗證後走同一套重啟套用流程
    confirmRestoreFile?.let { target ->
        GlassDialog(
            onDismissRequest = { confirmRestoreFile = null },
            isDark = isDarkSettings,
            title = { Text("還原這份備份？") },
            text = { Text("將以「${target.name}」取代目前的文件清單與全部註解，此動作無法復原。") },
            confirmText = "確定還原",
            onConfirm = {
                confirmRestoreFile = null
                isBackupBusy = true
                backupStatus = "正在驗證備份檔…"
                coroutineScope.launch {
                    val result = BackupManager.stageRestore(
                        context, android.net.Uri.fromFile(target)
                    )
                    result.fold(
                        onSuccess = { count ->
                            backupStatus = "備份驗證成功（$count 份文件），重啟後套用"
                            showRestartDialog = true
                        },
                        onFailure = {
                            backupStatus = "還原失敗：${it.message}"
                        }
                    )
                    isBackupBusy = false
                }
            }
        )
    }

    if (confirmClearLegacy) {
        GlassDialog(
            onDismissRequest = { confirmClearLegacy = false },
            isDark = isDarkSettings,
            title = { Text("清除舊私有備份？") },
            text = { Text("舊私有備份已不再更新，刪除後無法復原；文件/InkFlow 裡的公開備份不受影響。") },
            confirmText = "清除",
            onConfirm = {
                confirmClearLegacy = false
                coroutineScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        AutoBackupScheduler.clearLegacyPrivateBackups(context)
                    }
                    reloadBackups()
                }
            },
            dismissText = "取消"
        )
    }

    if (showRestartDialog) {
        GlassDialog(
            onDismissRequest = { showRestartDialog = false },
            isDark = isDarkSettings,
            title = { Text("還原就緒") },
            text = { Text("備份資料已完成驗證。重新啟動 App 後將以備份內容取代目前的文件清單與註解。") },
            confirmText = "立即重新啟動",
            onConfirm = {
                showRestartDialog = false
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
                if (intent != null) context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            },
            dismissText = "稍後自行重啟"
        )
    }

    if (showPenColorPicker) {
        ColorPickerDialog(
            initialColor = Color(defaultPenColor),
            onColorSelected = { color ->
                showPenColorPicker = false
                val argb = color.toArgb()
                defaultPenColor = argb
                prefs.edit().putInt("default_pen_color", argb).apply()
            },
            onDismiss = { showPenColorPicker = false }
        )
    }

    // 自訂起始網址：一家 AI 可能有多個入口（kimi.ai／kimi.com、不同區域站），
    // 哪個登得進去由網站決定，所以讓使用者自己填；留白＝回到 driver 預設。
    aiUrlEditing?.let { provider ->
        val isCustom = provider == AiProvider.CUSTOM
        GlassDialogCustom(
            onDismissRequest = { aiUrlEditing = null },
            isDark = isDarkSettings,
            title = { Text(if (isCustom) "自訂 AI" else "${provider.label} 網址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isCustom) {
                        androidx.compose.material3.OutlinedTextField(
                            value = aiNameInput,
                            onValueChange = { aiNameInput = it },
                            singleLine = true,
                            label = { Text("名稱（顯示在工具列）") },
                            colors = glassFieldColors(isDarkSettings),
                            shape = ShapeLg,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = if (isCustom) {
                            "貼上這家 AI 的網址，例如 https://chat.deepseek.com/"
                        } else {
                            "留白＝使用預設 ${provider.startUrl}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = aiUrlInput,
                        onValueChange = { aiUrlInput = it },
                        singleLine = true,
                        label = { Text("https://…") },
                        colors = glassFieldColors(isDarkSettings),
                        shape = ShapeLg,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            buttons = {
                // 自訂 AI 沒有預設網站，所以不給「用預設」。
                if (!isCustom) {
                    GlassTextButton(
                        text = "用預設",
                        onClick = {
                            prefs.edit().remove(aiCustomUrlKey(provider)).apply()
                            aiCustomUrls = readAiCustomUrls(prefs)
                            AiDiag.log(context, provider, "CFG", "url=default")
                            aiUrlEditing = null
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                GlassTextButton(
                    text = "取消",
                    onClick = { aiUrlEditing = null },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "儲存",
                    onClick = {
                        val norm = normalizeCustomAiUrl(aiUrlInput)
                        // 自訂 AI 沒有網址就等於沒有站台：不許存成空，否則會打開空白面板。
                        if (isCustom && norm == null) {
                            android.widget.Toast.makeText(
                                context,
                                "請填完整網址，例如 https://chat.deepseek.com/",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@GlassTextButton
                        }
                        if (aiUrlInput.isBlank()) {
                            prefs.edit().remove(aiCustomUrlKey(provider)).apply()
                        } else if (norm == null) {
                            android.widget.Toast.makeText(
                                context,
                                "網址要完整，例如 https://kimi.ai/",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@GlassTextButton
                        } else {
                            prefs.edit().putString(aiCustomUrlKey(provider), norm).apply()
                        }
                        if (isCustom) {
                            val name = aiCustomDisplayName(aiNameInput)
                            prefs.edit().putString(KEY_AI_CUSTOM_NAME, name).apply()
                            aiCustomNameState = name
                        }
                        aiCustomUrls = readAiCustomUrls(prefs)
                        AiDiag.log(
                            context, provider, "CFG",
                            "url=${AiDiag.q(prefs.getString(aiCustomUrlKey(provider), null) ?: "default")}" +
                                if (isCustom) " name=${AiDiag.q(aiCustomNameState)}" else ""
                        )
                        aiUrlEditing = null
                    }
                )
            }
        )
    }

    // 診斷流水帳：整段流程（開站→探針→送圖→匯入→錯誤）都在這裡。
    // 用可捲動的 monospace 呈現，因為要對齊欄位才看得出順序。
    if (aiDiagVisible) {
        GlassDialogCustom(
            onDismissRequest = { aiDiagVisible = false },
            isDark = isDarkSettings,
            title = { Text("AI 診斷記錄") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = aiDiagText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            buttons = {
                GlassTextButton(
                    text = "關閉",
                    onClick = { aiDiagVisible = false }
                )
            }
        )
    }

    if (showHighlighterColorPicker) {
        ColorPickerDialog(
            initialColor = Color(defaultHighlighterColor),
            onColorSelected = { color ->
                showHighlighterColorPicker = false
                val argb = color.toArgb()
                defaultHighlighterColor = argb
                prefs.edit().putInt("default_highlighter_color", argb).apply()
            },
            onDismiss = { showHighlighterColorPicker = false }
        )
    }

    if (showAccentPicker) {
        ColorPickerDialog(
            initialColor = accentColor,
            subtitle = "套用到標題、按鈕、滑桿與選取框",
            onColorSelected = { color ->
                showAccentPicker = false
                onAccentChanged(color)
            },
            onDismiss = { showAccentPicker = false }
        )
    }

    if (showPaletteColorPicker != null) {
        val (index, colorInt) = showPaletteColorPicker!!
        ColorPickerDialog(
            initialColor = Color(colorInt),
            onColorSelected = { color ->
                val intVal = color.toArgb()
                if (index < paletteState.size) paletteState[index] = intVal else paletteState.add(intVal)
                persistPalette()
                showPaletteColorPicker = null
            },
            onDismiss = { showPaletteColorPicker = null }
        )
    }
}

// ---- 版面常數 ---------------------------------------------------------------

/** 設定頁內容最大寬度：平板橫向不再把每一列拉成超寬一條。 */
private val SETTINGS_CONTENT_MAX_WIDTH: Dp = 820.dp

// ---- 頁首 -------------------------------------------------------------------

@Composable
private fun SettingsTopBar(isDark: Boolean, onNavigateBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .fauxGlassPanel(isDark, CircleShape)
                    .glassClickable(onClick = onNavigateBack, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "設定",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---- 區塊與列 ---------------------------------------------------------------

/** 設定頁 AI 列的副標：把 capabilities 轉成人話，讓使用者一眼知道哪家能做什麼。 */
private fun aiCapabilitySummary(provider: AiProvider): String {
    val caps = provider.capabilities
    val parts = buildList {
        if (caps.sendImage) add("送圖")
        if (caps.importReply) add("匯入回覆")
        if (caps.mathCrop) add("數學裁圖")
    }
    return if (parts.isEmpty()) "僅手動對話" else parts.joinToString("・")
}

/**
 * 一次讀齊每家的自訂網址（null＝用預設）。
 *
 * 走 prefs 而不是 state 的原因：編輯器在另一個畫面讀同一份 prefs，
 * 兩邊都只認 prefs 為真相，才不會出現「設定頁顯示改了、編輯器還在舊網址」。
 */
private fun readAiCustomUrls(prefs: SharedPreferences): Map<AiProvider, String?> =
    AiProvider.entries.associateWith { prefs.getString(aiCustomUrlKey(it), null) }

/**
 * 一家 AI 的探針結果。
 *
 * 只顯示「命中的第一條」，不列全部候選：要鎖選擇器只需要那一條，
 * 其餘十幾條會把設定頁變成一堵牆。完整原始資料仍留在 prefs（`ai_probe_*`）。
 */
@Composable
private fun SettingsProbeRow(provider: AiProvider, record: AiProbeRecord) {
    val readiness = record.report.readiness()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = provider.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = record.report.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // 「就緒／需登入／驗證頁」是從 DOM 推論的結論；後面的「初載」代表
            // 4 秒那次還沒跑到，數字可能還是空殼，別當真。
            Text(
                text = "${readiness.label}・${if (record.isHydrated) "已載入" else "初載"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (readiness.state == AiReadiness.READY) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        // 不是「就緒」就把原因寫出來：使用者不必猜，我也不必問。
        if (readiness.state != AiReadiness.READY) {
            Text(
                text = readiness.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (record.report.title.isNotBlank()) {
            Text(
                text = "標題：${record.report.title}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ProbeLine("輸入框", record.report.firstInput())
        ProbeLine("送出鈕", record.report.firstSend())
        ProbeLine("回覆區", record.report.firstReply())
        ProbeLine("停止鈕", record.report.idle)
    }
}

/** 一行 `標籤：selector（命中數）`；null＝這個角色在頁面上找不到。 */
@Composable
private fun ProbeLine(label: String, hit: AiProbeHit?) {
    Text(
        text = if (hit == null) "$label：沒有" else "$label：${hit.selector}（${hit.count}）",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 已知的網站限制（實測得到的事實，不是推測）。
 * 寫在設定頁而不是默默留著：使用者有權在切過去之前就知道哪家會卡，
 * 才不會以為是 App 壞了。
 */
private fun aiProviderNote(provider: AiProvider): String? = when (provider) {
    AiProvider.CLAUDE -> "Claude 官網目前會擋 App 內建瀏覽器登入；帳號沒問題，用系統瀏覽器仍可登入。"
    else -> null
}

@Composable
private fun SettingsSection(
    title: String,
    isDark: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fauxGlassPanel(isDark, ShapeLg)
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

/** 一句話說明（不搶版面，放在區塊最上面或狀態下面）。 */
@Composable
private fun SettingsHint(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

/** 群組小標（畫筆／螢光筆／調色盤）。 */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp)
    )
}

/** 備份清單前的小標。 */
@Composable
private fun SettingsCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
    )
}

/** 標題＋一行副標＋右側開關，整列可點。[trailing] 放開關左邊的動作鈕（例如「網址」）。 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 標題＋分段條（單顆玻璃槽），設定頁所有「多選一」都走這裡。 */
@Composable
private fun SettingsChoiceBlock(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    isDark: Boolean,
    hint: String? = null,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        GlassSegmentedBar(
            options = options,
            selectedIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
            onSelect = onSelect,
            isDark = isDark
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 主題色：預設色票＋自訂色。所有重點色 UI 都吃這一個變數（見 shared/Accent.kt）。 */
@Composable
private fun SettingsAccentBlock(
    accent: Color,
    isDark: Boolean,
    onSelect: (Color) -> Unit,
    onCustom: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "主題色",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                com.vic.inkflow.ui.theme.AccentPresets.forEach { preset ->
                    val selected = preset.color.toArgb() == accent.toArgb()
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(preset.color, CircleShape)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .glassClickable(onClick = { onSelect(preset.color) }, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = if (preset.color.luminance() > 0.5f) Color(0xFF1B1235) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            SettingsPillButton(text = "自訂…", isDark = isDark, onClick = onCustom)
        }
        Text(
            text = String.format("目前 #%06X　標題、按鈕、滑桿、開關與選取框都跟著它", accent.toArgb() and 0xFFFFFF),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 顏色列：左標題、右色球。 */
@Composable
private fun SettingsColorRow(title: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/** 滑桿列：標題與數值同一列，滑桿吃滿寬度。 */
@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    subtitle: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
    }
}

/** 狀態列（私有／公開備份）：固定寬度的標籤＋可換行的內容。 */
@Composable
private fun SettingsStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 玻璃膠囊按鈕（設定頁的動作都走它，取代 M3 OutlinedButton）。 */
@Composable
private fun SettingsPillButton(
    text: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .fauxGlassPanel(isDark, CircleShape)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color.copy(alpha = if (enabled) 1f else 0.38f),
            maxLines = 1
        )
    }
}

/** 備份列：檔名＋來源大小，右側還原。 */
@Composable
private fun BackupRow(
    item: AutoBackupScheduler.BackupItem,
    isDark: Boolean,
    isBusy: Boolean,
    isLatest: Boolean,
    onRestore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.file.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "［${item.source}］${String.format("%.1f", item.file.length() / 1024.0 / 1024.0)}MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        GlassTextButton(
            text = if (isLatest) "還原最新" else "還原",
            onClick = onRestore,
            enabled = !isBusy
        )
    }
}

/** 調色盤色磚。 */
@Composable
private fun PaletteColorTile(
    color: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    tileSize: Dp = 56.dp
) {
    Box(
        modifier = Modifier
            .size(tileSize)
            .fauxGlassPanel(isDark, ShapeLg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(ShapeSm)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), ShapeSm)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 新增色磚。 */
@Composable
private fun PaletteAddTile(isDark: Boolean, onClick: () -> Unit, tileSize: Dp = 56.dp) {
    Box(
        modifier = Modifier
            .size(tileSize)
            .fauxGlassPanel(isDark, ShapeLg)
            .glassClickable(onClick = onClick, shape = ShapeLg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "新增",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 手指落筆校正區（精簡版）：左搖桿、右校正墊、右側說明。
 *
 * 舊版校正墊用 `weight(1f).aspectRatio(1f)`，在平板上長成近 800dp 的正方形，
 * 比整個設定頁任何東西都大，卻只需示範一個點。固定 152dp 就足夠：
 * 偏移範圍只有 ±30dp，圈圈在方形內一定看得到。
 *
 * 用法：用筆尖按住校正墊不放，另一手推搖桿——墊上紅十字是系統報點、
 * 靛色圈是校正後落筆點，把靛圈推到筆尖正下方即可。搖桿是低靈敏微調
 * （拖 4dp 只走 1dp），放開自動回中、偏移保留，可多次疊加到 ±30dp。
 */
private const val TOUCH_CAL_RANGE_DP = 30f
private const val TOUCH_CAL_STICK_SENS = 0.25f

@Composable
private fun TouchCalibrationBlock(
    dxDp: Float,
    dyDp: Float,
    onOffsetChange: (Float, Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = String.format("dx %+.1f dp　dy %+.1f dp", dxDp, dyDp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format("可調範圍 ±%.0f dp", TOUCH_CAL_RANGE_DP),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 誤觸搖桿會留下垃圾偏移（按下即結算、彈簧回去但數值保留），且關掉開關
            // 只是停用、不清零——給一個顯式歸零，否則使用者永遠不知道值髒了。
            GlassTextButton(
                text = "歸零",
                onClick = { onOffsetChange(0f, 0f) },
                enabled = dxDp != 0f || dyDp != 0f
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalibrationJoystick(dxDp = dxDp, dyDp = dyDp, onOffsetChange = onOffsetChange)
            CalibrationPad(dxDp = dxDp, dyDp = dyDp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "① 用筆尖按住左側方框",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "② 另一手推搖桿，把靛色圈推到筆尖正下方",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 校正搖桿：增量微調，放開回中、偏移保留。 */
@Composable
private fun CalibrationJoystick(
    dxDp: Float,
    dyDp: Float,
    onOffsetChange: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    var knobPx by remember { mutableStateOf(Offset.Zero) }
    // 長駐協程讀最新偏移必須走 ref，直讀 dxDp/dyDp 會凍結在首次 composition
    val dxRef = rememberUpdatedState(dxDp)
    val dyRef = rememberUpdatedState(dyDp)
    val maxDeflectPx = with(density) { 34.dp.toPx() }
    Box(
        modifier = Modifier
            .size(112.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val centerPx = Offset(size.width / 2f, size.height / 2f)
                    // 每個手勢以當下最新偏移為基點累加
                    val baseDx = dxRef.value
                    val baseDy = dyRef.value
                    // 死區：輕碰（落點離中心不到一根手指的誤觸範圍）不結算。
                    // 之前沒有死區，點開設定時手指擦過搖桿就會寫入垃圾偏移，
                    // 而搖桿彈簧回去、數值保留——使用者永遠不知道值髒了。
                    val deadPx = with(density) { 10.dp.toPx() }
                    fun push(pos: Offset) {
                        val d = pos - centerPx
                        val len = hypot(d.x, d.y)
                        if (len < deadPx) {
                            knobPx = Offset.Zero
                            return
                        }
                        val clamped = if (len > maxDeflectPx) d * (maxDeflectPx / len) else d
                        knobPx = clamped
                        val step = Offset(
                            clamped.x / density.density * TOUCH_CAL_STICK_SENS,
                            clamped.y / density.density * TOUCH_CAL_STICK_SENS
                        )
                        onOffsetChange(
                            (baseDx + step.x).coerceIn(-TOUCH_CAL_RANGE_DP, TOUCH_CAL_RANGE_DP),
                            (baseDy + step.y).coerceIn(-TOUCH_CAL_RANGE_DP, TOUCH_CAL_RANGE_DP)
                        )
                    }
                    push(down.position)
                    var drag = awaitDragOrCancellation(down.id)
                    while (drag != null && drag.pressed) {
                        push(drag.position)
                        drag.consume()
                        drag = awaitDragOrCancellation(drag.id)
                    }
                    knobPx = Offset.Zero
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(knobPx.x.roundToInt(), knobPx.y.roundToInt()) }
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 校正墊：按住顯示原始報點（紅十字）與校正落筆點（靛圈）。 */
@Composable
private fun CalibrationPad(dxDp: Float, dyDp: Float) {
    var rawPosPx by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val offPx = with(density) { Offset(dxDp.dp.toPx(), dyDp.dp.toPx()) }
    val primaryColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(152.dp)
            .clip(ShapeMd)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ShapeMd)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    rawPosPx = down.position
                    down.consume()
                    var drag = awaitDragOrCancellation(down.id)
                    while (drag != null && drag.pressed) {
                        rawPosPx = drag.position
                        drag.consume()
                        drag = awaitDragOrCancellation(drag.id)
                    }
                    rawPosPx = null
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (rawPosPx == null) {
            Text(
                "按住",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val armPx = with(density) { 14.dp.toPx() }
        val dotRadiusPx = with(density) { 12.dp.toPx() }
        val strokePx = with(density) { 2.5.dp.toPx() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val raw = rawPosPx
            if (raw == null) {
                // 靜置：只給一個極淡的中心十字，告訴使用者落點大概在哪
                val faint = primaryColor.copy(alpha = 0.22f)
                drawLine(faint, center - Offset(armPx, 0f), center + Offset(armPx, 0f), strokeWidth = 2f)
                drawLine(faint, center - Offset(0f, armPx), center + Offset(0f, armPx), strokeWidth = 2f)
            } else {
                // 原始報點：紅十字
                drawLine(Color.Red, raw + Offset(-armPx, 0f), raw + Offset(armPx, 0f), strokeWidth = 3f)
                drawLine(Color.Red, raw + Offset(0f, -armPx), raw + Offset(0f, armPx), strokeWidth = 3f)
                // 校正落筆點：靛色圈
                drawCircle(Color.Red.copy(alpha = 0.25f), radius = 6f, center = raw + offPx)
                drawCircle(
                    primaryColor,
                    radius = dotRadiusPx,
                    center = raw + offPx,
                    style = Stroke(width = strokePx)
                )
            }
        }
    }
}

// ---- 顯示名稱 ---------------------------------------------------------------

private fun ThemeMode.settingsLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "系統"
    ThemeMode.LIGHT -> "亮色"
    ThemeMode.DARK -> "深色"
}

private fun PageBackground.settingsLabel(): String = when (this) {
    PageBackground.BLANK -> "空白"
    PageBackground.RULED -> "橫線"
    PageBackground.NARROW_RULED -> "密行"
    PageBackground.WIDE_RULED -> "寬行"
    PageBackground.GRID -> "方格"
    PageBackground.DOT_GRID -> "點格"
}

/**
 * 顯示用的區域網 IPv4。只為讓使用者知道該在電腦端填哪個位址，**不**參與協定——
 * 協定用 UDP 探索自動發現，IP 只是手動輸入與排查問題時的資訊。
 *
 * 刻意挑 `wlan`／`eth`：手機在同時有行動網路與熱點時會列出多個介面，讓使用者從
 * 一堆位址裡猜哪個才是電腦看得見的那個，是沒有價值的摩擦。
 */
private fun localLanIpv4(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces()
        ?.toList()
        ?.asSequence()
        ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        ?.sortedBy { iface ->
            when {
                iface.name.startsWith("wlan") -> 0
                iface.name.startsWith("eth") -> 1
                else -> 2
            }
        }
        ?.firstNotNullOfOrNull { iface ->
            iface.inetAddresses.toList().asSequence()
                .mapNotNull { addr ->
                    addr as? java.net.Inet4Address
                }
                .firstOrNull { runCatching { it.isSiteLocalAddress }.getOrDefault(false) }
                ?.hostAddress
        }
}.getOrNull()
