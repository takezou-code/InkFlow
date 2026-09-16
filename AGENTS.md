# AGENTS.md — 給 AI 助手的作業規範（血淚換來的）

## Shell：長命令一律丟背景，結束就結束
- gradle 編譯、adb install 這種長命令，**絕對不要前台等**（gradle daemon / adb 會抓著輸出 handle 不放，工具轉圈圈卡死，`exit=0` 印了也回不來）。
- 固定套路：`Start-Process -NoNewWindow -PassThru` + `-RedirectStandardOutput/-RedirectStandardError` 到 `C:\Users\Vic\AppData\Local\Temp\opencode\*.log`，立刻回傳 pid；之後用短命令 `Get-Content` 輪詢 log。
- 路徑有空格（本專案一定有）：`Start-Process -ArgumentList` 傳參會被空白切開，APK 路徑必須**內嵌雙引號**再傳。
- `exit=0` = 成功；看到它就可以收尾，不要再等。
- adb daemon 啟動訊息（`* daemon started`）是噪音，無視。`adb devices` 空的 = 平板沒插。

## 中文顯示不可信，以位元組為準
- PowerShell 5.1 / 工具輸出的中文常常爛成 `?`（grep、read、console 全都會）。
- **看到 `????` 先別信**：用 `[BitConverter]` 看 UTF-8 位元組，或用 `git diff` 對照，確認真的爛了再修。
- Edit 工具回報成功 = 當時真的有匹配到（ground truth），不要因為顯示怪就重改。

## Git：動大刀前先存檔
- 重寫/大改前先開分支 + commit（例：`backup/<主題>-<hash>`），壞了可一鍵退回。
- 每個里程碑獨立 build + 裝機 + 用戶驗收，不攢大包一次上。
- remote 已切 SSH（`git@github.com:takezou-code/InkFlow.git`），直接 push；host key 已在 `~/.ssh/known_hosts`。

## 專案現況速記
- 裝置：`1d985f84`（小米平板，user 手上）。
- 工具鏈：Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.4.10，JDK 在 `C:\Users\Vic\AppData\Local\Temp\opencode\jdk\jdk-21.0.12.1+1`，SDK 在 `%LOCALAPPDATA%\Android\Sdk`。
- 架構：`LazyColumn` 原生捲動 + 每頁 GPU 預覽圖層；相機重寫路線已封存（教訓：自幹虛擬化前先做隔離驗證）。
- 已知地雷：`LazyColumn` 只組成可見頁（官方文件背書）；`graphicsLayer` 大圖層會爆 GPU 貼圖上限；`animateItem` 掛大圖會卡滾動；`awaitDragOrCancellation` 的 slop 是本地座標。
- `remember { derivedStateOf { ... } }` 裡讀普通參數不會訂閱，快照系統看不見——參數必須加進 `remember` key，否則值永遠是第一次的。
- 彈簧（spring）只能追靜止目標；追移動目標會越落越遠。跟手位置用直給 + 到位小動畫，不要用追的。

## 雙軌常駐（debug + release 同機）
- 包名：release `com.vic.inkflow`／debug `.debug` 後綴；名稱 `InkFlow`／`InkFlow Debug`；圖示 `app/src/debug/res/drawable/ic_launcher_background.xml`（橘底，main 綠底不動）。
- **一律 debug**：預設只 `assembleDebug`、只裝 `InkFlow Debug`；`assembleRelease`／動正式包必須 user 明確開口，agent 不得自行決定打 release、不得拿 release APK 覆蓋正式版。
- `install -r` 前先認 APK 路徑（debug/release），禁覆蓋錯邊；裝置 `1d985f84`。
- 測試走私有軌，禁碰 `Documents/InkFlow` 公開備份；目錄／歷史面板凍結不動。
- 手勢新規：空白區單指＋雙指全域二維平移（`panOffsetX`，已轉正）；`offset` 直給、禁大圖層、禁 spring 追；至少留 1/4 紙在區內；垂直一律走原生 `LazyColumn`。

