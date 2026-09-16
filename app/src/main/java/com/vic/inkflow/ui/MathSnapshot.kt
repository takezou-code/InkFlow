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
 * M5：KaTeX 離線數學渲染 — 常駐隱藏 WebView（1px，掛 decorView）載入本地 katex，
 * 每個數學塊一次 evaluateJavascript 渲染，再量測＋截圖成 Bitmap。
 *
 * 全部方法只能在 Main thread 呼叫（WebView 要求）；非 Main 直接回 null（上層退回 Unicode）。
 * 內建自檢：全白/異常尺寸視為失敗回 null。
 */
object MathSnapshot {
    const val RENDER_W_PX = 1500
    private const val TAG = "MathSnapshot"
    private const val BASE = "file:///android_asset/katex/"

    private var wv: WebView? = null
    private var ready: Boolean = false
    private var reqId = 1L

    // 注意：Kotlin raw string 裡 $ 會被當模板 —— 這裡刻意不用 $ delimiter，
    // Kotlin 側已先把 $..$ 行內數學轉成 \(..\)，auto-render 只配 $$ / \( / \[ / 環境。
    private const val SHELL = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
<script src="auto-render.min.js"></script>
<style>
html,body{margin:0;padding:0;background:transparent;}
#root{color:#111111;font-size:46px;line-height:1.35;padding:8px 12px;word-wrap:break-word;background:transparent;}
.katex-display{margin:0.4em 0;}
</style>
<script>
function renderBlock(b64){
  try{
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for(var i=0;i<bin.length;i++) bytes[i]=bin.charCodeAt(i);
    var html = new TextDecoder('utf-8').decode(bytes);
    var root = document.getElementById('root');
    root.innerHTML = html;
    renderMathInElement(root,{
      delimiters:[
        {left:'$$',right:'$$',display:true},
        {left:'\\(',right:'\\)',display:false},
        {left:'\\[',right:'\\]',display:true},
        {left:'\\begin{equation}',right:'\\end{equation}',display:true},
        {left:'\\begin{align}',right:'\\end{align}',display:true},
        {left:'\\begin{gather}',right:'\\end{gather}',display:true}
      ],
      throwOnError:false
    });
    return 'OK:'+root.scrollHeight;
  }catch(e){ return 'ERR:'+e; }
}
</script>
</head><body><div id="root"></div></body></html>"""

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 確保隱藏 WebView 存在且載好 shell（Main only，可重複呼叫）。 */
    fun ensure(activity: Activity) {
        if (!isMain()) return
        val cur = wv
        if (cur != null && cur.parent != null) return
        try {
            (cur?.parent as? ViewGroup)?.removeView(cur)
        } catch (_: Exception) { }
        try {
            val w = WebView(activity)
            // 透明底：公式圖跟紙色走，深色主題不出白塊（白紙墊底在紙層負責）
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
            decor.addView(w, FrameLayout.LayoutParams(1, 1))
            wv = w
            ready = false
            w.loadDataWithBaseURL(BASE, SHELL, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            Log.w(TAG, "ensure failed: $t")
        }
    }

    /**
     * 渲染一段 HTML（含 $$ / \( / \[ delimiter）成 Bitmap（寬固定 [RENDER_W_PX]）。
     * @return Bitmap（呼叫方負責 recycle）或 null（失敗→上層退回 Unicode 文字）。
     */
    suspend fun render(htmlBody: String, timeoutMs: Long = 15000): Bitmap? {
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
            val b64 = Base64.encodeToString(htmlBody.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val jsRet = CompletableDeferred<String?>()
            w.evaluateJavascript("renderBlock('$b64')") { v -> jsRet.complete(v) }
            val ret = withTimeoutOrNull(timeoutMs) { jsRet.await() }
            if (ret == null || !ret.contains("OK")) {
                Log.w(TAG, "renderBlock bad ret=$ret")
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
            w.layout(0, 0, RENDER_W_PX, h)
            // 截圖＋雙向自檢：透明/白=空，近黑過半=硬體加速沒畫完，隔 400ms 重截一次
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
            w.postVisualStateCallback(reqId++, object : WebView.VisualStateCallback() {
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
}
