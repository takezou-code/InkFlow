# AGENTS.md — 給 AI 助手的作業規範（血淚換來的）

## Shell：長命令一律丟背景，結束就結束
- gradle 編譯、adb install 這種長命令，**絕對不要前台等**（gradle daemon / adb 會抓著輸出 handle 不放，工具轉圈圈卡死，`exit=0` 印了也回不來）。
- 固定套路：`Start-Process -NoNewWindow -PassThru` + `-RedirectStandardOutput/-RedirectStandardError` 到 `C:\Users\Vic\AppData\Local\Temp\opencode\*.log`，立刻回傳 pid；之後用短命令 `Get-Content` 輪詢 log。
- 路徑有空格（本專案一定有）：`Start-Process -ArgumentList` 傳參會被空白切開，APK 路徑必須**內嵌雙引號**再傳；或改傳陣列 `-ArgumentList @(...)`（免內嵌引號，`--tests` 過濾器必用此式，字串式會被吃掉）。
- `exit=0` = 成功；看到它就可以收尾，不要再等。
- adb daemon 啟動訊息（`* daemon started`）是噪音，無視。`adb devices` 空的 = 平板沒插。

## 中文顯示不可信，以位元組為準
- PowerShell 5.1 / 工具輸出的中文常常爛成 `?`（grep、read、console 全都會）。
- **看到 `????` 先別信**：用 `[BitConverter]` 看 UTF-8 位元組，或用 `git diff` 對照，確認真的爛了再修。
- Edit 工具回報成功 = 當時真的有匹配到（ground truth），不要因為顯示怪就重改。

## Git：動大刀前先存檔＋禁掃別人的檔
- 重寫/大改前先開分支 + commit（例：`backup/<主題>-<hash>`），壞了可一鍵退回。
- 每個里程碑獨立 build + 裝機 + 用戶驗收，不攢大包一次上。
- remote 已切 SSH（`git@github.com:takezou-code/InkFlow.git`），直接 push；host key 已在 `~/.ssh/known_hosts`。
- **禁 `add -A`**（同分支雙 agent 施工；7df3094 代掃教訓：PdfManager 溢位修復被掃進他人 commit，歸屬混淆）。commit 前先 `git status` 看別人的髒檔，只 stage 自己的檔，不碰別人的未提交改動。

## 專案現況速記
- 裝置：`1d985f84`（小米平板，user 手上）。
- 工具鏈：Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.4.10，JDK 在 `C:\Users\Vic\AppData\Local\Temp\opencode\jdk\jdk-21.0.12.1+1`，SDK 在 `%LOCALAPPDATA%\Android\Sdk`。
- 架構：`LazyColumn` 原生捲動 + 每頁 GPU 預覽圖層；相機重寫路線已封存（教訓：自幹虛擬化前先做隔離驗證）。
- 已知地雷：`LazyColumn` 只組成可見頁（官方文件背書）；`graphicsLayer` 大圖層會爆 GPU 貼圖上限；`animateItem` 禁掛大圖（只許縮圖小卡）；`awaitDragOrCancellation` 的 slop 是本地座標。
- `remember { derivedStateOf { ... } }` 裡讀普通參數不會訂閱，快照系統看不見——參數必須加進 `remember` key，否則值永遠是第一次的。
- 彈簧（spring）只能追靜止目標；追移動目標會越落越遠。跟手位置用直給 + 到位小動畫，不要用追的。
- 渲染：`renderScope` 世代門控（頁操作取消舊渲染，不排 37 秒隊）＋手勢中暫停貼圖投送（`setRendersPaused`/`flushPendingRenders`，由 Workspace 在手勢起止調）。
- 純數學雙子：`util/DocLayout.kt`（版式）＋`util/DocTransform.kt`（座標變換），皆 pure object（僅 `Offset` 值物件，豁免）。
- merge 溢位已修（`PdfManager.resolveInsertionIndex`，9/22）；合併順序確認框在工作區未提交。
- 臨時診斷 log（`InkFlowGesture`/`InkFlowPinch`/`InkFlowDbg`/`PdfViewModel perf`/`fileSplit`、`P0-0 PROBE`）：定案即刪，不許留存過夜。

## 雙軌常駐（debug + release 同機）
- 包名：release `com.vic.inkflow`／debug `.debug` 後綴；名稱 `InkFlow`／`InkFlow Debug`；圖示 `app/src/debug/res/drawable/ic_launcher_background.xml`（橘底，main 綠底不動）。
- **一律 debug**：預設只 `assembleDebug`、只裝 `InkFlow Debug`；`assembleRelease`／動正式包必須 user 明確開口，agent 不得自行決定打 release、不得拿 release APK 覆蓋正式版。
- `install -r` 前先認 APK 路徑（debug/release），禁覆蓋錯邊；裝置 `1d985f84`。
- 測試走私有軌，禁碰 `Documents/InkFlow` 公開備份；目錄／歷史面板凍結不動。

