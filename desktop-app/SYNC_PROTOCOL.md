# InkFlow Local LAN Sync Protocol — v3

> 本文件是 Android 平板端與 Windows 桌面端之間的**唯一協定真相來源（single source of truth）**。
> 實作：
> - 桌面端：`desktop-app/src/main/kotlin/com/vic/inkflow/sync/SyncProtocol.kt`、`LocalSyncManager.kt`
> - 平板端：`app/src/main/java/com/vic/inkflow/sync/`（**待實作**，見 §9）
>
> `desktop-app/SYNC_GUIDE.md` 描述的是已廢棄的 v1（行分隔 JSON、`get_documents` 動詞），
> 與本文件矛盾，已刪除。不要參考它。

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
| Transfer  | TCP Socket  | **53531** | 元資料 diff、筆跡拉取、PDF 文件傳輸 |

設計原則：

- **零雲端**：僅局域網內通信，不經公網。
- **Desktop 為 client、Tablet 為 server**：所有數據以平板為準（tablet is the writer of truth），桌面只做拉取（pull-only）。
- **JSON over length-prefixed frames**：易調試，且能安全承載大體積二進制。

> **規劃中的替換**：UDP 廣播與自架幀格式在 v4 會分別換成 `NsdManager`（DNS-SD）與
> HTTP/1.1（Ktor）。兩者的原因與遷移時機見 §11。v3 的邏輯（diff 條件、世代、刪除）
> 不受影響，所以 Android 端**現在就照 v3 實作**不會白做。

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

### 3.1 請求（Desktop → broadcast）

```json
{
  "app": "InkFlow",
  "type": "discover",
  "requesterRole": "desktop",
  "deviceId": "pc-1a2b3c4d",
  "deviceName": "DESKTOP-ABC",
  "instanceId": null
}
```

### 3.2 應答（單播回 sender）

```json
{
  "app": "InkFlow",
  "type": "response",
  "deviceId": "tab-9f8e7d6c",
  "deviceName": "Galaxy Tab S9",
  "role": "tablet",
  "ip": "192.168.1.42",
  "transferPort": 53531,
  "protocolVersion": 3,
  "instanceId": "3f2a9c10-..."
}
```

規則：

1. 只回應**不同 role** 的請求（desktop 不回應 desktop）。
2. `protocolVersion` 不相容時，桌面端**拒絕建立 TCP 連線**並顯示升級提示（v3 新增：v2 只是不警告然後繼續，會在後面以神祕的方式失敗）。
3. 設備在 **90 秒**未見任何廣播後視為離線（TTL = 3 × 廣播間隔）。

---

## 4. TCP 會話流程

```
Desktop                                   Tablet (server)
   │ ── handshake (psk) ───────────────────► │
   │ ◄─ ack { protocolVersion, instanceId } ─│
   │ ── doc_manifest ──────────────────────► │
   │ ◄─ [DocumentManifestEntry] ──────────── │
   │   (for each entry whose docVersion differs from the stored one)
   │ ── document_detail ───────────────────► │
   │ ◄─ payload ───────────────────────────── │
   │ ── file_meta ────────────────────────► │
   │ ◄─ FileMetaPayload ──────────────────── │
   │ ── file_data(offset,limit) ───────────► │
   │ ◄─ binary frame (≤1 MiB chunk) ──────── │
   │   ... repeat until offset == size ...    │
   │ ── close ─────────────────────────────► │
```

一次同步 = 一個 TCP 連接，串行處理請求（request/response lock-step，無 pipelining）。

### 4.1 請求信封 `SyncRequest`

```json
{ "type": "<verb>", "deviceId": "...", "instanceId": "...?",
  "documentUri": "...?", "pageIndex": 0?, "offset": 0?, "limit": 1048576?,
  "psk": "...?  /* 僅 handshake */" }
```

