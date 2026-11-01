package com.vic.inkflow.ui

import android.net.Uri

/**
 * AI 契約層。**這裡不許出現任何具體 AI 的 DOM selector、網址細節或品牌字樣**——
 * 那是各 provider driver 的事。契約只描述「要做什麼」，不描述「怎麼做」。
 *
 * 分層約定（全專案 AI 相關程式碼都照這套走）：
 *   L0 意圖  ：按鈕只產生 [AiRequest]，不知道當前是哪個 AI。永不需修改。
 *   L1 契約  ：本檔。
 *   L2 驅動  ：每個 AI 一份 driver，差異的容身處（AiGeminiDriver／AiChatGptDriver…）。
 *   L3 效應  ：AiImportFlow／AiTextImport／WebCrop／MathSnapshot，只吃結果，不知道來源。
 *
 * 加第 3、第 4 個 AI 時，**只新增 L2 一個檔案**（driver）＋[AiProvider] 加一行，
 * 契約層不再有 `when (provider)` 需要補分支。
 */

/**
 * AI 來源。**每一格只綁一個 [AiDriver]，行為全部委派給它**——
 * 契約層不再有 `when (provider)`，加一家 AI 只需新增 driver 檔＋這裡加一行。
 *
 * 持久化用的是 enum 的 `.name`（`ai_provider` / `ai_doc_provider_*` / `ai_doc_url_*`），
 * 所以 **GEMINI／CHATGPT 這兩個名字永遠不可改**，改了舊 prefs 會還原失敗。
 *
 * host 是 onPageFinished 的閘門，label/host/startUrl/capabilities 都從 driver 取，
 * 避免「URL 在一處、host 在另一處」再次分家。
 */
enum class AiProvider(val driver: AiDriver) {
    /** Gemini：貼圖、匯入、數學裁圖全部已接上（既有功能，行為不可變）。 */
    GEMINI(AiGeminiDriver),

    /** ChatGPT：貼圖、整塊像素裁圖、匯入（共用圈選／收集實作）。 */
    CHATGPT(AiChatGptDriver),

    /**
     * 以下幾家是 **Tier 1 起跳**：開站、登入、送圖可用；匯入／數學裁圖逐項實測後才開
     * （`PROBE3` log 鎖定 selector）。新增一家 AI 只要：
     * ①新增 `AiXxxDriver.kt` ②這裡加一行。契約層不需要再補任何 `when` 分支。
     */
    CLAUDE(AiClaudeDriver),
    GROK(AiGrokDriver),
    KIMI(AiKimiDriver),
    QWEN(AiQwenDriver),
    DEEPSEEK(AiDeepSeekDriver),

    /**
     * 自訂 AI：**沒有預設網站**，名稱與網址都由使用者在設定頁填。
     *
     * 為什麼要有這一格：新 AI 一直出，每次都要等我們發版才能用不合理；
     * 網站改版或擋區時，使用者自己換網址就能繼續。
     *
     * 契約上它跟其他家完全一樣（一支 driver），差別只在 [startUrl]／[host] 是空的，
     * 實際值由 `ai_custom_url_CUSTOM` 推導（見 [effectiveStartUrl]／[effectiveHost]）。
     * 沒填網址時不可用，由 [coerceUsableProvider] 退回其他家。
     */
    CUSTOM(AiCustomDriver);

    val label: String get() = driver.label
    val startUrl: String get() = driver.startUrl
    val host: String get() = driver.host
    val capabilities: AiCapabilities get() = driver.capabilities
    val importStrategy: AiImportStrategy get() = driver.importStrategy
    val glyph: AiGlyph get() = driver.glyph

    /**
     * 舊版二元切換。
     *
     * Phase 2 起工具列改用「設定頁勾選的啟用清單」輪替（見 [nextEnabledProvider]），
     * 這裡只為既有單測與相容保留；三家以上請勿再呼叫。
     */
    val other: AiProvider get() = AiProvider.entries.firstOrNull { it != this } ?: this
}

/** AI 來源持久化 key（與 AppNav 的 theme_mode／power_saver 共用同一份 prefs）。 */
const val KEY_AI_PROVIDER = "ai_provider"

/** 設定頁勾選的 AI 清單（逗號分隔 enum 名）；空／壞值 → 預設兩家。 */
const val KEY_AI_ENABLED_PROVIDERS = "ai_enabled_providers"