## 觸控對接協議（TOUCH_CONTRACT，兩 agent 共同遵守）
- 觸控流由外而內，贏家全拿：palm 採集（只看不攔）→ `twoFingerModifier`（≥2 非手掌觸點，**未定只攔新指**（首指放行不斷流）、鎖定即全攔；單指瞬斷 400ms 內算同一手勢（回來先重錨，單指段位移丟掉）；**筆出現直接重開**（墨水一刻不等）；否則空窗期內層 InkCanvas 會用第二指起筆）→ `blankPanModifier`（1 指＋起點在紙實際矩形**X 區間**外，首像素 consume，被 consume 的幀跳過不死；第二根**手指**出現整段作廢，手掌不算）→ `InkCanvas`（紙面單指 consume 畫畫，多指放行）。
- 空白判定**僅判 X**（`isBlankX`，18dp 頁縫算紙內）；至少留 1/4 紙在區內（`KEEP`，「留一半」是舊文作廢）；垂直一律走原生 `LazyColumn`。
- `mainListState` 寫者一律 `dispatchRawDelta` 同步，禁 `scope.launch(scrollBy)`；**已知違規**：`EditorScreen:790` drag-strip 直向（第三寫者，待遷移）。**唯一例外**：捏合垂直錨定必須 `requestScrollToItem` 一步定態（`dispatchRawDelta` 會跟框架原生保持疊加、每幀 1.8 倍飛走）；放手慣性 fling（單一可取消、`scroll{}` 互斥內跑、接管即殺，禁多重排隊；只許 `!sawPinch`＋初速有限＋≥3 點＋span≥50ms＋>3 倍 `minimumFlingVelocity`，振幅 cap ±8000）。
- 單幀封頂 96px（`MAX_FRAME_DELTA_PX`），超的記欠款下幀先還（±300 上限），不斷流不丟失；雙指/空白兩條同理。加指/換指只重錨 span 系（`rebaselineSpan`，PAN 賽局不斷）；PAN→PINCH 升級門檻 1x spanSlop（tie 先判 PAN）。
- `pinchActive`：工作區寫、墨水讀；PAN 鎖定也立旗（兩指平移時寫筆邊緣捲必須停，否則跟主列表雙寫打架跳頁）；上鎖配 `watchLock(10s)` 自清；接管即殺 fling/舊動畫＋`setRendersPaused(true)`，放手 `flushPendingRenders()`。新增棄筆條件走同一旗子，禁另起旗子。
- `pageLock`：墨水寫、跟隨讀；上鎖必須配超時自清（`watchLock(10s)`），無超時的鎖視為 bug。`programmaticTarget(2.5s)` 是**側欄機制**，不是鎖，不許混寫。
- 放手後零外力：手勢結束後禁寫 `panOffsetX`／捲主列表；收斂只許寫入點＋viewport/zoom 變化時。雙指無慣性（抓住紙放手即停）；單指慣性只走上面那條 fling。
- 側欄：主→側永遠被動跟隨（只動側欄，150ms 防抖）；側→主只許點按＋側欄親自拖（`programmaticTarget`＋`sidebarFollowActive`＋`lastSidebarDriveMs(1000ms)`＋`interactionSource.Drag` 過濾，**無 epoch**，「帶 epoch」是舊文）；程式化捲動用單一可取消 `programmaticScrollJob`，手勢一接管（pinchActive）立刻取消＋清目標，禁反方向回彈。
- 手掌：只看接觸面積（`touchMajor`，小米檔 >1.8／像素檔 >45dp），`touchMajor<=0` 一律當手指（fail-open）；禁拿位移猜手掌（手掌和捏合錨定指運動學相同）。

## 復原（undo/redo）公約
- 命令＋逆操作對稱（`DrawCommand` 全分支 undo/redo 必成對，不許單邊）。
- 手勢級合併：一筆手勢只記一格（橡皮擦用累積器＋`begin/end/discardEraseGesture`；`end` 排空 mutex 後結算；取消不留格）。新增手勢型操作照此辦理。
- 結構操作（增/刪/移頁、插 PDF、AI 整批匯入）超出復原範圍：動頁前必**同步** `clearUndoStacks()`（禁先 launch 後清，空窗內 undo 可插隊）。AI 匯入模式：在首個開新頁前清（同批先寫入的匯入格一併作廢）。
- 歷史上限 200 格；`redo` 回填也要裁頭（與 `pushUndo` 同規）；跨文件 VM 重建不保留歷史（業界標準）。
- 目標：結構操作單一閘門 `performStructureOp{清棧→journal→mutate}`（未建；現階段按上一條手寫，禁 UI 直呼 VM/PdfManager 而不清棧）。

