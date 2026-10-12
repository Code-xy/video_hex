package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.seagull.htv.data.ListCache
import com.seagull.htv.data.Section
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.seagull.htv.ui.VideoAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 方案 B 主入口：原生列表。
 * 切分区 / 从播放器或搜索页返回 都不重新联网 —— 靠 ListCache + 滚动位置记忆。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var bar: ProgressBar
    private lateinit var adapter: VideoAdapter

    private val pool = Executors.newFixedThreadPool(3)
    private val busy = AtomicBoolean(false)

    private var section: Section = Section.RECOMMEND
    private var keyword: String = ""
    private var page: Int = 1
    private var reachedEnd: Boolean = false
    private var opening: Boolean = false
    private var currentKey: String = ""

    private val playerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data ?: return@registerForActivityResult
            val id = d.getStringExtra(VideoActivity.RES_ID).orEmpty()
            val title = d.getStringExtra(VideoActivity.RES_TITLE).orEmpty()
            val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
            if (id.isNotEmpty() && pos > 3000) History.save(this, id, title, pos)
        }

    private companion object {
        const val K_SECTION = "k_section"
        const val K_KEYWORD = "k_keyword"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        swipe = findViewById(R.id.swipe)
        bar = findViewById(R.id.bar)

        adapter = VideoAdapter(
            onClick = { openVideo(it) },
            onLongClick = { openWeb(Site.BASE + "video-${it.id}.htm") },
            onAuthorClick = { v -> if (v.author.isNotBlank()) openAuthor(v.author) }
        )
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null

        swipe.setColorSchemeColors(0xFF3DDC97.toInt())
        swipe.setOnRefreshListener {
            // 下拉刷新 = 明确要求重新拉，丢掉这一格的缓存
            ListCache.drop(currentKey)
            reload()
        }

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.size() - 6) loadNext()
            }
        })

        findViewById<ImageButton>(R.id.nav_recommend).setOnClickListener { switchSection(Section.RECOMMEND) }
        findViewById<ImageButton>(R.id.nav_newest).setOnClickListener { switchSection(Section.NEWEST) }
        findViewById<ImageButton>(R.id.nav_rank).setOnClickListener { pickRank() }
        findViewById<ImageButton>(R.id.nav_search).setOnClickListener { openSearch() }
        findViewById<ImageButton>(R.id.nav_web).setOnClickListener { openWeb(Site.BASE) }

        // 进程被回收后回到原来的分区（列表本身重新拉一次第 1 页）
        if (s != null) {
            runCatching { section = Section.valueOf(s.getString(K_SECTION) ?: Section.RECOMMEND.name) }
            keyword = s.getString(K_KEYWORD).orEmpty()
        }
        restoreOrReload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(K_SECTION, section.name)
        outState.putString(K_KEYWORD, keyword)
    }

    override fun onPause() {
        stash()
        super.onPause()
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    // ───── 缓存：切分区不重载 ─────

    private fun firstVisible(): Int {
        val lm = list.layoutManager as? GridLayoutManager ?: return 0
        return lm.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    /** 把当前分区的列表 + 页码 + 滚动位置存进内存缓存 */
    private fun stash() {
        if (currentKey.isEmpty() || adapter.size() == 0) return
        ListCache.put(
            currentKey,
            ListCache.Entry(adapter.items(), page, reachedEnd, firstVisible())
        )
    }

    private fun restoreOrReload() {
        currentKey = ListCache.key(section, keyword)
        val e = ListCache.get(currentKey)
        if (e != null && e.items.isNotEmpty()) {
            adapter.add(e.items, false)
            page = e.page
            reachedEnd = e.reachedEnd
            bar.visibility = View.GONE
            swipe.isRefreshing = false
            list.scrollToPosition(e.scroll)
            return
        }
        reload()
    }

    // ───── 分区切换 ─────

    private fun switchSection(sec: Section) {
        if (sec == section && keyword.isEmpty()) {
            list.scrollToPosition(0)
            return
        }
        stash()
        section = sec
        keyword = ""
        tip(sec.label)
        restoreOrReload()
    }

    private fun pickRank() {
        val group = Section.rankGroup
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_rank)
            .setItems(group.map { it.label }.toTypedArray()) { _, i -> switchSection(group[i]) }
            .show()
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    // ───── 加载 ─────

    private fun reload() {
        page = 1
        reachedEnd = false
        adapter.add(emptyList(), false)
        fetchPage(1, false)
    }

    private fun loadNext() {
        if (reachedEnd || busy.get() || keyword.isNotEmpty()) return
        fetchPage(page + 1, true)
    }

    private fun fetchPage(p: Int, append: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        bar.visibility = View.VISIBLE
        val kw = keyword
        val sec = section
        val key = currentKey
        pool.execute {
            val res: List<Video>? = try {
                if (kw.isNotEmpty()) Site.search(kw, p, Site.SortOrder.NEW) else Site.page(sec, p)
            } catch (e: Exception) { null }
            runOnUiThread {
                busy.set(false)
                bar.visibility = View.GONE
                swipe.isRefreshing = false
                if (res == null) { tip(getString(R.string.load_fail)); return@runOnUiThread }
                if (res.isEmpty()) {
                    reachedEnd = true
                    if (append) tip(getString(R.string.no_more))
                    return@runOnUiThread
                }
                page = p
                adapter.add(res, append)
                // 每次拿到数据就更新缓存，返回时直接命中
                ListCache.put(key, ListCache.Entry(adapter.items(), page, reachedEnd, firstVisible()))
            }
        }
    }

    // ───── 进播放 / 作者 / 网页 ─────

    private fun openVideo(v: Video) {
        if (opening) return
        opening = true
        bar.visibility = View.VISIBLE
        pool.execute {
            val url = try { Site.streamOf(v.id) } catch (e: Exception) { null }
            runOnUiThread {
                opening = false
                bar.visibility = View.GONE
                if (url.isNullOrBlank()) {
                    tip(getString(R.string.parse_fail))
                    openWeb(Site.BASE + "video-${v.id}.htm")
                    return@runOnUiThread
                }
                val hist = History.pos(this, v.id)
                val i = Intent(this, VideoActivity::class.java)
                i.putExtra(VideoActivity.EXTRA_URL, url)
                i.putStringArrayListExtra(VideoActivity.EXTRA_ALTS, ArrayList(Site.altLines(url)))
                i.putExtra(VideoActivity.EXTRA_TITLE, v.title)
                i.putExtra(VideoActivity.EXTRA_VIDEO_ID, v.id)
                i.putExtra(VideoActivity.EXTRA_POS, if (hist > 5000) hist else 0L)
                i.putExtra(VideoActivity.EXTRA_REFERER, Site.BASE + "video-${v.id}.htm")
                playerResult.launch(i)
            }
        }
    }

    private fun openAuthor(name: String) {
        startActivity(Intent(this, AuthorActivity::class.java).putExtra(AuthorActivity.EXTRA_AUTHOR, name))
    }

    private fun openWeb(url: String) {
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url))
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
