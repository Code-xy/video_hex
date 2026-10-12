package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.seagull.htv.data.Section
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.seagull.htv.ui.VideoAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 方案 B：原生列表。
 * 列表/搜索用 OkHttp + Jsoup 直接解析站点 HTML，播放交原生 GSY，
 * WebView 只在解析失败或用户主动点「网页」时兜底。
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
    private var openingDetail: Boolean = false

    private val playerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data ?: return@registerForActivityResult
            val id = d.getStringExtra(VideoActivity.RES_ID).orEmpty()
            val title = d.getStringExtra(VideoActivity.RES_TITLE).orEmpty()
            val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
            if (id.isNotEmpty() && pos > 3000) History.save(this, id, title, pos)
        }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        swipe = findViewById(R.id.swipe)
        bar = findViewById(R.id.bar)

        adapter = VideoAdapter(onClick = { openVideo(it) }, onLongClick = { openWeb(detailUrl(it.id)) })
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null

        swipe.setColorSchemeColors(0xFF3DDC97.toInt())
        swipe.setOnRefreshListener { reload() }

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

        reload()
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    // ───── 分区切换 ─────

    private fun switchSection(sec: Section) {
        section = sec
        keyword = ""
        tip(sec.label)
        reload()
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
        pool.execute {
            val res: List<Video>? = try {
                if (kw.isNotEmpty()) Site.search(kw) else Site.page(sec, p)
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
                // 搜索只有第一页（实测 &page= 无效）
                if (kw.isNotEmpty()) reachedEnd = true
            }
        }
    }

    // ───── 进播放 ─────

    private fun openVideo(v: Video) {
        if (openingDetail) return
        openingDetail = true
        bar.visibility = View.VISIBLE
        pool.execute {
            val url = try { Site.streamOf(v.id) } catch (e: Exception) { null }
            runOnUiThread {
                openingDetail = false
                bar.visibility = View.GONE
                if (url.isNullOrBlank()) {
                    tip(getString(R.string.parse_fail))
                    openWeb(detailUrl(v.id))
                    return@runOnUiThread
                }
                val hist = History.pos(this, v.id)
                val i = Intent(this, VideoActivity::class.java)
                i.putExtra(VideoActivity.EXTRA_URL, url)
                i.putStringArrayListExtra(VideoActivity.EXTRA_ALTS, ArrayList(Site.altLines(url)))
                i.putExtra(VideoActivity.EXTRA_TITLE, v.title)
                i.putExtra(VideoActivity.EXTRA_VIDEO_ID, v.id)
                i.putExtra(VideoActivity.EXTRA_POS, if (hist > 5000) hist else 0L)
                i.putExtra(VideoActivity.EXTRA_REFERER, detailUrl(v.id))
                playerResult.launch(i)
            }
        }
    }

    // ───── 网页兜底 ─────

    private fun detailUrl(id: String) = Site.BASE + "video-$id.htm"

    private fun openWeb(url: String) {
        val i = Intent(this, WebActivity::class.java)
        i.putExtra(WebActivity.EXTRA_URL, url)
        startActivity(i)
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
