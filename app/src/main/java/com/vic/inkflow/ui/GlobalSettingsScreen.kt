package com.vic.inkflow.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.BrandTheme
import com.vic.inkflow.util.BackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalSettingsScreen(
    prefs: SharedPreferences,
    onNavigateBack: () -> Unit,
    currentBrandTheme: BrandTheme,
    onBrandThemeChanged: (BrandTheme) -> Unit,
    currentThemeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit
) {
    val scrollState = rememberScrollState()

    var defaultInputMode by remember { mutableStateOf(prefs.getString("default_input_mode", InputMode.FREE.name) ?: InputMode.FREE.name) }
    var defaultQuickSwipe by remember { mutableStateOf(prefs.getBoolean("default_quick_swipe_eraser_enabled", false)) }
    var defaultAutoSwitchToPenAfterErase by remember { mutableStateOf(prefs.getBoolean("default_auto_switch_to_pen_after_erase", false)) }
    var defaultPalmThresholdDp by remember { mutableFloatStateOf(prefs.getFloat("default_palm_threshold_dp", 45f)) }
    var defaultStrokeSpeedSensitivity by remember { mutableFloatStateOf(prefs.getFloat("default_stroke_speed_sensitivity", 1f)) }
    var defaultFingerTouchThresholdDp by remember { mutableFloatStateOf(prefs.getFloat("default_finger_touch_threshold_dp", 8f)) }
    var defaultPenColor by remember { mutableIntStateOf(prefs.getInt("default_pen_color", 0xFF000000.toInt())) }
    var defaultHighlighterColor by remember { mutableIntStateOf(prefs.getInt("default_highlighter_color", 0xFFFFC700.toInt())) }
    var defaultPenWidth by remember { mutableFloatStateOf(prefs.getFloat("default_pen_width", 4f)) }
    var defaultHighlighterWidth by remember { mutableFloatStateOf(prefs.getFloat("default_highlighter_width", 8f)) }
    var defaultBackground by remember { mutableStateOf(prefs.getString("default_paper_background", PageBackground.BLANK.name) ?: PageBackground.BLANK.name) }

    var showPenColorPicker by remember { mutableStateOf(false) }
    var showHighlighterColorPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Section 1: 外觀 (Appearance)
            SettingsSection("外觀 (Appearance)") {
                ThemeModeSelector(currentThemeMode) { mode ->
                    onThemeModeChanged(mode)
                    prefs.edit().putString("theme_mode", mode.name).apply()
                }
                BrandThemeSelector(currentBrandTheme) { brand ->
                    onBrandThemeChanged(brand)
                    prefs.edit().putString("brand_theme", brand.name).apply()
                }
            }

            // Section 1b: 備份與還原 (Backup & Restore)
            val appContext = androidx.compose.ui.platform.LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            var isBackupBusy by remember { mutableStateOf(false) }
            var backupStatus by remember { mutableStateOf<String?>(null) }
            var showRestartDialog by remember { mutableStateOf(false) }

            val exportBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
            ) { destUri ->
                if (destUri != null && !isBackupBusy) {
                    isBackupBusy = true
                    backupStatus = "正在匯出…"
                    coroutineScope.launch {
                        val result = BackupManager.createBackup(appContext, destUri) { msg -> backupStatus = msg }
                        backupStatus = result.fold(
                            onSuccess = { count -> "備份完成（$count 份文件）" },
                            onFailure = { "備份失敗：${it.message}" }
                        )
                        isBackupBusy = false
                    }
                }
            }

            val importBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { srcUri ->
                if (srcUri != null && !isBackupBusy) {
                    isBackupBusy = true
                    backupStatus = "正在驗證備份檔…"
                    coroutineScope.launch {
                        val result = BackupManager.stageRestore(appContext, srcUri)
                        result.fold(
                            onSuccess = { count ->
                                backupStatus = "備份驗證成功（$count 份文件），重啟後套用"
                                showRestartDialog = true
                            },
                            onFailure = {
                                backupStatus = "還原失敗：${it.message}"
                                isBackupBusy = false
                            }
                        )
                    }
                }
            }

            SettingsSection("備份與還原 (Backup)") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Button(
                        onClick = {
                            exportBackupLauncher.launch(
                                "InkFlow_Backup_" + SimpleDateFormat("yyyyMMdd_HHmm").format(Date()) + ".zip"
                            )
                        },
                        enabled = !isBackupBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isBackupBusy) "處理中…" else "匯出全部備份") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { importBackupLauncher.launch(arrayOf("*/*", "application/zip", "application/octet-stream")) },
                        enabled = !isBackupBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("還原備份") }
                }
                backupStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (showRestartDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showRestartDialog = false },
                    title = { Text("還原就緒") },
                    text = { Text("備份資料已完成驗證。重新啟動 App 後將以備份內容取代目前的文件清單與註解。") },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestartDialog = false
                            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                            intent?.addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                            if (intent != null) appContext.startActivity(intent)
                            Runtime.getRuntime().exit(0)
                        }) { Text("立即重新啟動") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestartDialog = false }) { Text("稍後自行重啟") }
                    }
                )
            }

            // Section 2: 預設操作 (Interactions)
            SettingsSection("預設操作 (Interactions)") {
                InputModeSelector(InputMode.valueOf(defaultInputMode)) { mode ->
                    defaultInputMode = mode.name
                    prefs.edit().putString("default_input_mode", mode.name).apply()
                }

                SettingsSwitchRow(
                    title = "快劃橡皮擦 (Quick Swipe Eraser)",
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

                SettingsSliderRow("手掌偵測敏感度 (Palm Detection Threshold)", defaultPalmThresholdDp, 20f..60f) {
                    defaultPalmThresholdDp = it
                    prefs.edit().putFloat("default_palm_threshold_dp", it).apply()
                }

                SettingsSliderRow("筆畫速度感應 (Stroke Speed Sensitivity)", defaultStrokeSpeedSensitivity, 0.5f..2.0f) {
                    defaultStrokeSpeedSensitivity = it
                    prefs.edit().putFloat("default_stroke_speed_sensitivity", it).apply()
                }

                SettingsSliderRow("手指/筆接觸面積門檻", defaultFingerTouchThresholdDp, 4f..16f) {
                    defaultFingerTouchThresholdDp = it
                    prefs.edit().putFloat("default_finger_touch_threshold_dp", it).apply()
                }
            }

            // Section 3: 預設筆刷 (Brushes)
            SettingsSection("預設筆刷 (Brushes)") {
                SettingsColorRow("預設畫筆顏色", Color(defaultPenColor)) { showPenColorPicker = true }
                SettingsSliderRow("預設畫筆粗細", defaultPenWidth, 1f..20f) {
                    defaultPenWidth = it
                    prefs.edit().putFloat("default_pen_width", it).apply()
                }
                
                SettingsColorRow("預設螢光筆顏色", Color(defaultHighlighterColor)) { showHighlighterColorPicker = true }
                SettingsSliderRow("預設螢光筆粗細", defaultHighlighterWidth, 4f..40f) {
                    defaultHighlighterWidth = it
                    prefs.edit().putFloat("default_highlighter_width", it).apply()
                }

                val paletteDefaults = remember {
                    listOf(
                        0xFF000000.toInt(),
                        0xFFFFC700.toInt(),
                        0xFFF44336.toInt(),
                        0xFF4CAF50.toInt(),
                        0xFF3B82F6.toInt(),
                        0xFF8B5CF6.toInt()
                    )
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

                Surface(
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("預設調色盤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "新增到調色盤的顏色會成為新文件的起始調色盤，所有文件共用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = "${paletteState.size} 色",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    paletteState.clear()
                                    paletteState.addAll(paletteDefaults)
                                    persistPalette()
                                }
                            ) {
                                Text("重設預設")
                            }
                            TextButton(
                                onClick = {
                                    openPalettePicker(paletteState.size, paletteState.lastOrNull() ?: paletteDefaults.first())
                                }
                            ) {
                                Text("新增顏色")
                            }
                        }
                    }
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

            // Section 4: 預設紙張 (Paper)
            SettingsSection("預設紙張 (Paper)") {
                PageBackgroundSelector(PageBackground.valueOf(defaultBackground)) { bg ->
                    defaultBackground = bg.name
                    prefs.edit().putString("default_paper_background", bg.name).apply()
                }
            }

            Spacer(Modifier.padding(24.dp))
        }
    }

    if (showPenColorPicker) {
        ColorPickerDialog(
            onColorSelected = { color ->
                showPenColorPicker = false
                val argb = color.toArgb()
                defaultPenColor = argb
                prefs.edit().putInt("default_pen_color", argb).apply()
            },
            onDismiss = { showPenColorPicker = false }
        )
    }

    if (showHighlighterColorPicker) {
        ColorPickerDialog(
            onColorSelected = { color ->
                showHighlighterColorPicker = false
                val argb = color.toArgb()
                defaultHighlighterColor = argb
                prefs.edit().putInt("default_highlighter_color", argb).apply()
            },
            onDismiss = { showHighlighterColorPicker = false }
        )
    }
}