/** 沒設定過時預設啟用的兩家（既有已驗收功能）。 */
val DEFAULT_ENABLED_AI_PROVIDERS: List<AiProvider> = listOf(AiProvider.GEMINI, AiProvider.CHATGPT)

/**
 * 解析 prefs 裡逗號分隔的 provider 名。
 *
 * 認不得的名字忽略、重複去重，結果一律照 [AiProvider.entries] 的順序（切換可預期）。
 * 空或全部認不得 → 回預設兩家，避免使用者把自己鎖在「沒有任何 AI」。
 */
fun parseEnabledProviders(raw: String?): List<AiProvider> {
    val names = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val picked = AiProvider.entries.filter { it.name in names }
    return picked.ifEmpty { DEFAULT_ENABLED_AI_PROVIDERS }
}

/** 把啟用清單寫回 prefs 字串（照 enum 順序）。 */
fun encodeEnabledProviders(providers: List<AiProvider>): String =
    AiProvider.entries.filter { it in providers }.joinToString(",") { it.name }

/** 在啟用清單裡找下一家（工具列切換用）；只有一家時停在原地。 */
fun nextEnabledProvider(current: AiProvider, enabled: List<AiProvider>): AiProvider {
    val list = enabled.ifEmpty { DEFAULT_ENABLED_AI_PROVIDERS }
    val idx = list.indexOf(current)
    return if (idx < 0) list.first() else list[(idx + 1) % list.size]
}

/** 目前 provider 被設定頁關掉時，退回啟用清單第一家。 */
fun coerceEnabledProvider(current: AiProvider, enabled: List<AiProvider>): AiProvider {
    val list = enabled.ifEmpty { DEFAULT_ENABLED_AI_PROVIDERS }
    return if (current in list) current else list.first()
}

/**
 * 目前 provider 是否「真的能用」；不能用就退回啟用清單裡第一個有預設站台的。
 *
 * 只有一個情境：自訂 AI 還沒填網址（或填了壞掉的字串）。它沒有預設網站，
 * 硬留下來只會讓 WebView 開一個錯誤頁，使用者看到的是「壞了」而不是「還沒填」。
 */
fun coerceUsableProvider(
    current: AiProvider,
    enabled: List<AiProvider>,
    customUrl: String?
): AiProvider {
    val coerced = coerceEnabledProvider(current, enabled)
    if (coerced != AiProvider.CUSTOM) return coerced
    if (normalizeCustomAiUrl(customUrl) != null) return coerced
    val list = enabled.ifEmpty { DEFAULT_ENABLED_AI_PROVIDERS }
    return list.firstOrNull { it != AiProvider.CUSTOM } ?: DEFAULT_ENABLED_AI_PROVIDERS.first()
}

/** 自訂 AI 的顯示名稱（設定頁可改）；空＝預設名。 */
const val KEY_AI_CUSTOM_NAME = "ai_custom_name"

/** 自訂 AI 沒取名時用的名字。 */
const val DEFAULT_AI_CUSTOM_NAME = "自訂 AI"

/** 把 prefs 裡的名字正規化成可顯示的字串。 */
fun aiCustomDisplayName(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AI_CUSTOM_NAME

/**
 * 每份文件的 AI 記憶（provider＋上次對話 URL），存在同一份 `inkflow_settings` prefs。
 *
 * 為什麼不用 DB／document_preferences：這只是「打開哪頁」的 UI 記憶，
 * 跟文件內容無關；prefs 讀寫同步、無需 DAO，壞了最多回到起始頁，不會傷資料。
 * key 帶 provider 名：同一份文件在兩個站各有自己的對話，切站不互相覆蓋。
 */
fun aiDocProviderKey(docId: String): String = "ai_doc_provider_$docId"

fun aiDocUrlKey(docId: String, provider: AiProvider): String =
    "ai_doc_url_${provider.name}_$docId"

// ── 自訂起始網址 ---------------------------------------------------------------

/**
 * 每家 AI 的自訂起始網址（prefs key）。
 *
 * 為什麼需要：同一家 AI 常有多個入口（Kimi 國際版 `kimi.ai` vs 中國版 `kimi.com`、
 * Qwen 的不同區域站），哪個進得去是「網站政策」決定的，改版或擋區時不該逼使用者
 * 等我們重新出包。使用者自己填一次就換得過去。
 *
 * 空值＝用 driver 的預設 [AiProvider.startUrl]（driver 仍是唯一預設來源）。
 */
fun aiCustomUrlKey(provider: AiProvider): String = "ai_custom_url_${provider.name}"

/**
 * 使用者輸入的網址正規化。
 *
 * 只收 `http://` / `https://` 且解析得出 host 的字串——WebView 什麼都吃，
 * 但把 `kimi.ai` 當相對路徑載入會變成一片空白，使用者只會覺得「壞了」。
 * 不合法回 null（呼叫端顯示錯誤，不要靜默存進去）。
 */
fun normalizeCustomAiUrl(raw: String?): String? {
    val s = raw.orEmpty().trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) return null
    return if (urlHost(s) == null) null else s
}

