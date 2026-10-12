package com.seagull.htv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** 网页兜底：解析失败 / 用户主动点「网页」/ 需要登录态时用 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var bar: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pageUrl: String = ""
    private var playTitle: String = ""
    private var playUrl: String = ""
    private val jsSource: String by lazy { readAsset("inject.js") }

    private val pool = Executors.newFixedThreadPool(2)

    private val adDomains = listOf(
        "go.tscprts.com", "go.whitetrafsa.com", "smartpop",
        "91porn.cyou", "mc.yandex.ru", "googletagmanager.com",
        "google-analytics.com", "cloudflareinsights.com",
        "doubleclick.net", "popads.net", "propellerads.com",
        "onclickads.net", "exoclick.com", "adsterra"
    )

    private val playerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data ?: return@registerForActivityResult
            val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
            val url = d.getStringExtra(VideoActivity.RES_URL).orEmpty()
            if (url.isNotEmpty()) {
                web.evaluateJavascript("window.__sgResume(${JSONObject.quote(url)},$pos)", null)
            }
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = fileCb ?: return@registerForActivityResult
            fileCb = null
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        }

    inner class Bridge {
        @JavascriptInterface
        fun play(json: String) = runOnUiThread { handlePlay(json) }

        @JavascriptInterface
        fun toast(msg: String) = runOnUiThread { tip(msg) }

        @JavascriptInterface
        fun log(msg: String) {}
    }

    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_web)
        web = findViewById(R.id.web)
        root = findViewById(R.id.root)
        bar = findViewById(R.id.bar)
        swipe = findViewById(R.id.swipe)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " HtvApp/1.0"
        }
        web.setBackgroundColor(0xFF0E0E12.toInt())
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.addJavascriptInterface(Bridge(), "Seagull")
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        swipe.setColorSchemeColors(0xFF3DDC97.toInt())
        swipe.setOnRefreshListener { web.reload() }

        // 不探测特性：直接试，不支持就靠 onPageFinished 兜底。
        // inject.js 开头有 if (window.__SG__) return; 所以重复注入是幂等的。
        runCatching { WebViewCompat.addDocumentStartJavaScript(web, jsSource, setOf("*")) }

        web.webViewClient = client
        web.webChromeClient = chrome

        val target = intent.getStringExtra(EXTRA_URL) ?: getString(R.string.start_url)
        if (s != null) web.restoreState(s) else web.loadUrl(target)
    }

    private fun readAsset(n: String) = try {
        assets.open(n).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "" }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            if (isAd(u.toString())) return true
            if (u.scheme == "http" || u.scheme == "https") return false
            return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { true }
        }

        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (isAd(req.url.toString())) {
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                    emptyMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) {
            pageUrl = url.orEmpty()
            bar.visibility = View.VISIBLE
        }

        override fun onPageFinished(v: WebView, url: String?) {
            bar.visibility = View.GONE
            swipe.isRefreshing = false
            pageUrl = url.orEmpty()
            playTitle = v.title.orEmpty()
            web.evaluateJavascript(jsSource, null)   // 幂等，不怕和上面的重复
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            bar.progress = p
            bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(v: WebView, t: String?) { playTitle = t.orEmpty() }

        override fun onShowCustomView(view: View, cb: CustomViewCallback) {
            if (customView != null) { cb.onCustomViewHidden(); return }
            customView = view
            customCb = cb
            view.setBackgroundColor(0xFF000000.toInt())
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            web.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCb?.onCustomViewHidden()
            customCb = null
            web.visibility = View.VISIBLE
        }

        override fun onShowFileChooser(
            v: WebView,
            cb: android.webkit.ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try { filePicker.launch(params.createIntent()); true }
            catch (e: Exception) { fileCb = null; false }
        }
    }

    private fun handlePlay(json: String) {
        val url: String
        val title: String
        val pos: Long
        try {
            val o = JSONObject(json)
            url = o.optString("url")
            title = o.optString("title").ifEmpty { playTitle }
            pos = o.optLong("pos", 0L)
        } catch (e: Exception) { tip("解析失败"); return }
        if (url.isEmpty()) { tip("没抓到播放地址"); return }

        playUrl = url
        pool.execute {
            val alts = ArrayList(com.seagull.htv.data.Site.altLines(url))
            runOnUiThread {
                val i = Intent(this, VideoActivity::class.java)
                i.putExtra(VideoActivity.EXTRA_URL, url)
                i.putStringArrayListExtra(VideoActivity.EXTRA_ALTS, alts)
                i.putExtra(VideoActivity.EXTRA_TITLE, title)
                i.putExtra(VideoActivity.EXTRA_POS, pos)
                i.putExtra(VideoActivity.EXTRA_REFERER, pageUrl)
                i.putExtra(VideoActivity.EXTRA_COOKIE, CookieManager.getInstance().getCookie(url).orEmpty())
                i.putExtra(VideoActivity.EXTRA_UA, web.settings.userAgentString)
                playerResult.launch(i)
            }
        }
    }

    private fun isAd(s: String): Boolean {
        val l = s.lowercase()
        return adDomains.any { l.contains(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(out) }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { web.onResume(); super.onResume() }
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { web.webChromeClient?.onHideCustomView(); return }
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
