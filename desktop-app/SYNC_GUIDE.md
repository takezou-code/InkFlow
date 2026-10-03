# InkFlow Windows 桌面版 - 本地網絡同步方案

## 技術架構概述

### 為什麼選擇本地網絡廣播同步？

**優點：**
1. ✅ **完全免費** - 不需要雲端服務或伺服器
2. ✅ **隱私安全** - 數據只在您的設備之間傳輸
3. ✅ **低延遲** - 局域網內直接通信
4. ✅ **離線可用** - 不需要互聯網連接
5. ✅ **簡單維護** - 沒有後端服務需要管理

**限制：**
- ⚠️ 設備需要在同一局域網內
- ⚠️ 不支持跨互聯網同步
- ⚠️ 需要手動觸發同步或等待定時廣播

## 同步工作原理

### 1. 設備發現 (UDP 廣播)
```
端口：53530
協議：UDP Broadcast

每 30 秒自動廣播設備存在信息：
{
  "type": "presence",
  "deviceId": "unique_device_id",
  "hostname": "device_hostname"
}
```

### 2. 數據傳輸 (TCP 連接)
```
端口：53531
協議：TCP

支持的請求類型：
- get_documents: 獲取所有文檔列表
- get_strokes: 獲取指定頁面的筆跡
- receive_document: 接收文檔數據
- receive_strokes: 接收筆跡數據
```

### 3. 數據結構
使用與 Android 平板完全相同的數據結構：
- `DocumentEntity`: 文檔元數據（URI、名稱、最後打開時間等）
- `StrokeEntity`: 筆跡元數據（顏色、粗細、邊界框等）
- `PointEntity`: 筆跡座標點（x, y, width）

## 使用流程

### 在平板上（Android）：
1. 用手寫筆在 PDF 上做筆記
2. 確保平板和電腦在同一 WiFi 網絡
3. 數據自動保存到本地數據庫

### 在電腦上（Windows）：
1. 啟動 InkFlow Windows 應用
2. 應用自動開始監聽局域網廣播
3. 點擊右上角的 **Sync** 按鈕
4. 選擇要查看的文檔
5. 瀏覽帶有筆記內容的 PDF

## UI 設計說明

### 主界面佈局
```
┌─────────────────────────────────────────────┐
│  InkFlow - PDF Reader          [Sync] [⋮]   │  ← 頂部工具欄（同步按鈕）
├──────┬──────────────────────────────────────┤
│      │                                      │
│ Home │                                      │
│ Docs │         文檔圖書館 / PDF 閱讀器          │  ← 主內容區
│      │                                      │
│ Set  │                                      │
│      │                                      │
│      │                                      │
│      │                                      │
│      │                                      │
│[Sync]│                                      │  ← 底部同步狀態
└──────┴──────────────────────────────────────┘
   ↑
 導航欄
```

### 同步狀態指示器
- 🟢 **Connected** (綠色): 已檢測到局域網內的其他設備
- ⚪ **Offline** (灰色): 未檢測到其他設備
- 🔄 **Syncing** (旋轉圖標): 正在同步數據

## 數據庫位置

Windows 版本數據庫存儲在：
```
C:\Users\<你的用戶名>\.inkflow\inkflow.db
```

這與 Android 版本的數據庫結構完全相同，方便未來進行數據遷移。

## 構建和運行

### 前置條件
- JDK 17 或更高版本
- Gradle 8.x

### 開發模式運行
```bash
cd desktop-app
./gradlew run
```

### 打包發布版本
```bash
cd desktop-app
./gradlew packageReleaseDistributionForCurrentOS
```

生成的安裝包位於：
`desktop-app/build/compose/binaries/main-release/msi/`

## 防火牆設置

Windows 防火牆可能會阻止 UDP/TCP 通信，需要添加例外規則：

### 方法 1：通過 Windows  Defender 防火牆 GUI
1. 打開"Windows Defender 防火牆"
2. 點擊"高級設置"
3. 在"入站規則"中創建新規則
4. 選擇"端口" → TCP 和 UDP
5. 添加端口：53530, 53531
6. 選擇"允許連接"

### 方法 2：通過命令行（管理員權限）
```powershell
# 允許 UDP 端口 53530
netsh advfirewall firewall add rule name="InkFlow UDP" dir=in action=allow protocol=UDP localport=53530

# 允許 TCP 端口 53531
netsh advfirewall firewall add rule name="InkFlow TCP" dir=in action=allow protocol=TCP localport=53531
```

## 故障排除

### 問題：無法檢測到其他設備
**解決方案：**
1. 確認兩台設備在同一 WiFi 網絡
2. 檢查防火牆設置是否允許端口 53530/53531
3. 嘗試手動點擊同步按鈕
4. 查看日誌文件確認是否有錯誤

### 問題：同步失敗
**解決方案：**
1. 確保兩台設備都運行最新版本的應用
2. 檢查數據庫文件是否被其他程序佔用
3. 重啟應用並重新嘗試同步

### 查看日誌
日誌文件位置：
```
C:\Users\<你的用戶名>\.inkflow\logs\
```

## 未來改進方向

### 短期（AI 功能集成）
- [ ] PDF 內容 OCR 識別
- [ ] 手寫筆跡轉文字
- [ ] 智能截圖生成知識卡片
- [ ] 對話式閱讀助手

### 中期
- [ ] 可選的雲端同步備份（加密）
- [ ] 多設備衝突解決機制
- [ ] 增量同步優化

### 長期
- [ ] 完整的 Kotlin Multiplatform 重構
- [ ] macOS 和 Linux 版本
- [ ] 實時協作編輯功能

## 技術棧

- **UI 框架**: Jetpack Compose for Desktop
- **PDF 處理**: Apache PDFBox 3.0.4
- **數據庫**: SQLite (JDBC)
- **網絡通信**: Java Socket (UDP/TCP)
- **序列化**: Gson
- **日誌**: Kotlin Logging + Logback

## 授權條款

本项目繼承自原 Android 版本的授權條款。