| Verb | 必要欄位 | 應答 |
|---|---|---|
| `handshake` | `psk`（若已配對） | `ack` + `{protocolVersion, instanceId, pskProof}` |
| `doc_manifest` | – | JSON：`[DocumentManifestEntry]` |
| `document_detail` | `documentUri` | JSON：`DocumentDetailPayload` |
| `stroke_delta` | `documentUri` | JSON：`StrokeDeltaPayload` |
| `stroke_page` | `documentUri`, `pageIndex` | JSON：`StrokeDeltaPayload` |
| `file_meta` | `documentUri` | JSON：`FileMetaPayload` |
| `file_data` | `documentUri`, `offset`, `limit` | **二進制幀** |

### 4.2 應答信封 `SyncResponse`

```json
{ "type": "<mirrored verb | error | ack>", "ok": true, "error": null, "payload": "<JSON string>" }
```

錯誤時 `ok=false`、`error` 為人類可讀訊息；客戶端應跳過該文件繼續其餘同步。

---

## 5. 跨設備身分：`instanceId`（v3 新增）

平板端的 `documents.uri` 是 `file://` 路徑，檔名是隨機 UUID，位於 **app 私有儲存**。
它在「單次安裝」內是穩定的 join key，但 **Android 解除安裝會清空 app 私有儲存**，
於是所有 uri 全換新。

v2 無法分辨以下三種情況（三者的共同點都是「該 uri 不再出現在 manifest 中」）：

1. 使用者刪除了文件
2. 平板重灌／工廠重設
3. App 崩潰遺失狀態

再加上 v2「manifest 缺席一律不刪」規則，結果是**平板重灌一次，桌面端就永久累積一批
再也無法被任何 manifest 命中的孤兒列與 PDF，且沒有任何復原路徑**。

### 解法

平板端在**首次啟動時生成一個隨機 UUID** 存進 SharedPreferences，並在每次
`handshake` 的 ack 與每個 `SyncRequest` 中攜帶。

> **平板端不需要改任何 schema。** 這只是一個 app 自己產生並儲存的值的。

桌面端行為：

- 記住上次看到的 `instanceId`。
- 收到**不同**的 `instanceId` → 視為重灌：清空本地鏡像（documents / strokes / points / sync_docs），
  從乾淨狀態重新同步。`SyncResult.generationWiped = true`。
- 收到**相同**的 `instanceId` → 同一世代，缺席 manifest 才代表刪除（見 §6）。

---

## 6. 變更判定：`docVersion`（v3 新增）

### 為什麼廢除 `lastOpenedAt`

v2 用 `lastOpenedAt` 當修改時間戳（newer wins）。這在**兩個方向**都錯：

- **假陽性**：使用者只是打開文件閱讀、沒有畫任何東西 → 時間戳前進 → 桌面端判定
  遠端較新 → 重拉整列 + 全部筆跡 + 整份 PDF。搭配 v2 那個無條件的 28 秒輪詢，
  這會持續發生。
- **假陰性（危險）**：任何沒有同步更新 `lastOpenedAt` 的寫入路徑——undo/redo、
  匯入、頁面重排——會讓文件在桌面端**永久停留在舊狀態，且無法自癒**，
  因為 manifest 會一直說「不是較新的」。

### 解法：內容定址的雜湊

```
docVersion = SHA-256(instanceId ‖ uri ‖ strokeCount ‖ fileSha256 ‖ fileSize)
```

分隔符是 NUL byte（URI 與 hex digest 都不含 NUL），避免 `("ab","c")` 與 `("a","bc")`
雜湊相同的串接歧義。

正確性是**建構上成立**的：內容相同 → 雜湊必然相同；筆跡數、檔案內容或大小任一
改變 → 雜湊必然改變。它不可能假陽性，也不可能假陰性，並且**完全不需要兩台機器
的時鐘一致**。

桌面端把 `docVersion` 存進**自己的** `sync_docs(instanceId, uri)` 表——平板端的
schema 完全不需要動。

> `lastOpenedAt` 仍保留在 manifest 中，供 UI 顯示「上次開啟時間」與未來的雙向 push
> 使用，但**不再用於同步判定**。

---

## 7. 刪除傳播（v3 新增）

平板是唯一寫入者，而且桌面端每次都請求**完整** manifest，所以「完整 manifest 中
缺席」在語意上就是「已被刪除」。

