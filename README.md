# InkFlow — Windows 桌面版

本分支（`windows`）只負責 Windows 桌面版。平板 Android 版在 `beta` 分支，兩者分開演进。

---

## 目錄結構

```
InkFlow-Windows/
├── desktop-app/          # 主力專案：Compose for Desktop（Kotlin JVM）
│   ├── src/main/kotlin/com/vic/inkflow/
│   │   ├── MainKt.kt           # 應用入口
│   │   ├── data/               # SQLite：Document / Folder / Stroke / DatabaseManager
│   │   ├── sync/               # LocalSyncManager + SyncProtocol
│   │   ├── ui/                 # LibraryView / PdfViewer / PdfThumbnail / AiAssistantPanel / Theme
│   │   └── util/               # PdfManager
│   ├── src/test/kotlin/        # SyncProtocolTest、SyncEndToEndTest
│   ├── SYNC_PROTOCOL.md        # 與平板端的同步協定（權威）
│   └── SYNC_GUIDE.md           # 同步操作手冊
└── legacy-prototype/      # 早期雛形（com.inkflow.windows，mock 資料），已被 desktop-app 取代，僅留參考
```

## 技術棧

| 項目 | 版本 |
|---|---|
| Kotlin | 2.0.21（jvmToolchain 17） |
| Compose for Desktop | 1.7.3 |
| PDF | Apache PDFBox 3.0.4 |
| DB | SQLite（org.xerial:sqlite-jdbc 3.46.0.0） |
| JSON | Gson 2.14.0 |
| 同步 | Gson over TCP 8765 + UDP 廣播 8766 |

## 建置與執行

```powershell
cd desktop-app

# 執行
.\gradlew.bat run

# 測試（同步協定）
.\gradlew.bat test

# 打包（EXE + MSI）
.\gradlew.bat packageDistributionForCurrentOS
.\gradlew.bat createDistributable   # 產出 build/compose/binaries/main/exe/InkFlow-1.0.0.exe
```

## 現況

已實作：文件庫、PDF 開啟/渲染、畫布與筆跡、SQLite 落地、撤銷重做、區域網裝置發現、同步協定與端到端測試。

待辦：與平板端實際對接、PDF 匯出封裝、AI 面板串真實模型。
