package com.vic.inkflow.ui

/**
 * MathML → LaTeX 的 JS 片段（會被插進共用的 collect 腳本）。
 *
 * **為什麼需要**：`texify()` 原本每一條規則都建立在「站台自己留下 TeX」之上——
 * Gemini 的 `data-math`、ChatGPT 的 `data-math-source`、KaTeX 的
 * `<annotation encoding="application/x-tex">`。Qwen（chat.qwen.ai）實機 census 是
 * `.katex=45`、`<math>=45`、`annotation=0`：**渲染後的 MathML 留著，TeX 被清掉**。
 * 於是 `mathB=0 mathI=0`，公式只剩 innerText——上標被壓平（`x2`）、分數變兩行、
 * 根號與希臘字母消失。使用者看到的「數學壞掉」就是這個。
 *
 * 這支把 MathML（含 MathJax CHTML 的 `mjx-*` 標籤）還原成可再餵給離線 KaTeX 的
 * LaTeX。行為由 `AiMathmlTexTest` 用 node 釘住（純字串、不碰 DOM API 以外的東西）。
 *
 * 為什麼獨立成檔案而不是塞在 collect 字串裡：這段是純函數、可以單獨測；
 * 塞進幾百行的 collect 腳本裡就只能靠肉眼。
 */