/** 這次要進的起始網址：自訂優先，否則 driver 預設。 */
fun AiProvider.effectiveStartUrl(customUrl: String? = null): String =
    normalizeCustomAiUrl(customUrl) ?: startUrl

/**
 * 這次的「本站」host：有自訂網址就用它的 host。
 *
 * 少了這一步，自訂網址會被注入閘門擋掉（`onPageFinished` 比的是 provider.host），
 * 症狀是「網站開得起來、但送圖／探針完全不動作」——比壞掉更難查。
 */
fun AiProvider.effectiveHost(customUrl: String? = null): String =
    normalizeCustomAiUrl(customUrl)?.let { urlHost(it) } ?: host

/**
 * 算出這份文件這次該進的地址。純函數，單測釘住。
 *
 * 只接受「同站」的記憶地址：登入跳轉（accounts.google.com 之類）不存，
 * 存了也不認——否則還原會落在登入中轉頁而不是對話。
 *
 * 「同站」的判定走 [matchesHost]（含 `www.` 等子網域），不是子字串：
 * 中轉頁的 `?continue=https://gemini...` 查詢參數裡也帶著站名，子字串比對會誤判（單測釘死）。
 *
 * @param customUrl 使用者自訂的起始網址；有值時「同站」以它的 host 為準，
 *   否則存了 `kimi.com` 的舊記憶會蓋掉使用者剛填的 `kimi.ai`。
 */
fun AiProvider.resolveRestoreUrl(savedUrl: String?, customUrl: String? = null): String {
    val s = savedUrl.orEmpty()
    val base = customUrl?.let { normalizeCustomAiUrl(it) }
    // 登入回呼頁不算對話頁：早期版本會把它記成「上次待的地方」（還帶著 id_token），
    // 還原到那裡只會得到一片空白。這種記憶一律當成沒記過。
    return if (s.isNotBlank() && !looksLikeAuthCallback(s) && matchesHost(s, base?.let { urlHost(it) })) {
        s
    } else {
        effectiveStartUrl(base)
    }
}

/**
 * 這個 URL 算不算「本站」。
 *
 * 除了 host 完全相符，也接受子網域（`www.kimi.ai`）：多數站台會在裸網域與
 * `www.` 之間跳轉，也可能把對話放在 `chat.` 之類的子網域。若只比完全相符，
 * 使用者實際落在 `www.` 時就永遠記不住、也認不回自己的對話。
 *
 * 只認「點號分界」的子網域——`notkimi.ai` 這種前綴相似的不算，
 * 否則偽站或拼字相近的網域會被當成本站而注入腳本。
 *
 * @param overrideHost 自訂網址帶進來的 host（見 [effectiveHost]）；null＝用 driver 的 host。
 */
fun AiProvider.matchesHost(url: String, overrideHost: String? = null): Boolean {
    val base = overrideHost?.takeIf { it.isNotBlank() } ?: host
    val h = urlHost(url) ?: return false
    return h == base || h.endsWith(".$base")
}

/** 從 URL 反推是哪個站；認不得回 null（呼叫端自行忽略，不存）。 */
fun aiProviderForUrl(url: String): AiProvider? =
    AiProvider.entries.firstOrNull { it.matchesHost(url) }

/**
 * 取 URL 的 host（纯字串解析，不碰 android.net.Uri——单测里 Uri 是 stub）。
 * 比大小寫、大寫 scheme、帶 port 都先正規化；解析不出來回 null。
 */
internal fun urlHost(url: String): String? {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    val host = afterScheme.substringBefore("/").substringBefore(":").lowercase().trim()
    return host.ifEmpty { null }
}

/**
 * 以下是對 [AiDriver] 的**薄委派**：行為在 driver，契約層只負責轉發。
 * 面板與 driver 之間唯一的注入分歧點仍是 [promptSendJs]，只是現在由 driver 自己回答。
 */
