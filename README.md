# InkFlow

A tablet-first PDF reader and handwritten notebook for Android, built with Jetpack Compose. The point of the app is simple: the AI works where your notes already are. You box something, you ask about it, and the answer ends up back on the page.

---
![IMG_20260408_224011](https://github.com/user-attachments/assets/ac6b68e7-a6fa-49a8-9f0e-575311146733)

---

## AI features

### Box anything, ask about it (Lasso → AI)
Draw a lasso around any region and a bubble offers **AI Parse**: the region — PDF content plus your ink, images, and text — is screenshotted and sent to the assistant.

It works on empty regions too. The bubble anchors to the boxed area itself rather than to selected strokes, so boxing a blank patch of the page still produces a valid capture.

One-tap shortcuts submit automatically (in Traditional Chinese):
- **Explain** — a thorough walkthrough of the content with key points
- **Summarize** — at most five bullet points
- **Translate** — translates pictured text into Traditional Chinese

**Extract to new page** renders the boxed region as an image, inserts a fresh page, and places it ready for further editing.

### Math answers become notes, not screenshots
The built-in Q&A panel watches for math in the conversation (MathML / MathJax / annotation signals). When an answer contains formulas, it takes a different path from plain text:

1. Text is split into blocks; formula regions are pixel-cropped from screenshots (S0/S1/S2 crop pipeline).
2. Blocks carrying TeX source are rendered offline by a bundled **KaTeX engine** (a persistent hidden WebView — no network round-trip, falls back to text on failure).
3. Text and rendered math are laid out together, written into a blank page, and the reader jumps there.

The TeX source is recovered from `data-math` attributes and KaTeX annotations — read out of the page, not guessed by OCR.

### Text import
Prose answers can be imported into notes as cleaned-up paragraphs (`AiTextImport`). Inline `$..$` math containing `\ ^ _` is converted to KaTeX-renderable form; plain dollar amounts are left alone.

## Everything else
Pen, highlighter, eraser, shapes, text, stamps, images; pinch zoom and pan; thumbnail sidebar; insert/delete pages; undo/redo; vector PDF export; `.inkbak` backup and restore. The basics are covered — this README won't enumerate them.

---

## Tech stack

Kotlin 2.4.21 · Compose BOM 2026.08.00 · Compose Multiplatform 1.12.2 · Room 2.8.4 · PdfBox-Android 2.0.27.0 · AGP 9.3.2 · Gradle 9.7.1 · minSdk 32 / targetSdk 36

---

## 三個平臺

同一個 repo、同一個 Gradle build：

```
app/          Android 平板（真相來源：唯一的寫入者）
desktopApp/   Windows 桌面（鏡像：拉取 ＋ 提案）
shared/       跨平臺共用（KMP）：協定、文件模型、儲存契約、幾何、繪製、設計系統
```

- **Android**：Room、SAF、haze 真折射玻璃、筆壓與傾斜
- **Windows**：SQLite JDBC、PDFBox、jpackage 打包、faux 玻璃
- **iOS**：進行中（`:shared` 已經能編譯成 iOS framework；App 本身待做）

平板是唯一寫入者。桌面在同一個 Wi-Fi 下單向 pull：拉取平板的文件、PDF 與筆跡，
並可在本地加註（本地加註**不**推回平板——要改平板的內容要走提案）。

### `:shared` 裡有什麼

一份規則只寫一次，兩個平臺共用同一份實作：

| 內容 | 位置 |
|---|---|
| 同步協定（幀格式、wire DTO、雜湊、版本） | `shared/.../sync/` |
| 文件模型（Document / Stroke / Text / Image） | `shared/.../data/Model.kt` |
| 儲存契約（`StrokeStore` / `DocumentStore` …）與平台實作 | `shared/.../data/store/` ＋ 各平臺 |
| 幾何（選取命中、形狀、座標換算、折行、筆寬） | `shared/.../util/` ＋ `shared/.../ui/SelectionGeometry.kt` |
| 繪製（包絡路徑、畫法、高亮混合） | `shared/.../ui/PageInk.kt`、`StrokePaint.kt` |
| 設計系統（玻璃、主題、動態背景） | `shared/.../ui/` |

三個平臺各自要做的只有「這個平台怎麼存檔、怎麼開檔、怎麼畫 PDF」——
那是 `DocumentSource` / `PdfEngine` / `ImageDecoder` 三個介面，各平台一份實作。

### 墨跡格式：只有一份實作

平板記錄的是**每個採樣點各自的寬度**（速度推導，對無壓感筆是 fallback），
不是整筆一個寬度。實際同步資料裡，一筆 1,174 點的筆跡就有 457 種不同寬度。

- `EnvelopeUtils.generateEnvelopePath()` — 由中心線與半寬生成填充外框
- `InkWidthModel` — 由速度推導每點寬度

`strokes.strokeWidth` 只是**使用者選的基礎寬度**，供 PDF 匯出用；實際畫出來的寬度在
`points.width`。這兩個檔案住在 `shared/`：桌面端不再手抄一份，因為手抄過一次，
而且漂移了。

---

## 目錄結構

```
├── app/                   Android 平板（來源）
│   └── src/{main,debug}/
├── desktopApp/           Windows 桌面（鏡像）
│   ├── smoke-test.ps1     打包後啟動檢查
│   └── SYNC_PROTOCOL.md   協定真相來源
├── shared/                跨平臺共用（KMP：android / jvm("desktop") / iosArm64 / iosSimulatorArm64）
└── legacy-prototype/      早期原型，僅供參考
```

---

## 同步協定（v6）

完整規格見 `desktopApp/SYNC_PROTOCOL.md`（協定的唯一人工文件）。

- **平板是唯一寫入者**；桌面端 pull 完整 manifest，逐份比對 `docVersion`（內容雜湊）決定要不要重抓。
- **桌面端不能改平板的文件**。它只能送**提案**（`upsert_stroke` / `upsert_text` / `delete_*`），
  平板逐 op 仲裁：提案帶的 `baseVersion` 比平板現在的版本舊 → **判成衝突，不套用**。
- 缺席＝已刪除（平板是唯一寫入者，manifest 每次都是完整的），但只在**連續兩輪**缺席後才刪，
  而且只刪**曾經從平板同步來**的文件——你自己在桌面匯入的本機檔不受影響。
- 平板重灌（`instanceId` 變了）→ 桌面端清空鏡像重抓。

---

## Build & run

這個 repo 用 `ink` 這支工具做建置／裝機／提交（它擋 release、擋 `git add -A`、
會等建置鎖，避免兩個 agent 同時 build 互相毀產物）。

```powershell
ink doctor              # 環境與不變量自我檢查
ink build                # assembleDebug + compile
ink build -Install       # 裝到平板並啟動（debug 軌）
ink stage <檔案...>      # 只 stage 明列的檔案
ink commit "訊息"        # 訊息走 UTF-8 檔案，中文不會被吃掉
```

簽章的正式包走 `inkflow-quick-release`（只裝到平板）或 `inkflow-release`（連 tag 與
GitHub Release 一起出）。

或者用 Android Studio 開啟這個專案，跑 `app` 設定（建議用 API 32+ 的實機平板）。

---

## 驗證

### Android
```powershell
ink build :app:testDebugUnitTest
```

### 共用模組（三個平臺都跑）
```powershell
ink build :shared:desktopTest :shared:testAndroidHostTest
```

`commonTest` 放的是**兩端必須逐位元一致**的東西：協定的黃金向量（`docVersion` 的分隔符與
雜湊、幀格式、SHA-256 對照 JDK 的差異測試）、文件模型的語意、形狀幾何、筆寬規則。

### Windows
```powershell
ink build :desktopApp:test
ink build :desktopApp:createDistributable
powershell -File desktopApp\smoke-test.ps1
```

`smoke-test.ps1` 驗證的是 `gradlew run` **看不出來**的東西：打包後的 runtime image
是否含 `java.sql`、launcher 是否找得到設定檔、日誌是否真的寫出來。這三個缺陷都曾經
讓打包的 EXE 完全無法啟動，而開發時一切正常。

### 對真實平板的同步測試
```powershell
$env:INKFLOW_PAIRING_CODE = <平板設定頁顯示的 8 位配對碼>
$env:INKFLOW_TABLET_HOST = <平板區網 IP>
ink build :desktopApp:test --tests '*AppDatabaseSyncTest*'
```

平板的探索廣播跟著 app 走：**平板 app 退到背景就同步不到**（桌面端會印
"No tablets discovered yet"）。

---

## Branches

- `beta` — day-to-day development
- `main` — stable (merged only on explicit request)

平板與桌面已合併到同一分支；`windows` 分支的桌面原始碼已併入 `beta`。

---

## License

For personal and educational use.