internal val MATHML_TO_TEX_JS: String = """
            // ── MathML → LaTeX（沒有 TeX annotation 的站台，例如 Qwen）──────────
            var TEX_FN = {
                'sin': '\\sin', 'cos': '\\cos', 'tan': '\\tan', 'cot': '\\cot',
                'sec': '\\sec', 'csc': '\\csc', 'arcsin': '\\arcsin', 'arccos': '\\arccos',
                'arctan': '\\arctan', 'sinh': '\\sinh', 'cosh': '\\cosh', 'tanh': '\\tanh',
                'log': '\\log', 'ln': '\\ln', 'exp': '\\exp', 'lim': '\\lim',
                'max': '\\max', 'min': '\\min', 'det': '\\det', 'gcd': '\\gcd', 'sup': '\\sup'
            };
            var TEX_OP = {
                '\u00D7': '\\times', '\u00F7': '\\div', '\u2212': '-', '\u00B1': '\\pm',
                '\u2213': '\\mp', '\u22C5': '\\cdot', '\u00B7': '\\cdot', '\u2044': '/',
                '\u2264': '\\le', '\u2265': '\\ge', '\u2260': '\\ne', '\u2248': '\\approx',
                '\u2261': '\\equiv', '\u221D': '\\propto', '\u221E': '\\infty',
                '\u2211': '\\sum', '\u220F': '\\prod', '\u222B': '\\int', '\u2202': '\\partial',
                '\u2207': '\\nabla', '\u2192': '\\to', '\u21D2': '\\Rightarrow',
                '\u21D4': '\\Leftrightarrow', '\u2208': '\\in', '\u2209': '\\notin',
                '\u2282': '\\subset', '\u2286': '\\subseteq', '\u222A': '\\cup', '\u2229': '\\cap',
                '\u2200': '\\forall', '\u2203': '\\exists', '\u00AC': '\\neg', '\u2227': '\\wedge',
                '\u2228': '\\vee', '\u00B0': '{}^{\\circ}', '\u2032': "'", '\u2033': "''",
                '\u2026': '\\ldots', '\u22EF': '\\cdots',
                '\u2061': '', '\u2062': ' ', '\u2063': ','
            };
            function texEsc(s) {
                var out = '';
                for (var i = 0; i < s.length; i++) {
                    var c = s.charAt(i);
                    if (c === '\\' || c === '{' || c === '}' || c === '_' || c === '^' ||
                        c === '$' || c === '%' || c === '&' || c === '#') out += '\\' + c;
                    else out += c;
                }
                return out;
            }
            function texOp(s) {
                if (!s) return '';
                if (TEX_OP[s] !== undefined) {
                    // 空字串＝不可見運算子（U+2061 函數套用等）：吃掉，不要留空白。
                    return TEX_OP[s] === '' ? '' : TEX_OP[s] + ' ';
                }
                return texEsc(s) + ' ';
            }
            function mmlTex(node) {
                if (!node) return '';
                if (node.nodeType === 3) return (node.nodeValue || '').replace(/\s+/g, ' ');
                if (node.nodeType !== 1) return '';
                var tag = (node.tagName || '').toLowerCase();
                // MathJax CHTML/SVG：mjx-mi、mjx-mo、mjx-mrow… 去掉前綴就同一套文法。
                if (tag.indexOf('mjx-') === 0) tag = tag.slice(4);
                function kids(elm) {
                    var s = '';
                    for (var i = 0; i < elm.childNodes.length; i++) s += mmlTex(elm.childNodes[i]);
                    return s;
                }
                function kid(i) {
                    return (node.children && node.children[i]) ? mmlTex(node.children[i]) : '';
                }
                function childText(i) {
                    if (!node.children || !node.children[i] || !node.children[i].textContent) return '';
                    return node.children[i].textContent.trim();
                }
                // MathJax 的容器同時有「無障礙 MathML」與「畫面用 CHTML」兩份，只能取一份。
                if (tag === 'container') {
                    var asm = null, vis = null;
                    for (var c = 0; c < node.children.length; c++) {
                        var ct = (node.children[c].tagName || '').toLowerCase();
                        if (ct.indexOf('mjx-assistive') === 0) asm = node.children[c];
                        else if (ct.indexOf('mjx-math') === 0) vis = node.children[c];
                    }
                    if (asm) return mmlTex(asm);
                    if (vis) return mmlTex(vis);
                    return kids(node);
                }
                switch (tag) {
                    case 'annotation':
                    case 'annotation-xml':
                        return '';
                    case 'semantics':
                        // semantics 的第一個子節點才是式子；annotation 是同一份內容的另一種寫法。
                        for (var i2 = 0; i2 < node.children.length; i2++) {
                            var ct2 = (node.children[i2].tagName || '').toLowerCase();
                            if (ct2 !== 'annotation' && ct2 !== 'annotation-xml') return mmlTex(node.children[i2]);
                        }
                        return '';
                    case 'mi': {
                        var name = (node.textContent || '').trim();
                        if (!name) return '';
                        if (TEX_FN[name] !== undefined) return TEX_FN[name] + ' ';
                        if (name.length <= 1) return name;
                        return '\\mathrm{' + texEsc(name) + '}';
                    }
                    case 'mn':
                        return (node.textContent || '').trim();
                    case 'mo':
                        return texOp((node.textContent || '').trim());
                    case 'mtext':
                        return '\\text{' + texEsc((node.textContent || '').trim()) + '}';
                    case 'mspace':
                        return '\\,';
                    case 'msup':
                        return '{' + kid(0) + '}^{' + kid(1) + '}';
                    case 'msub':
                        return '{' + kid(0) + '}_{' + kid(1) + '}';
                    case 'msubsup':
                        return '{' + kid(0) + '}_{' + kid(1) + '}^{' + kid(2) + '}';
                    case 'mfrac':
                        return '\\frac{' + kid(0) + '}{' + kid(1) + '}';
                    case 'msqrt':
                        return '\\sqrt{' + kids(node) + '}';
                    case 'mroot':
                        return '\\sqrt[' + kid(1) + ']{' + kid(0) + '}';
                    case 'mover': {
                        var base = kid(0);
                        var over = childText(1);
                        if (over === '\u00AF' || over === '\u203E') return '\\overline{' + base + '}';
                        if (over === '^' || over === '\u02C6') return '\\hat{' + base + '}';
                        if (over === '\u2192') return '\\vec{' + base + '}';
                        if (over === '.') return '\\dot{' + base + '}';
                        if (over === '~') return '\\tilde{' + base + '}';
                        return '\\overset{' + kid(1) + '}{' + base + '}';
                    }
                    case 'munder': {
                        var base2 = kid(0);
                        var under = childText(1);
                        if (under === '\u0338') return '\\not{' + base2 + '}';
                        return '\\underset{' + kid(1) + '}{' + base2 + '}';
                    }
                    case 'munderover':
                        return '\\underset{' + kid(1) + '}{\\overset{' + kid(2) + '}{' + kid(0) + '}}';
                    case 'mfenced': {
                        var open = node.getAttribute('open');
                        if (open === null || open === undefined) open = '(';
                        var close = node.getAttribute('close');
                        if (close === null || close === undefined) close = ')';
                        var sep = node.getAttribute('separators');
                        if (sep === null || sep === undefined) sep = ',';
                        var parts = [];
                        for (var p = 0; p < node.children.length; p++) parts.push(mmlTex(node.children[p]));
                        return '\\left' + texEsc(open) + parts.join(sep + ' ') + '\\right' + texEsc(close);
                    }
                    case 'mtable': {
                        var rows = [];
                        for (var r = 0; r < node.children.length; r++) {
                            var rowEl = node.children[r];
                            if ((rowEl.tagName || '').toLowerCase() !== 'mtr') continue;
                            var cells = [];
                            for (var cc = 0; cc < rowEl.children.length; cc++) cells.push(mmlTex(rowEl.children[cc]));
                            rows.push(cells.join(' & '));
                        }
                        if (rows.length === 0) return kids(node);
                        return '\\begin{matrix} ' + rows.join(' \\\\ ') + ' \\end{matrix}';
                    }
                    default:
                        return kids(node);
                }
            }
""".trimIndent()
