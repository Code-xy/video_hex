package com.seagull.htv

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var bar: ProgressBar

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: android.webkit.ValueCallback<Array<Uri>>? = null

    private val pool = Executors.newFixedThreadPool(4)
    private var lastPageUrl: String = ""
    private var pageTitle: String = ""

    /** 站点实测的 4 条线路 */
    private val cdnHosts = listOf(
        "cdn.hdcdn.store",
        "shark.hdcdn.store",
        "fdc.hdcdn.store",
        "shark-v6.hdcdn.store"
    )

    /** 弹窗/统计/广告域，直接拦掉 */
    private val adDomains = listOf(
        "go.tscprts.com", "go.whitetrafsa.com", "smartpop",
        "91porn.cyou", "mc.yandex.ru", "googletagmanager.com",
        "google-analytics.com", "cloudflareinsights.com",
        "doubleclick.net", "popads.net", "propellerads.com",
        "onclickads.net", "exoclick.com", "adsterra"
    )

    private val mediaRe = Regex("\\.(m3u8|mp4|flv|ts|mkv|mov|webm|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE)

    // ───────── 结果回调 ─────────

    private val playerResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data ?: return@registerForActivityResult
        val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
        val url = d.getStringExtra(VideoActivity.RES_URL).orEmpty()
        if (url.isNotEmpty()) {
            web.evaluateJavascript("window.__sgResume(${JSONObject.quote(url)},$pos)", null)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCb ?: return@registerForActivityResult
        fileCb = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    // ───────── JS 桥 ─────────

    inner class Bridge {
        @JavascriptInterface
        fun play(json: String) = runOnUiThread { handlePlay(json) }

        @JavascriptInterface
        fun toast(msg: String) = runOnUiThread { tip(msg) }

        @JavascriptInterface
        fun log(msg: String) { /* 预留 */ }
    }

    // ───────── 生命周期 ─────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        root = findViewById(R.id.root)
        bar = findViewById(R.id.bar)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)          // 广告新窗口全拦
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " HtvApp/1.0"
        }
        web.setBackgroundColor(0xFF0E0E12.toInt())
        web.addJavascriptInterface(Bridge(), "Seagull")
        WebView.setWebContentsDebuggingEnabled(true)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        installInjector()
        web.webViewClient = client
        web.webChromeClient = chrome
        setupDownloader()

        if (s != null) web.restoreState(s) else web.loadUrl(getString(R.string.start_url))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { web.onResume(); super.onResume() }
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { web.webChromeClient?.onHideCustomView(); return }
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ───────── 注入 ─────────

    private fun installInjector() {
        val js = readAsset("inject.js")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))
            } catch (e: Exception) {
                web.evaluateJavascript(js, null)
            }
        }
    }

    private fun readAsset(name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "" }

    // ───────── WebViewClient ─────────

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            val s = u.toString()
            if (isAd(s)) return true
            if (u.scheme == "http" || u.scheme == "https") return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, u)); true
            } catch (e: Exception) { true }
        }

        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
            val s = req.url.toString()
            if (isAd(s)) {
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                    emptyMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) {
            lastPageUrl = url.orEmpty()
            bar.visibility = View.VISIBLE
        }

        override fun onPageFinished(v: WebView, url: String?) {
            bar.visibility = View.GONE
            lastPageUrl = url.orEmpty()
            pageTitle = v.title.orEmpty()
            // 老 WebView 走这里补注入
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                web.evaluateJavascript(readAsset("inject.js"), null)
            }
        }
    }

    // ───────── WebChromeClient ─────────

    private val chrome = object : WebChromeClient() {

        override fun onProgressChanged(v: WebView, p: Int) {
            bar.progress = p
            bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(v: WebView, t: String?) { pageTitle = t.orEmpty() }

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
            customViewCallbackClear()
            web.visibility = View.VISIBLE
        }

        override fun onShowFileChooser(
            v: WebView,
            cb: android.webkit.ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try {
                filePicker.launch(params.createIntent()); true
            } catch (e: Exception) {
                fileCb = null; false
            }
        }
    }

    private fun customViewCallbackClear() {
        customCb?.onCustomViewHidden()
        customCb = null
    }

    // ───────── 下载 ─────────

    private fun setupDownloader() {
        web.setDownloadListener { url, ua, disposition, mime, len ->
            try {
                if (!URLUtil.guessFileName(url, disposition, mime).lowercase().let {
                        it.endsWith(".mp4") || it.endsWith(".m3u8") || it.endsWith(".ts")
                    }) return@setDownloadListener
                val cm = CookieManager.getInstance()
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", ua)
                req.addRequestHeader("Cookie", cm.getCookie(url).orEmpty())
                req.addRequestHeader("Referer", lastPageUrl)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, disposition, mime))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                tip("已开始下载")
            } catch (e: Exception) { tip("下载失败: ${e.message}") }
        }
    }

    // ───────── 核心：抓到的地址交给原生播放器 ─────────

    private fun handlePlay(json: String) {
        val url: String
        val title: String
        val pos: Long
        try {
            val o = JSONObject(json)
            url = o.optString("url")
            title = o.optString("title").ifEmpty { pageTitle }
            pos = o.optLong("pos", 0L)
        } catch (e: Exception) { tip("解析失败"); return }

        if (url.isEmpty()) { tip("没抓到视频地址"); return }

        tip("正在探测线路…")
        pool.execute {
            val alts = buildAlts(url)
            val pick = probeFirst(alts) ?: url
            runOnUiThread { launch(pick, alts, title, pos) }
        }
    }

    /** 同一个 path 换成其它线路 host —— 站点有 4 条线，主站挂了自动切 */
    private fun buildAlts(url: String): List<String> {
        val out = ArrayList<String>()
        out.add(url)
        try {
            val u = URL(url)
            for (h in cdnHosts) if (h != u.host) out.add("${u.protocol}://$h${u.file}")
        } catch (e: Exception) {}
        return out
    }

    /** 逐个探活，返回第一个能通的 */
    private fun probeFirst(urls: List<String>): String? {
        for (u in urls) {
            var c: HttpURLConnection? = null
            try {
                c = (URL(u).openConnection() as HttpURLConnection)
                c.requestMethod = "GET"
                c.setRequestProperty("Range", "bytes=0-0")
                c.setRequestProperty("Referer", "https://hsex.tv/")
                c.setRequestProperty("User-Agent", web.settings.userAgentString)
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.instanceFollowRedirects = true
                val code = c.responseCode
                if (code in 200..399) return u
            } catch (e: Exception) {
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
        return null
    }

    private fun launch(url: String, alts: List<String>, title: String, pos: Long) {
        val i = Intent(this, VideoActivity::class.java)
        i.putExtra(VideoActivity.EXTRA_URL, url)
        i.putStringArrayListExtra(VideoActivity.EXTRA_ALTS, ArrayList(alts))
        i.putExtra(VideoActivity.EXTRA_TITLE, title)
        i.putExtra(VideoActivity.EXTRA_POS, pos)
        i.putExtra(VideoActivity.EXTRA_REFERER, lastPageUrl.ifEmpty { getString(R.string.start_url) })
        i.putExtra(VideoActivity.EXTRA_COOKIE, CookieManager.getInstance().getCookie(url).orEmpty())
        i.putExtra(VideoActivity.EXTRA_UA, web.settings.userAgentString)
        playerResult.launch(i)
    }

    // ───────── 工具 ─────────

    private fun isAd(s: String): Boolean {
        val l = s.lowercase()
        return adDomains.any { l.contains(it) }
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
