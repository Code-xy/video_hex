package com.seagull.htv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
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

    /**
     * 搜索。实测两条正交维度：
     *  1) 路径式分页 search-N.htm 有效（N=1~300 都返回 20 条，页间不重合）；
     *     而 query 参数形式的 &page= / &p= / &start= / &offset= 全部无效。
     *  2) sort 与页码正交：p1+hot 与 p2+hot 结果重合 0 → 换 sort 等于换一批。
     */
    fun search(q: String, page: Int, sort: SortOrder): List<Video> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val n = if (page < 1) 1 else page
        val path = if (n == 1) "search.htm?search=$enc&sort=${sort.key}"
                   else "search-$n.htm?search=$enc&sort=${sort.key}"
        return parseCards(fetch(path))
    }

    /** 首页挂着的 30 个热搜词，实测可抓 */
    fun hotWords(): List<String> {
        val doc = fetch(BASE)
        val out = LinkedHashSet<String>()
        for (a in doc.select("a[href*='search.htm?search=']")) {
            val m = Regex("search=([^&]+)").find(a.attr("href")) ?: continue
            val w = try {
                URLDecoder.decode(m.groupValues[1], "UTF-8")
            } catch (e: Exception) { continue }
            if (w.isNotBlank()) out.add(w)
        }
        return out.toList()
    }

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
        // 用普通转义字符串，不用三引号原始串：结尾连续 4 个引号在 Kotlin 里是有歧义的
        val m = Regex("<source[^>]+id=\"video-source\"[^>]+src=\"([^\"]+)\"").find(doc.html())
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

    /**
     * 实测只有 3 种真正不同的排序（两个关键词各跑一遍，结论一致）：
     *   new / hot / view 三者互相重合 0 条；
     *   而 like、comments、以及不传 sort 三者返回的结果**完全相同**，
     *   是无效参数，所以不放进 UI —— 放了只会让用户以为排序坏了。
     */
    enum class SortOrder(val label: String, val key: String) {
        NEW("最新", "new"),
        HOT("最热", "hot"),
        VIEW("最受欢迎", "view")
    }
}
