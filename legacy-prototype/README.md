# InkFlow Windows 版本

基於 Kotlin Compose for Desktop 開發的 Windows 桌面應用程式，專注於 PDF 閱讀和 AI 輔助功能。

## 技術架構

- **UI 框架**: Jetpack Compose for Desktop
- **語言**: Kotlin
- **PDF 渲染**: Apache PDFBox
- **數據存儲**: SQLite
- **本地同步**: UDP 廣播 + TCP 傳輸

## 功能特點

### 當前實現
- ✅ 主畫面文件庫界面
- ✅ 文件分類和搜索
- ✅ PDF 檢視器（基礎框架）
- ✅ AI 助手側邊欄（待開發功能）
- ✅ 本地網絡設備發現
- ✅ 設備間數據同步基礎架構

### 待開發功能
- 🔄 PDF 渲染集成
- 🔄 真實數據庫連接
- 🔄 與平板端的完整同步協議
- 🔄 AI 功能實現（智能截圖、手寫轉文字、摘要生成）

## 建置說明

### 環境要求
- JDK 17+
- Gradle 8.4+

### 編譯運行

```bash
# 進入專案目錄
cd InkFlow-Windows

# 編譯並運行
./gradlew run

# 打包為 Windows 安裝程序
./gradlew packageDistributionForCurrentOS

# 創建 MSI 安裝包
./gradlew createMsiInstaller

# 創建 EXE 安裝包
./gradlew createExeInstaller
```

## 同步機制

### 工作原理
1. **設備發現**: 使用 UDP 廣播（端口 8766）在局域網內廣播設備信息
2. **數據傳輸**: 使用 TCP 連接（端口 8765）進行實際數據傳輸
3. **數據格式**: JSON 序列化，包含文檔元數據和筆跡數據

### 使用場景
- 平板負責手寫筆記生產
- 電腦負責 AI 輔助閱讀和整理
- 兩端通過本地網絡實時同步

## 專案結構

```
InkFlow-Windows/
├── app/
│   └── src/main/kotlin/com/inkflow/windows/
│       ├── Main.kt                 # 應用入口
│       ├── data/
│       │   └── Models.kt           # 數據模型
│       ├── ui/
│       │   ├── HomeScreen.kt       # 主畫面
│       │   └── DocumentViewerScreen.kt  # 文檔檢視器
│       ├── network/
│       │   └── LocalSyncManager.kt # 本地同步管理
│       └── sync/                   # (待開發) 同步邏輯
├── build.gradle.kts                # 建構配置
└── settings.gradle.kts             # 專案設置
```

## 下一步開發計劃

1. **集成 PDF 渲染**: 使用 PDFBox 實現真實的 PDF 顯示
2. **完善數據層**: 連接 SQLite 數據庫，讀取真實文檔數據
3. **實現同步協議**: 定義完整的同步數據格式和流程
4. **開發 AI 功能**: 
   - 智能截圖生成知識卡片
   - 手寫筆跡識別和轉換
   - 文檔內容摘要生成
   - 對話式閱讀助手

## 注意事項

- 目前為基礎框架版本，部分功能使用模擬數據
- AI 功能需要後續集成相關模型和服務
- 同步功能需要在平板端實現對應的接收和發送邏輯
