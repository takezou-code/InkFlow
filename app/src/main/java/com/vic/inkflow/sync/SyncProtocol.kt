package com.vic.inkflow.sync

/**
 * 協定 v3 的常數與線上 DTO。
 *
 * 這份檔案是 `InkFlow-Windows/desktop-app/src/main/kotlin/com/vic/inkflow/sync/SyncProtocol.kt`
 * 的對應實作。**兩邊的欄位名必須逐字一致**（Gson 靠名字配對，沒有任何編譯期檢查），
 * 任何一端改名就是靜默的資料遺失。
 *
 * 真相來源：`InkFlow-Windows/desktop-app/SYNC_PROTOCOL.md`。
 *
 * 傳輸分工（§1）：
 *  - 探索：UDP broadcast 53530
 *  - 傳輸：TCP 53531，4-byte big-endian 長度前綴幀（見 [SyncWire]）
 *
 * 角色：**桌面端是 client，平板端是 server**，且平板是唯一的寫入者（pull-only）。
 */
object SyncPorts {
    const val DISCOVERY_PORT = 53530
    const val TRANSFER_PORT = 53531
}

object SyncConstants {
    /**
     * v5。
     *
     * 升版是**強制**的。v5 新增 `proposal_submit`/`proposal_status` 兩個 verb；
     * 舊平板會回「未知 verb」，新桌面若不檢查版本就會一直排提案進永遠沒人讀的
     * 佇列——佇列無限增長，而症狀只是「桌面改的東西沒過去」。handshake 本來就會拒
     * 絕版本不符，所以升版是為了把「靜默漏寫」換成「一句明確的錯誤」。兩端的這個
     * 常數必須一致。
     */
    const val PROTOCOL_VERSION = 5

    /** 探索封包用的應用程式標記；不是 InkFlow 的封包直接不回應（§3）。 */
    const val APP_TAG = "InkFlow"
}

/** 探索訊息裡的 role 字串。用字串而非 enum，避免 Gson 對 enum 的序號/名稱綁定。 */
object SyncRoles {
    const val TABLET = "tablet"
    const val DESKTOP = "desktop"
}

// ─── Discovery（UDP :53530）────────────────────────────────────────────────

/** Desktop → broadcast：「有沒有 InkFlow 平板？」 */
data class DiscoveryRequest(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "discover",
    /** "tablet" | "desktop"（§3 規則 1：只回應不同 role，所以兩個平板互相忽略）。 */
    val requesterRole: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    /** 發送者自身安裝的身分。桌面端目前不填，保留欄位是為了 wire 對稱。 */
    val instanceId: String? = null
)

/** 平板 → 單播回 sender：「我在這裡，用這個 IP 連我」。 */
data class DiscoveryResponse(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "response",
    val deviceId: String,
    val deviceName: String,
    val role: String = SyncRoles.TABLET,
    /** 區網 IP。**不能**用 InetAddress.getLocalHost()（見 [DiscoveryResponder.localIpAddress]）。 */
    val ip: String,
    val transferPort: Int = SyncPorts.TRANSFER_PORT,
    /** 不相容時桌面端拒絕建立 TCP（§3 規則 2），v2 是靜默繼續然後神祕地失敗。 */
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION,
    /** 讓桌面端記下世代（§5）：不同值 = 平板重灌 = 清空本地鏡像重新同步。 */
    val instanceId: String? = null
)

// ─── TCP request / response envelope（§4）────────────────────────────────

/**
 * Client → Server。`type`/`deviceId` 刻意宣告成 nullable：Gson 是反射式賦值，不會跑
 * Kotlin 的 null 檢查，所以「欄位缺漏」在這裡只會得到 null，必須在讀取端自己守。
 */
data class SyncRequest(
    val type: String? = null,
    val deviceId: String? = null,
    val instanceId: String? = null,
    /** document_* / stroke_* / file_* 需要。 */
    val documentUri: String? = null,
    /** 僅 stroke_page。 */
    val pageIndex: Int? = null,
    /** 僅 file_data。 */
    val offset: Long? = null,
    /** 僅 file_data。 */
    val limit: Int? = null,
    /** 僅 handshake：原始 PSK，不是雜湊。 */
    val psk: String? = null,
    /** v5：僅 proposal_submit，提案 body。其他 verb 為 null。 */
    val proposal: ProposalSubmitPayload? = null,
    /** v5：僅 proposal_status，查詢哪個提案。 */
    val proposalId: String? = null
) {
    companion object {
        const val TYPE_HANDSHAKE = "handshake"
        const val TYPE_DOC_MANIFEST = "doc_manifest"
        const val TYPE_DOCUMENT_DETAIL = "document_detail"
        const val TYPE_STROKE_DELTA = "stroke_delta"
        const val TYPE_STROKE_PAGE = "stroke_page"
        const val TYPE_FILE_META = "file_meta"
        const val TYPE_FILE_DATA = "file_data"
        /** v5：desktop → tablet，「我改了這些」。 */
        const val TYPE_PROPOSAL_SUBMIT = "proposal_submit"
        /** v5：「我的提案後來怎麼樣了」。 */
        const val TYPE_PROPOSAL_STATUS = "proposal_status"
    }
}

/**
 * Server → Client。`payload` 是 **JSON 字串**（不是巢狀物件）——桌面端拿到後會再做一次
 * `gson.fromJson(resp.payload, X::class.java)`，所以這裡必須 `gson.toJson(payload)`。
 */
data class SyncResponse(
    val type: String = "",
    val ok: Boolean = true,
    val error: String? = null,
    val payload: String? = null
)

// ─── JSON payload bodies ─────────────────────────────────────────────────

