package com.vic.inkflow.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import com.vic.inkflow.data.RenderedMath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * S1：Gemini 公式像素裁圖 — TeX 拿不到時的第二引擎（所見即所得）。
 * 流程：collect 回傳元素 CSS 框 → 映射 window 像素 → PixelCopy 整窗 → 裁剪 →
 * 深底自動反白 → 墨量自檢 → 存檔。全部失敗一律回 null，上層退回文字路。
 */
object WebCrop {
    private const val TAG = "WebCrop"

    /** CSS px 框（getBoundingClientRect 原值，viewport 相對）。 */
    data class CssBox(val x: Float, val y: Float, val w: Float, val h: Float)

    /** 捲動後重量的結果：新框＋當下 viewport 尺寸（CSS px）＋遮擋資訊＋捲動位置。 */
    data class PrepareRect(
        val box: CssBox,
        val viewW: Float,
        val viewH: Float,
        /** 目標是否被 fixed/sticky 浮動層蓋住（座標在框內、畫面卻是別人的）。 */
        val covered: Boolean = false,
        val scrollY: Float = 0f,
        /** 是否有任何非目標元素命中（含版面重疊，不一定遮得住）。 */
        val rawCovered: Boolean = false,
        /** 遮擋者描述（診斷用，例如 `DIV.input-area@fixed[0/470/420x228]`）。 */
        val occluder: String? = null,
        /** 目標是否完整落在「視窗 ∩ 祖先捲動/裁切容器」內（JS 端算好）。 */
        val fit: Boolean = true,
        /** 祖先裁切容器的可見帶（CSS px，診斷用）。 */
        val clipTop: Float = 0f,
        val clipBottom: Float = 0f
    )

