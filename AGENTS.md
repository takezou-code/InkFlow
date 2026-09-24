# AGENTS.md — 總綱（血淚換來的，只留 MUST）

> 分級：MUST 不可破；SHOULD 預設照做；FYI 背景。細則在 `docs/specs/`，動手前讀對應分冊。真相以 code 為準，註解會過期。

## MUST 一覽（違反即退回）
1. 一律 debug：只 `assembleDebug`、只裝 `InkFlow Debug`；禁動 release。（見 env）
2. 禁 `add -A`：同分支雙施工，commit 前 `git status`，只 stage 自己的檔。（見 env/collab）
3. UI 新碼一律走共用：`glassPanel/fauxGlassPanel/bubbleGlass/pressableGlass`＋對話框三件套；禁 M3 原味上屏、禁 `Modifier.shadow()`。（見 ui）
4. 索引禁裸 `+1`：一律經 `resolveInsertionIndex` / `multiInsertStartCursor`。（見 data）
5. 結構操作前同步 `clearUndoStacks()`；手勢一筆只記一格。（見 data）
6. 執行緒自保函內建：IO 函內 `withContext(IO)`，Toast/導航內 `withContext(Main)`。（見 data）
7. 觸控 TOUCH_CONTRACT：贏家全拿、`dispatchRawDelta` 唯一寫者、單幀 96px 封頂、鎖必配超時。（見 touch）
8. 長命令丟背景、中文以位元組為準、診斷 log 定案即刪。（見 env）

## 分冊（按需讀，不要全背）
- `docs/specs/env.md` — Shell / 中文 / Git / 雙軌 / 測試
- `docs/specs/touch.md` — TOUCH_CONTRACT（兩 agent 共有，改前雙方確認）
- `docs/specs/data.md` — 復原 / 統一畫布 / 索引 / 執行緒
- `docs/specs/ui.md` — 玻璃共用件＋決策表＋拖曳排序（UI 統一本體）
- `docs/specs/collab.md` — 檔權切分 / 註解 / 專案現況

## 開工三步
1. 認領檔權（collab），不碰別人的髒檔。
2. 讀對應分冊再動手；涉座標/順序/執行緒先讀實現，不信註解。
3. 每個里程碑獨立 build＋裝機＋用戶驗收，不攢大包。