## 統一畫布（單一文件座標＋切頁）
- 概念：整份文件一個座標系（PDF pt），`x∈[0,W]`，`y∈[0,N×stride)`；頁只是開在上面的窗口。`W/H` 逐文件（首頁真實尺寸，`MODEL_W/H` 預設 A4）。
- 兩個 stride：`docStride=modelH`（資料用，無縫）；`layoutStride=canvasH+gapPx`（螢幕用，含縫）。縫只存在於版式。
- `pageIndex` 是衍生視圖（`floor(docY/stride)`），書籤/上次頁/側欄/匯出照用；真相來源是 `docY`。混合尺寸走逐頁真實（`pageSizesMap`＋首頁 fallback）。
- 新墨水縫歸屬：中線切；跨頁長筆存完整一筆＋錨點，繪製按窗口裁剪，切段退役。
- 不變量：`docY == pageIndex×stride + 頂邊 ± ε`。開文件回填檢查一律軟失敗（記 log＋繼續，禁拋）——9/17 證據：parity check 磚掉真實文件（紙改小後舊墨合法出界）。閃退決議收回。
- 手勢層只認 `DocLayout`＋`DocTransform`（pure object，無 Compose）；**資料層禁手算 stride**（`EditorViewModel:1840` 旧均一公式是反例，混合尺寸即錯，待遷）。

## 索引運算：禁裸 `+1`
- `afterIndex/cursor/insertionIndex` 換算一律經中央 helper，禁手寫 `afterIndex+1`＋`coerceIn`（`Int.MAX_VALUE+1` 溢位成 `MIN_VALUE` 被箍到 0＝merge 反轉元兇，9/22）。
- 接尾慣例：`PdfManager.resolveInsertionIndex(afterIndex, pageCount)`（`MAX_VALUE`＝接尾）；多份插頁 cursor：`PdfViewModel.multiInsertStartCursor(afterIndex, currentCount)`（由正規化後的 insertionIndex 倒推，禁拿 MAX 去 `+=`）。
- `commitMovedStrokes(maxPageIndex=MAX)` 是另一語義（不夾），不許混用。新增索引 helper 必須附回歸單測（見測試章）。

## 執行緒自保：函內建，不靠呼叫方
- 同步 IO 函必須內建 `withContext(Dispatchers.IO)`，刪「呼叫方保證在 IO」式註解（`openPdfFileDescriptor`/`readFirstPageSize` 是反例，目前靠運氣）。
- `Toast`/導航必須函內 `withContext(Dispatchers.Main)`，刪「呼叫方在 Main」假設（`AiImportFlow` 裸 Toast 群是反例）。
- DB 寫＋`pushUndo` 回 Main 是定式（`EditorViewModel` 全篇照此）；頁操作走 `viewModelScope.launch(IO)`＋`renderMutex`＋`PageOpJournal` 備份。