    /**
     * 只量測、不捲動：回傳目標目前的 rect、viewport 尺寸、**是否被浮動層遮擋**、遮擋者與 scrollY。
     *
     * 用途有三個：①裁圖前判斷「本來就在畫面內」→ 不必捲（使用者抱怨連在畫面內的塊也被捲動）；
     * ②捲動後輪詢，直到 rect 連續不變，才確認捲動真的停了；
     * ③抓「座標在視窗內、畫面卻被蓋住」——Gemini／ChatGPT 的輸入列是 `position:fixed`，
     * `getBoundingClientRect` 看不到它，PixelCopy 卻只拍到它（實機踩過：裁到「問問 Gemini」輸入框）。
     *
     * 遮擋判定要**往祖先鏈找** float root：實測命中的是輸入列內層的 static div，
     * 只看命中元素本身會回報「有遮擋但找不到浮動層」而全部退文字。
     */
    internal fun probeJs(idx: Int): String = """
        (function() {
            try {
                var els = window.__inkcropEls || [];
                var el = els[$idx];
                if (!el) return 'NONE';
                if (el.isConnected === false) return 'DETACHED';
                var r = el.getBoundingClientRect();
                function up(n) {
                    var q = n.parentElement;
                    if (!q && n.getRootNode) {
                        var rt = n.getRootNode();
                        if (rt && rt.host) q = rt.host;
                    }
                    return q;
                }
                function floatRoot(n) {
                    var p = n, d = 0;
                    while (p && p.nodeType === 1 && d < 20) {
                        if (p === el || p.contains(el)) return null;
                        var s = getComputedStyle(p);
                        if (s.position === 'fixed' || s.position === 'sticky') return p;
                        p = up(p); d++;
                    }
                    return null;
                }
                function describe(n) {
                    try {
                        var s = getComputedStyle(n), q = n.getBoundingClientRect();
                        var cls = '';
                        if (n.className && typeof n.className === 'string') {
                            var parts = n.className.trim().split(/\s+/).slice(0, 2);
                            if (parts.length && parts[0]) cls = '.' + parts.join('.');
                        }
                        return (n.tagName || '?') + cls + (n.id ? ('#' + n.id) : '') +
                            '@' + s.position + '[' + Math.round(q.left) + '/' + Math.round(q.top) +
                            '/' + Math.round(q.width) + 'x' + Math.round(q.height) + ']';
                    } catch (e) { return '?'; }
                }
                var cx = Math.min(Math.max(r.left + r.width / 2, 1), window.innerWidth - 2);
                // 網格取樣：小浮動件（「↓ 回到底部」約 36px）用 5 個點會擦邊漏掉（實機踩過），
                // 每 14px 一列 × 左/中/右三欄，任何蓋在目標上的東西都會被踩到。
                var pts = [];
                var stepY = Math.max(14, r.height / 20);
                for (var yy = r.top + 4; yy <= r.bottom - 4; yy += stepY) {
                    pts.push([r.left + 6, yy]);
                    pts.push([cx, yy]);
                    pts.push([r.right - 6, yy]);
                }
                if (pts.length === 0) {
                    pts.push([cx, r.top + 3], [cx, r.top + r.height / 2], [cx, r.bottom - 3]);
                }
                var raw = 0, cov = 0, who = '';
                for (var i = 0; i < pts.length; i++) {
                    var t = document.elementFromPoint(pts[i][0], pts[i][1]);
                    if (!t || t === el || el.contains(t)) continue;
                    raw = 1;
                    var root = floatRoot(t);
                    if (root) { cov = 1; if (!who) who = describe(root); }
                }
                // 真正的可見區＝視窗 ∩ 所有祖先「捲動/裁切」容器的框。
                // Gemini 的對話區是 overflow 容器，輸入列是同一個 flex 直欄的普通子元素：
                // 塊的座標在 innerHeight 內，卻落在對話區被裁掉的下方 → 拍到輸入列（實機踩過）。
                var ct = 0, cb = window.innerHeight;
                var p = el.parentElement;
                while (p && p.nodeType === 1) {
                    var st = getComputedStyle(p);
                    if (/(hidden|auto|scroll|clip)/.test(st.overflowY) ||
                        /(hidden|auto|scroll|clip)/.test(st.overflowX)) {
                        var q = p.getBoundingClientRect();
                        if (q.height > 0) {
                            ct = Math.max(ct, q.top);
                            cb = Math.min(cb, q.bottom);
                        }
                    }
                    p = p.parentElement;
                }
                var fit = 0;
                var bandH = cb - ct;
                // Gemini 在可見帶底部有一層漸層淡出＋「↓」按鈕：短塊留 36px 邊距，
                // 讓它被捲到淡出區上方；比可見帶還高的塊只能貼滿（留邊距會永遠不合格）。
                var margin = (r.height <= bandH - 36) ? 36 : 0;
                if (r.top >= ct - 1 && r.bottom <= cb - 1 - margin &&
                    r.left >= -1 && r.right <= window.innerWidth + 1) fit = 1;
                return 'OK:' + [r.x, r.y, r.width, r.height,
                    window.innerWidth, window.innerHeight, cov, window.scrollY, raw, fit,
                    Math.round(ct), Math.round(cb)].join(',') + '|' + who;
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /**
     * 暫時隱藏遮住目標的 fixed/sticky 浮動層（輸入列、頁首、浮動工具列），回傳隱藏數量。
     *
     * 為什麼可以這樣做：fixed/sticky 不佔版面流，隱藏不會推動內容，目標底下的像素本來就在；
     * 拍完由 [restoreOverlaysJs] 還原。非浮動層的遮擋（真的疊在一起的內容）不處理。
     *
     * 關鍵：隱藏的是**祖先鏈上的 float root**（例：`input-area` 外框），不是命中的內層 div
     * ——內層多半是 static，只認命中元素本身會一個都找不到（實機踩過）。
     */
    internal fun hideOverlaysJs(idx: Int): String = """
        (function() {
            try {
                var els = window.__inkcropEls || [];
                var el = els[$idx];
                if (!el) return 'NONE';
                var r = el.getBoundingClientRect();
                function up(n) {
                    var q = n.parentElement;
                    if (!q && n.getRootNode) {
                        var rt = n.getRootNode();
                        if (rt && rt.host) q = rt.host;
                    }
                    return q;
                }
                function floatRoot(n) {
                    var p = n, d = 0;
                    while (p && p.nodeType === 1 && d < 20) {
                        if (p === el || p.contains(el)) return null;
                        var s = getComputedStyle(p);
                        if (s.position === 'fixed' || s.position === 'sticky') return p;
                        p = up(p); d++;
                    }
                    return null;
                }
                var cx = Math.min(Math.max(r.left + r.width / 2, 1), window.innerWidth - 2);
                // 與 probeJs 用同一套網格：只取 3 點會漏掉小的浮動按鈕。
                var pts = [];
                var stepY = Math.max(14, r.height / 20);
                for (var yy = r.top + 4; yy <= r.bottom - 4; yy += stepY) {
                    pts.push([r.left + 6, yy]);
                    pts.push([cx, yy]);
                    pts.push([r.right - 6, yy]);
                }
                if (pts.length === 0) {
                    pts.push([cx, r.top + 3], [cx, r.top + r.height / 2], [cx, r.bottom - 3]);
                }
                var found = [];
                for (var i = 0; i < pts.length; i++) {
                    var stack = document.elementsFromPoint(pts[i][0], pts[i][1]) || [];
                    for (var j = 0; j < stack.length; j++) {
                        var n = stack[j];
                        if (n === el || el.contains(n)) break;
                        var root = floatRoot(n);
                        if (root && found.indexOf(root) < 0) found.push(root);
                    }
                }
                window.__inkcropHidden = found;
                var k = 0;
                for (var m = 0; m < found.length; m++) {
                    var f = found[m];
                    try {
                        if (f.__inkVis == null) f.__inkVis = f.style.visibility;
                        f.style.visibility = 'hidden';
                        k++;
                    } catch (e) {}
                }
                try { void document.body.offsetHeight; } catch (e) {}
                return 'OK:' + k;
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 還原 [hideOverlaysJs] 隱藏過的浮動層（一定要在拍完後呼叫）。 */
    internal fun restoreOverlaysJs(): String = """
        (function() {
            try {
                var a = window.__inkcropHidden || [];
                for (var i = 0; i < a.length; i++) {
                    try {
                        a[i].style.visibility = a[i].__inkVis == null ? '' : a[i].__inkVis;
                        a[i].__inkVis = null;
                    } catch (e) {}
                }
                window.__inkcropHidden = [];
                return 'OK';
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /**
     * 捲動定位腳本：用 collect 階段留下的 `__inkcropEls[i]` 找出目標本尊，
     * `scrollIntoView` 捲到畫面中央，回傳**捲動後**的 rect 與 viewport 尺寸。
     *
     * 為什麼是節點索引而不是座標：rect 出界時座標本身沒有意義（見 cropWindowPx 註解），
     * 必須回到節點重新量。字串回傳格式刻意用 `OK:x,y,w,h,vw,vh,sy` 而非 JSON——
     * evaluateJavascript 的結果本身會被 JSON 編碼一次，JSON 疊 JSON 只是徒增解碼陷阱。
     */
    internal fun prepareJs(idx: Int, block: String = "center"): String = """
        (function() {
            try {
                var els = window.__inkcropEls || [];
                var el = els[$idx];
                if (!el) return 'NONE';
                if (el.isConnected === false) return 'DETACHED';
                try { el.scrollIntoView({block:'$block', inline:'center'}); }
                catch(e) { try { el.scrollIntoView(); } catch(e2) { return 'SCROLL_FAIL'; } }
                var r = el.getBoundingClientRect();
                return 'OK:' + [r.x, r.y, r.width, r.height, window.innerWidth, window.innerHeight, window.scrollY].join(',');
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 整批裁圖結束後捲回使用者的原位（避免匯入完面板停在最後一個 shot）。 */
    internal fun restoreScrollJs(): String = """
        (function() {
            try {
                if (window.__inkcropY == null) return 'NOOP';
                window.scrollTo(0, window.__inkcropY);
                window.__inkcropY = null;
                return 'OK';
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 記下目前捲動位置（批次開始前呼叫一次；重複呼叫不覆蓋）。 */
    internal fun saveScrollJs(): String = """
        (function() {
            try {
                if (window.__inkcropY == null) window.__inkcropY = window.scrollY;
                return 'OK';
            } catch (e) { return 'ERR'; }
        })();
    """.trimIndent()

    /**
     * 解析 [prepareJs]／[probeJs] 的回傳字串（evaluateJavascript 會把字串值再包一層引號）。
     *
     * 欄位：`OK:x,y,w,h,vw,vh[,cov[,sy[,raw[,fit[,ct[,cb]]]]]]|遮擋者描述`。
     * 舊格式（6 欄、無 `|`）仍吃，cov/raw 預設 false、fit 預設 true。純函數，可單測。
     */
    fun parsePrepare(raw: String?): PrepareRect? {
        val s0 = raw?.trim() ?: return null
        if (s0 == "null" || s0.isEmpty()) return null
        val s = s0.removeSurrounding("\"").replace("\\\"", "\"")
        if (!s.startsWith("OK:")) return null
        val body = s.removePrefix("OK:")
        val cut = body.indexOf('|')
        val numsPart = if (cut >= 0) body.substring(0, cut) else body
        val occluder = if (cut >= 0) body.substring(cut + 1).trim().takeIf { it.isNotEmpty() } else null
        val nums = numsPart.split(',')
        if (nums.size < 6) return null
        val f = nums.take(13).map { it.trim().toFloatOrNull() ?: return null }
        if (f[2] <= 0f || f[3] <= 0f || f[4] <= 0f || f[5] <= 0f) return null
        return PrepareRect(
            box = CssBox(f[0], f[1], f[2], f[3]),
            viewW = f[4],
            viewH = f[5],
            covered = f.getOrElse(6) { 0f } >= 0.5f,
            scrollY = f.getOrElse(7) { 0f },
            rawCovered = f.getOrElse(8) { 0f } >= 0.5f,
            occluder = occluder,
            fit = f.getOrElse(9) { 1f } >= 0.5f,
            clipTop = f.getOrElse(10) { 0f },
            clipBottom = f.getOrElse(11) { 0f }
        )
    }

    /** 框是否完整落在回報的 viewport 內（容差 1px）。框比視窗大 → 捲也救不了，必須退文字。 */
    fun insideViewport(box: CssBox, viewW: Float, viewH: Float, tol: Float = 1f): Boolean =
        box.x >= -tol && box.y >= -tol &&
            box.x + box.w <= viewW + tol && box.y + box.h <= viewH + tol

    /**
     * 目標是否**真的看得見**：JS 端的 fit（視窗 ∩ 祖先捲動/裁切容器）＋ Kotlin 端視窗邊界雙重確認。
     *
     * 只看 innerHeight 不夠（實機踩過）：Gemini 的對話區是 overflow 容器，輸入列是同一個
     * flex 直欄的普通子元素，塊的座標在 innerHeight 內、卻落在被裁掉的下方 → 拍到輸入列。
     */
    fun fits(pr: PrepareRect): Boolean =
        pr.fit && insideViewport(pr.box, pr.viewW, pr.viewH)

    /** Window 像素裁剪矩（純資料，可單測）。 */
    data class CropPx(val l: Int, val t: Int, val r: Int, val b: Int)

    /** CSS px → device px（WebView.scale 已含 density 與頁面縮放）。 */
    fun cssToDevice(v: Float, scale: Float): Int = (v * scale).roundToInt()

    /**
     * 算出在 window 點陣圖上的裁剪矩形；框無效/任何一邊出界/過小/過大回 null。
     *
     * ## 為什麼出界必須回 null，不能 clamp（踩過的坑）
     *
     * 這裡原本把出界座標 `coerceIn` 掰回視窗內。但 `captureWindow` 只有「當下螢幕」
     * 的像素：框頂超出螢幕時 clamp 到 0 → 裁到頁面標題/工具列；框底超出螢幕時 clamp
     * 到視窗底 → 裁到 AI 輸入框。**部分出界完全沒被擋掉，垃圾就從這裡進來。**
     *
     * 正確做法是裁之前先 `scrollIntoView` 把目標捲進畫面（見 [prepareJs]），
     * 捲不動（框比視窗大）就回 null 退回文字／KaTeX 路徑。
     * 純函數（可單測），呼叫方再包成 android.graphics.Rect。
     */
    fun cropWindowPx(
        box: CssBox,
        scale: Float,
        locX: Int,
        locY: Int,
        winW: Int,
        winH: Int,
        insetPx: Int = 2,
        // 面積上限（佔視窗比例）。分段裁圖的一段本來就接近整條可見帶，
        // 舊的 0.8 會把「一屏高的正常段」判成誤裁；分段路徑明確傳 1.0。
        areaLimit: Float = 0.8f
    ): CropPx? {
        if (box.w <= 0f || box.h <= 0f || scale <= 0f) return null
        val l = locX + cssToDevice(box.x, scale) + insetPx
        val t = locY + cssToDevice(box.y, scale) + insetPx
        val r = locX + cssToDevice(box.x + box.w, scale) - insetPx
        val b = locY + cssToDevice(box.y + box.h, scale) - insetPx
        // 出界一律拒收（不再 clamp 硬裁）：寧可退文字，也不要把工具列/輸入框裁進筆記。
        if (l < 0 || t < 0 || r > winW || b > winH) return null
        if (r <= l || b <= t) return null
        val w = r - l
        val h = b - t
        // 太小的大概率是 UI 殘件不是公式
        if (w < 24 || h < 24) return null
        // 太大的整頁誤裁（>80% 窗）也不要
        if (w.toLong() * h > winW.toLong() * winH * (areaLimit * 100).toLong() / 100) return null
        return CropPx(l, t, r, b)
    }

    /**
     * 算出在 window 點陣圖上的裁剪矩形；框無效/完全在窗外回 null。
     * @param loc WebView 左上角在 window 的 device px（getLocationInWindow）
     */
    fun cropWindowRect(
        box: CssBox,
        scale: Float,
        locX: Int,
        locY: Int,
        winW: Int,
        winH: Int,
        insetPx: Int = 2,
        areaLimit: Float = 0.8f
    ): Rect? {
        val c = cropWindowPx(box, scale, locX, locY, winW, winH, insetPx, areaLimit) ?: return null
        return Rect(c.l, c.t, c.r, c.b)
    }

    /**
     * 捲動到目標元素並重量 rect；任何一步失敗回 null（上層退文字路）。Main thread only。
     *
     * 找不到節點（SPA 重繪把節點換掉）或節點已離線一律回 null：
     * 寧可退文字，也不要拿失效座標去裁一張垃圾圖。
     */
    suspend fun measureAfterScroll(
        webView: WebView,
        idx: Int,
        timeoutMs: Long = 1200,
        block: String = "center"
    ): PrepareRect? = evalRect(webView, prepareJs(idx, block), idx, timeoutMs, "scroll-$block")

    /** 只量測、不捲動（見 [probeJs]）。 */
    suspend fun probe(webView: WebView, idx: Int, timeoutMs: Long = 1200): PrepareRect? =
        evalRect(webView, probeJs(idx), idx, timeoutMs, "probe")

    /**
     * 暫時隱藏遮住目標的 fixed/sticky 浮動層；回傳隱藏數量（0＝沒有遮擋或失敗）。
     * 拍完務必呼叫 [restoreOverlays]。Main thread only。
     */
    suspend fun hideOverlays(webView: WebView, idx: Int, timeoutMs: Long = 1200): Int {
        if (Looper.myLooper() != Looper.getMainLooper()) return 0
        val ret = CompletableDeferred<String?>()
        try {
            webView.evaluateJavascript(hideOverlaysJs(idx)) { v -> ret.complete(v) }
        } catch (t: Throwable) {
            Log.w(TAG, "hideOverlays eval failed idx=$idx: $t")
            return 0
        }
        val s = withTimeoutOrNull(timeoutMs) { ret.await() } ?: run {
            Log.w(TAG, "hideOverlays timeout idx=$idx")
            return 0
        }
        val v = s.trim().removeSurrounding("\"").trim()
        return v.removePrefix("OK:").toIntOrNull() ?: 0
    }

    /** 還原 [hideOverlays] 隱藏過的浮動層。Main thread only；失敗不拋。 */
    fun restoreOverlays(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            webView.evaluateJavascript(restoreOverlaysJs(), null)
        } catch (t: Throwable) {
            Log.w(TAG, "restoreOverlays failed: $t")
        }
    }

    private suspend fun evalRect(
        webView: WebView,
        js: String,
        idx: Int,
        timeoutMs: Long,
        tag: String
    ): PrepareRect? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val ret = CompletableDeferred<String?>()
        try {
            webView.evaluateJavascript(js) { v -> ret.complete(v) }
        } catch (t: Throwable) {
            Log.w(TAG, "$tag eval failed idx=$idx: $t")
            return null
        }
        val s = withTimeoutOrNull(timeoutMs) { ret.await() } ?: run {
            Log.w(TAG, "$tag timeout idx=$idx")
            return null
        }
        return parsePrepare(s)
    }

    /**
     * 等捲動真的停下來：輪詢 rect（含 y）與 scrollY，**連續 3 次完全相同**才放行。
     *
     * ## 為什麼不能只等一幀（實機踩過）
     *
     * `scrollIntoView` 遇到 `scroll-behavior:smooth`（Gemini／ChatGPT 都有）是動畫：
     * `postVisualStateCallback` 只保證「畫了一幀」，不保證「捲動結束」。
     * 動畫途中截圖 → 畫面位置與量到的 rect 不一致 → 裁出來上下錯位、
     * 甚至把輸入框或頁首帶進來。輪詢 scrollY＋rect 是唯一看得到「停了沒」的訊號。
     */
    suspend fun awaitSettled(
        webView: WebView,
        idx: Int,
        timeoutMs: Long = 2000
    ): PrepareRect? {
        val t0 = SystemClock.uptimeMillis()
        var prev: PrepareRect? = null
        var stable = 0
        var samples = 0
        while (SystemClock.uptimeMillis() - t0 <= timeoutMs) {
            val cur = probe(webView, idx)
            samples++
            if (cur != null) {
                Log.d(TAG, "settle#$samples idx=$idx y=${cur.box.y} h=${cur.box.h} sy=${cur.scrollY}")
            }
            if (cur != null && prev != null &&
                cur.scrollY == prev.scrollY &&
                cur.fit == prev.fit &&
                cur.box.y == prev.box.y &&
                cur.box.h == prev.box.h &&
                cur.box.w == prev.box.w
            ) {
                stable++
                if (stable >= 2) {
                    Log.d(TAG, "settled idx=$idx after ${samples} samples (${SystemClock.uptimeMillis() - t0}ms)")
                    return cur
                }
            } else stable = 0
            prev = cur
            delay(60)
        }
        Log.w(TAG, "settle timeout idx=$idx after ${samples} samples, using last=$prev")
        return prev
    }

    private var frameId = 1L

    /** 等 WebView 把捲動後的重繪落地（postVisualStateCallback 一幀）。逾時回 false。 */
    suspend fun waitFrame(webView: WebView, timeoutMs: Long = 1500): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val done = CompletableDeferred<Boolean>()
        try {
            webView.postVisualStateCallback(frameId++, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    done.complete(true)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "waitFrame failed: $t")
            done.complete(false)
        }
        return withTimeoutOrNull(timeoutMs) { done.await() } == true
    }

    /** 批次開始前記下捲動位置；結束後捲回。皆為 fire-and-forget，失敗不影響匯入。 */
    fun saveScroll(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            webView.evaluateJavascript(saveScrollJs(), null)
        } catch (_: Throwable) { }
    }

    fun restoreScroll(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            webView.evaluateJavascript(restoreScrollJs(), null)
        } catch (_: Throwable) { }
    }

    /** 整窗截圖（Main thread 呼叫；PixelCopy API 26+，minSdk 32）。 */
    suspend fun captureWindow(activity: Activity): Bitmap? {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "captureWindow off-main")
            return null
        }
        return try {
            val decor = activity.window?.decorView ?: return null
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val done = CompletableDeferred<Int?>()
            PixelCopy.request(
                activity.window,
                bmp,
                { result -> done.complete(result) },
                Handler(Looper.getMainLooper())
            )
            val code = withTimeoutOrNull(5000) { done.await() }
            if (code == PixelCopy.SUCCESS) bmp else {
                bmp.recycle()
                Log.w(TAG, "pixelcopy code=$code")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "captureWindow failed: $t")
            null
        }
    }

    /** 取樣中位亮度（0黑～255白），步長 stridePx。 */
    fun medianLuminance(bmp: Bitmap, stridePx: Int = 12): Float {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 255f
        var n = 0
        var sum = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                sum += (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3
                n++
                x += stridePx
            }
            y += stridePx
        }
        return if (n > 0) sum.toFloat() / n else 255f
    }

    /** 純函數：兩色是否接近（最大分量差容限，可單測）。 */
    fun nearBg(px: Int, bgR: Int, bgG: Int, bgB: Int, tol: Int = 28): Boolean {
        val r = (px shr 16) and 0xff
        val g = (px shr 8) and 0xff
        val b = px and 0xff
        return maxOf(kotlin.math.abs(r - bgR), kotlin.math.abs(g - bgG), kotlin.math.abs(b - bgB)) <= tol
    }

    /** 邊緣取樣底色（公式碰不到極邊，取四邊內縮 6px 的中位色）。 */
    private fun edgeBg(bmp: Bitmap): Triple<Int, Int, Int> {
        val w = bmp.width
        val h = bmp.height
        val rs = mutableListOf<Int>()
        val gs = mutableListOf<Int>()
        val bs = mutableListOf<Int>()
        val m = 6
        fun feed(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val p = bmp.getPixel(x, y)
            if (((p ushr 24) and 0xff) < 128) return
            rs.add((p shr 16) and 0xff)
            gs.add((p shr 8) and 0xff)
            bs.add(p and 0xff)
        }
        var x = 0
        while (x < w) {
            feed(x, m); feed(x, h - 1 - m)
            x += 8
        }
        var y = 0
        while (y < h) {
            feed(m, y); feed(w - 1 - m, y)
            y += 8
        }
        if (rs.isEmpty()) return Triple(255, 255, 255)
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        return Triple(rs[mid], gs[mid], bs[mid])
    }

    private fun invert(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xff
            px[i] = (a shl 24) or ((255 - ((p shr 16) and 0xff)) shl 16) or
                ((255 - ((p shr 8) and 0xff)) shl 8) or (255 - (p and 0xff))
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return out
    }

    /**
     * 兩步正規化（取代整張反色）：
     * 1) 底深先反色（median <128）
     * 2) 邊緣取樣底色、近底色壓純白（洗米黃/淡藍殘留），字不動。
     * 輸入一律消耗（recycle），回傳可用圖由呼叫方 recycle。
     */
    fun normalizeToWhite(bmp: Bitmap): Bitmap {
        var cur = bmp
        val med = medianLuminance(cur)
        if (med < 128f) {
            cur = invert(cur)
            Log.d(TAG, "inverted dark crop to white")
        }
        val (br, bg, bb) = edgeBg(cur)
        Log.d(TAG, "crop bg=($br,$bg,$bb) med=$med")
        if (br >= 240 && bg >= 240 && bb >= 240) return cur
        val w = cur.width
        val h = cur.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        cur.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            if (nearBg(px[i], br, bg, bb)) px[i] = -0x1 // opaque white
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        cur.recycle()
        return out
    }

    /** 墨量比（非白像素佔比；透明視為空）。 */
    fun inkRatio(bmp: Bitmap, stridePx: Int = 12): Float {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0f
        var n = 0
        var ink = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                n++
                val a = (p ushr 24) and 0xff
                if (a >= 128) {
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    if (r < 240 || g < 240 || b < 240) ink++
                }
                x += stridePx
            }
            y += stridePx
        }
        return if (n > 0) ink.toFloat() / n else 0f
    }

    /**
     * 裁一個公式框：**捲到畫面內 → 等重繪 → 整窗截圖 → 裁剪 → 反白 → 自檢 → 存檔**。
     *
     * @param idx collect 階段留下的節點索引（`__inkcropEls[i]`）。>= 0 時先用它捲動定位；
     *   < 0（沒有索引）才退回用 [fallbackBox] 直接裁，但仍受窗內檢查保護。
     * @return RenderedMath（file/px尺寸）或 null（任一步失敗→上層退文字／KaTeX 路）
     */
    suspend fun cropFormula(
        activity: Activity,
        webView: WebView,
        idx: Int,
        fallbackBox: CssBox,
        tag: String
    ): RenderedMath? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        var hiddenOverlays = 0
        return try {
            @Suppress("DEPRECATION")
            val scale = webView.scale.takeIf { it > 0f }
                ?: webView.resources.displayMetrics.density
            val loc = IntArray(2)
            (webView as View).getLocationInWindow(loc)
            val decor = activity.window?.decorView ?: return null
            val winW = decor.width
            val winH = decor.height
            // 0) 先量一次「現況」：本來就完整可見就不捲（使用者抱怨連頁面內的塊也被捲動）。
            //    診斷同時記下 JS 的 innerHeight 與 WebView 實際可見高（device px / scale），
            //    兩者不一致時，「比內文視窗矮、卻比可見區高」的塊會躲過檢查而被截斷。
            var box = fallbackBox
            if (idx >= 0) {
                val before = probe(webView, idx)
                if (before == null) {
                    Log.w(TAG, "crop skipped no-element idx=$idx tag=$tag")
                    return null
                }
                val visH = if (webView.height > 0) webView.height / scale else before.viewH
                val visW = if (webView.width > 0) webView.width / scale else before.viewW
                Log.d(
                    TAG,
                    "DIAG idx=$idx tag=$tag scale=$scale win=${winW}x$winH " +
                        "loc=(${loc[0]},${loc[1]}) view=${webView.width}x${webView.height} " +
                        "vis=${"%.1f".format(visW)}x${"%.1f".format(visH)} " +
                        "inner=${"%.1f".format(before.viewW)}x${"%.1f".format(before.viewH)} " +
                        "clip=${"%.1f".format(before.clipTop)}..${"%.1f".format(before.clipBottom)} " +
                        "fit=${before.fit} sy=${"%.1f".format(before.scrollY)} " +
                        "covered=${before.covered} box=${before.box}"
                )
                if (fits(before)) {
                    Log.d(TAG, "already-visible idx=$idx -> skip scroll")
                    box = before.box
                } else {
                    // 1) 捲到畫面中央後重量：出界框在這裡被救回來，捲不動的（比可見區大）則退文字。
                    val scrolled = measureAfterScroll(webView, idx)
                    if (scrolled == null) {
                        Log.w(TAG, "crop skipped scroll-noop idx=$idx tag=$tag")
                        return null
                    }
                    // 2) 等捲動動畫真的停（smooth scroll 會讓「畫了一幀」≠「捲動結束」）。
                    val settled = awaitSettled(webView, idx) ?: scrolled
                    waitFrame(webView)
                    // 3) 捲動後再量最後一次：這是唯一可信的裁切依據。
                    val after = probe(webView, idx) ?: settled
                    if (!fits(after)) {
                        Log.w(
                            TAG,
                            "crop skipped oversize idx=$idx box=${after.box} " +
                                "clip=${after.clipTop}..${after.clipBottom} fit=${after.fit} " +
                                "inner=${after.viewW}x${after.viewH} sy=${after.scrollY}"
                        )
                        return null
                    }
                    box = after.box
                }
                waitFrame(webView)
                // 4) 座標在視窗內、畫面卻被 fixed/sticky 浮動層蓋住（Gemini 的輸入列就是這樣）：
                //    暫時隱藏遮擋層再拍，拍完在 finally 還原。隱不掉（不是浮動層）就退文字。
                val cov = probe(webView, idx)
                if (cov != null && cov.covered) {
                    Log.d(
                        TAG,
                        "covered idx=$idx by=${cov.occluder ?: "?"} box=${cov.box}"
                    )
                    hiddenOverlays = hideOverlays(webView, idx)
                    Log.d(TAG, "covered idx=$idx hidden=$hiddenOverlays")
                    if (hiddenOverlays <= 0) {
                        // 找不到浮動層可藏：改用「對齊頂端」再試一次（被底部輸入列蓋住的塊
                        // 通常捲到頂端就露出來），仍被蓋才退文字。
                        Log.w(TAG, "covered-no-floating idx=$idx -> retry top-align")
                        val re = measureAfterScroll(webView, idx, block = "start")
                        if (re == null) {
                            Log.w(TAG, "crop skipped retry-failed idx=$idx tag=$tag")
                            return null
                        }
                        awaitSettled(webView, idx)
                        waitFrame(webView)
                        val chk = probe(webView, idx)
                        if (chk == null || chk.covered || !fits(chk)) {
                            Log.w(
                                TAG,
                                "crop skipped still-covered idx=$idx tag=$tag " +
                                    "by=${chk?.occluder ?: "?"} box=${chk?.box}"
                            )
                            return null
                        }
                        box = chk.box
                        waitFrame(webView)
                    } else {
                        waitFrame(webView)
                        val clear = probe(webView, idx)
                        if (clear == null || clear.covered) {
                            Log.w(TAG, "crop skipped still-covered idx=$idx tag=$tag")
                            return null
                        }
                        // sticky 會佔版面流：隱藏後可能位移，重新確認仍在可見區內。
                        if (!fits(clear)) {
                            Log.w(TAG, "crop skipped shifted-out idx=$idx box=${clear.box}")
                            return null
                        }
                        box = clear.box
                        waitFrame(webView)
                    }
                } else if (cov != null && cov.rawCovered) {
                    // 版面本來就重疊、但沒有浮動層：照拍，留紀錄。
                    Log.d(TAG, "in-flow overlap idx=$idx, cropping anyway box=${cov.box}")
                }
            }
            val rect = cropWindowRect(box, scale, loc[0], loc[1], winW, winH)
            if (rect == null) {
                Log.w(TAG, "crop skipped box=$box scale=$scale win=${winW}x$winH")
                return null
            }
            Log.d(TAG, "crop-rect idx=$idx tag=$tag box=$box rect=$rect overlays=$hiddenOverlays")
            val full = captureWindow(activity) ?: return null
            var bmp = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
            full.recycle()
            bmp = normalizeToWhite(bmp)
            val ink = inkRatio(bmp)
            Log.d(TAG, "crop $tag ${rect.width()}x${rect.height()} ink=${"%.3f".format(ink)}")
            if (ink < 0.0005f) {
                bmp.recycle()
                Log.w(TAG, "crop blank, fallback")
                return null
            }
            // 經 PdfManager 漏斗落 workingDir：跟公式渲染圖同一條命（還原不斷鏈）。
            val f = com.vic.inkflow.util.PdfManager.newAnnotationImageFile(activity, ".png")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val rm = RenderedMath(f.absolutePath, bmp.width, bmp.height)
            bmp.recycle()
            rm
        } catch (t: Throwable) {
            Log.w(TAG, "cropFormula failed: $t")
            null
        } finally {
            // 浮動層是使用者的頁面狀態，任何離開路徑都要還原（否則 AI 頁面永久缺一塊 UI）。
            if (hiddenOverlays > 0) restoreOverlays(webView)
        }
    }

    // ================================================================
    // 分段裁圖（ChatGPT）：捲到哪、拍到哪，超高勾選塊也能完整拍下。
    // ================================================================

    /** 分段裁圖的幾何（CSS px；viewport 相對，另含元素在捲動內容中的座標）。 */
    data class SegGeom(
        val x: Float, val y: Float, val w: Float, val h: Float,
        /** 元素頂端在「捲動容器內容座標」的位置（不隨捲動改變）。 */
        val contentTop: Float,
        /** 真正可見帶（視窗 ∩ 祖先裁切容器）。 */
        val bandTop: Float, val bandBottom: Float,
        /** 捲動容器在 viewport 的 top、可見高、目前 scrollTop、可捲上限。 */
        val scTop: Float, val scClientH: Float, val scScrollTop: Float, val scMax: Float,
        /** `sc` = 內層捲動容器；`win` = 捲整個 document。 */
        val scroller: String
    )

    /**
     * 量測元素現況：rect、可見帶、捲動容器狀態。
     * 捲動容器優先取呼叫前 [segSaveJs] 記下的那個（`__inkcropSc`），沒有才往上找。
     */
    internal fun segInfoJs(idx: Int): String = """
        (function() {
            try {
                var el = (window.__inkcropEls || [])[$idx];
                if (!el) return 'NONE';
                if (el.isConnected === false) return 'DETACHED';
                var sc = window.__inkcropSc || null;
                if (sc && sc.isConnected === false) sc = null;
                if (!sc) {
                    var p0 = el.parentElement;
                    while (p0 && p0.nodeType === 1) {
                        var s0 = getComputedStyle(p0);
                        if (/(auto|scroll|overlay)/.test(s0.overflowY) && p0.scrollHeight > p0.clientHeight + 4) { sc = p0; break; }
                        p0 = p0.parentElement;
                    }
                }
                var r = el.getBoundingClientRect();
                var ct = 0, cb = window.innerHeight;
                var q = el.parentElement;
                while (q && q.nodeType === 1) {
                    var s2 = getComputedStyle(q);
                    if (/(hidden|auto|scroll|clip|overlay)/.test(s2.overflowY) || /(hidden|auto|scroll|clip|overlay)/.test(s2.overflowX)) {
                        var qr = q.getBoundingClientRect();
                        if (qr.height > 0) { ct = Math.max(ct, qr.top); cb = Math.min(cb, qr.bottom); }
                    }
                    q = q.parentElement;
                }
                var scTop = 0, scCh = window.innerHeight, scSt = window.scrollY || 0;
                var scMax = Math.max(0, (document.documentElement ? document.documentElement.scrollHeight : 0) - window.innerHeight);
                var contentTop = r.top + scSt;
                if (sc) {
                    var sr = sc.getBoundingClientRect();
                    scTop = sr.top; scCh = sc.clientHeight; scSt = sc.scrollTop;
                    scMax = Math.max(0, sc.scrollHeight - sc.clientHeight);
                    contentTop = r.top - sr.top + sc.scrollTop;
                }
                return 'OK:' + [r.x, r.y, r.width, r.height, contentTop, ct, cb, scTop, scCh, scSt, scMax].join(',') + (sc ? '|sc' : '|win');
            } catch(e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 記下捲動容器與位置（批次前一次），並暫時關掉平滑捲動，讓每次定位是「立刻到點」。 */
    internal fun segSaveJs(idx: Int): String = """
        (function() {
            try {
                var el = (window.__inkcropEls || [])[$idx];
                if (!el) return 'NONE';
                var sc = null, p = el.parentElement;
                while (p && p.nodeType === 1) {
                    var s = getComputedStyle(p);
                    if (/(auto|scroll|overlay)/.test(s.overflowY) && p.scrollHeight > p.clientHeight + 4) { sc = p; break; }
                    p = p.parentElement;
                }
                window.__inkcropSc = sc;
                window.__inkcropScTop = sc ? sc.scrollTop : null;
                window.__inkcropWinY = window.scrollY || 0;
                window.__inkcropSb = sc ? sc.style.scrollBehavior : null;
                if (sc) sc.style.scrollBehavior = 'auto';
                return 'OK';
            } catch(e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 把捲動容器移到指定 scrollTop（元素座標換算在 Kotlin 端做）。 */
    internal fun segScrollJs(idx: Int, target: Float): String = """
        (function() {
            try {
                var el = (window.__inkcropEls || [])[$idx];
                if (el && el.isConnected === false) return 'DETACHED';
                var sc = window.__inkcropSc || null;
                if (sc) { sc.style.scrollBehavior = 'auto'; sc.scrollTop = $target; return 'OK:' + Math.round(sc.scrollTop); }
                window.scrollTo(0, $target);
                return 'OK:' + Math.round(window.scrollY || 0);
            } catch(e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 還原捲動位置與平滑捲動設定（批次結束一律呼叫）。 */
    internal fun segRestoreJs(): String = """
        (function() {
            try {
                var sc = window.__inkcropSc || null;
                if (sc) {
                    if (window.__inkcropScTop != null) sc.scrollTop = window.__inkcropScTop;
                    try { sc.style.scrollBehavior = window.__inkcropSb == null ? '' : window.__inkcropSb; } catch(e) {}
                } else if (window.__inkcropWinY != null) {
                    window.scrollTo(0, window.__inkcropWinY);
                }
                window.__inkcropSc = null; window.__inkcropScTop = null;
                window.__inkcropWinY = null; window.__inkcropSb = null;
                return 'OK';
            } catch(e) { return 'ERR'; }
        })();
    """.trimIndent()

    /** 解析 [segInfoJs] 回傳。純函數，可單測。 */
    fun parseSeg(raw: String?): SegGeom? {
        val s0 = raw?.trim() ?: return null
        if (s0 == "null" || s0.isEmpty()) return null
        val s = s0.removeSurrounding("\"").replace("\\\"", "\"")
        if (!s.startsWith("OK:")) return null
        val body = s.removePrefix("OK:")
        val bar = body.indexOf('|')
        val numsPart = if (bar >= 0) body.substring(0, bar) else body
        val scroller = if (bar >= 0) body.substring(bar + 1).trim() else "?"
        val f = numsPart.split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (f.size < 11) return null
        if (f[2] <= 0f || f[3] <= 0f) return null
        return SegGeom(
            x = f[0], y = f[1], w = f[2], h = f[3], contentTop = f[4],
            bandTop = f[5], bandBottom = f[6],
            scTop = f[7], scClientH = f[8], scScrollTop = f[9], scMax = f[10],
            scroller = scroller
        )
    }

    private suspend fun segEval(webView: WebView, js: String, timeoutMs: Long, tag: String): String? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val ret = CompletableDeferred<String?>()
        try {
            webView.evaluateJavascript(js) { v -> ret.complete(v) }
        } catch (t: Throwable) {
            Log.w(TAG, "$tag eval failed: $t")
            return null
        }
        return withTimeoutOrNull(timeoutMs) { ret.await() } ?: run {
            Log.w(TAG, "$tag timeout")
            null
        }
    }

    suspend fun segInfo(webView: WebView, idx: Int, timeoutMs: Long = 1500): SegGeom? =
        parseSeg(segEval(webView, segInfoJs(idx), timeoutMs, "segInfo"))

    suspend fun segSave(webView: WebView, idx: Int) {
        segEval(webView, segSaveJs(idx), 1500, "segSave")
    }

    suspend fun segScroll(webView: WebView, idx: Int, target: Float) {
        segEval(webView, segScrollJs(idx, target), 1500, "segScroll")
    }

    fun segRestore(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            webView.evaluateJavascript(segRestoreJs(), null)
        } catch (t: Throwable) {
            Log.w(TAG, "segRestore failed: $t")
        }
    }

    /** 等捲動真的停：rect 與 scrollTop 連續兩次相同才放行（smooth scroll 會骗過單幀檢查）。 */
    private suspend fun settleSeg(webView: WebView, idx: Int, timeoutMs: Long = 1800): SegGeom? {
        val t0 = SystemClock.uptimeMillis()
        var prev: SegGeom? = null
        while (SystemClock.uptimeMillis() - t0 <= timeoutMs) {
            waitFrame(webView, 500)
            val cur = segInfo(webView, idx)
            if (cur != null && prev != null &&
                cur.y == prev.y && cur.h == prev.h && cur.scScrollTop == prev.scScrollTop
            ) return cur
            prev = cur
            delay(40)
        }
        return prev
    }

    /** 截「視窗內的某個 CSS 框」，回傳裁好的點陣圖（未正規化）。 */
    private suspend fun captureCss(
        activity: Activity,
        box: CssBox,
        scale: Float,
        loc: IntArray,
        winW: Int,
        winH: Int,
        areaLimit: Float = 0.8f
    ): Bitmap? {
        val rect = cropWindowRect(box, scale, loc[0], loc[1], winW, winH, 2, areaLimit) ?: run {
            Log.w(TAG, "seg capture rejected box=$box win=${winW}x$winH")
            return null
        }
        val full = captureWindow(activity) ?: return null
        val bmp = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
        full.recycle()
        return bmp
    }

    /** 正規化＋自檢＋存檔；空白圖回 null（呼叫方計失敗）。 */
    private fun saveCrop(activity: Activity, bmp: Bitmap): RenderedMath? {
        val norm = normalizeToWhite(bmp)
        val ink = inkRatio(norm)
        if (ink < 0.0005f) {
            Log.w(TAG, "seg chunk blank, dropped")
            norm.recycle()
            return null
        }
        val f = com.vic.inkflow.util.PdfManager.newAnnotationImageFile(activity, ".png")
        FileOutputStream(f).use { out -> norm.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val rm = RenderedMath(f.absolutePath, norm.width, norm.height)
        Log.d(TAG, "seg img ${norm.width}x${norm.height} ink=${"%.3f".format(ink)}")
        norm.recycle()
        return rm
    }

    /** 整行是否同一色（＝沒有字／線穿過），用來找切塊點，避免切斷文字行。 */
    private fun rowUniform(bmp: Bitmap, y: Int): Boolean {
        val w = bmp.width
        val first = bmp.getPixel(0, y)
        val r = (first shr 16) and 0xff
        val g = (first shr 8) and 0xff
        val b = first and 0xff
        var x = 0
        while (x < w) {
            if (!nearBg(bmp.getPixel(x, y), r, g, b, 6)) return false
            x += 2
        }
        return true
    }

    /**
     * 把累積的切片合成一張，找空白列切成「一塊」，剩下的帶到下一塊。
     * @return 下一塊的起始（元素本地 CSS y）
     */
    private fun flushChunk(
        activity: Activity,
        parts: MutableList<Pair<Float, Bitmap>>,
        chunkStart: Float,
        scale: Float,
        wPx: Int,
        out: MutableList<RenderedMath>,
        tail: Boolean,
        maxChunkCss: Float
    ): Float {
        if (parts.isEmpty()) return chunkStart
        val endCss = parts.maxOf { it.first + it.second.height / scale }
        val hPx = ((endCss - chunkStart) * scale).roundToInt().coerceIn(1, 20000)
        val full = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(full)
        for ((st, b) in parts) {
            cv.drawBitmap(b, 0f, (st - chunkStart) * scale, null)
            b.recycle()
        }
        parts.clear()
        val limitPx = (maxChunkCss * scale).roundToInt().coerceIn(16, hPx)
        var split = hPx
        if (!tail) {
            // 只在「不超過一頁」的範圍內找空白列：找不到就硬切在頁界，絕不讓圖超高被縮小。
            val floorY = (limitPx * 0.6f).toInt().coerceAtLeast(8)
            var y = minOf(hPx - 2, limitPx)
            while (y > floorY) {
                if (rowUniform(full, y)) { split = y; break }
                y--
            }
            if (split >= hPx) split = limitPx
        }
        if (split < 16) split = hPx
        if (split >= hPx) {
            saveCrop(activity, full)?.let { out.add(it) }
            return chunkStart + hPx / scale
        }
        val top = Bitmap.createBitmap(full, 0, 0, wPx, split)
        val carry = Bitmap.createBitmap(full, 0, split, wPx, hPx - split)
        full.recycle()
        saveCrop(activity, top)?.let { out.add(it) }
        val nextStart = chunkStart + split / scale
        parts.add(nextStart to carry)
        return nextStart
    }

    /**
     * ChatGPT 專用：把一個勾選塊完整拍成一張或多張圖（像素級，所見即所得）。
     *
     * 舊路徑 `cropFormula` 要求整個框一次塞進視窗，ChatGPT 的勾選塊動輒數千 px，
     * 永遠 `fit=false` → 整批退文字。這裡改成：
     *  1. 逐段把「還沒拍到的元素本地 y」對齊可見帶頂端；
     *  2. 每段只裁元素當下真正可見的那一條，按元素座標貼回大圖；
     *  3. 累積約一個螢幕高就用「整行同色」切塊（不切斷文字行），多的帶到下一塊。
     * 全程量測用捲動後實測 rect，不用收集當下的舊座標。
     *
     * @return 依序排好的圖（可能 0 張＝失敗，上層退文字）
     */
    suspend fun cropPickedBlock(
        activity: Activity,
        webView: WebView,
        idx: Int,
        fallbackBox: CssBox,
        tag: String,
        maxChunkCss: Float = 640f
    ): List<RenderedMath> {
        if (Looper.myLooper() != Looper.getMainLooper()) return emptyList()
        val out = mutableListOf<RenderedMath>()
        var hidden = 0
        var saved = false
        return try {
            @Suppress("DEPRECATION")
            val scale = webView.scale.takeIf { it > 0f }
                ?: webView.resources.displayMetrics.density
            val loc = IntArray(2)
            (webView as View).getLocationInWindow(loc)
            val decor = activity.window?.decorView ?: return emptyList()
            val winW = decor.width
            val winH = decor.height

            // 沒有節點索引（舊資料）：退成單張直裁，仍然受窗內檢查保護。
            if (idx < 0) {
                captureCss(activity, fallbackBox, scale, loc, winW, winH)?.let { bmp ->
                    saveCrop(activity, bmp)?.let { out.add(it) }
                }
                return out
            }

            segSave(webView, idx)
            saved = true
            val g = segInfo(webView, idx)
            if (g == null) {
                Log.w(TAG, "seg skipped no-element idx=$idx tag=$tag")
                return emptyList()
            }
            val bandH = g.bandBottom - g.bandTop
            Log.d(
                TAG,
                "seg start idx=$idx tag=$tag h=${g.h} y=${g.y} band=${g.bandTop}..${g.bandBottom} " +
                    "sc=${g.scroller} st=${g.scScrollTop}/${g.scMax} w=${g.w}"
            )
            if (bandH < 120f || g.w < 24f || g.h < 8f) {
                Log.w(TAG, "seg skipped geometry idx=$idx bandH=$bandH w=${g.w} h=${g.h}")
                return emptyList()
            }

            val covered0 = probe(webView, idx)?.covered == true
            if (!covered0 && g.h <= bandH - 6f &&
                g.y >= g.bandTop - 1f && g.y + g.h <= g.bandBottom + 1f
            ) {
                val bmp = captureCss(activity, CssBox(g.x, g.y, g.w, g.h), scale, loc, winW, winH)
                    ?: return emptyList()
                saveCrop(activity, bmp)?.let { out.add(it) }
                Log.d(TAG, "seg single idx=$idx h=${g.h} imgs=${out.size}")
                return out
            }

            val parts = mutableListOf<Pair<Float, Bitmap>>()
            var chunkStart = 0f
            var s = 0f
            var guard = 0
            val wPx = (g.w * scale).roundToInt().coerceAtLeast(1)
            while (s < g.h - 0.5f && guard++ < 240) {
                val target = g.contentTop + s - (g.bandTop - g.scTop + 6f)
                segScroll(webView, idx, target.coerceIn(0f, g.scMax))
                val m = settleSeg(webView, idx)
                if (m == null) {
                    Log.w(TAG, "seg settle lost idx=$idx s=$s")
                    break
                }
                if (kotlin.math.abs(m.h - g.h) > 2f) {
                    Log.w(TAG, "seg height drift idx=$idx ${g.h}->${m.h}, abort")
                    break
                }
                var mm = m
                val cov = hideOverlays(webView, idx)
                if (cov > 0) {
                    hidden += cov
                    mm = segInfo(webView, idx) ?: m
                }
                val e0 = maxOf(s, mm.bandTop - mm.y)
                val e1 = minOf(g.h, mm.bandBottom - mm.y)
                if (e1 <= e0 + 0.5f) {
                    Log.w(TAG, "seg stuck idx=$idx s=$s y=${mm.y} band=${mm.bandTop}..${mm.bandBottom}")
                    if (cov > 0) { restoreOverlays(webView); hidden = 0 }
                    break
                }
                val slice = captureCss(
                    activity, CssBox(mm.x, mm.y + e0, mm.w, e1 - e0),
                    scale, loc, winW, winH, areaLimit = 1.0f
                )
                if (cov > 0) { restoreOverlays(webView); hidden = 0 }
                if (slice == null) {
                    Log.w(
                        TAG,
                        "seg slice rejected idx=$idx e=$e0..$e1 y=${mm.y} " +
                            "band=${mm.bandTop}..${mm.bandBottom} cov=$cov"
                    )
                    break
                }
                parts.add(e0 to slice)
                s = e1
                Log.d(TAG, "seg slice idx=$idx e=$e0..$e1 px=${slice.width}x${slice.height} s=$s/${g.h}")
                if (s - chunkStart >= maxChunkCss || s >= g.h - 0.5f) {
                    chunkStart = flushChunk(
                        activity, parts, chunkStart, scale, wPx, out,
                        tail = s >= g.h - 0.5f,
                        maxChunkCss = maxChunkCss
                    )
                }
                if (out.size >= 16) {
                    Log.w(TAG, "seg chunk cap reached idx=$idx, rest dropped")
                    break
                }
            }
            if (parts.isNotEmpty()) flushChunk(activity, parts, chunkStart, scale, wPx, out, tail = true, maxChunkCss = maxChunkCss)
            Log.d(TAG, "seg done idx=$idx imgs=${out.size} covered=$s/${g.h}")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "cropPickedBlock failed: $t")
            out
        } finally {
            if (hidden > 0) restoreOverlays(webView)
            if (saved) segRestore(webView)
        }
    }
}
