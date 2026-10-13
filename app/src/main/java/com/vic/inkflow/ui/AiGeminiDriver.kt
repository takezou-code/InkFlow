package com.vic.inkflow.ui

/**
 * Gemini 的 L2 驅動（選項與腳本）。
 *
 * **這裡是 Gemini 專屬的東西集中處**：rich-textarea、Send 鈕的 aria-label、
 * `data-message-author-role="model"`、`.math-block[data-math]`。
 * 加入新 provider 時這個檔案不動；改 provider 只會新增同型別的檔案。
 *
 * 內容自 AiWebPanel.kt 逐字搬來，行為零變更；搬移後由測試比對字串確認沒搬錯。
 */
data class PickPalette(
val accent: String = "#7C6CF0",
val tint: String = "rgba(124,108,240,0.16)",
val hover: String = "rgba(124,108,240,0.10)",
val outline: String = "rgba(124,108,240,0.40)",
val barBg: String = "rgba(24,24,30,0.86)",
val barFg: String = "rgba(255,255,255,0.92)",
val barBtn: String = "rgba(255,255,255,0.14)"
)


internal object AiGeminiDriver {

    /**
     * 回覆容器候選鏈（唯一真正需要隨 provider 變的東西）。
     *
     * 其餘看起來 Gemini 專屬的 selector（`.math-block[data-math]`、`span.math-inline`…）
     * 在 ChatGPT 上會自然匹配到 0 個，於是自動落到泛用的 KaTeX `annotation[encoding*=tex]`
     * 路徑——**因此不必參數化，也不該參數化**，多一份設定就多一處會走樣的機會。
     */
    val REPLY_CHAIN = listOf(
        "div[data-message-author-role=\"model\"]",
        "message-content",
        ".response-container",
        "[class*=\"model-response\"]",
        "[class*=\"response-content\"]"
    )

