# InkFlow Local LAN Sync Protocol — v2（正式版規格）

> 本文件是 Android 平板端與 Windows 桌面端之間的**唯一協定真相來源（single source of truth）**。
> 實作參考：
> - 桌面端：`desktop-app/src/main/kotlin/com/vic/inkflow/sync/SyncProtocol.kt`、`LocalSyncManager.kt`
> - 平板端（待實作）：`app/src/main/java/com/vic/inkflow/sync/`（建議新增 `SyncServer.kt` + `DiscoveryResponder.kt`）

---

## 1. 總覽

```
┌───────────────┐   UDP broadcast :53530    ┌───────────────┐
│   Desktop     │ ◄───────────────────────► │    Tablet     │
│  (Windows)    │      device discovery     │  (Android)    │
└──────┬────────┘                           └──────▲────────┘
       │        TCP :53531  length-prefixed         │
       └────────── JSON frames / binary chunks ─────┘
```

| 層 | 傳輸 | 端口 | 用途 |
|---|---|---|---|
| Discovery | UDP Broadcast | **53530** | 設備發現與在線狀態 |
| Transfer  | TCP Socket  | **53531** | 元數據 diff、筆跡拉取、PDF 文件傳輸 |

設計原則：
- **零雲端**：僅局域網內通信，不經公網。
- **Desktop 為 client、Tablet 為 server**：所有數據以平板為準（tablet is the writer of truth），桌面只做拉取（pull-only）。
- **JSON over length-prefixed frames**：易調試，且能安全承載大體積二進制。

---

## 2. 線格式（Wire Format）

TCP 上的每條消息是一個幀：

```
[4-byte big-endian int: payload length][payload bytes]
```

- 文本幀：payload 為 UTF-8 JSON。
- 二進制幀（僅 `file_data` 應答）：payload 為原始字節。
- 單幀上限 **512 MiB**（超出即斷連並報錯）。
- 空幀（length = 0）語義：「文件已無剩餘數據」（EOF marker）。

---

## 3. Discovery（UDP :53530）

### 3.1 請求（Desktop → broadcast，每 30s；Tablet 亦同）

```json
{
  "app": "InkFlow",
  "type": "discover",
  "requesterRole": "desktop",        // "desktop" | "tablet"
  "deviceId": "sha256(machine-id)",  // 穩定唯一 ID
  "deviceName": "DESKTOP-ABC"
}
```

### 3.2 應答（單播回 sender）

```json
{
  "app": "InkFlow",
  "type": "response",
  "deviceId": "...",
  "deviceName": "Galaxy Tab S9",
  "role": "tablet",
  "ip": "192.168.1.42",
  "transferPort": 53531,
  "protocolVersion": 2
}
```

規則：
1. 只回應**不同 role** 的請求（desktop 不應答 desktop）。
2. `protocolVersion` 不相容時，桌面端顯示警告且不建立 TCP 連接。
3. 設備在 **90 秒**未見任何廣播/presence 後視為離線（TTL = 3 × 廣播間隔）。

---

## 4. TCP 會話流程

```
Desktop                                   Tablet (server)
   │ ── handshake ──────────────────────────► │
   │ ◄─ ack ───────────────────────────────── │
   │ ── doc_manifest ───────────────────────► │
   │ ◄─ [DocumentManifestEntry] ───────────── │
   │   (for each entry needing update)        │
   │ ── document_detail / stroke_delta ─────► │
   │ ◄─ payload ───────────────────────────── │
   │ ── file_meta ──────────────────────────► │
   │ ◄─ FileMetaPayload ──────────────────── │
   │ ── file_data(offset,limit) ────────────► │
   │ ◄─ binary frame (≤1 MiB chunk) ──────── │
   │   ... repeat until offset == size ...    │
   │ ── close ──────────────────────────────► │
```

一次同步 = 一個 TCP 連接，串行處理請求（request/response lock-step，無 pipelining）。

### 4.1 請求信封 `SyncRequest`

```json
{ "type": "<verb>", "deviceId": "...", "documentUri": "...?", "pageIndex": 0?, "offset": 0?, "limit": 1048576? }
```

| Verb | 必要欄位 | 應答 |
|---|---|---|
| `handshake` | – | `ack` |
| `doc_manifest` | – | JSON：`[DocumentManifestEntry]` |
| `document_detail` | `documentUri` | JSON：`DocumentDetailPayload`（整檔 + 全部筆跡快照） |
| `stroke_delta` | `documentUri` | JSON：`StrokeDeltaPayload`（可分頁：`hasMore`/`nextOffset`） |
| `stroke_page` | `documentUri`, `pageIndex` | JSON：`StrokeDeltaPayload`（單頁筆跡） |
| `file_meta` | `documentUri` | JSON：`FileMetaPayload` |
| `file_data` | `documentUri`, `offset`, `limit` | **二進制幀**（該區間字節；空幀 = EOF） |

### 4.2 應答信封 `SyncResponse`

```json
{ "type": "<mirrored verb | error | ack>", "ok": true, "error": null, "payload": "<JSON string>" }
```

錯誤時 `ok=false`、`error` 為人類可讀訊息；客戶端應跳過該文件繼續其餘同步。

---

## 5. 數據模型對齊（跨設備契約）

