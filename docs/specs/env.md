# env — 環境 / Shell / Git / 雙軌 / 測試

> 分級：MUST = 不可破；SHOULD = 預設照做，例外需理由；FYI = 背景知識。

## Shell（MUST）
- gradle / adb install 這種長命令，絕對不要前台等（gradle daemon / adb 會抓著輸出 handle 不放，工具轉圈卡死，`exit=0` 印了也回不來）。
- 固定套路：`Start-Process -NoNewWindow -PassThru` + `-RedirectStandardOutput/-RedirectStandardError` 到 `C:\Users\Vic\AppData\Local\Temp\opencode\*.log`，立刻回傳 pid；之後用短命令 `Get-Content` 輪詢 log。
- 路徑有空格（本專案一定有）：`Start-Process -ArgumentList` 傳參會被空白切開，APK 路徑必須內嵌雙引號再傳；或改傳陣列 `-ArgumentList @(...)`（免內嵌引號，`--tests` 過濾器必用此式，字串式會被吃掉）。
- `exit=0` = 成功；看到它就可以收尾，不要再等。
- adb daemon 啟動訊息（`* daemon started`）是噪音，無視。`adb devices` 空的 = 平板沒插。

## 中文顯示（MUST）
- PowerShell 5.1 / 工具輸出的中文常常爛成 `?`（grep、read、console 全都會）。
- 看到 `????` 先別信：用 `[BitConverter]` 看 UTF-8 位元組，或用 `git diff` 對照，確認真的爛了再修。
- Edit 工具回報成功 = 當時真的有匹配到（ground truth），不要因為顯示怪就重改。

## Git（MUST）
- 重寫/大改前先開分支 + commit（例：`backup/<主題>-<hash>`），壞了可一鍵退回。
- 每個里程碑獨立 build + 裝機 + 用戶驗收，不攢大包一次上。
- remote 已切 SSH（`git@github.com:takezou-code/InkFlow.git`），直接 push；host key 已在 `~/.ssh/known_hosts`。
- 禁 `add -A`（同分支雙 agent 施工；7df3094 代掃教訓）。commit 前先 `git status` 看別人的髒檔，只 stage 自己的檔，不碰別人的未提交改動。

## 雙軌常駐 debug + release（MUST）
- 包名：release `com.vic.inkflow`／debug `.debug` 後綴；名稱 `InkFlow`／`InkFlow Debug`；圖示 `app/src/debug/res/drawable/ic_launcher_background.xml`（橘底，main 綠底不動）。
- 一律 debug：預設只 `assembleDebug`、只裝 `InkFlow Debug`；`assembleRelease`／動正式包必須 user 明確開口，agent 不得自行決定打 release、不得拿 release APK 覆蓋正式版。
- `install -r` 前先認 APK 路徑（debug/release），禁覆蓋錯邊；裝置 `1d985f84`。
- 測試走私有軌，禁碰 `Documents/InkFlow` 公開備份；目錄／歷史面板凍結不動。

## 測試（MUST / SHOULD）
- MUST：索引/仲裁/版式數學抽成 `internal` 純函數＋回歸單測（`MergeOrderTest`、`GestureArbitratorTest`、`DocTransformTest` 模式）；VM/DB 難測邏輯先抽 companion 再測。
- MUST：`testDebugUnitTest --tests` 用 `-ArgumentList @(...)` 陣列式傳參（字串式過濾器會被吃掉跑全量）。
- SHOULD：每個里程碑獨立 build＋裝機＋用戶驗收；懷疑視覺問題一次只動一個變因＋診斷版＋`screencap` 讀像素，不用猜的。
- MUST：臨時診斷 log（`InkFlowGesture`/`InkFlowPinch`/`InkFlowDbg`/`PdfViewModel perf`/`fileSplit`、`P0-0 PROBE`）：定案即刪，不許留存過夜。
