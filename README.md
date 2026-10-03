# InkFlow

A tablet-first PDF reader and handwritten-notebook for Android, built with Jetpack Compose. The point of the app is simple: the AI works where your notes already are. You box something, you ask about it, and the answer ends up back on the page.

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

Kotlin 2.4.10 · Compose BOM 2026.08.00 · Room 2.8.4 · PdfBox-Android 2.0.27.0 · AGP 9.3.2 · Gradle 9.7.1 · minSdk 32 / targetSdk 36

---

## Build & run

```bash
git clone https://github.com/takezou-code/InkFlow.git
cd InkFlow
./gradlew assembleDebug
```

Or open the project in Android Studio and run the `app` configuration (a physical tablet on API 32+ is recommended).

---

## 兩個平臺

同一個 repo、同一個 Gradle build：

```
app/            Android 平板（真相來源）
desktop-app/    Windows 桌面（鏡像）
shared/         跨平臺共用：玻璃材質、墨跡格式、協定、主題
```

- **Android**：Room、SAF、haze 真折射玻璃、筆壓與傾斜
- **Windows**：SQLite JDBC、jpackage 打包、方向鍵、faux 玻璃

平板是唯一寫入者。桌面在同一個 Wi-Fi 下單向 pull：拉取平板的文件、PDF 與筆跡，
並可在本地加註（本地加註不推回平板）。

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
├── desktop-app/           Windows 桌面（鏡像）
│   ├── smoke-test.ps1     打包後啟動檢查
│   └── SYNC_PROTOCOL.md   v3 協定真相來源
├── shared/                跨平臺共用（KMP）
└── legacy-prototype/      早期原型，僅供參考
```

---

## 驗證

### Android
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Windows
```bash
cd desktop-app
./gradlew test createDistributable
powershell -File smoke-test.ps1
```

`smoke-test.ps1` 驗證的是 `gradlew run` **看不出來**的東西：打包後的 runtime image
是否含 `java.sql`、launcher 是否找得到設定檔、日誌是否真的寫出來。這三個缺陷都曾經
讓打包的 EXE 完全無法啟動，而開發時一切正常。

### 對真實平板的同步測試
```powershell
$env:INKFLOW_PAIRING_CODE = <平板設定頁顯示的 8 位配對碼>
$env:INKFLOW_TABLET_HOST = <平板區網 IP>
./gradlew test --tests '*AppDatabaseSyncTest*'
```

---

## Branches

- `beta` — day-to-day development
- `main` — stable (merged only on explicit request)

平板與桌面已合併到同一分支；`windows` 分支的桌面原始碼已併入 `beta`。

---

## License

For personal and educational use.