雙端 SQLite schema 必須逐欄位一致（Android Room ⇄ Desktop SQLDelight/JDBC）：

### documents
| 欄位 | 型別 | 說明 |
|---|---|---|
| `uri` | TEXT PK | **跨設備主鍵**。平板 SAF/content URI 或 app-private `file://` 路徑。**同步過程中任何一側都不得改寫此值。** |
| `displayName` | TEXT | 標題 |
| `lastOpenedAt` | INTEGER | 衝突解決時間戳（每次編輯/打開更新） |
| `lastPageIndex` | INTEGER | 閱讀進度 |
| `isFavorite` | INTEGER(0/1) | 收藏 |
| `folderId` | TEXT NULL | FK → folders.id（分類） |

> 桌面端的「本地文件實際位置」不落 DB：由 `resolveLocalFile()` 依「documentsDir mirror
> 優先、原始路徑回退」規則派生，因此同步任何階段都不需要（也不允許）改寫資料列。

### strokes / points
- Stroke：`id`(PK)、`documentUri`(FK)、`pageIndex`、`color`、`strokeWidth`、外框等。
- Point：`x`、`y`、`pressure?`、順序索引。

---

## 6. 衝突解決（Conflict Resolution）

1. **文檔級**：比較 `lastOpenedAt`。
   - `remote > local` → 遠端勝，拉取 `document_detail`（整檔覆蓋 + 筆跡快照替換）。
   - `remote ≤ local` → 保留本地（等 timestamp 時本地優先，避免抖動重複拉取）。
2. **筆跡級**：不做 point-wise merge。筆跡是 **document-scoped snapshot**——文檔確定誰勝負後，該文檔全部筆跡整體替換。這與平板「單一寫者」模型一致，杜絕半截合併造成的渲染錯亂。
3. **刪除**：manifest 中缺席的本地文檔**不會**被自動刪除（軟刪除策略，v2 範圍外；v3 計劃加入 tombstone 表）。

---

## 7. 文件本體傳輸（PDF Body Transfer）

觸發條件（`needsFileDownload`）：
- 本地 `localPath` 指向的文件不存在；或
- 本地文件的 SHA-256 ≠ manifest 中的 `fileSha256`。

流程：
1. `file_meta` → 取得 `{exists, size, sha256}`。
2. 循環 `file_data(offset, limit=1MiB)`，寫入 `<filename>.part`（**天然支援斷點續傳**：崩潰後重啟從 `.part` 現有長度繼續即可，v2 目前整檔重傳，接口已預留）。
3. 完成後校驗 SHA-256：不符 → 刪除 `.part`、報錯、跳過。
4. 校驗通過 → rename 為 `documents/<filename>`；DB 資料列完全不動（本地路徑由 mirror 派生）。

> ⚠️ 關鍵不變量（v2.0.1 修復）：**絕不改寫 `uri`**。
> 舊實作曾把 `uri` repoint 成桌面本地 `file://` 路徑，導致下一輪 manifest diff
> 找不到匹配行 → 每次同步都全量重拉。`uri` 是雙端 join key，必須保持與平板一致。

存儲佈局鏡像 Android `PdfManager.copyPdfToAppDir()`：
```
<desktop data dir>/documents/<原文件名>.pdf
```

---

## 8. 超時與冪等

| 項目 | 值 |
|---|---|
| TCP connect timeout | 5 s |
| Socket read timeout | 60 s |
| Discovery 廣播間隔 | 30 s |
| 設備 TTL | 90 s |
| 單 chunk | 1 MiB |

所有 request 均為冪等讀取（pull-only），任意時刻斷線重連都是安全的；下一次 sync 從 manifest diff 重新開始。

---

## 9. Android 端待辦（Sync Server v2 實作清單）

平板端 `app/` 需新增：

- [ ] `sync/DiscoveryResponder.kt`：監聽 UDP 53530，收到 `requesterRole != "tablet"` 的 discover 後單播 `DiscoveryResponse`（含本機 WLAN IP）。
- [ ] `sync/TabletSyncServer.kt`：`ServerSocket(53531)`，按 §4 表實作 7 個 verb；`file_data` 用 `RandomAccessFile.seek(offset)` 讀 chunk。
- [ ] 前台 Service（`foregroundServiceType="dataSync"`）承載兩者，並在設定頁提供開關。
- [ ] Manifest 查詢直接讀 Room（`documents` LEFT JOIN count(strokes)，`fileSha256` 可延遲計算/快取）。
- [ ] 防火牆提示：首次啟動引導用戶允許局域網訪問。

驗證方式：兩台設備同 WiFi → 桌面自動出現平板卡片 → 點 Sync → 檢查
`updated/strokes/files/skipped` 計數，第二次同步應全部 `skipped`（冪等性測試）。

---

## 10. 版本演進

| 版本 | 變更 |
|---|---|
| v1 | 行分隔 JSON（已廢棄：無法承載二進制） |
| **v2** | 長度前綴幀、manifest diff、SHA-256 校驗、chunked 文件傳輸 |
| v2.0.1 | 修復 uri repoint bug（同步永不改寫 uri，本地路徑鏡像派生） |
| v3（規劃） | tombstone 刪除同步、雙向 push（桌面書籤/分類回傳）、增量筆跡 delta、TLS-PSK 可選加密 |