/**
 * `doc_manifest` 的每一列。**完整**清單是刪除傳播（§7）的前提：桌面端每輪都拿全量，
 * 「全量裡缺席」才等於「已被刪除」。
 *
 * [docVersion] 才是桌面端實際 diff 的欄位，內容定址雜湊，不是時間戳（§6）。
 */
data class DocumentManifestEntry(
    val uri: String,
    val displayName: String,
    /** 僅供 UI 顯示「上次開啟」，**不參與同步判定**。 */
    val lastOpenedAt: Long,
    val strokeCount: Int,
    val filePresent: Boolean,
    val fileSha256: String? = null,
    val fileSize: Long? = null,
    /** SHA-256(instanceId ‖ uri ‖ strokeCount ‖ fileSha256 ‖ fileSize ‖ textCount)，見 [SyncIdentity.docVersion]。 */
    val docVersion: String? = null,
    /**
     * v4：文字註解筆數，並參與 [docVersion]。沒有它的話「只加了一條註解」在桌面端
     * 看起來完全沒變化，註解就永遠不會出現——這是 v4 存在的唯一理由。
     *
     * 預設 0，讓 v3 payload 仍可解碼。**不可**是 -1：負數同樣代表「有變」，會讓每輪
     * 都重拉整份文件。
     */
    val textCount: Int = 0
)

/**
 * `document_detail` 的應答：整列 + 全部筆跡（含點）+（v4）全部文字註解。
 *
 * 註解跟筆跡放同一個 payload，而不是另開一個 verb：刪除模型本來就是「manifest 完整、
 * 平板是唯一寫者、桌面端整份替換」，兩張表本來就一起取。多開一個 verb 只會多一套
 * 「這一輪看過了」的簿記，沒有任何好處。
 */
data class DocumentDetailPayload(
    val document: Any?,
    val strokes: List<Any>,
    /** v4：List<TextAnnotationEntity>。預設空清單讓 v3 payload 仍可解碼。 */
    val texts: List<Any> = emptyList()
)

/** `stroke_delta` / `stroke_page` 的應答。 */
data class StrokeDeltaPayload(
    val documentUri: String,
    /** 順序有意義：**points 的陣列順序就是 polyline 順序**（§9）。 */
    val strokes: List<Any>,
    val deletedStrokeIds: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int = 0
)

/** `file_meta` 的應答：PDF 本體的尺寸與校驗碼。 */
data class FileMetaPayload(
    val exists: Boolean,
    val size: Long = 0,
    val sha256: String? = null
)

/**
 * v5：提案裡的一個 id 級變更。
 *
 * 按物件 id 操作，絕不用整檔快照：整檔快照當提案會把「桌面舊快照蓋掉平板新筆跡」
 * 重新引入，而那正是整份取代拉取要避免的失敗。upsert 帶完整物件、delete 只帶 id，
 * 兩者按 id 冪等——重送同一提案會落在同一狀態，不需持久化去重表。
 */
data class ProposalOp(
    /** "upsert_stroke" | "delete_stroke" | "upsert_text" | "delete_text"。 */
    val op: String,
    /** op == "upsert_stroke" 時：StrokeWithPoints。 */
    val stroke: Any? = null,
    /** op == "upsert_text" 時：TextAnnotationEntity。 */
    val text: Any? = null,
    /** op 以 "delete_" 開頭時：目標 id。 */
    val id: String? = null
) {
    companion object {
        const val UPSERT_STROKE = "upsert_stroke"
        const val DELETE_STROKE = "delete_stroke"
        const val UPSERT_TEXT = "upsert_text"
        const val DELETE_TEXT = "delete_text"
    }
}

/**
 * v5 `proposal_submit` 的 body。
 *
 * [baseDocVersion] 是樂觀鎖 token：桌面做這些修改時看到的版本。平板若已經往前走，
 * 提案算過期，直接回衝突而不是靜默覆寫新內容。「合併」與「按意外讓最後寫入者贏」
 * 的全部差別就在這一次比對。
 */
data class ProposalSubmitPayload(
    /** UUID 冪等鍵。重送同一提案絕不應用兩次。 */
    val proposalId: String,
    val documentUri: String,
    val baseDocVersion: String,
    val baseInstanceId: String?,
    val actorDeviceId: String,
    val ops: List<ProposalOp>
)

/**
 * v5 對 `proposal_submit` 與 `proposal_status` 的應答。
 */
data class ProposalStatusPayload(
    val proposalId: String,
    /** "accepted" | "conflict_stale" | "rejected" | "unknown"。 */
    val status: String,
    /** 平板當前版本——桌面要 rebase 到的版本。 */
    val winnerDocVersion: String? = null,
    /** 提案動過但已對不上的物件 id。接受時為空。 */
    val conflictIds: List<String> = emptyList(),
    val message: String? = null
) {
    companion object {
        const val ACCEPTED = "accepted"
        const val CONFLICT_STALE = "conflict_stale"
        const val REJECTED = "rejected"
        const val UNKNOWN = "unknown"
    }
}

/**
 * `handshake` 的應答 payload（§8）。
 *
 * [pskProof] = SHA-256(psk ‖ "inkflow-sync-v3")。雙向皆驗證：桌面端先送原始 PSK 給我們，
 * 我們用 constant-time 比對並回 proof 讓它確認我們也持有同一份秘密。
 */
data class HandshakeAck(
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION,
    val instanceId: String? = null,
    val pskProof: String? = null
)

/** 請求層級的錯誤（空 documentUri、未知 verb、未知文件…）。回 ok=false 讓 client 跳過續跑。 */
class SyncRequestException(message: String) : Exception(message)
