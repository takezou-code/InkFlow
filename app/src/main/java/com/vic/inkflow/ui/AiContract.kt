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
 * 加第 3、第 4 個 AI 時，**只新增 L2 一個檔案**，其餘三層零改動。
 */

/**
 * AI 來源。開關放在工具列（EditorChrome），面板本身只是被動的 view。
 *
 * host 是 onPageFinished 的閘門：換 host 卻沒同步換這裡，整套注入會靜默失效，
 * 所以每個 provider 的 URL／host 綁在同一個 enum，避免再次分家。
 *
 * [capabilities] 說明這個站**現在真的能做什麼**。按鈕的啟用與提示一律讀它，
 * 不要在呼叫端寫 `if (provider != GEMINI)` 這種守衛——那會隨 provider 增加而爆炸，
 * 而且新 provider 沒被加進那些 if 就會靜默失效。
 */
enum class AiProvider(
    val label: String,
    val startUrl: String,
    val host: String,
    val capabilities: AiCapabilities
) {
    /** Gemini：貼圖、匯入、數學裁圖全部已接上（既有功能，行為不可變）。 */
    GEMINI(
        "Gemini",
        "https://gemini.google.com/app",
        "gemini.google.com",
        AiCapabilities(sendImage = true, importReply = true, mathCrop = true)
    ),

    /**
     * ChatGPT：Phase 0 實測頁面可開、可登入、可對話（沒被 Cloudflare 擋）。
     *
     * S3 起開啟 [sendImage]（整頁／圈選送圖），但**匯入仍未接上**——
     * 匯入要等 reply selector 定案，且必須等停止鈕消失才收（虛擬滾動，長對話只有畫面上的在 DOM）。
     * 每開一項都必須先有實機驗證；沒驗證就翻 flag 等於對使用者說謊。
     */
    CHATGPT(
        "ChatGPT",
        "https://chatgpt.com/",
        "chatgpt.com",
        AiCapabilities(sendImage = true, importReply = false, mathCrop = false)
    );

    val other: AiProvider get() = if (this == GEMINI) CHATGPT else GEMINI
}

/** AI 來源持久化 key（與 AppNav 的 theme_mode／power_saver 共用同一份 prefs）。 */
const val KEY_AI_PROVIDER = "ai_provider"

/**
 * 產生填詞腳本。**這是面板與 driver 之間唯一的注入分歧點。**
 *
 * S3：以前這個 `buildPromptSendJs` 是 AiWebPanel 裡一整支 Gemini 專屬函式；
 * 現在由 provider 自己決定，避免面板裡長出 if/else provider 分支。
 */
fun AiProvider.promptSendJs(promptQuoted: String, send: Boolean): String = when (this) {
    AiProvider.GEMINI -> AiGeminiDriver.promptSendJs(promptQuoted, send)
    AiProvider.CHATGPT -> AiChatGptDriver.promptSendJs(promptQuoted, send)
}

/** DOM 探針腳本（臨時診斷，S3 定完 selector 即刪）。 */
fun AiProvider.domProbeJs(): String = when (this) {
    AiProvider.GEMINI -> buildDomProbeJs()
    AiProvider.CHATGPT -> AiChatGptDriver.domProbeJs()
}

/** 貼圖要瞄準的輸入框 selector——provider 專屬的部分就這一個字串。 */
internal val AiProvider.imagePasteInputSelector: String
    get() = when (this) {
        AiProvider.GEMINI -> "rich-textarea, div[role=\"textbox\"][contenteditable=\"true\"]"
        AiProvider.CHATGPT -> AiChatGptDriver.IMAGE_PASTE_INPUT_SELECTOR
    }

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
 */
data class AiRequest(
    val action: AiAction,
    /** 要送的圖（可空＝純文字追問）。 */
    val image: Uri? = null,
    /** 要填入的提示詞（可空＝只送圖）。 */
    val prompt: String? = null,
    /** 填完要不要自動送出。整頁鈕 false（只填不送），圈選快捷列 true。 */
    val autoSend: Boolean = true
) {
    /** 沒圖也沒字＝空請求，送了沒有意義。上游應該先擋掉。 */
    val isEmpty: Boolean get() = image == null && prompt == null

    /**
     * 去重鍵。沿用舊的「比對 Uri」語意，但把它變成一個名字，
     * 避免新 provider 把比較邏輯散落重寫。
     */
    fun dedupeKey(): String = dedupeKey(action, image?.toString(), prompt, autoSend)

    companion object {
        /**
         * 去重鍵的**純函數**版本：吃字串、不碰任何 Android 類別。
         *
         * 為什麼多這一層：`image` 是 [Uri]，而 plain JVM 單測裡 android.net.Uri 是
         * stub（build.gradle 有 isReturnDefaultValues，Uri.parse 會回 null），
         * 直接對 Uri 取值根本測不了。環境規則要求可測邏輯抽成純函數。
         *
         * 語意：同一張圖配不同提示詞或不同 autoSend＝**不同請求**。
         * 舊版只比對 Uri，整頁(解釋·不送)與圈選(總結·自動送)若剛好同一張 Uri
         * 會被誤判成同一個而漏送。
         */
        fun dedupeKey(
            action: AiAction,
            imageId: String?,
            prompt: String?,
            autoSend: Boolean
        ): String = "${action.name}|$imageId|$prompt|$autoSend"
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
    val mathCrop: Boolean = false
)

/**
 * L2 驅動介面。**每個 provider 一份實作，差異全部關在這裡。**
 *
 * 實作者要回答的問題只有四個，這個站怎麼：認得頁面、找到輸入框、把圖貼進去、把話送出去。
 */
interface AiDriver {
    val provider: AiProvider
    val capabilities: AiCapabilities

    /** 頁面可用（登入完成、SPA 渲染完）。回傳 false 代表還沒 ready，driver 可自己續等。 */
    fun onPageReady(bridge: AiJsBridge): Boolean

    /** 執行一次 [AiRequest]。 */
    fun deliver(bridge: AiJsBridge, request: AiRequest): AiOutcome

    /** 抓回覆回傳 JSON（圈選段落 / 整篇回覆）。 */
    fun importReply(bridge: AiJsBridge): AiOutcome
}

/**
 * JS 注入通道。driver 只透過它碰 WebView，不直接持有 WebView 參考，
 * 避免 driver 變成另一個「到處直接摸 WebView」的隱性耦合點。
 */
interface AiJsBridge {
    /** 執行 JS，結果同步回呼（[onResult] 收到字串化結果，可能為 null）。 */
    fun eval(js: String, onResult: (String?) -> Unit = {})

    /** 把訊息回報給 Kotlin（對應 JS 側 window.AndroidBridge）。 */
    fun report(code: String)

    /** 延遲執行（等 SPA 渲染用）。 */
    fun postDelayed(delayMs: Long, block: () -> Unit)
}