v2 從不刪除任何東西，這是孤兒問題的另一半。v3：

- 每次成功同步後，manifest 中出現的文件把 `lastSeenPass` 更新為本次的 pass 編號。
- 對於本地持有、但本次 manifest 未提及，且 `lastSeenPass < pass - 1` 的文件
  → 刪除（列、筆跡、點、PDF 一併移除）。

**一個 pass 的寬限期**：文件必須連續兩次完整 manifest 都缺席才會被刪除。這防止
manifest 因任何原因被截斷時造成資料遺失。

pass 編號持久化在 `sync_meta.passCounter`——**不能放在記憶體**，因為 sync manager
每次 App 啟動都會重建，記憶體計數會歸零，刪除偵測就永遠不會觸發。

---

## 8. 認證（v3 新增）

威脅模型中最強的一項**不是區域網路，而是平板本身**：平板上任何擁有 `INTERNET`
權限的 App 都可以連到 `localhost:53531` 讀走全部文件與 PDF。Android 的沙箱
**不會**保護你 App 的 inbound socket。

- 平板首次啟動時生成隨機 PSK，以 8 位數字或 QR 呈現。
- 桌面端配對輸入後，兩端在 `handshake` 驗證。
- 平板回傳 `pskProof`（= `SHA-256(psk ‖ "inkflow-sync-v3")`），桌面端以
  **constant-time** 比對。雙向皆然。

未配對（`psk == null`）時仍可運作，適用於單人環境；v4 將要求預設開啟。

---

## 9. 資料模型對齊

> **v3 起，兩端的 SQL schema 不再需要逐欄一致。** 線上的 DTO 才是契約；SQL 是各端
> 的實作細節。桌面端是唯讀鏡像，可以自由決定自己的欄位；好處是 Android 端未來
> 加欄位時不會變成桌面端的潛在 bug（v2 的「schema 必須相同」規則讓每一次未來的
> 欄位變更都變成長期稅負）。

桌面端實際儲存的表（見 `DatabaseManager`）：`documents`、`folders`、`strokes`
（**含 v3 新增的 `docY`**）、`points`、`sync_meta`、`sync_docs`。

### documents

| 欄位 | 型別 | 說明 |
|---|---|---|
| `uri` | TEXT PK | 平板的 `file://` 路徑。**只在搭配 `instanceId` 時有意義**（見 §5），不是全域身分。 |
| `displayName` | TEXT | 標題 |
| `lastOpenedAt` | INTEGER | 顯示用；**不參與同步判定** |
| `lastPageIndex` | INTEGER | 閱讀進度 |
| `isFavorite` | INTEGER(0/1) | 收藏 |
| `folderId` | TEXT NULL | 分類 |

> 桌面端的「本地文件實際位置」不落 DB：由 `resolveLocalFile()` 依「documentsDir
> mirror 優先、原始路徑回退」規則派生。

### strokes（v3：含 `docY`）

- Stroke：`id`(PK)、`documentUri`(FK)、`pageIndex`、`docY`、顏色、粗細、外框等。
- `docY` = 平板 S1 單畫布的文件座標錨點（`pageIndex × stride + boundsTop`）。
- **`docY` 必須可為 NULL**：平板 v24 前的舊資料尚未回填。桌面端**不得自行推算**，
  猜 stride 會把墨跡畫到錯誤位置；平板在開檔時會用自己的 live modelH 惰性回填。
- Point：`x`、`y`、`width?`、順序索引。**順序就是筆劃順序**，接收端必須保留。

---

## 10. 檔案本體傳輸（PDF Body Transfer）

觸發條件：本地缺少該檔案，或本地 SHA-256 ≠ manifest 的 `fileSha256`。

流程：

1. `file_meta` → `{exists, size, sha256}`。
2. 循環 `file_data(offset, limit=1MiB)`，寫入 `<filename>.part`。
3. 完成後校驗 SHA-256：不符 → 刪除 `.part`、報錯、跳過。
4. 校驗通過 → rename 為 `documents/<filename>`。

> ⚠️ **絕不改寫 `uri`**。它是跨世代的 join key，被改寫後下一輪 diff 就找不到對應。