// Ensure ColorPickerDialog accepts initialColor - we might need to modify ColorPickerDialog.kt 
// if it doesn't currently support it, but for our MVP, it's fine.

// ---- Helper Composables ----

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsColorRow(title: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

@Composable
private fun PaletteColorTile(
    color: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    tileSize: Dp = 68.dp
) {
    Surface(
        modifier = Modifier.size(tileSize),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
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

@Composable
private fun PaletteAddTile(onClick: () -> Unit, tileSize: Dp = 68.dp) {
    Surface(
        modifier = Modifier.size(tileSize),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge,
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
}

@Composable
private fun SettingsSliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = String.format("%.1f", value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val mapping = mapOf(
        ThemeMode.SYSTEM to "系統預設",
        ThemeMode.LIGHT to "亮色 (Light)",
        ThemeMode.DARK to "深色 (Dark)"
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = mapping[current] ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(180.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mapping[mode] ?: "") },
                        onClick = { onSelect(mode); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputModeSelector(current: InputMode, onSelect: (InputMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val mapping = mapOf(
        InputMode.FREE to "手動模式 (均可畫)",
        InputMode.PALM_REJECTION to "防手掌誤觸",
        InputMode.STYLUS_ONLY to "僅限觸控筆"
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("觸控模式", style = MaterialTheme.typography.bodyLarge)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = mapping[current] ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(180.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                InputMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mapping[mode] ?: "") },
                        onClick = { onSelect(mode); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBackgroundSelector(current: PageBackground, onSelect: (PageBackground) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("背景底圖", style = MaterialTheme.typography.bodyLarge)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = current.name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(180.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PageBackground.values().forEach { bg ->
                    DropdownMenuItem(
                        text = { Text(bg.name) },
                        onClick = { onSelect(bg); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrandThemeSelector(current: BrandTheme, onSelect: (BrandTheme) -> Unit) {
    val orderedThemes = listOf(
        BrandTheme.ROSE,
        BrandTheme.SUNSET,
        BrandTheme.FOREST,
        BrandTheme.TEAL,
        BrandTheme.OCEAN,
        BrandTheme.INDIGO,
        BrandTheme.VIOLET,
        BrandTheme.MONOCHROME
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("品牌主題色", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            orderedThemes.forEach { theme ->
                val isSelected = (theme == current)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(theme.primary)
                        .clickable { onSelect(theme) }
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "選取", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
