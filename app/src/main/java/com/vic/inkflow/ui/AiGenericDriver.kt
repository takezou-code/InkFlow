package com.vic.inkflow.ui

/**
 * 候選鏈驅動的共用骨架。
 *
 * 新加入的 AI 只要宣告「輸入框／送出鈕／回覆容器／停止鈕」四組候選，
 * 其餘注入腳本、貼圖 selector、圈選／收集全部共用這裡與 [AiGeminiDriver] 的實作。
 *
 * 為什麼是「候選鏈」而不是一個 selector：實測前不可能知道對方 DOM 長怎樣
 * （ChatGPT 當初就量到 `promptTextarea=0`）。候選鏈由具體到泛用，配合
 * [domProbeJs] 在實機回報命中數，等實測再收斂成單一 selector。
 */
internal abstract class CandidateChainDriver : AiDriver {

    protected abstract val inputCandidates: List<String>
    protected abstract val sendCandidates: List<String>

    override val importStrategy: AiImportStrategy = AiImportStrategy(
        blockCrop = false,
        promoteInlineMath = true,
        mathEngine = MathEngine.KATEX
    )
    override val glyph: AiGlyph = AiGlyph.CHAT

    /** 貼圖瞄準：候選鏈本身就是 CSS selector list，querySelector 取第一個命中。 */
    override val imagePasteInputSelector: String
        get() = inputCandidates.joinToString(", ")

    override fun promptSendJs(promptQuoted: String, send: Boolean): String =
        AiGenericScripts.promptSendJs(inputCandidates, sendCandidates, promptQuoted, send)

    override fun domProbeJs(): String =
        AiGenericScripts.domProbeJs(inputCandidates, sendCandidates, replyChain, replyIdleSelector)

    override fun pickJs(palette: PickPalette): String =
        AiGeminiDriver.pickJs(replyChain = replyChain, palette = palette)

    override fun collectJs(): String =
        // cropFormulas 跟著 capabilities 走：以前寫死 false，即使 driver 把
        // mathCrop 打開，收集腳本也不會回報公式座標，裁圖永遠沒東西可裁。
        // 「開一個 flag 就生效」是這裡唯一的正確形狀。
        AiGeminiDriver.collectJs(replyChain = replyChain, cropFormulas = capabilities.mathCrop)
}

/**
 * 候選鏈的通用 JS 工廠。
 *
 * selector 一律用**單引號**包成 JS 字串：候選裡通常含 `"`（屬性選擇器），
 * 用雙引號會讓整段腳本語法錯誤。這是 ChatGPT 舊碼踩過的坑，新 driver 不再重犯。
 */
internal object AiGenericScripts {

    fun promptSendJs(
        inputCandidates: List<String>,
        sendCandidates: List<String>,
        promptQuoted: String,
        send: Boolean
    ): String {
        val inputs = inputCandidates.joinToString(", ") { "'$it'" }
        val sends = sendCandidates.joinToString(", ") { "'$it'" }
        return """
            (function() {
                var PROMPT = $promptQuoted;
                var SEND = $send;
                function report(s) {
                    try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
                }
                // 穿 shadow root 的遞迴查詢：部分站台的輸入框不在一般 DOM 裡。
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

                        // 方式一：合成 paste（ProseMirror 類輸入框會走 paste handler）
                        var filledBy = null;
                        try {
                            var dt = new DataTransfer();
                            dt.setData('text/plain', text);
                            var pe = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });
                            el.dispatchEvent(pe);
                            filledBy = 'PASTE_TEXT';
                        } catch(e){}

                        // 方式二：execCommand（contenteditable 的通用退路）
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
                            report('GENERIC_INSERT_UNCONFIRMED:' + filledBy + ':sel=' + hit.sel);
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
                        report('GENERIC_INPUT_FOUND:sel=' + hit.sel + ':n=' + hit.n);
                        setTimeout(function() { report(fillAndSend(PROMPT)); }, 800);
                    } else {
                        attempts++;
                        if (attempts >= 20) { clearInterval(interval); report('PROMPT_NO_INPUT_FOUND'); }
                    }
                }, 500);
            })();
        """.trimIndent()
    }

    fun domProbeJs(
        inputCandidates: List<String>,
        sendCandidates: List<String>,
        replyCandidates: List<String>,
        idleSelector: String?
    ): String {
        val inputs = inputCandidates.joinToString(", ") { "'$it'" }
        val sends = sendCandidates.joinToString(", ") { "'$it'" }
        val replies = replyCandidates.joinToString(", ") { "'$it'" }
        val idle = idleSelector?.let { "'$it'" } ?: "null"
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
                function report(sel) { return sel + '=' + deepCount(document, sel, 0); }
                var inputCands = [$inputs];
                var inputReport = [];
                for (var k = 0; k < inputCands.length; k++) inputReport.push(report(inputCands[k]));
                var sendCands = [$sends];
                var sendReport = [];
                for (var s = 0; s < sendCands.length; s++) sendReport.push(report(sendCands[s]));
                var replyCands = [$replies];
                var replyReport = [];
                for (var r = 0; r < replyCands.length; r++) replyReport.push(report(replyCands[r]));
                var idleSel = $idle;
                return 'PROBE3 url=' + location.href
                    + ' | input=[' + inputReport.join(' | ') + ']'
                    + ' | send=[' + sendReport.join(' | ') + ']'
                    + ' | reply=[' + replyReport.join(' | ') + ']'
                    + ' | idle=' + (idleSel ? report(idleSel) : 'none')
                    + ' | katex=' + report('.katex')
                    + ' | mathjax=' + report('mjx-container')
                    + ' | fileInput=' + report('input[type="file"]');
            })();
        """.trimIndent()
    }
}
