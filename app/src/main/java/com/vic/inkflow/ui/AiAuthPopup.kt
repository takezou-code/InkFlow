package com.vic.inkflow.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 站方用 `window.open()` 開的登入視窗（Google／Apple／Microsoft OAuth 幾乎都是這招）。
 *
 * **為什麼非有不可**：WebView 預設不支援多重視窗（`setSupportMultipleWindows(false)`），
 * `window.open()` 會被當成「就在目前這個 WebView 開」。於是 OAuth 回呼頁
 * （例如 Kimi 的 `/google-callback#id_token=…`）直接蓋掉主頁，而它的
 * `window.opener` 是 null → 站方的 `opener.postMessage(...)` 直接 TypeError
 * （logcat 實測：`Cannot read properties of null (reading 'postMessage')`），
 * 權杖永遠交不回主頁 → 登入沒完成、畫面一片空白，而且這個壞掉的網址還被
 * 記成「這份文件上次待的地方」，下次進來繼續空白。
 *
 * 這裡把子視窗做成一個獨立 Dialog：主頁留在原地當 opener，回呼頁
 * postMessage 給主頁後自己 `window.close()`，登入才走得完。
 *
 * 為什麼用 Dialog 而不是塞進 Compose 佈局：AndroidView 只能回傳單一 View，
 * 要疊子視窗就得先把根換成 FrameLayout 再自己管理測量。Dialog 是 Android 的
 * 原生視窗，尺寸／生命週期都現成，也不會干擾主面板的測量。
 */
class AiAuthPopup(
    private val context: Context,
    private val provider: AiProvider,
    private val userAgent: String,
    /** 關掉（不論誰關的）之後回呼：登入可能剛完成，呼叫端可以順手量一次。 */
    private val onClosed: () -> Unit
) {
    private var dialog: Dialog? = null
    private var webView: WebView? = null

    /** 標題列文字（站方的 `<title>`，讓使用者知道現在在哪一頁）。 */
    private lateinit var titleView: TextView

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 建立並顯示子視窗；回傳的 WebView 必須交給 `WebViewTransport`，
     * 由系統把 `window.open()` 的內容載進去。
     */
    fun show(): WebView? {
        if (dialog?.isShowing == true) return webView

        titleView = TextView(context).apply {
            text = "登入／外部視窗"
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), 0)
        }
        // 站方自己 window.close() 會走 onCloseWindow；這顆是給「站方不關」的情況。
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { close() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(28, 28, 32))
            addView(
                titleView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(
                closeBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val web = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                // 與主面板同一個 UA：Google OAuth 會擋帶 `wv` 字樣的 WebView UA，
                // 換一個 UA 等於換一個「瀏覽器」，登入會被判成可疑裝置。
                userAgentString = userAgent
                // 面板是一條窄欄，用窄版佈局才不會把登入頁縮成螞蟻字。
                useWideViewPort = false
                loadWithOverviewMode = false
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
            }
            // Cookie 與主面板共用同一個 store：登入完成後主頁立刻看得到 session。
            // 第三方 cookie 一定要收：Google 的登入是在 accounts.google.com 這個
            // 「第三方」網域上完成，拒收等於登入永遠不會成立。
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(Color.WHITE)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean = false // OAuth 中轉一律留在子視窗裡

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AiDiag.log(context, provider, "POPUP_PAGE", "url=${AiDiag.url(url)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    // 站方回呼頁處理完會 window.close()：這是「登入完成」的正常訊號。
                    AiDiag.log(context, provider, "POPUP_CLOSE", "by=page")
                    close()
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val t = title?.trim().orEmpty()
                    if (t.isNotEmpty() && ::titleView.isInitialized) titleView.text = t
                }
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
            addView(
                web,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        // 全螢幕 Dialog 會蓋到狀態列／導覽列；用 insets 讓標題列與內容不被切掉。
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val d = Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar).apply {
            setContentView(root)
            setOnDismissListener {
                web.stopLoading()
                web.destroy()
                if (this@AiAuthPopup.webView === web) webView = null
                if (this@AiAuthPopup.dialog === this) dialog = null
                AiDiag.log(context, provider, "POPUP_CLOSED", "")
                onClosed()
            }
            window?.apply {
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                // 不讓子視窗自己變暗：登入頁整片都是內容，變暗只是難讀。
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        d.show()
        dialog = d
        webView = web
        return web
    }

    /** 關掉子視窗（使用者按 ✕、站方 window.close()、或面板被換掉時）。 */
    fun close() {
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
    }
}
