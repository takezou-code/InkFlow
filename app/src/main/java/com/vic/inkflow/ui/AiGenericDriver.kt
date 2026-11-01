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
        AiGeminiDriver.pickJs(replyChain = pickChain, palette = palette)

    /**
     * 圈選容器：**整個 `main`**，不是 [replyChain] 裡那幾個猜出來的容器。
     *
     * 實測代價：Kimi 的 `replyChain` 最後命中 `div.message-list`（空殼，`txt=0`），
     * Qwen 的 `div[class*="message"]` 甚至命中送鈕容器——用它們當圈選範圍，
     * 使用者只選得到最後一則或什麼都選不到。`main` 之外還有 body 退路，
     * 選不到東西的情況不會發生；精準度由「文字葉節點 → 區塊祖先」補足。
     */
    override val pickChain: List<String> get() = listOf("main")

    override fun collectJs(): String =
        // cropFormulas＝「整塊像素裁圖」。它對應的是 importStrategy.blockCrop（ChatGPT），
        // **不是** capabilities.mathCrop。這兩件事以前在這裡被綁死，結果一開
        // mathCrop，收集腳本就把每一段都標成 k='b'（整塊裁圖），
        // 「只裁公式」那條路（k='m'）永遠收不到東西——公式就退成殘缺文字。
        // 現在語意對齊：blockCrop → 整塊裁圖；mathCrop → 文字切分＋公式裁圖。
        AiGeminiDriver.collectJs(
            replyChain = replyChain,
            cropFormulas = importStrategy.blockCrop
        )
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
                        var tag = (el.tagName || '').toLowerCase();
                        var isField = tag === 'textarea' || tag === 'input';
                        try { el.focus(); } catch(e){}

                        function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
                        function readText() {
                            try {
                                if (tag === 'textarea' || tag === 'input') return el.value || '';
                                return el.innerText || el.textContent || el.value || '';
                            } catch(e) { return ''; }
                        }
                        // 原生 setter＋input 事件：React/Vue 的受控 textarea 只吃這條，
                        // 直接 el.value = x 會被框架的下一次 render 蓋回去。
                        function setNativeValue() {
                            try {
                                var proto = Object.getPrototypeOf(el);
                                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                                if (desc && desc.set) desc.set.call(el, text); else el.value = text;
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                el.dispatchEvent(new Event('change', { bubbles: true }));
                                return true;
                            } catch(e) { return false; }
                        }
                        // contenteditable：先真的把光標放到內容尾端，再讓瀏覽器插入。
                        // Lexical/ProseMirror 會收到 beforeinput/input，才會更新自己的 state。
                        function execInsert() {
                            try {
                                el.focus();
                                var range = document.createRange();
                                range.selectNodeContents(el);
                                range.collapse(false);
                                var sel = window.getSelection();
                                sel.removeAllRanges();
                                sel.addRange(range);
                                return !!(document.execCommand && document.execCommand('insertText', false, text));
                            } catch(e) { return false; }
                        }
                        function pasteInsert() {
                            try {
                                var dt = new DataTransfer();
                                dt.setData('text/plain', text);
                                el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
                                return true;
                            } catch(e) { return false; }
                        }
                        function domInsert() {
                            try {
                                el.textContent = text;
                                el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
                                return true;
                            } catch(e) { return false; }
                        }

                        // 依元素型別排優先序，逐一嘗試。**不能只看 dispatch 的回傳值**：
                        // 站方的 handler 常常是非同步的，當下讀不到字不代表沒插進去。
                        var strategies = isField
                            ? [['VALUE_SET', setNativeValue], ['PASTE_TEXT', pasteInsert]]
                            : [['EXEC_TEXT', execInsert], ['PASTE_TEXT', pasteInsert], ['DOM_SET', domInsert]];
                        var si = 0;
                        var used = '';
                        function attempt() {
                            while (si < strategies.length) {
                                var name = strategies[si][0], fn = strategies[si][1];
                                si++;
                                if (fn()) { used = name; return true; }
                            }
                            return false;
                        }
                        attempt();

                        // 驗收：非同步輪詢，直到真的讀得到字才回報成功。
                        // 舊版在 dispatch 後「立刻」讀一次，Qwen 的 React、Kimi 的 Lexical
                        // 都還沒把字寫回 DOM → 明明填好了卻報 PROMPT_INSERT_FAILED（假失敗）。
                        var target = norm(text);
                        var head = target.slice(0, 24);
                        function looksFilled(got) {
                            var g = norm(got);
                            if (!g) return false;
                            if (g === target || g.indexOf(target) >= 0) return true;
                            if (head.length >= 6 && g.indexOf(head) >= 0) return true;
                            return false;
                        }
                        var tries = 0;
                        var waiter = setInterval(function() {
                            var got = readText();
                            if (looksFilled(got)) {
                                clearInterval(waiter);
                                report('PROMPT_FILLED:' + used + ':sel=' + hit.sel + ':t=' + tries);
                                if (!SEND) return;
                                var sendHit = findByCandidates([$sends]);
                                var canSend = false;
                                try {
                                    canSend = !!sendHit && !sendHit.el.disabled && sendHit.el.getAttribute('aria-disabled') !== 'true';
                                } catch(e){}
                                if (canSend) {
                                    sendHit.el.click();
                                    report('PROMPT_SENT:' + used + ':btn=' + sendHit.sel);
                                } else {
                                    var ev;
                                    try {
                                        ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                                    } catch(e) { ev = document.createEvent('Event'); ev.initEvent('keydown', true, true); }
                                    (document.activeElement || el).dispatchEvent(ev);
                                    report('PROMPT_SENT_ENTER:' + used);
                                }
                                return;
                            }
                            tries++;
                            // 每 ~450ms 換下一招（前一次可能只是還沒 render）。
                            if (tries % 3 === 0) attempt();
                            if (tries >= 20) {
                                clearInterval(waiter);
                                report('PROMPT_INSERT_FAILED:sel=' + hit.sel + ':by=' + used + ':read=' + norm(got).length);
                            }
                        }, 150);
                        return 'PROMPT_FILL_WAIT';
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
                        setTimeout(function() {
                            var r = fillAndSend(PROMPT);
                            // 成功路徑由輪詢回報 PROMPT_FILLED／PROMPT_SENT，這裡只報「一開始就失敗」。
                            if (r !== 'PROMPT_FILL_WAIT') report(r);
                        }, 800);
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
                // 元素的「身分證」：tag.class.class[data-testid] —— 只留排錯需要的部分。
                function sig(el) {
                    try {
                        var t = (el.tagName || '?').toLowerCase();
                        var cls = (el.getAttribute('class') || '').trim().split(/\s+/).slice(0, 2).join('.');
                        var mark = el.getAttribute('data-testid') || el.getAttribute('data-message-author-role') || '';
                        return (t + (cls ? '.' + cls : '') + (mark ? '[' + mark + ']' : '')).slice(0, 60);
                    } catch(e) { return '?'; }
                }
                var inputCands = [$inputs];
                var inputReport = [];
                for (var k = 0; k < inputCands.length; k++) inputReport.push(report(inputCands[k]));
                var sendCands = [$sends];
                var sendReport = [];
                for (var s = 0; s < sendCands.length; s++) sendReport.push(report(sendCands[s]));
                var replyCands = [$replies];
                var replyReport = [];
                // 回覆容器 census：候選鏈命中之後，這個容器到底長怎樣？
                // 只記「最後一個命中」的 tag.class、三層祖先、子節點 tag 分佈、文字長度、
                // 串流屬性——這四樣就足以把候選收斂成單一 selector，並找到「停止鈕」。
                // 有這件事，才不必靠猜 class 名再要使用者測一輪。
                var census = [];
                for (var r = 0; r < replyCands.length; r++) {
                    replyReport.push(report(replyCands[r]));
                    var hits = deepAll(document, replyCands[r], []);
                    if (!hits.length) continue;
                    var el = hits[hits.length - 1];
                    var tags = {};
                    var kids = el.querySelectorAll('*');
                    for (var q = 0; q < kids.length && q < 500; q++) {
                        var tn = (kids[q].tagName || '?').toLowerCase();
                        tags[tn] = (tags[tn] || 0) + 1;
                    }
                    var top = Object.keys(tags).sort(function(a, b) { return tags[b] - tags[a]; })
                        .slice(0, 6).map(function(k) { return k + ':' + tags[k]; }).join(',');
                    var anc = [];
                    var par = el.parentElement;
                    for (var d = 0; d < 3 && par; d++) { anc.push(sig(par)); par = par.parentElement; }
                    var runAttr = el.getAttribute('data-is-streaming') || el.getAttribute('aria-busy') || '';
                    census.push(
                        replyCands[r] + '=>' + sig(el) + '{n=' + hits.length +
                        ',anc=' + anc.join('>') +
                        ',kids=' + top +
                        ',txt=' + ((el.innerText || '').length) +
                        ',run=' + (runAttr || 'none') + '}'
                    );
                }
                var idleSel = $idle;
                return 'PROBE3 url=' + location.href
                    + ' | input=[' + inputReport.join(' | ') + ']'
                    + ' | send=[' + sendReport.join(' | ') + ']'
                    + ' | reply=[' + replyReport.join(' | ') + ']'
                    + ' | idle=' + (idleSel ? report(idleSel) : 'none')
                    + ' | katex=' + report('.katex')
                    + ' | mathjax=' + report('mjx-container')
                    + ' | fileInput=' + report('input[type="file"]')
                    // census 放在 title 之前：title 吃掉尾巴（可能含 `|`），放後面會污染它。
                    // census 內部的 `|` 先換成 `/`，否則前面的 ` | ` 分隔會被切壞。
                    + ' | replyCensus=[' + census.join(' ; ').replace(/\|/g, '/').slice(0, 900) + ']'
                    // 以下三欄用來判斷「是沒登入、被擋、還是版面改了」。
                    // 標題放最後（可能含 `|`，先在 JS 裡清掉），前面的欄位才不會被污染。
                    + ' | pwd=' + report('input[type="password"]')
                    + ' | captcha=' + report('[class*="captcha"], iframe[src*="captcha"], [class*="turnstile"], [id*="challenge"]')
                    + ' | title=' + ((document.title || '').slice(0, 60).replace(/[|\n\r]/g, ' '));
            })();
        """.trimIndent()
    }
}
