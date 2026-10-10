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
internal object AiGeminiDriver {


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
fun pickJs(): String {
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
                var chains = ['div[data-message-author-role="model"]', 'message-content', '.response-container', '[class*="model-response"]', '[class*="response-content"]'];
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
                st.textContent = '[data-inkpick="0"]{outline:2px dashed #1a73e8 !important;outline-offset:2px !important;cursor:pointer !important;}'
                    + '[data-inkpick="1"]{outline:2px solid #1a73e8 !important;outline-offset:2px !important;background:rgba(26,115,232,0.15) !important;cursor:pointer !important;}'
                    + '[data-inkpick="1"]::before{content:"✓ ";color:#1a73e8;font-weight:bold;}';
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
                    var on = t.getAttribute('data-inkpick') === '1';
                    t.setAttribute('data-inkpick', on ? '0' : '1');
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
 * 一個都沒勾則退回最後回覆全文。清掉委派 listener、樣式與全部標記。
 * 結果經 AndroidBridge.onTextGrabbed 回傳（上限 20000 字）。
 */


/**
 * M2b-2 收集腳本 v2：照文件順序收打勾段落（排除被包在已勾段落內的子段，避免重複）；
 * 一個都沒勾則退回最後回覆全文。清掉委派 listener、樣式與全部標記。
 * 結果經 AndroidBridge.onTextGrabbed 回傳（上限 20000 字）。
 */
fun collectJs(): String {
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
            function lastReply() {
                var chains = ['div[data-message-author-role="model"]', 'message-content', '.response-container', '[class*="model-response"]', '[class*="response-content"]'];
                for (var c = 0; c < chains.length; c++) {
                    var hits = deepAll(document, chains[c], []);
                    if (hits.length > 0) {
                        var node = hits[hits.length - 1];
                        var t = node.innerText || '';
                        if (t.trim().length > 0) {
                            try {
                                var cl = node.cloneNode(true);
                                var cc = texify(cl);
                                var tt = cl.innerText || '';
                                if (tt.trim().length > 0) return { text: tt, b: cc.b, i: cc.i };
                            } catch(e){}
                            return { text: t, b: 0, i: 0 };
                        }
                    }
                }
                try {
                    var blocks = document.querySelectorAll('main p, main li, article p, article li');
                    var acc = [];
                    var tb = 0, ti = 0;
                    for (var k = 0; k < blocks.length; k++) {
                        try {
                            var bcl = blocks[k].cloneNode(true);
                            var bc = texify(bcl);
                            tb += bc.b; ti += bc.i;
                            var bt = bcl.innerText || '';
                            if (bt.trim().length > 0) acc.push(bt.trim());
                        } catch(e){}
                    }
                    if (acc.length > 0) return { text: acc.join('\n\n'), b: tb, i: ti };
                } catch(e){}
                return { text: '', b: 0, i: 0 };
            }
            try {
                window.__inkpick = false;
                if (window.__inkpickHandler) document.removeEventListener('click', window.__inkpickHandler, true);
                window.__inkpickHandler = null;
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
            var src = 'picked x' + picks.length;
            if (picks.length === 0) {
                var fb = lastReply();
                if (fb.text.trim().length > 0) {
                    picks.push({k:'t', t:fb.text.slice(0, 20000)});
                    mathB += fb.b; mathI += fb.i;
                    src = 'fallback-full';
                }
            }
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
