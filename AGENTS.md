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
- `install -r` 前先認 APK 路徑（debug/release），禁覆蓋錯邊；裝置 `1d985f84`。
- 測試走私有軌，禁碰 `Documents/InkFlow` 公開備份；目錄／歷史面板凍結不動。
- 手勢新規：空白區單指＋雙指全域二維平移（`panOffsetX`，已轉正）；`offset` 直給、禁大圖層、禁 spring 追；至少留一半紙在區內；垂直一律走原生 `LazyColumn`。
