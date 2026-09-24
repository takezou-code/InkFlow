# touch — 觸控對接協議（TOUCH_CONTRACT，兩 agent 共同遵守）

> MELD 區：改前雙方確認。分級除特別註明外皆 MUST。

- 觸控流由外而內，贏家全拿：palm 採集（只看不攔）→ `twoFingerModifier`（≥2 非手掌觸點，未定只攔新指（首指放行不斷流）、鎖定即全攔；單指瞬斷 400ms 內算同一手勢（回來先重錨，單指段位移丟掉）；筆出現直接重開（墨水一刻不等）；否則空窗期內層 InkCanvas 會用第二指起筆）→ `blankPanModifier`（1 指＋起點在紙實際矩形 X 區間外，首像素 consume，被 consume 的幀跳過不死；第二根手指出現整段作廢，手掌不算）→ `InkCanvas`（紙面單指 consume 畫畫，多指放行）。
- 空白判定僅判 X（`isBlankX`，18dp 頁縫算紙內）；至少留 1/4 紙在區內（`KEEP`，「留一半」是舊文作廢）；垂直一律走原生 `LazyColumn`。
- `mainListState` 寫者一律 `dispatchRawDelta` 同步，禁 `scope.launch(scrollBy)`；已知違規：`EditorScreen:790` drag-strip 直向（第三寫者，待遷移）。唯一例外：捏合垂直錨定必須 `requestScrollToItem` 一步定態（`dispatchRawDelta` 會跟框架原生保持疊加、每幀 1.8 倍飛走）；放手慣性 fling（單一可取消、`scroll{}` 互斥內跑、接管即殺，禁多重排隊；只許 `!sawPinch`＋初速有限＋≥3 點＋span≥50ms＋>3 倍 `minimumFlingVelocity`，振幅 cap ±8000）。
- 單幀封頂 96px（`MAX_FRAME_DELTA_PX`），超的記欠款下幀先還（±300 上限），不斷流不丟失；雙指/空白兩條同理。加指/換指只重錨 span 系（`rebaselineSpan`，PAN 賽局不斷）；PAN→PINCH 升級門檻 1x spanSlop（tie 先判 PAN）。
- `pinchActive`：工作區寫、墨水讀；PAN 鎖定也立旗（兩指平移時寫筆邊緣捲必須停，否則跟主列表雙寫打架跳頁）；上鎖配 `watchLock(10s)` 自清；接管即殺 fling/舊動畫＋`setRendersPaused(true)`，放手 `flushPendingRenders()`。新增棄筆條件走同一旗子，禁另起旗子。
- `pageLock`：墨水寫、跟隨讀；上鎖必須配超時自清（`watchLock(10s)`），無超時的鎖視為 bug。`programmaticTarget(2.5s)` 是側欄機制，不是鎖，不許混寫。
- 放手後零外力：手勢結束後禁寫 `panOffsetX`／捲主列表；收斂只許寫入點＋viewport/zoom 變化時。雙指無慣性（抓住紙放手即停）；單指慣性只走上面那條 fling。
- 側欄：主→側永遠被動跟隨（只動側欄，150ms 防抖）；側→主只許點按＋側欄親自拖（`programmaticTarget`＋`sidebarFollowActive`＋`lastSidebarDriveMs(1000ms)`＋`interactionSource.Drag` 過濾，無 epoch，「帶 epoch」是舊文）；程式化捲動用單一可取消 `programmaticScrollJob`，手勢一接管（pinchActive）立刻取消＋清目標，禁反方向回彈。
- 手掌：只看接觸面積（`touchMajor`，小米檔 >1.8／像素檔 >45dp），`touchMajor<=0` 一律當手指（fail-open）；禁拿位移猜手掌（手掌和捏合錨定指運動學相同）。

## 可檢查點（SHOULD）
- 新增手勢分支先補 `GestureArbitratorTest` 純函數例，再接實機。
- 新增棄筆/鎖旗先查 `pinchActive` 是否可複用，禁另起旗子。
