package com.vic.inkflow.ui

/**
 * DOM → 純文字的 JS 片段（會被插進共用的 collect 腳本）。
 *
 * **為什麼不能直接用 `innerText`**（這是 AI 匯入「換行不見」的根因）：
 * collect 是在 `cloneNode(true)` 出來的**離線節點**上跑 `texify()`（把公式換成
 * `$…$`／`$$…$$`），然後讀複本上的 `innerText`。依 DOM 規格，`innerText` 對
 * **沒有被渲染**的元素會退回 `textContent` 語義——`textContent` 只串接文字節點，
 * **不會**為 `<p>`／`<div>`／`<li>`／`<br>` 產生換行。
 *
 * 實機症狀：AI 回覆的五個段落匯入後黏成一大段、清單每一項接在一起，
 * 使用者得手動重排格式。
 *
 * 這支改成**自己走訪 DOM**：block 元素換行、`<p>` 級別多留一個空行、
 * `<br>` 換行、`<pre>` 保留原樣、其餘文字節點的空白收斂成單一空格。
 * 好處是行為與 CSS 無關、與節點是否掛在 document 上無關，而且可以用假 DOM 單測
 * （`AiDomTextTest`）。
 *
 * 排版細節：
 *  - 段落級（`p`／`h1..h6`／`blockquote`／`pre`／`table`／`figure`／`dl`）
 *    前後各留一個空行 → `\n\n`；
 *  - 其他 block（`div`／`li`／`tr`…）只留單一換行 → `\n`；
 *  - `<ul>`／`<ol>` 的項目補回紙上看得懂的符號（`• `／`1. `）：列表符號是 CSS
 *    的 `::marker`，不是文字節點，走訪時看不到；
 *  - 表格一列一行、儲存格用 ` | ` 分隔（`td` 不能當 block，否則一格一行）；
 *  - 連續 3 個以上換行收斂成 2 個（不讓巢狀 div 產生一堆空行）；
 *  - `mjx-assistive-mml`／`annotation`／`svg` 跳過（MathJax 的無障礙副本與圖形，
 *    公式本身已由 `texify` 換成 TeX 文字）。
 */
internal val DOM_TEXT_JS: String = """
            var DOM_BLOCK = {
                p: 1, div: 1, li: 1, ul: 1, ol: 1, dl: 1, dt: 1, dd: 1,
                h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, blockquote: 1, pre: 1,
                table: 1, thead: 1, tbody: 1, tfoot: 1, tr: 1,
                section: 1, article: 1, header: 1, footer: 1, aside: 1, main: 1,
                nav: 1, figure: 1, figcaption: 1, form: 1, fieldset: 1, hr: 1
            };
            var DOM_PARA = {
                p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, blockquote: 1,
                pre: 1, table: 1, figure: 1, figcaption: 1, dl: 1, hr: 1
            };
            var DOM_SKIP = {
                script: 1, style: 1, noscript: 1, svg: 1, button: 1, textarea: 1,
                input: 1, select: 1, annotation: 1, 'annotation-xml': 1
            };
            // 螢幕上看不見的副本：KaTeX 的 MathML 版、無障礙用的 sr-only。
            // 不跳過就會和可見文字重複（一顆公式變成兩份）。
            function domSkipClass(n) {
                try {
                    var c = n.className;
                    if (c && typeof c !== 'string') c = c.baseVal || '';
                    c = ' ' + String(c || '') + ' ';
                    return c.indexOf(' katex-mathml ') >= 0 ||
                           c.indexOf(' sr-only ') >= 0 ||
                           c.indexOf(' visually-hidden ') >= 0 ||
                           c.indexOf(' screen-reader-text ') >= 0;
                } catch(e) { return false; }
            }
            function domText(root) {
                var out = '';
                // 剛寫完清單符號：下一個 block 的換行要吞掉，否則「• 」會自己佔一行。
                var suppressNl = false;
                function newlines(n) {
                    if (suppressNl) { suppressNl = false; return; }
                    if (out.length === 0) return;
                    var have = 0;
                    for (var i = out.length - 1; i >= 0 && out.charAt(i) === '\n'; i--) have++;
                    for (; have < n; have++) out += '\n';
                }
                // 把結尾換行「削到」n 個：清單項內含 <p> 時，段落的空行不該把清單撐開。
                function capNewlines(n) {
                    suppressNl = false;
                    var have = 0;
                    for (var i = out.length - 1; i >= 0 && out.charAt(i) === '\n'; i--) have++;
                    if (have > n) out = out.substring(0, out.length - (have - n));
                }
                function walk(n, pre, marker) {
                    if (!n) return;
                    if (n.nodeType === 3) {
                        var s = n.nodeValue || '';
                        // 一般文字：HTML 的空白（含原始碼換行）收斂成一個空格；
                        // <pre> 內原樣保留。
                        suppressNl = false;
                        out += pre ? s : s.replace(/[\s\u00A0]+/g, ' ');
                        return;
                    }
                    if (n.nodeType !== 1) return;
                    var tag = (n.tagName || '').toLowerCase();
                    if (DOM_SKIP[tag]) return;
                    if (tag === 'mjx-assistive-mml') return;
                    if (domSkipClass(n)) return;
                    if (tag === 'br') { suppressNl = false; out += '\n'; return; }
                    var isBlock = !!DOM_BLOCK[tag];
                    var isPara = !!DOM_PARA[tag];
                    if (isBlock) newlines(1);
                    if (marker) { out += marker; suppressNl = true; }
                    var liN = 0, cellN = 0;
                    for (var i = 0; i < n.childNodes.length; i++) {
                        var c = n.childNodes[i];
                        var mk = '';
                        if (c && c.nodeType === 1) {
                            var ctag = (c.tagName || '').toLowerCase();
                            if (tag === 'ol' && ctag === 'li') { liN++; mk = liN + '. '; }
                            else if (tag === 'ul' && ctag === 'li') { mk = '• '; }
                            else if (tag === 'tr' && (ctag === 'td' || ctag === 'th')) {
                                cellN++;
                                if (cellN > 1) mk = ' | ';
                            }
                        }
                        walk(c, pre || tag === 'pre', mk);
                    }
                    if (isBlock) {
                        // 清單項一律「一項一行」：<li><p>…</p></li> 不能變成兩項之間空一行。
                        if (tag === 'li') { capNewlines(1); } else { newlines(isPara ? 2 : 1); }
                    }
                }
                walk(root, false, '');
                return out.replace(/[ \t]+\n/g, '\n')
                          .replace(/\n{3,}/g, '\n\n')
                          .replace(/^[ \n]+/, '')
                          .replace(/[ \n]+$/, '');
            }
""".trimIndent()