fun AiProvider.promptSendJs(promptQuoted: String, send: Boolean): String =
    driver.promptSendJs(promptQuoted, send)

/** DOM 探針腳本（臨時診斷，定完 selector 即刪）。 */
fun AiProvider.domProbeJs(): String = driver.domProbeJs()

/** 貼圖要瞄準的輸入框 selector——provider 專屬的部分就這一個字串。 */
internal val AiProvider.imagePasteInputSelector: String
    get() = driver.imagePasteInputSelector

/**
 * 圈選腳本。所有 provider 共用同一份實作，只換回覆容器候選鏈
 * （Gemini 是 `model` 角色、ChatGPT 是 `assistant`）。
 */
fun AiProvider.pickJs(palette: PickPalette = PickPalette()): String = driver.pickJs(palette)

/** 收集腳本，同上。 */
fun AiProvider.collectJs(): String = driver.collectJs()

/** 回覆容器候選鏈。 */
val AiProvider.replyChain: List<String> get() = driver.replyChain

/**
 * 收集前必須等它消失的 selector（串流中的指標）。null＝不用等。
 *
 * 這是 ChatGPT 特有的必要閘門：回覆還在生成就去抓會拿到殘缺內容，
 * 而且長對話會虛擬滾動，畫面外的訊息根本不在 DOM 裡。
 * 放在 Kotlin 側輪詢（不在 JS 裡改流程），是為了**完全不去動 Gemini 已驗收的腳本結構**。
 */
val AiProvider.replyIdleSelector: String? get() = driver.replyIdleSelector

/** 這次請求要做什麼。 */
enum class AiAction {
    /** 送圖＋填提示詞（整頁送 AI、圈選快捷列都走這條）。 */
    SEND_PROMPT,

    /** 抓取 AI 回覆匯入筆記（工具列 ⬇ 匯入鈕）。 */
    IMPORT_REPLY
}

/**
 * 一次 AI 請求。**這是 L0 與 L2 之間唯一的通道**。
 *
 * 取代過去三個各自為政的隱性變數（fileUri／prompt／autoSend）——它們從來沒有名字，
 * 靠約定湊在一起，這就是換 provider 時最容易漏改的地方。
 *
 * @param id 建立時指派的唯一序號，去重只看它。**不要用內容推導去重鍵**（見 dedupeKey）。
 */
data class AiRequest(
    val id: Long,
    val action: AiAction,
    /** 要送的圖（可空＝純文字追問）。 */
    val image: Uri? = null,
    /** 要填入的提示詞（可空＝只送圖）。 */
    val prompt: String? = null,
    /** 填完要不要自動送出。整頁鈕 false（只填不送），圈選快捷列 true。 */
    val autoSend: Boolean = true
) {
    /** 沒圖也沒字＝空請求，送了沒意義。上游應該先擋掉。 */
    val isEmpty: Boolean get() = image == null && prompt == null

    /**
     * 投遞去重鍵＝請求序號。
     *
     * ## 為什麼不能用內容推導（這是踩過的坑）
     *
     * 原本是 `"$action|$image|$prompt|$autoSend"`。但 [prompt] 會在投遞後被
     * `onPromptConsumed` 清成 null（提示詞已填入網頁），於是鍵立刻改變 →
     * 下一輪重組看起來像「新請求」→ **同一張圖被貼兩次**（Gemini／ChatGPT 都中）。
     *
     * 序號在 `copy()` 時會被保留，所以「內容變了」不再等於「換了一次投遞」。
     * 而同一張圖再送一次是**新請求、新序號**，照樣送得出去。
     */
    fun dedupeKey(): String = "${id}_${action.name}"

    companion object {
        /** 去重鍵的純函數版本，供 JVM 測試直接驗證，不碰 Android 類別。 */
        fun dedupeKey(id: Long, action: AiAction): String = "${id}_${action.name}"
    }
}

/** 執行結果。code 沿用既有 JS bridge 的狀態碼語彙，方便對帳。 */
data class AiOutcome(
    val code: String,
    val ok: Boolean,
    /** 有值就 Toast 給使用者；null＝成功或可靜默重試，不打扰。 */
    val userHint: String? = null
) {
    companion object {
        fun ok(code: String) = AiOutcome(code, ok = true)
        fun fail(code: String, hint: String) = AiOutcome(code, ok = false, userHint = hint)
    }
}