    /**
 * 圈選高亮的配色。**由 App 注入，不在腳本裡寫死品牌色**。
 *
 * 舊版把 Google 藍 `#1a73e8` 寫死在 JS 裡——那既是 Gemini 的品牌色（換到 ChatGPT
 * 就完全不搭），也讓注入樣式跟 App 自己的玻璃配色脫鉤。依 ui.md「色票唯一來源」的
 * 精神，這裡由呼叫端從 MaterialTheme 取色後傳進來。
 */
/** 把候選鏈鋪成 JS 陣列字面值。Gemini 預設產出與舊版逐字相同。 */
    private fun chainJs(chain: List<String>): String = chain.joinToString(", ") { "'$it'" }


/**
 * 快捷指令送出腳本：獨立輪詢 Gemini 輸入框（穿一層 shadow DOM），填入 prompt 並自動送出。
 * 與貼圖腳本並行執行，內部延遲 2.5s 等圖片先附著，避免圖文分家。
 * 執行結果透過 AndroidBridge.onPasteResult 回報（PROMPT_SENT / PROMPT_SENT_ENTER / …），可用 logcat 觀察。
 */
fun promptSendJs(promptQuoted: String, send: Boolean = true): String {
    return """
        (function() {
            var PROMPT = $promptQuoted;
            var SEND = $send;
            function report(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
            function deepInput(root) {
                try {
                    var el = root.querySelector('div[contenteditable="true"], div[role="textbox"]');
                    if (el) return el;
                    var all = root.querySelectorAll('*');
                    for (var i = 0; i < all.length; i++) {
                        try {
                            if (all[i].shadowRoot) {
                                var f = deepInput(all[i].shadowRoot);
                                if (f) return f;
                            }
                        } catch(e){}
                    }
                } catch(e){}
                return null;
            }
            function findChatInput() {
                return deepInput(document) || document.querySelector('rich-textarea');
            }
            function fillAndSend(text) {
                try {
                    var el = findChatInput();
                    if (!el) return 'PROMPT_NO_INPUT';
                    try { el.focus(); } catch(e){}
                    try {
                        var range = document.createRange();
                        range.selectNodeContents(el);
                        range.collapse(false);
                        var sel = window.getSelection();
                        sel.removeAllRanges();
                        sel.addRange(range);
                    } catch(e){}
                    var ok = false;
                    try { ok = document.execCommand('insertText', false, text); } catch(e){}
                    if (!ok) {
                        try {
                            el.dispatchEvent(new InputEvent('beforeinput', { data: text, inputType: 'insertText', bubbles: true, cancelable: true }));
                        } catch(e){}
                        try {
                            el.textContent = text;
                            el.dispatchEvent(new InputEvent('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            ok = true;
                        } catch(e){}
                    }
                    if (!ok) return 'PROMPT_INSERT_FAILED';
                    if (!SEND) return 'PROMPT_FILLED';
                    // rich-textarea 有內部狀態：填完立刻送會送空包。輪詢等字進去（或送出鈕亮起）再送。
                    function findSendBtn() {
                        return document.querySelector('button[aria-label*="Send"]')
                            || document.querySelector('button[aria-label*="傳送"]')
                            || document.querySelector('button[aria-label*="发送"]');
                    }
                    function doSend() {
                        var sendBtn = findSendBtn();
                        if (sendBtn) {
                            sendBtn.click();
                            return 'PROMPT_SENT';
                        }
                        var ev;
                        try {
                            ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                        } catch(e) {
                            ev = document.createEvent('Event');
                            ev.initEvent('keydown', true, true);
                        }
                        (document.activeElement || el).dispatchEvent(ev);
                        return 'PROMPT_SENT_ENTER';
                    }
                    var tries = 0;
                    var waiter = setInterval(function() {
                        var got = '';
                        try { got = el.innerText || el.textContent || ''; } catch(e){}
                        var btn = findSendBtn();
                        var btnOn = false;
                        try { btnOn = !!btn && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true'; } catch(e){}
                        if ((text && got.indexOf(text) >= 0) || btnOn) {
                            clearInterval(waiter);
                            report(doSend());
                        } else {
                            tries++;
                            if (tries >= 12) {
                                clearInterval(waiter);
                                report('PROMPT_SEND_NOT_READY');
                            }
                        }
                    }, 250);
                    return 'PROMPT_FILL_WAIT_SEND';
                } catch(err) {
                    return 'PROMPT_EXCEPTION';
                }
            }
            var attempts = 0;
            var interval = setInterval(function() {
                var el = findChatInput();
                if (el) {
                    clearInterval(interval);
                    setTimeout(function() { report(fillAndSend(PROMPT)); }, 2500);
                } else {
                    attempts++;
                    if (attempts >= 20) {
                        clearInterval(interval);
                        report('PROMPT_NO_INPUT_FOUND');
                    }
                }
            }, 500);
        })();
    """.trimIndent()
}

/**
 * M2b-2 圈選模式 v2：事件委派（document 級 capture listener，串流中新增/重渲染的段落照樣可點），
 * 打勾樣式走 <style> + ::before（不插入 DOM，innerText 永遠乾淨）。
 * 回報 PICK_MODE_ON:N（N=當下可點段落數）。
 */


/**
 * M2b-2 圈選模式 v2：事件委派（document 級 capture listener，串流中新增/重渲染的段落照樣可點），
 * 打勾樣式走 <style> + ::before（不插入 DOM，innerText 永遠乾淨）。
 * 回報 PICK_MODE_ON:N（N=當下可點段落數）。
 */
fun pickJs(
        replyChain: List<String> = REPLY_CHAIN,
        palette: PickPalette = PickPalette()
    ): String {
    val chainsJs = chainJs(replyChain)
    val p = palette
    return """
    (function() {
            function note(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
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
            function inChrome(el) {
                try { return !!el.closest('header, nav, button, [role="navigation"], [role="button"]'); }
                catch(e){ return false; }
            }
            function findContainer() {
                var chains = [$chainsJs];
                for (var c = 0; c < chains.length; c++) {
                    var hits = deepAll(document, chains[c], []);
                    if (hits.length > 0) return { el: hits[hits.length - 1], fb: false };
                }
                try {
                    var m = document.querySelector('main') || document.body;
                    if (m) return { el: m, fb: true };
                } catch(e){}
                return null;
            }
            try {
                window.__inkpick = false;
                if (window.__inkpickHandler) document.removeEventListener('click', window.__inkpickHandler, true);
                window.__inkpickHandler = null;
                if (window.__inkpickTeardown) { try { window.__inkpickTeardown(); } catch(e){} window.__inkpickTeardown = null; }
                window.__inkpickAnchor = null;
                var oldStyle = document.getElementById('inkpick-style');
                if (oldStyle) oldStyle.remove();
                document.querySelectorAll('[data-inkpick]').forEach(function(el) { el.removeAttribute('data-inkpick'); });
            } catch(e){}
            var found = findContainer();
            if (!found) { note('PICK_NO_CONTAINER'); return; }
            var container = found.el;
            var fallbackMain = found.fb;
            window.__inkpick = true;
            try {
                var st = document.createElement('style');
                st.id = 'inkpick-style';
                // 未選中＝乾淨（舊版每段都畫虛線框，整篇回覆看起來像表單）。
                // 只在 hover／點到時才浮出提示，畫面預設留白。
                // 已選中＝左側實心條＋淡底，用 inset box-shadow 而非 ::before：
                // ::before 的 content 會把整段文字往右推一行，讀起來斷掉。
                st.textContent = ''
    + '[data-inkpick]{border-radius:6px;cursor:pointer;transition:background-color .12s ease,box-shadow .12s ease;}'
    + '[data-inkpick="0"]:hover{background:${p.hover};box-shadow:inset 0 0 0 1px ${p.outline};}'
    + '[data-inkpick="1"]{background:${p.tint};box-shadow:inset 3px 0 0 ${p.accent};}'
    + '#inkpick-bar{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);z-index:2147483000;'
    + 'display:flex;align-items:center;gap:10px;padding:8px 10px 8px 14px;border-radius:999px;'
    + 'background:${p.barBg};color:${p.barFg};box-shadow:0 6px 24px rgba(0,0,0,.34);'
    + 'font:500 13px/1.2 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;'
    + 'backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);white-space:nowrap;user-select:none;}'
    + '#inkpick-bar b{font-weight:700;}'
    + '#inkpick-bar button{all:unset;cursor:pointer;padding:6px 12px;border-radius:999px;'
    + 'background:${p.barBtn};color:${p.barFg};font:inherit;font-weight:600;}'
    + '#inkpick-bar button:active{opacity:.7;}';
                document.documentElement.appendChild(st);
            } catch(e){}
            function mark(el) {
                try {
                    if (!el || el.hasAttribute('data-inkpick')) return false;
                    // 祖先已被標就不重複描框（document 順序父先子後；收集去重本來就有）
                    if (el.parentElement && el.parentElement.closest && el.parentElement.closest('[data-inkpick]')) return false;
                    if (!el.innerText || el.innerText.trim().length === 0) return false;
                    if (fallbackMain && inChrome(el)) return false;
                    el.setAttribute('data-inkpick', '0');
                    return true;
                } catch(e){ return false; }
            }
            var parts = container.querySelectorAll('p, li, h1, h2, h3, h4, pre, blockquote, div.math-block, span.math-inline, div.katex-display, span.katex');
            var n = 0;
            if (parts.length === 0) {
                if (mark(container)) n = 1;
            } else {
                parts.forEach(function(el) { if (mark(el)) n++; });
            }
            window.__inkpickAnchor = null;
            function marked() {
                return Array.prototype.slice.call(document.querySelectorAll('[data-inkpick]'));
            }
            function selectedCount() {
                return document.querySelectorAll('[data-inkpick="1"]').length;
            }
            function refreshBar() {
                var bar = document.getElementById('inkpick-bar');
                if (!bar) return;
                var c = selectedCount();
                var label = bar.querySelector('span');
                if (label) label.innerHTML = '已選 <b>' + c + '</b> 段';
                var clearBtn = bar.querySelector('[data-act="none"]');
                var doneBtn = bar.querySelector('[data-act="done"]');
                if (clearBtn) clearBtn.style.opacity = c === 0 ? '.45' : '1';
                if (doneBtn) doneBtn.style.opacity = c === 0 ? '.45' : '1';
            }
            // 浮動工具列：計數＋全選／清除／完成。
            // 放在 fixed 層、不帶 data-inkpick，所以永遠不會被 collectJs 收進去，
            // 也不影響任何段落的 innerText。
            function buildBar() {
                try {
                    var old = document.getElementById('inkpick-bar');
                    if (old) old.remove();
                    var bar = document.createElement('div');
                    bar.id = 'inkpick-bar';
                    bar.innerHTML = '<span>已選 <b>0</b> 段</span>'
    + '<button data-act="all">全選</button>'
    + '<button data-act="none">清除</button>'
    + '<button data-act="done">完成</button>';
                    document.body.appendChild(bar);
                    bar.addEventListener('click', function(ev) {
                        var b = ev.target && ev.target.closest ? ev.target.closest('button') : null;
                        if (!b) return;
                        ev.preventDefault();
                        ev.stopPropagation();
                        var act = b.getAttribute('data-act');
                        if (act === 'all') {
                            marked().forEach(function(el) { el.setAttribute('data-inkpick', '1'); });
                        } else if (act === 'none') {
                            marked().forEach(function(el) { el.setAttribute('data-inkpick', '0'); });
                            window.__inkpickAnchor = null;
                        } else if (act === 'done') {
                            try { if (window.AndroidBridge && AndroidBridge.onPickAction) AndroidBridge.onPickAction('done'); }
                            catch(e){}
                            return;
                        }
                        refreshBar();
                    }, true);
                    refreshBar();
                } catch(e){}
            }
            function teardownBar() {
                try {
                    window.__inkpickAnchor = null;
                    var bar = document.getElementById('inkpick-bar');
                    if (bar) bar.remove();
                } catch(e){}
            }
            window.__inkpickTeardown = teardownBar;
            buildBar();
            window.__inkpickHandler = function(ev) {
                try {
                    if (!window.__inkpick) return;
                    var t = (ev.target && ev.target.closest) ? ev.target.closest('p, li, h1, h2, h3, h4, pre, blockquote, div.math-block, span.math-inline, div.katex-display, span.katex') : null;
                    if (!t) return;
                    // 自癒：串流中重渲染導致容器換新時，不卡容器歸屬、有字就地標記（chrome 區除外）
                    if (inChrome(t)) return;
                    if (!t.hasAttribute('data-inkpick')) {
                        if (!t.innerText || t.innerText.trim().length === 0) return;
                        t.setAttribute('data-inkpick', '0');
                    }
                    ev.stopPropagation();
                    ev.preventDefault();
                    // Shift 連續選取：從上一次點的地方一路選到這裡，
                    // 長回覆不用一則一則點（這是「更好選擇」的關鍵）。
                    if (ev.shiftKey && window.__inkpickAnchor) {
                        var list = marked();
                        var a = list.indexOf(window.__inkpickAnchor);
                        var b = list.indexOf(t);
                        if (a >= 0 && b >= 0) {
                            var lo = Math.min(a, b), hi = Math.max(a, b);
                            for (var i2 = lo; i2 <= hi; i2++) list[i2].setAttribute('data-inkpick', '1');
                            refreshBar();
                            return;
                        }
                    }
                    var on = t.getAttribute('data-inkpick') === '1';
                    t.setAttribute('data-inkpick', on ? '0' : '1');
                    window.__inkpickAnchor = t;
                    refreshBar();
                } catch(e){}
            };
            document.addEventListener('click', window.__inkpickHandler, true);
            try {
                var mc = 0, mcMathml = 0, mcMjx = 0;
                try {
                    mc = container.querySelectorAll('.math-block, .math-inline, span.katex, [data-math]').length;
                    mcMathml = container.querySelectorAll('math, .katex-mathml').length;
                    mcMjx = container.querySelectorAll('mjx-container, .MathJax, .mjx-chtml').length;
                } catch(e){}
                note('MATHCOUNT total=' + mc + ' mathml=' + mcMathml + ' mjx=' + mcMjx);
            } catch(e){}
            note('PICK_MODE_ON:' + n);
        })();
    """.trimIndent()
}

/**
 * M2b-2 收集腳本 v2：照文件順序收打勾段落（排除被包在已勾段落內的子段，避免重複）；
 * 一個都沒勾則回空陣列、不插入。清掉委派 listener、樣式與全部標記。
 * 結果經 AndroidBridge.onTextGrabbed 回傳（上限 20000 字）。
 */
fun collectJs(replyChain: List<String> = REPLY_CHAIN): String {
    return """
    (function() {
            function note(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
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
            // TeX 還原：Gemini 用 KaTeX 渲染（copy-tex 沒開，innerText 沒源碼），
            // 真源在 .math-block/.math-inline 的 data-math；裸 .katex 看 annotation。
            function texify(root) {
                var nb = 0, ni = 0;
                try {
                    root.querySelectorAll('.math-block[data-math]').forEach(function(el) {
                        var t = el.getAttribute('data-math') || '';
                        if (t.trim().length > 0) {
                            el.replaceWith(document.createTextNode('$$' + t.trim() + '$$'));
                            nb++;
                        }
                    });
                    root.querySelectorAll('.math-inline[data-math]').forEach(function(el) {
                        var t = el.getAttribute('data-math') || '';
                        if (t.trim().length > 0) {
                            el.replaceWith(document.createTextNode('$' + t.trim() + '$'));
                            ni++;
                        }
                    });
                    root.querySelectorAll('span.katex').forEach(function(el) {
                        try {
                            var an = el.querySelector('.katex-mathml annotation');
                            if (an && an.textContent && an.textContent.trim().length > 0) {
                                var disp = false;
                                try { disp = !!el.closest('.katex-display'); } catch(e){}
                                var s = an.textContent.trim();
                                el.replaceWith(document.createTextNode(disp ? ('$$' + s + '$$') : ('$' + s + '$')));
                                if (disp) nb++; else ni++;
                            }
                        } catch(e){}
                    });
                    // 通用：任何 annotation[encoding*=tex]（MathML 直出、換殼渲染都適用；
                    // 在 KaTeX 規則之後跑，已被換掉的不會重複）。
                    root.querySelectorAll('annotation').forEach(function(el) {
                        try {
                            var enc = (el.getAttribute('encoding') || '').toLowerCase();
                            if (enc.indexOf('tex') < 0) return;
                            var host = null;
                            try { host = el.closest('math, span.katex, .math-block, .math-inline'); } catch(e2){}
                            var target = host || el.parentElement;
                            if (!target || target === root) return;
                            var s = el.textContent || '';
                            if (s.trim().length === 0) return;
                            var disp = false;
                            try { disp = !!target.closest('.katex-display'); } catch(e2){}
                            target.replaceWith(document.createTextNode(disp ? ('$$' + s.trim() + '$$') : ('$' + s.trim() + '$')));
                            if (disp) nb++; else ni++;
                        } catch(e){}
                    });
                } catch(e){}
                return { b: nb, i: ni };
            }
            try {
                window.__inkpick = false;
                if (window.__inkpickHandler) document.removeEventListener('click', window.__inkpickHandler, true);
                window.__inkpickHandler = null;
                // 收集完要把浮動列一起收掉，否則匯入完成後網頁上還留著工具列。
                if (window.__inkpickTeardown) { try { window.__inkpickTeardown(); } catch(e){} window.__inkpickTeardown = null; }
                window.__inkpickAnchor = null;
                var st = document.getElementById('inkpick-style');
                if (st) st.remove();
            } catch(e){}
            var all = Array.prototype.slice.call(document.querySelectorAll('[data-inkpick="1"]'));
            var roots = all.filter(function(el) {
                var p = el.parentElement;
                while (p) {
                    if (p.getAttribute && p.getAttribute('data-inkpick') === '1') return false;
                    p = p.parentElement;
                }
                return true;
            });
            var picks = [];
            var mathB = 0, mathI = 0;
            // T-X1 取證：勾選節點標籤普查＋數學痕跡計數（data-math / annotation 有無）
            var census = {};
            var censusMath = 0, censusDataMath = 0, censusAnno = 0;
            var rects = [];
            roots.forEach(function(el) {
                try {
                    var tn = (el.tagName || '?').toLowerCase();
                    census[tn] = (census[tn] || 0) + 1;
                    var hasMath = false;
                    try { hasMath = el.querySelector('.math-block, .math-inline, span.katex, [data-math], math, mjx-container, .MathJax') != null; } catch(e){}
                    censusMath += el.querySelectorAll('.math-block, .math-inline, span.katex, math, mjx-container, .MathJax').length;
                    censusDataMath += el.querySelectorAll('[data-math]').length;
                    censusAnno += el.querySelectorAll('.katex-mathml annotation, annotation').length;
                    var r = null;
                    try {
                        var rr = el.getBoundingClientRect();
                        r = [Math.round(rr.x), Math.round(rr.y), Math.round(rr.width), Math.round(rr.height)];
                        rects.push(r);
                    } catch(e){}
                    var clone = el.cloneNode(true);
                    var cc = texify(clone);
                    mathB += cc.b; mathI += cc.i;
                    var t = clone.innerText || '';
                    if (hasMath) {
                        picks.push({k:'m', r:r, t:t.trim().slice(0, 20000)});
                    } else if (t.trim().length > 0) {
                        picks.push({k:'t', r:r, t:t.trim().slice(0, 20000)});
                    }
                } catch(e){}
            });
            // 未勾選 = 不插入：直接回空陣列，Kotlin 側 parsePicks 為空會 Toast「沒有可插入的內容」並返回。
            // （舊行為：退回最後回覆全文，已按需求拿掉。）
            var src = picks.length === 0 ? 'empty-nopick' : 'picked x' + picks.length;
            try {
                document.querySelectorAll('[data-inkpick]').forEach(function(el) { el.removeAttribute('data-inkpick'); });
            } catch(e){}
            // F1：強制 reflow，逼殘影樣式先退，Kotlin 側 PixelCopy 前還會再等 600ms
            try { void document.body.offsetHeight; } catch(e){}
            note('COLLECT src=' + src + ' mathB=' + mathB + ' mathI=' + mathI + ' census=' + JSON.stringify(census) + ' inMath=' + censusMath + ' dataMath=' + censusDataMath + ' anno=' + censusAnno + ' rects=' + JSON.stringify(rects) + ' dpr=' + window.devicePixelRatio);
            try { if (window.AndroidBridge && window.AndroidBridge.onPickedJson) window.AndroidBridge.onPickedJson(JSON.stringify(picks).slice(0, 200000)); } catch(e){}
        })();
    """.trimIndent()
}

/**
 * Phase 0 ChatGPT DOM 探針（臨時診斷，取證完即刪）。
 * 不猜 selector：把候選輸入框／送出鈕／回覆容器／數學痕跡的命中數一次回傳，
 * 外加當前 URL、標題與 data-message-author-role 分布，實機結果決定 Phase 2 移植。
 */
}
