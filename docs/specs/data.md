# data — 復原 / 座標 / 索引 / 執行緒

## 復原 undo/redo（MUST）
- 命令＋逆操作對稱（`DrawCommand` 全分支 undo/redo 必成對，不許單邊）。
- 手勢級合併：一筆手勢只記一格（橡皮擦用累積器＋`begin/end/discardEraseGesture`；`end` 排空 mutex 後結算；取消不留格）。新增手勢型操作照此辦理。
- 結構操作（增/刪/移頁、插 PDF、AI 整批匯入）超出復原範圍：動頁前必同步 `clearUndoStacks()`（禁先 launch 後清，空窗內 undo 可插隊）。AI 匯入模式：在首個開新頁前清（同批先寫入的匯入格一併作廢）。
- 歷史上限 200 格；`redo` 回填也要裁頭（與 `pushUndo` 同規）；跨文件 VM 重建不保留歷史（業界標準）。
- 目標：結構操作單一閘門 `performStructureOp{清棧→journal→mutate}`（未建；現階段按上一條手寫，禁 UI 直呼 VM/PdfManager 而不清棧）。

## 統一畫布：單一文件座標＋切頁（MUST）
- 概念：整份文件一個座標系（PDF pt），`x∈[0,W]`，`y∈[0,N×stride)`；頁只是開在上面的窗口。`W/H` 逐文件（首頁真實尺寸，`MODEL_W/H` 預設 A4）。
- 兩個 stride：`docStride=modelH`（資料用，無縫）；`layoutStride=canvasH+gapPx`（螢幕用，含縫）。縫只存在於版式。
- `pageIndex` 是衍生視圖（`floor(docY/stride)`），書籤/上次頁/側欄/匯出照用；真相來源是 `docY`。混合尺寸走逐頁真實（`pageSizesMap`＋首頁 fallback）。
- 新墨水縫歸屬：中線切；跨頁長筆存完整一筆＋錨點，繪製按窗口裁剪，切段退役。
- 不變量：`docY == pageIndex×stride + 頂邊 ± ε`。開文件回填檢查一律軟失敗（記 log＋繼續，禁拋）——9/17 證據：parity check 磚掉真實文件（紙改小後舊墨合法出界）。閃退決議收回。
- 手勢層只認 `DocLayout`＋`DocTransform`（pure object，無 Compose）；資料層禁手算 stride（`EditorViewModel:1840` 舊均一公式是反例，混合尺寸即錯，待遷）。

## 索引運算：禁裸 `+1`（MUST）
- `afterIndex/cursor/insertionIndex` 換算一律經中央 helper，禁手寫 `afterIndex+1`＋`coerceIn`（`Int.MAX_VALUE+1` 溢位成 `MIN_VALUE` 被箍到 0＝merge 反轉元兇，9/22）。
- 接尾慣例：`PdfManager.resolveInsertionIndex(afterIndex, pageCount)`（`MAX_VALUE`＝接尾）；多份插頁 cursor：`PdfViewModel.multiInsertStartCursor(afterIndex, currentCount)`（由正規化後的 insertionIndex 倒推，禁拿 MAX 去 `+=`）。
- `commitMovedStrokes(maxPageIndex=MAX)` 是另一語義（不夾），不許混用。新增索引 helper 必須附回歸單測。

## 執行緒自保：函內建，不靠呼叫方（MUST）
- 同步 IO 函必須內建 `withContext(Dispatchers.IO)`，刪「呼叫方保證在 IO」式註解（`openPdfFileDescriptor`/`readFirstPageSize` 是反例，目前靠運氣）。
- `Toast`/導航必須函內 `withContext(Dispatchers.Main)`，刪「呼叫方在 Main」假設（`AiImportFlow` 裸 Toast 群是反例）。
- DB 寫＋`pushUndo` 回 Main 是定式（`EditorViewModel` 全篇照此）；頁操作走 `viewModelScope.launch(IO)`＋`renderMutex`＋`PageOpJournal` 備份。

## 可檢查點
- 新索引 helper 附單測（`MergeOrderTest` 模式）。
- merge 溢位已修（`PdfManager.resolveInsertionIndex`，9/22）——回歸測試釘住，勿回退。
