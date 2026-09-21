# InkFlow Desktop (Windows)

InkFlow 桌面版 - 基於 Jetpack Compose for Desktop 的 PDF 註解和筆記應用程式

## 功能特性

### 核心功能
- 📄 PDF 文件管理（開啟、創建、刪除）
- ✏️ 繪圖工具（鋼筆、螢光筆、橡皮擦）
- 🔍 縮放和平移功能
- 💾 PDF 導出和保存
- ↩️ 撤銷/重做功能

### 技術架構
- **UI 框架**: Jetpack Compose for Desktop
- **語言**: Kotlin 2.0.21
- **PDF 處理**: Apache PDFBox 3.0.4
- **資料庫**: SQLite
- **目標平台**: Windows 10/11

## 系統需求

- Windows 10 或更高版本
- JDK 17 或更高版本
- 至少 4GB RAM
- 1280x800 或更高分辨率

## 開發環境設置

### 1. 安裝必要軟體
```bash
# 安裝 JDK 17+
# 下載並安裝：https://adoptium.net/

# 驗證安裝
java -version
```

### 2. 建置專案
```bash
cd desktop-app

# 使用 Gradle Wrapper 建置
./gradlew build

# 或者使用系統 Gradle
gradle build
```

### 3. 執行應用程式
```bash
# 開發模式運行
./gradlew run

# 打包為 Windows 可執行檔
./gradlew packageDistributionForCurrentOS

# 創建安裝程式 (MSI)
./gradlew packageMsi
```

## 專案結構

```
desktop-app/
├── src/main/kotlin/com/vic/inkflow/
│   ├── MainKt.kt              # 應用程式入口
│   ├── data/                  # 資料層（資料庫實體、DAO）
│   ├── ui/                    # UI 組件（Composables）
│   └── util/                  # 工具類（PDF 處理、匯入/匯出）
├── build.gradle.kts           # 建構配置
└── settings.gradle.kts        # 專案設置
```

## 與 Android 版本的差異

| 特性 | Android 版本 | Windows 桌面版 |
|------|-------------|---------------|
| UI 框架 | Jetpack Compose (Android) | Compose for Desktop |
| PDF 渲染 | PdfRenderer + Canvas | PDFBox + Skia |
| 儲存 | Room Database | SQLite + 檔案系統 |
| 觸控支援 | 多點觸控、手寫筆 | 滑鼠、觸控螢幕 |
| 分發 | APK / Play Store | EXE / MSI |

## 開發路線圖

- [x] 基本專案架構
- [x] Compose Desktop 環境設置
- [ ] PDF 讀取和渲染
- [ ] 繪圖畫布實現
- [ ] 手寫筆和滑鼠輸入處理
- [ ] 資料庫整合
- [ ] 文件管理功能
- [ ] PDF 導出功能
- [ ] 撤銷/重做系統
- [ ] Windows 安裝程式打包

## 授權

本專案供個人和教育用途。

## 參考資源

- [Compose for Desktop 官方文件](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-installation.html)
- [Apache PDFBox 文件](https://pdfbox.apache.org/)
- [Material Design 3](https://m3.material.io/)
