package com.vic.inkflow.ui

/**
 * ChatGPT 的 L2 驅動。
 *
 * ## 為什麼是「候選鏈」而不是一個 selector
 *
 * 實測（Phase 0 探針）在 chatgpt.com 上量到：
 * ```
 * promptTextarea=0 contenteditable=0 assistant=0 articles=0  mains=1  fileInput=3
 * ```
 * 使用者手動打字、回覆都正常，但一般 `document.querySelectorAll` **一個都量不到**。
 * 已知有兩種可能：(1) 內容在 **shadow DOM**；(2) 內容在 **iframe**。
 *
 * Gemini 當初能量到，靠的就是穿 shadow root（見 AiGeminiDriver 裡的 deepInput／deepAll）。
 * 所以這裡一律**穿 shadow 後再找**，並且把每個候選的命中數回報給 log，
 * 讓實機結果決定正式 selector，而不是在這裡猜死。
 *
 * 實機驗證前，所有 selector 都是「待確認」，不是「已知正確」。
 */
internal object AiChatGptDriver {

    /** 輸入框候選：由具體到泛用。每個都會穿 shadow root 去找。 */
    private val INPUT_CANDIDATES = listOf(
        "#prompt-textarea",
        "div[contenteditable=\"true\"][id*=\"prompt\"]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    /** 送出鈕候選：ChatGPT 用 data-testid，不是 Gemini 的 aria-label。 */
    private val SEND_CANDIDATES = listOf(
        "button[data-testid=\"send-button\"]",
        "button[data-testid*=\"send\"]",
        "button[aria-label*=\"Send message\"]",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"傳送\"]"
    )

    /** 回覆容器候選：ChatGPT 是 assistant 角色，包在 <article> 裡，不是 Gemini 的 model。 */
    private val REPLY_CANDIDATES = listOf(
        "article[data-message-author-role=\"assistant\"]",
        "[data-message-author-role=\"assistant\"]",
        "article[data-message-author-role]",
        "[data-message-author-role]"
    )

    /** 串流中判斷：ChatGPT 會顯示停止鈕，Gemini 沒有這個語意。 */
    private val STOP_SELECTOR = "[data-testid=\"stop-button\"]"

    /** 貼圖時瞄準的輸入框（與 [buildImagePasteJs] 的 selector 同一套）。 */
    const val IMAGE_PASTE_INPUT_SELECTOR = "#prompt-textarea, div[contenteditable=\"true\"]"

    /** 供探針與 log 使用：候選清單本體（保持 JS 與 Kotlin 單一真相）。 */
    fun candidatesForLog(): String = buildString {
        append("input=").append(INPUT_CANDIDATES.joinToString("|"))
        append(" send=").append(SEND_CANDIDATES.joinToString("|"))
        append(" reply=").append(REPLY_CANDIDATES.joinToString("|"))
    }

    /**
     * 填入提示詞並視需要送出。
     *
     * 文字注入**同時試兩種方式**，先成功者贏，並回報是哪一種生效：
     * - `PASTE_TEXT`：合成 paste ClipboardEvent。ProseMirror（ChatGPT 的輸入框）有 paste handler，
     *   對 `execCommand` 常見的反應是「看起來沒事但狀態沒更新」。
     * - `EXEC_TEXT`：`document.execCommand('insertText')`，Gemini 那條路。
     *
     * 兩種都失敗才回報 PROMPT_INSERT_FAILED——寧可明確失敗，也不要假裝填進去了。
     */
    fun promptSendJs(promptQuoted: String, send: Boolean): String {
        val inputs = INPUT_CANDIDATES.joinToString(", ") { "\"$it\"" }
        val sends = SEND_CANDIDATES.joinToString(", ") { "\"$it\"" }
        return """
            (function() {
                var PROMPT = $promptQuoted;
                var SEND = $send;
                function report(s) {
                    try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
                }
                // 穿 shadow root 的遞迴查詢：ChatGPT 的輸入框不在一般 DOM 裡。
                function deepAll(root, sel, out) {
                    try {
                        var found = root.querySelectorAll(sel);
                        for (var i = 0; i < found.length; i++) out.push(found[i]);
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            try { if (all[j].shadowRoot) deepAll(all[j].shadowRoot, sel, out); } catch(e){}
                        }
                    } catch(e){}
                    return out;
                }
                function findByCandidates(cands) {
                    for (var i = 0; i < cands.length; i++) {
                        var hits = deepAll(document, cands[i], []);
                        if (hits.length > 0) return { el: hits[0], sel: cands[i], n: hits.length };
                    }
                    return null;
                }
                function fillAndSend(text) {
                    try {
                        var hit = findByCandidates([$inputs]);
                        if (!hit) return 'PROMPT_NO_INPUT';
                        var el = hit.el;
                        try { el.focus(); } catch(e){}

                        // 方式一：合成 paste（ProseMirror 會走 paste handler）
                        var filledBy = null;
                        try {
                            var dt = new DataTransfer();
                            dt.setData('text/plain', text);
                            var pe = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });
                            el.dispatchEvent(pe);
                            filledBy = 'PASTE_TEXT';
                        } catch(e){}

                        // 方式二：execCommand（Gemini 那條路；ProseMirror 常無聲失敗）
                        if (filledBy === null) {
                            try {
                                var range = document.createRange();
                                range.selectNodeContents(el);
                                range.collapse(false);
                                var sel = window.getSelection();
                                sel.removeAllRanges();
                                sel.addRange(range);
                                if (document.execCommand('insertText', false, text)) filledBy = 'EXEC_TEXT';
                            } catch(e){}
                        }

                        // 驗收：有沒有真的把字放進去（貼上事件可能被忽略）
                        var got = '';
                        try { got = el.innerText || el.textContent || el.value || ''; } catch(e){}
                        if (filledBy !== null && text && got.indexOf(text) < 0 && got.trim().length === 0) {
                            report('CHATGPT_INSERT_UNCONFIRMED:' + filledBy + ':sel=' + hit.sel);
                            filledBy = null;
                        }
                        if (filledBy === null) return 'PROMPT_INSERT_FAILED';
                        if (!SEND) { report('PROMPT_FILLED:' + filledBy + ':sel=' + hit.sel); return 'PROMPT_FILLED'; }

                        var tries = 0;
                        var waiter = setInterval(function() {
                            var got2 = '';
                            try { got2 = el.innerText || el.textContent || el.value || ''; } catch(e){}
                            var sendHit = findByCandidates([$sends]);
                            var btnOn = false;
                            try { btnOn = !!sendHit && !sendHit.el.disabled && sendHit.el.getAttribute('aria-disabled') !== 'true'; } catch(e){}
                            if ((text && got2.indexOf(text) >= 0) || btnOn) {
                                clearInterval(waiter);
                                if (sendHit) {
                                    sendHit.el.click();
                                    report('PROMPT_SENT:' + filledBy + ':btn=' + sendHit.sel);
                                } else {
                                    var ev;
                                    try {
                                        ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                                    } catch(e) { ev = document.createEvent('Event'); ev.initEvent('keydown', true, true); }
                                    (document.activeElement || el).dispatchEvent(ev);
                                    report('PROMPT_SENT_ENTER:' + filledBy);
                                }
                            } else {
                                tries++;
                                if (tries >= 12) { clearInterval(waiter); report('PROMPT_SEND_NOT_READY'); }
                            }
                        }, 250);
                        return 'PROMPT_FILL_WAIT_SEND';
                    } catch(err) {
                        return 'PROMPT_EXCEPTION';
                    }
                }
                var attempts = 0;
                var interval = setInterval(function() {
                    var hit = findByCandidates([$inputs]);
                    if (hit) {
                        clearInterval(interval);
                        report('CHATGPT_INPUT_FOUND:sel=' + hit.sel + ':n=' + hit.n);
                        setTimeout(function() { report(fillAndSend(PROMPT)); }, 800);
                    } else {
                        attempts++;
                        if (attempts >= 20) { clearInterval(interval); report('PROMPT_NO_INPUT_FOUND'); }
                    }
                }, 500);
            })();
        """.trimIndent()
    }

    /**
     * DOM 探針：穿 shadow ＋檢查 iframe 可達性 ＋ 印出實際結構。
     *
     * 這是臨時診斷，S3 定完 selector 就整段刪掉（見 env.md：定案即刪，不留過夜）。
     * 存在的唯一理由：**不要再靠猜**。
     */
    fun domProbeJs(): String {
        val inputs = INPUT_CANDIDATES.joinToString(", ") { "\"$it\"" }
        val sends = SEND_CANDIDATES.joinToString(", ") { "\"$it\"" }
        val replies = REPLY_CANDIDATES.joinToString(", ") { "\"$it\"" }
        return """
            (function() {
                function deepCount(root, sel, out) {
                    try {
                        var f = root.querySelectorAll(sel);
                        for (var i = 0; i < f.length; i++) out++;
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            try { if (all[j].shadowRoot) deepCount(all[j].shadowRoot, sel, out); } catch(e){}
                        }
                    } catch(e){}
                    return out;
                }
                function deepFirst(root, sel) {
                    try {
                        var f = root.querySelectorAll(sel);
                        if (f.length > 0) return f[0];
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            try { if (all[j].shadowRoot) { var r = deepFirst(all[j].shadowRoot, sel); if (r) return r; } } catch(e){}
                        }
                    } catch(e){}
                    return null;
                }
                function report(sel) { return sel + '=' + deepCount(document, sel, 0); }

                // iframe：ChatGPT 若把對話放在 iframe，這裡才量得到（且決定注入要不要改走 contentDocument）
                var iframes = document.querySelectorAll('iframe');
                var iframeReach = [];
                for (var i = 0; i < iframes.length; i++) {
                    var reach = 'x';
                    try { var d = iframes[i].contentDocument; reach = d ? ('ok:' + d.querySelectorAll('*').length) : 'null'; } catch(e){ reach = 'blocked'; }
                    iframeReach.push(iframes[i].src.slice(0, 40) + '=' + reach);
                }

                var inputCands = [$inputs];
                var inputReport = [];
                var inputHit = null;
                for (var k = 0; k < inputCands.length; k++) {
                    var c = deepCount(document, inputCands[k], 0);
                    inputReport.push(inputCands[k] + '=' + c);
                    if (c > 0 && !inputHit) inputHit = inputCands[k];
                }

                var el = inputHit ? deepFirst(document, inputHit) : null;
                var elHtml = 'none';
                var elTag = 'none';
                if (el) {
                    elTag = (el.tagName || '?').toLowerCase() + (el.id ? '#' + el.id : '');
                    try { elHtml = (el.outerHTML || '').replace(/\s+/g, ' ').slice(0, 200); } catch(e){}
                }

                return 'PROBE2 url=' + location.href
                    + ' | input=[' + inputReport.join(' | ') + ']'
                    + ' | hit=' + (inputHit || 'NONE')
                    + ' | tag=' + elTag
                    + ' | send=[' + report([$sends][0]) + ']'
                    + ' | stop=' + report("$STOP_SELECTOR")
                    + ' | reply=[' + report([$replies][0]) + ']'
                    + ' | katex=' + report('.katex')
                    + ' | fileInput=' + report('input[type="file"]')
                    + ' | iframes=' + iframes.length + '[' + iframeReach.join(',') + ']'
                    + ' | el=' + elHtml;
            })();
        """.trimIndent()
    }
}