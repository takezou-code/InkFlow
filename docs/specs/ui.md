# ui — 玻璃共用件＋決策表（新碼一律走共用，舊債凍結分期）

> 這就是你要的「UI 統一」規範本體。新碼 MUST 走共用，舊債凍結不學。

## 決策表（MUST）
- `glassPanel`＝真 blur（大面板：工具列/側欄/左 Dock）；`fauxGlassPanel`＝假玻璃（大量重複卡片、對話框卡，省 blur）；`bubbleGlass`＝壓紙浮層專用；`pressableGlass`＝可壓按鈕/FAB。`smartGlass` 是退役別名，新碼禁用。
- 對話框三件套：`AnimatedDialog`（純淡入殼，卡片 scale 由框內自播）＋`GlassDialog`（標準二鈕）/`GlassDialogCustom`（自訂鈕列）＋`GlassTextButton`/`GlassOptionChip`/`glassFieldColors`（輸入框）。
- 禁 M3 原味直接上屏：`AlertDialog`/`Button`/`TextButton`/`OutlinedButton`/`FilterChip`/`Switch`/`Slider`/`Card`/`DropdownMenu` 不許直接上屏；`DropdownMenu` 已複貼 4 處，待補 `GlassDropdown` 共用（未建之前新處沿用舊複貼並標 TODO）。
- 單一背景層、零 M3 chrome、禁第二層底，分區用分隔線＋字階。禁 `Modifier.shadow()`（9/18 血淚：小米平板白框殘影，9 輪診斷版實證）；深度用 rim＋sheen＋底內陰影。`graphicsLayer.shadowElevation` 同屬疑慮：紙本體（18dp）是既定例外，其餘新用法先打診斷版＋`adb screencap` 讀像素驗。

## Token（MUST）
- 圓角 `Sm12/Md16/Lg24/Xl32`（禁 2/6/16/22 等散數）；字階 `Type.kt`（對話框殼強制 `headlineSmall`＋`bodyMedium`，字標 Serif 破例需 user 點頭）；動畫走 `Motion.kt`（`FAST180/NORMAL240/SLOW320`，現裸 `tween` 群待遷）；間距沿 8pt 網格（`4/8/12/16/24`，禁怪數）；暗色只靠 `luminance<0.5`＋`if(isDark)`，玻璃色統一 `Color.kt` 那罐（`GlassSurface:67` 私罐待合併）。
- haze：`hazeSource` 掛背景層；`rememberHazeState` 每屏一個；orb 數量（書庫 12 vs 編輯器 5）待收斂；`faux` 升真 blur 需有幀率理由。

## 已知舊債（FYI，凍結，新碼不許學）
- `GlobalSettings` 整頁 M3、`ColorPickerDialog` 裸 Slider、`LibraryPanels` searchVeil 自造色、`PdfViewModel:379` 註解亂碼、`GlassRailItem` 待搬入 `GlassSurface` 團聚。

## 拖曳排序：走共用手感（MUST）
- 現有用：`util/ReorderableGrid.kt`＋`reorderableItem`（側欄縮圖，長按拖曳，`onMove=removeAt+add` 定式）。
- 待建：`util/ReorderableColumn.kt`（LazyColumn 版，合併順序對話框立項；grid 的 `ReorderableLazyGridState` 是 grid 專用，不許單列 grid 硬套）。
- 行內「上移/下移」按鈕已退役（合併對話框決議），拖曳配把手圖示提示；`animateItem` 禁掛大圖。

## 可檢查點（SHOULD）
- 新對話框先查 `EditorDialogs` 是否有現成三件套可用，禁自幹。
- 自查：`grep -rn "AlertDialog\|OutlinedButton\|FilterChip\|Modifier.shadow" app/src/main` 應只剩舊債清單裡的檔。