---

## 11. v4 規劃（尚未實作，Android 端不必等待）

1. **`NsdManager` 取代 UDP 廣播**。`255.255.255.255` 不會穿過路由器；客用網路、
   IoT VLAN、mesh WiFi 的 client isolation 會直接讓它失效。更關鍵的是
   **Android 16 開始 local network access 需使用者授權、API 37 強制
   `ACCESS_LOCAL_NETWORK`**，官方受影響清單同時列了「UDP 廣播」與 `NsdManager`。
   自己維護廣播等於自己找死。
2. **HTTP/1.1（Ktor）取代自架幀格式**。`file_data(offset,limit)` 直接變成標準
   `Range` / 206，斷點續傳免費；認證變成一個 header；錯誤變成 status code；
   **除錯可以用 `curl` 和 DevTools**——對一個從沒在真機上跑過的協定這點極重要。
3. **背景同步改為事件驅動**。v2 的無條件 28 秒輪詢在長文件上是持續的頻寬與耗電。
4. **明文雜湊改為增量筆跡 delta**（整份取代在約 50 MB 變更量以上才成為問題，
   而且那時瓶頸是 PDF 不是筆跡）。

---

## 12. 平板端待辦（Sync Server v3 實作清單）

平板端 `app/src/main/java/com/vic/inkflow/sync/` 需新增：

- [ ] `SyncIdentity.kt`：首次啟動生成 `instanceId`（UUID）與 `psk`（8 位數字），
      存 SharedPreferences。**不改任何 entity。**
- [ ] `TabletSyncServer.kt`：`ServerSocket(53531)`，實作 §4 的 7 個 verb。
      - `handshake`：回 `{protocolVersion: 3, instanceId, pskProof}`，用 constant-time 比對 psk
      - `doc_manifest`：Room `documents` LEFT JOIN `count(strokes)`，逐筆算 `docVersion`
      - `document_detail` / `stroke_delta` / `stroke_page`：**回傳前依 `points.id` 排序**
        （Room 的 `@Relation` 不保證順序，而順序就是筆劃順序）
      - `file_meta` / `file_data`：`RandomAccessFile.seek(offset)` 讀 chunk
- [ ] `DiscoveryResponder.kt`：UDP 53530，回應不同 role 的 discover
- [ ] `TabletSyncService`：前台 Service（`foregroundServiceType="dataSync"`）承載 server
- [ ] `AndroidManifest.xml`：`ACCESS_NETWORK_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、
      Service 宣告、`FOREGROUND_SERVICE_DATA_SYNC`
- [ ] 設定頁：開關、配對碼顯示、目前連線的桌面裝置

> **Android 15+ 限制**：`dataSync` 類型每 24 小時只有 **6 小時**上限，超時會
> `RemoteServiceException`，且**不允許從 `BOOT_COMPLETED` 啟動**。結論是平板只有
> 在 App 於前景或使用者明確啟動同步工作階段時才連得上。這對平板 app 沒問題，
> 但要設計成「使用者按『連線到電腦』」而不是看不見的背景輪詢。

驗收方式：兩台設備同 WiFi → 桌面自動出現平板卡片 → 點 Sync → 檢查
`updated / strokes / files / skipped / orphansRemoved` 計數，第二次同步應全部
`skipped`（冪等性）；接著在平板刪掉一份文件，第三次同步應出現 `orphansRemoved = 1`。

---

## 13. 版本演進

| 版本 | 變更 |
|---|---|
| v1 | 行分隔 JSON（已廢棄：無法承載二進制） |
| v2 | 長度前綴幀、manifest diff、SHA-256 校驗、chunked 文件傳輸 |
| v2.0.1 | 修復 uri repoint bug（同步永不改寫 uri） |
| **v3** | **`instanceId` 世代偵測、`docVersion` 內容雜湊取代時間戳、刪除傳播（含寬限期）、PSK 認證、`strokes.docY`、兩端 schema 解耦、單執行緒 DB** |
| v4（規劃） | `NsdManager`、HTTP/1.1、事件驅動同步、增量筆跡 delta |
