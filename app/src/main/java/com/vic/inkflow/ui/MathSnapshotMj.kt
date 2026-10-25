package com.vic.inkflow.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.view.View.MeasureSpec

/**
 * M5：MathJax 離線數學渲染（ChatGPT 專用）。
 *
 * 與 [MathSnapshot]（KaTeX）並存，**只供 ChatGPT 路徑使用**；Gemini 仍走 KaTeX。
 * MathJax 原生支援 \boxed / cases / align 等 KaTeX 本版不實作的命令。
 *
 * 全部方法只能在 Main thread 呼叫；非 Main 直接回 null（上層退回 Unicode）。
 */
object MathSnapshotMj {
    const val RENDER_W_PX = 1500
    private const val TAG = "MathSnapshotMj"
    private const val BASE = "file:///android_asset/mathjax/"
    private const val POLL_INTERVAL_MS = 100L
    private const val POLL_TIMEOUT_MS = 15000L

    private var wv: WebView? = null
    private var ready: Boolean = false

    private const val SHELL = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<script>
window.MathJax = {
  tex: { inlineMath: [['$','$'],['\\(','\\)']], displayMath: [['$$','$$'],['\\[','\\]']] },
  svg: { fontCache: 'global' },
  startup: { typeset: false }
};
</script>
<script src="tex-svg.js"></script>
<style>
html,body{margin:0;padding:0;background:transparent;}
#root{color:#111111;font-size:20px;line-height:1.35;padding:8px 12px;word-wrap:break-word;background:transparent;}
mjx-container{margin:0.4em 0;}
</style>
</head><body><div id="root"></div>
<script>
window.__renderDone = null;
function renderBlock(b64){
  try{
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for(var i=0;i<bin.length;i++) bytes[i]=bin.charCodeAt(i);
    var html = new TextDecoder('utf-8').decode(bytes);
    var root = document.getElementById('root');
    root.innerHTML = html;
    window.__renderDone = false;
    MathJax.typesetPromise([root]).then(function(){
      window.__renderDone = true;
    }).catch(function(e){
      window.__renderDone = 'ERR:'+e;
    });
    return 'PENDING';
  }catch(e){ return 'ERR:'+e; }
}
function isDone(){ return window.__renderDone; }
</script>
</body></html>"""

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun ensure(activity: Activity) {
        if (!isMain()) return
        val cur = wv
        if (cur != null && cur.parent != null) return
        try {
            (cur?.parent as? ViewGroup)?.removeView(cur)
        } catch (_: Exception) { }
        try {
            val w = WebView(activity)
            w.setBackgroundColor(Color.TRANSPARENT)
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = true
            w.settings.textZoom = 100
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    ready = true
                }
            }
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.addView(w, FrameLayout.LayoutParams(RENDER_W_PX, 1))
            w.translationX = -(RENDER_W_PX + 400f)
            wv = w
            ready = false
            w.loadDataWithBaseURL(BASE, SHELL, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            Log.w(TAG, "ensure failed: $t")
        }
    }

    suspend fun render(htmlBody: String, timeoutMs: Long = 20000): Bitmap? {
        if (!isMain()) {
            Log.w(TAG, "render off-main, fallback")
            return null
        }
        val w = wv ?: return null.also { Log.w(TAG, "no webview") }
        val waited = withTimeoutOrNull(10000) {
            while (!ready) delay(100)
        }
        if (waited == null) {
            Log.w(TAG, "shell not ready")
            return null
        }
        return try {
            val t0 = SystemClock.uptimeMillis()
            // reset done flag
            w.evaluateJavascript("window.__renderDone = null;", null)
            val b64 = Base64.encodeToString(htmlBody.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val ret = w.evaluateJavascriptSync("renderBlock('$b64')")
            if (ret == null || !ret.contains("PENDING")) {
                // maybe immediate error
                if (ret != null && ret.contains("ERR")) Log.w(TAG, "renderBlock bad ret=$ret")
                // still try polling
            }
            // poll isDone
            var polled = 0L
            var done: String? = null
            while (polled < POLL_TIMEOUT_MS) {
                val d = withTimeoutOrNull(1000) {
                    val deferred = CompletableDeferred<String?>()
                    w.evaluateJavascript("isDone()") { v -> deferred.complete(v) }
                    deferred.await()
                }
                done = d?.trim()?.removeSurrounding("\"")
                if (done == "true" || (done != null && done.startsWith("ERR"))) break
                delay(POLL_INTERVAL_MS)
                polled += POLL_INTERVAL_MS
            }
            if (done != "true") {
                Log.w(TAG, "renderBlock not done: $done")
                return null
            }
            w.measure(
                MeasureSpec.makeMeasureSpec(RENDER_W_PX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val h = w.measuredHeight.coerceIn(1, 8000)
            if (h <= 1) {
                Log.w(TAG, "zero height")
                return null
            }
            val lp = w.layoutParams
            if (lp != null && (lp.width != RENDER_W_PX || lp.height != h)) {
                lp.width = RENDER_W_PX
                lp.height = h
                w.layoutParams = lp
                w.requestLayout()
                (w.parent as? ViewGroup)?.requestLayout()
                delay(80)
            }
            w.layout(0, 0, RENDER_W_PX, h)
            repeat(2) { attempt ->
                val bmp = captureOnce(w, h) ?: return@repeat
                var sampled = 0
                var ink = 0
                var black = 0
                var y = 0
                while (y < bmp.height) {
                    var x = 0
                    while (x < bmp.width) {
                        val p = bmp.getPixel(x, y)
                        sampled++
                        val a = (p ushr 24) and 0xff
                        val r = (p shr 16) and 0xff
                        val g = (p shr 8) and 0xff
                        val b = p and 0xff
                        if (a >= 128) {
                            if (r < 240 || g < 240 || b < 240) ink++
                            if (r < 24 && g < 24 && b < 24) black++
                        }
                        x += 16
                    }
                    y += 16
                }
                val inkRatio = if (sampled > 0) ink.toFloat() / sampled else 0f
                val blackRatio = if (sampled > 0) black.toFloat() / sampled else 0f
                Log.d(TAG, "capture #$attempt ${bmp.width}x${bmp.height} ink=${"%.3f".format(inkRatio)} black=${"%.3f".format(blackRatio)} ms=${SystemClock.uptimeMillis() - t0}")
                if (inkRatio >= 0.0005f && blackRatio <= 0.5f) return bmp
                bmp.recycle()
                Log.w(TAG, "bad capture (blank or black), retry=$attempt")
                delay(400)
            }
            Log.w(TAG, "capture failed, fallback")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "render exception: $t")
            null
        }
    }

    private suspend fun captureOnce(w: WebView, h: Int): Bitmap? {
        val done = CompletableDeferred<Bitmap?>()
        try {
            w.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    try {
                        val bmp = Bitmap.createBitmap(RENDER_W_PX, h, Bitmap.Config.ARGB_8888)
                        (w as View).draw(Canvas(bmp))
                        done.complete(bmp)
                    } catch (t: Throwable) {
                        done.complete(null)
                    }
                }
            })
        } catch (t: Throwable) {
            done.complete(null)
        }
        return withTimeoutOrNull(5000) { done.await() }
    }

    // WebView.evaluateJavascript is async (callback). Provide a sync-ish helper.
    private fun WebView.evaluateJavascriptSync(js: String): String? {
        val deferred = CompletableDeferred<String?>()
        try {
            this.evaluateJavascript(js) { v -> deferred.complete(v) }
        } catch (e: Exception) {
            deferred.complete(null)
        }
        // block briefly (called on main thread already); use runBlocking? Avoid. Use polling.
        var waited = 0L
        while (waited < 5000 && !deferred.isCompleted) {
            Thread.sleep(10)
            waited += 10
        }
        return if (deferred.isCompleted) runCatching { deferred.getCompleted() }.getOrNull() else null
    }
}
