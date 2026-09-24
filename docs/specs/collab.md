# collab — 檔權切分 / 註解 / 專案現況

## 檔權切分（MUST，同分支施工，禁 `add -A` 互掃，只 stage 自己的檔）
- Vic-agent（手勢/畫布）：`PageWorkspace.kt` 手勢區、`util/DocLayout.kt`＋`util/DocTransform.kt`（後者改前雙方講）、`EditorViewModel.kt` 回填檢查區＋undo 棧、`AGENTS.md` 協議區（改前雙方確認）。
- 另一 agent：墨水、`InkCanvas*`、AI 面板＋新功能（`AiImportFlow`/`AiTextImport`/`AiWebPanel`/`WebCrop`）、PDF/渲染（`PdfManager`/`PdfViewModel`/`PageOpJournal`）、書庫玻璃（`Library*`/`GlassSurface`/`EditorDialogs`）、資料層 DAO。
- 共有需會診：`EditorScreen.kt`（手勢接線＋頁操作接線交會）、`SidebarPanel.kt`（主→側跟隨＋親自拖）。
- commit 只加自己的檔；動對方檔前先講（9/23 備註：P0 修復經 user 授權越界動 `PdfViewModel`/`AiImportFlow`/`EditorScreen`，下不為例）。

## 分支政策（MUST，9/24 user 定案）
- 全 repo 只留兩條分支：`beta`（日常開發線）＋`main`（穩定線）。
- 平常改的都是 `beta`；穩定後經 user 明確要求才合進 `main` 並 push。
- `backup/*` 檢查點用完即刪，不留過夜；`feat/*` 合併後即刪。

## 註解不可盡信（MUST，9/17 血淚）
- 註解會過期、會寫反（如「紙多走的量必須補進累計」實為雙倍計算，錯了三個月）。
- 凡涉及座標假設、執行順序、執行緒、生命週期的註解，動手前必須對原始碼驗證一次，不許憑註解寫 code。驗證手段：讀實現、寫單測釘住、或實機確認。
- debug 版行為異常時，優先懷疑「被註解誤導的舊邏輯」，而不是加新邏輯蓋過去。

## 專案現況速記（FYI，會過期，真相以 code 為準）
- 裝置：`1d985f84`（小米平板，user 手上）。
- 工具鏈：Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.4.10，JDK 在 `C:\Users\Vic\AppData\Local\Temp\opencode\jdk\jdk-21.0.12.1+1`，SDK 在 `%LOCALAPPDATA%\Android\Sdk`。
- 架構：`LazyColumn` 原生捲動 + 每頁 GPU 預覽圖層；相機重寫路線已封存（教訓：自幹虛擬化前先做隔離驗證）。
- 已知地雷：`LazyColumn` 只組成可見頁；`graphicsLayer` 大圖層會爆 GPU 貼圖上限；`animateItem` 禁掛大圖（只許縮圖小卡）；`awaitDragOrCancellation` 的 slop 是本地座標。
- `remember { derivedStateOf { ... } }` 裡讀普通參數不會訂閱——參數必須加進 `remember` key，否則值永遠是第一次的。
- 彈簧（spring）只能追靜止目標；追移動目標會越落越遠。跟手位置用直給 + 到位小動畫，不要用追的。
- 渲染：`renderScope` 世代門控＋手勢中暫停貼圖投送（`setRendersPaused`/`flushPendingRenders`，由 Workspace 在手勢起止調）。
- 純數學雙子：`util/DocLayout.kt`（版式）＋`util/DocTransform.kt`（座標變換），皆 pure object（僅 `Offset` 值物件，豁免）。