/**
 * 這個 provider 到底支援什麼。**按鈕的啟用／停用由這裡決定**，
 * 不要再寫「這個 provider 還沒接上，請用別家」那種手寫守衛。
 */
data class AiCapabilities(
    /** 能不能送圖。 */
    val sendImage: Boolean = false,
    /** 能不能抓回覆匯入筆記。 */
    val importReply: Boolean = false,
    /** 匯入時能不能做數學裁圖（需要 WebView 可見，見 WebCrop.cropFormula）。 */
    val mathCrop: Boolean = false,
    /**
     * 送圖優先用站方自己的 `input[type=file]`（由 `onShowFileChooser` 餵檔），
     * 而不是合成 `paste` 事件。
     *
     * 為什麼要分：合成 paste 的 `dispatchEvent` 回傳值 **不代表圖真的進去了**，
     * 只代表「沒有 listener 取消預設行為」。實測 Kimi／Qwen／Grok 都回
     * `PASTE_DISPATCH_FAILED`，其中有些其實已經把圖非同步塞進去了——我們卻照著
     * 這個假訊號又點了一次 file input，結果同一張圖進去兩份。
     * 這類站台就別猜了：直接走它自己的上傳入口，一條路，不可能重複。
     */
    val fileInputFirst: Boolean = false
)

/** 匯入時用哪個離線數學引擎（裁圖失敗的第二引擎）。 */
enum class MathEngine { KATEX, MATHJAX }

/**
 * 匯入策略：把「這家 AI 的版面怎麼變成頁面」收斂成資料。
 *
 * 以前這三件事散在 `EditorScreen` 的 `provider ==` 比較裡，新增 provider 會走錯路
 * 或靜默失效。現在由 driver 宣告，呼叫端只讀欄位。
 */
data class AiImportStrategy(
    /** 整塊像素裁圖（所見即所得）。false＝文字切分＋公式裁圖。 */
    val blockCrop: Boolean = false,
    /** 是否把行內數學提升成獨立塊。 */
    val promoteInlineMath: Boolean = true,
    /** 裁圖失敗時的離線渲染引擎。 */
    val mathEngine: MathEngine = MathEngine.KATEX
)

/** 工具列切換鈕用的圖示語意（不在 driver 裡碰 Compose）。 */
enum class AiGlyph { CHAT, SPARK }

/**
 * L2 驅動契約。**每個 provider 一份實作，差異全部關在這裡。**
 *
 * 刻意只放「資料 + JS 工廠」，不持有 WebView、不搬面板流程：
 * 貼圖／主題／投遞的單一路徑留在 [AiWebPanel]，這樣重構的風險最小。
 *
 * 新增一家 AI＝新增一個 `object XxxDriver : AiDriver` ＋ [AiProvider] 加一行。
 */
interface AiDriver {
    val label: String
    val startUrl: String
    val host: String
    val capabilities: AiCapabilities
    val importStrategy: AiImportStrategy
    val glyph: AiGlyph
    val replyChain: List<String>

    /**
     * 圈選時要找的容器鏈；預設與 [replyChain] 相同。
     *
     * 為什麼要分開：`replyChain` 是「探針要量哪幾個候選」，越精確越好；
     * 圈選要的是「整個對話區」，越寬越好。實測 Kimi／Qwen 的 `replyChain` 命中
     * 的是單一訊息泡泡（甚至空殼 `div.message-list`），導致只有最後一則能選。
     * 對版面還不熟的站台，圈選直接落在 `main` 最穩（見 CandidateChainDriver）。
     */
    val pickChain: List<String> get() = replyChain

    /**
     * 串流中必須等它消失的 selector；null＝這站沒有停止鈕語意。
     *
     * **不可含單引號**：[AiWebPanel] 的等待迴圈把這個字串直接插進單引號 JS 字串
     * （`document.querySelectorAll('...')`）。要寫屬性選擇器請用雙引號
     * （`[data-is-streaming="true"]`），否則整段腳本語法錯誤，匯入永遠逾時。
     */
    val replyIdleSelector: String?

    /** 貼圖要瞄準的輸入框 selector（可為逗號分隔的候選鏈，取第一個命中）。 */
    val imagePasteInputSelector: String
    fun promptSendJs(promptQuoted: String, send: Boolean): String
    fun pickJs(palette: PickPalette): String
    fun collectJs(): String
    fun domProbeJs(): String
}