## 玻璃：共用件＋決策表（新碼一律走共用，舊債凍結分期）
- 決策表：`glassPanel`＝真 blur（大面板：工具列/側欄/左 Dock）；`fauxGlassPanel`＝假玻璃（大量重複卡片、對話框卡，省 blur）；`bubbleGlass`＝壓紙浮層專用；`pressableGlass`＝可壓按鈕/FAB。`smartGlass` 是退役別名，新碼禁用。
- 對話框三件套：`AnimatedDialog`（純淡入殼，卡片 scale 由框內自播）＋`GlassDialog`（標準二鈕）/`GlassDialogCustom`（自訂鈕列）＋`GlassTextButton`/`GlassOptionChip`/`glassFieldColors`（輸入框）。**禁 M3 原味**：`AlertDialog`/`Button`/`TextButton`/`OutlinedButton`/`FilterChip`/`Switch`/`Slider`/`Card`/`DropdownMenu` 不許直接上屏；`DropdownMenu` 已複貼 4 處，待補 `GlassDropdown` 共用（未建之前新處沿用舊複貼並標 TODO）。
- 單一背景層、零 M3 chrome、禁第二層底，分區用分隔線＋字階。**禁 `Modifier.shadow()`**（9/18 血淚：小米平板白框殘影，9 輪診斷版實證）；深度用 rim＋sheen＋底內陰影。`graphicsLayer.shadowElevation` 同屬疑慮：紙本體（18dp）是既定例外，其餘新用法先打診斷版＋`adb screencap` 讀像素驗。
- Token：圓角 `Sm12/Md16/Lg24/Xl32`（禁 2/6/16/22 等散數）；字階 `Type.kt`（對話框殼強制 `headlineSmall`＋`bodyMedium`，字標 Serif 破例需 user 點頭）；動畫走 `Motion.kt`（`FAST180/NORMAL240/SLOW320`，現裸 `tween` 群待遷）；間距沿 8pt 網格（`4/8/12/16/24`，禁怪數）；暗色只靠 `luminance<0.5`＋`if(isDark)`，玻璃色統一 `Color.kt` 那罐（`GlassSurface:67` 私罐待合併）。
- haze：`hazeSource` 掛背景層；`rememberHazeState` 每屏一個；orb 數量（書庫 12 vs 編輯器 5）待收斂；`faux` 升真 blur 需有幀率理由。
- 已知舊債（凍結，新碼不許學）：`GlobalSettings` 整頁 M3、`ColorPickerDialog` 裸 Slider、`LibraryPanels` searchVeil 自造色、`PdfViewModel:379` 註解亂碼、`GlassRailItem` 待搬入 `GlassSurface` 團聚。

## 拖曳排序：走共用手感
- 現有用：`util/ReorderableGrid.kt`＋`reorderableItem`（側欄縮圖，長按拖曳，`onMove=removeAt+add` 定式）。
- 待建：`util/ReorderableColumn.kt`（LazyColumn 版，合併順序對話框立項；grid 的 `ReorderableLazyGridState` 是 grid 專用，不許單列 grid 硬套）。
- 行內「上移/下移」按鈕已退役（合併對話框決議），拖曳配把手圖示提示；`animateItem` 禁掛大圖。

## 測試：純函數釘住＋實機驗收
- 可測性：索引/仲裁/版式數學抽成 `internal` 純函數＋回歸單測（`MergeOrderTest`、`GestureArbitratorTest`、`DocTransformTest` 模式）；VM/DB 難測邏輯先抽 companion 再測。
- `testDebugUnitTest --tests` 必須用 `-ArgumentList @(...)` 陣列式傳參（字串式過濾器會被吃掉跑全量）。
- 實機：每個里程碑獨立 build＋裝機＋用戶驗收；懷疑視覺問題一次只動一個變因＋診斷版＋`screencap` 讀像素，不用猜的。

## 檔權切分（同分支施工，禁 `add -A` 互掃，只 stage 自己的檔）
- Vic-agent（手勢/畫布）：`PageWorkspace.kt` 手勢區、`util/DocLayout.kt`＋`util/DocTransform.kt`（後者改前雙方講）、`EditorViewModel.kt` 回填檢查區＋undo 棧、`AGENTS.md` 協議區（改前雙方確認）。
- 另一 agent：墨水、`InkCanvas*`、AI 面板＋新功能（`AiImportFlow`/`AiTextImport`/`AiWebPanel`/`WebCrop`）、PDF/渲染（`PdfManager`/`PdfViewModel`/`PageOpJournal`）、書庫玻璃（`Library*`/`GlassSurface`/`EditorDialogs`）、資料層 DAO。
- 共有需會診：`EditorScreen.kt`（手勢接線＋頁操作接線交會）、`SidebarPanel.kt`（主→側跟隨＋親自拖）。
- commit 只加自己的檔；動對方檔前先講（9/23 備註：P0 修復經 user 授權越界動 `PdfViewModel`/`AiImportFlow`/`EditorScreen`，下不為例）。
- 未結案（工作區未提交，等 user 裁示）：`LibraryScreen` 合併順序對話框＋`MergeOrderTest`（書庫/PDF 區）；手勢升級在途（fling/render-pause/rebaselineSpan＋`GestureArbitratorTest` 新例，Vic 區）。

## 註解不可盡信（9/17 血淚）
- 註解會過期、會寫反（如「紙多走的量必須補進累計」實為雙倍計算，錯了三個月）。
- 凡涉及座標假設、執行順序、執行緒、生命週期的註解，動手前必須對原始碼驗證一次，
  不許憑註解寫 code。驗證手段：讀實現、寫單測釘住、或實機確認。
- debug 版行為異常時，優先懷疑「被註解誤導的舊邏輯」，而不是加新邏輯蓋過去。
