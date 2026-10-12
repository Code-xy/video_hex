package com.seagull.htv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 站点抓取层。全部基于实测 DOM：
 *   div.thumbnail
 *     a[href=video-<id>.htm]
 *     div.image[style=background-image:url('<thumb>')][title=<完整标题>]
 *       var.duration  span.hd-thumbnail
 *     div.caption.title h5 a     <- 标题
 *     div.info p a[href=user.htm?author=..]  <- 作者
 */
object Site {

    const val BASE = "https://hsex.tv/"

    const val UA = "Mozilla/5.0 (Linux, Android 14, Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 实测 4 条线路；shark-v6 是纯 IPv6，IPv4 网络下必失败 */
    val cdnHosts = listOf(
        "cdn.hdcdn.store",
        "shark.hdcdn.store",
        "fdc.hdcdn.store",
        "shark-v6.hdcdn.store"
    )

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val idRe = Regex("video-(\\d+)\\.htm")
    private val bgRe = Regex("url\\('([^']+)'\\)")

    fun fetch(pathOrUrl: String): Document {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl
        else BASE + pathOrUrl.removePrefix("/")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", BASE)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return Jsoup.parse(body, url)
        }
    }

    fun page(section: Section, page: Int): List<Video> = parseCards(fetch(section.urlOf(page)))

    /** 实测：search 的 &page= 无效，只有第一页 20 条 */
    fun search(q: String): List<Video> =
        parseCards(fetch("search.htm?search=" + URLEncoder.encode(q, "UTF-8") + "&sort=new"))

    fun parseCards(doc: Document): List<Video> {
        val out = ArrayList<Video>()
        for (el in doc.select("div.thumbnail")) {
            val a = el.selectFirst("div.caption.title h5 a")
                ?: el.selectFirst("a[href*='video-']")
                ?: continue
            val id = idRe.find(a.attr("href"))?.groupValues?.get(1) ?: continue

            val img = el.selectFirst("div.image")
            val thumb = bgRe.find(img?.attr("style").orEmpty())?.groupValues?.get(1)
                ?: "https://i.hdcdn.store/thumb/$id.webp"

            val title = a.text().trim().ifBlank { img?.attr("title").orEmpty().trim() }
            val dur = el.selectFirst("var.duration")?.text().orEmpty()
            val hd = el.selectFirst("span.hd-thumbnail") != null
            val author = el.selectFirst("div.info p a[href*='user.htm']")?.text().orEmpty()

            out.add(Video(id, title, thumb, dur, hd, author))
        }
        return out
    }

    /** 详情页的 m3u8 明写在 <source id="video-source"> 里，不需要逆向加密 */
    fun streamOf(id: String): String? {
        val doc = fetch("video-$id.htm")
        val direct = doc.selectFirst("source#video-source")?.attr("src").orEmpty()
        if (direct.isNotBlank()) return direct
        val m = Regex("""<source[^>]+id="video-source"[^>]+src="([^"]+)"""").find(doc.html())
        return m?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    fun titleOf(id: String): String =
        fetch("video-$id.htm").selectFirst("meta[property='og:title']")?.attr("content").orEmpty()

    /** 同一 m3u8 path 换 host = 切线路 */
    fun altLines(url: String): List<String> {
        val out = ArrayList<String>()
        out.add(url)
        try {
            val u = java.net.URL(url)
            for (h in cdnHosts) if (h != u.host) out.add("${u.protocol}://$h${u.file}")
        } catch (e: Exception) {
        }
        return out
    }
}