## 觸控對接協議（TOUCH_CONTRACT，兩 agent 共同遵守）
- 觸控流由外而內，贏家全拿：palm 採集（只看不攔）→ `twoFingerModifier`（≥2 非手掌觸點，鎖定即 consume）→ `blankPanModifier`（1 指＋起點在紙實際矩形外，首像素 consume；第二根**手指**出現整段作廢，手掌不算）→ `InkCanvas`（紙面單指 consume 畫畫，多指放行）。
- 空白判定用紙**實際矩形**（置中＋當前 `panOffsetX`），禁拿置中假設。
- `mainListState` 雙寫者（平移/錨定 vs 寫筆邊緣捲）靠指數互斥，不加鎖；新增寫者須先證互斥，一律 `dispatchRawDelta` 同步，禁 `scope.launch(scrollBy)`。
- `pinchActive`：工作區寫、墨水讀；新增棄筆條件走同一旗子，禁另起旗子。
- `pageLock`：墨水寫、跟隨讀；上鎖必須配超時自清（`programmaticTarget` 2.5s 同規），無超時的鎖視為 bug。
- 放手後零外力：手勢結束後禁寫 `panOffsetX`／捲主列表；收斂只許寫入點＋viewport/zoom 變化時。
- 側欄：主→側永遠被動跟隨（只動側欄）；側→主只許點按＋側欄親自拖，帶 epoch、中間頁忽略。
- 手掌：只看接觸面積（`touchMajor`，小米檔 >1.8／像素檔 >45dp），`touchMajor<=0` 一律當手指（fail-open）；禁拿位移猜手掌（手掌和捏合錨定指運動學相同）。

## 復原（undo/redo）公約
- 命令＋逆操作對稱（`DrawCommand` 全分支 undo/redo 必成對，不許單邊）。
- 手勢級合併：一筆手勢只記一格（橡皮擦用累積器＋`begin/end/discardEraseGesture`；`end` 排空 mutex 後結算；取消不留格）。新增手勢型操作照此辦理。
- 結構操作（增/刪/移頁、插 PDF）超出復原範圍：動頁前必 `clearUndoStacks()`，杜絕舊命令錯位寫入。
- 歷史上限 200 格；跨文件 VM 重建不保留歷史（業界標準）。

## 統一畫布（單一文件座標＋切頁）
- 概念：整份文件一個座標系（PDF pt），`x∈[0,W]`，`y∈[0,N×stride)`；頁只是開在上面的窗口。`W/H` 逐文件（首頁真實尺寸，`MODEL_W/H` 預設 A4）。
- 兩個 stride：`docStride=modelH`（資料用，無縫）；`layoutStride=canvasH+gapPx`（螢幕用，含縫）。縫只存在於版式。
- `pageIndex` 是衍生視圖（`floor(docY/stride)`），書籤/上次頁/側欄/匯出照用；真相來源是 `docY`。混合尺寸走逐頁真實（`pageSizesMap`＋首頁 fallback）。
- 新墨水縫歸屬：中線切；跨頁長筆存完整一筆＋錨點，繪製按窗口裁剪，切段退役。
- 不變量：`docY == pageIndex×stride + 頂邊 ± ε`。開文件回填 check 維持閃退（user 決議），但須加 breadcrumb（哪個 assert＋哪份文件＋計數）再拋。
- 手勢層只認 `util/DocLayout.kt`（pure object，無 Compose），資料層怎麼翻不動手勢。

## 檔權切分（同分支施工，禁 `add -A` 互掃，只 stage 自己的檔）
- Vic-agent（手勢/畫布）：`PageWorkspace.kt` 手勢區、`util/DocLayout.kt`、`EditorViewModel.kt` 回填檢查區、`AGENTS.md` 協議區。
- 另一 agent：墨水、`InkCanvas*`、AI 面板＋新功能、`EditorViewModel.kt` 其餘區。
- commit 只加自己的檔；動對方檔前先講。
