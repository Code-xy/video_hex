package com.seagull.htv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.seagull.htv.ui.CardMenu
import com.seagull.htv.ui.PlayerLauncher
import com.seagull.htv.ui.VideoAdapter
import com.seagull.htv.ui.WordAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 独立搜索页。
 * 站点限制：单次搜索恒定 20 条。绕法（实测）：
 *   - 路径式分页 search-N.htm 有效（N 到 300+），query 形式的 &page= 无效
 *   - sort 与页码正交，换 sort 等于换一批结果
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var et: EditText
    private lateinit var wordsRv: RecyclerView
    private lateinit var resultRv: RecyclerView
    private lateinit var sortBar: LinearLayout
    private lateinit var bar: ProgressBar
    private lateinit var label: TextView
    private lateinit var wordAdapter: WordAdapter
    private lateinit var videoAdapter: VideoAdapter

    private val pool = Executors.newFixedThreadPool(3)
    private val busy = AtomicBoolean(false)

    private var query: String = ""
    private var sort: Site.SortOrder = Site.SortOrder.NEW
    private var page: Int = 0
    private var reachedEnd: Boolean = false
    private var lastVideo: Video? = null
    private val seen = HashSet<String>()

    private val playerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data ?: return@registerForActivityResult
            val id = d.getStringExtra(VideoActivity.RES_ID).orEmpty()
            val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
            val v = lastVideo
            if (v != null && id.isNotEmpty() && pos > 3000) History.save(this, v, pos)
        }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_search)

        et = findViewById(R.id.et)
        wordsRv = findViewById(R.id.words)
        resultRv = findViewById(R.id.results)
        sortBar = findViewById(R.id.sortBar)
        bar = findViewById(R.id.bar)
        label = findViewById(R.id.label)

        wordAdapter = WordAdapter { w ->
            et.setText(w)
            et.setSelection(w.length)
            runSearch(w)
        }
        wordsRv.layoutManager = GridLayoutManager(this, 3)
        wordsRv.adapter = wordAdapter

        videoAdapter = VideoAdapter(
            onClick = { openVideo(it) },
            onLongClick = { onCard(it) },
            onAuthorClick = { v -> if (v.author.isNotBlank()) openAuthor(v.author) }
        )
        resultRv.layoutManager = GridLayoutManager(this, 2)
        resultRv.adapter = videoAdapter
        resultRv.setHasFixedSize(true)
        resultRv.itemAnimator = null
        resultRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= videoAdapter.size() - 6) fetchNext()
            }
        })

        buildSortBar()

        findViewById<ImageButton>(R.id.go).setOnClickListener { runSearch(et.text.toString()) }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(et.text.toString()); true
            } else false
        }

        showWords()
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    // ───── 热词 / 历史 ─────

    private fun showWords() {
        val hist = SearchStore.list(this)
        wordAdapter.submit(hist)
        label.text = getString(R.string.hist_and_hot)
        wordsRv.visibility = View.VISIBLE
        resultRv.visibility = View.GONE
        sortBar.visibility = View.GONE

        pool.execute {
            val hot = try { Site.hotWords() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (query.isEmpty() && hot.isNotEmpty()) {
                    val merged = LinkedHashSet<String>()
                    merged.addAll(hist)
                    merged.addAll(hot)
                    wordAdapter.submit(merged.toList())
                }
            }
        }
    }

    // ───── 排序栏 ─────

    private fun buildSortBar() {
        sortBar.removeAllViews()
        for (o in Site.SortOrder.values()) {
            val tv = TextView(this)
            tv.text = o.label
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setPadding(20, 0, 20, 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            tv.layoutParams = lp
            tv.setOnClickListener { switchSort(o) }
            sortBar.addView(tv)
        }
        paintSortBar()
    }

    private fun paintSortBar() {
        for (i in 0 until sortBar.childCount) {
            val tv = sortBar.getChildAt(i) as TextView
            val on = Site.SortOrder.values()[i] == sort
            tv.setTextColor(if (on) 0xFF3DDC97.toInt() else 0x99FFFFFF.toInt())
        }
    }

    private fun switchSort(o: Site.SortOrder) {
        if (o == sort) return
        sort = o
        paintSortBar()
        if (query.isNotEmpty()) {
            page = 0; reachedEnd = false; seen.clear()
            videoAdapter.add(emptyList(), false)
            fetchNext()
        }
    }

    // ───── 搜索 ─────

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        query = q
        SearchStore.add(this, q)
        hideKeyboard()
        et.clearFocus()

        sort = Site.SortOrder.NEW
        paintSortBar()
        sortBar.visibility = View.VISIBLE
        wordsRv.visibility = View.GONE
        resultRv.visibility = View.VISIBLE
        label.text = "「$q」"

        page = 0
        reachedEnd = false
        seen.clear()
        videoAdapter.add(emptyList(), false)
        fetchNext()
    }

    private fun fetchNext() {
        if (reachedEnd || busy.get() || query.isEmpty()) return
        busy.set(true)
        bar.visibility = View.VISIBLE
        val q = query
        val p = page + 1
        val srt = sort
        pool.execute {
            val res: List<Video>? = try { Site.search(q, p, srt) } catch (e: Exception) { null }
            runOnUiThread {
                busy.set(false)
                bar.visibility = View.GONE
                if (res == null) { tip(getString(R.string.load_fail)); return@runOnUiThread }
                if (res.isEmpty()) {
                    // 站点对无结果的词直接返回 HTTP 404（body 为空），这里统一按空列表处理
                    reachedEnd = true
                    tip(getString(if (p == 1) R.string.no_result else R.string.no_more))
                    return@runOnUiThread
                }
                page = p
                val fresh = res.filter { seen.add(it.id) }   // 跨页去重
                if (fresh.isEmpty()) { reachedEnd = true; return@runOnUiThread }
                videoAdapter.add(fresh, p > 1)
            }
        }
    }

    // ───── 播放 / 网页 ─────

    private fun openVideo(v: Video) {
        lastVideo = v
        PlayerLauncher.open(this, v, playerResult, busy = ::setBusy, onFail = ::onPlayFail)
    }

    private fun onCard(v: Video) {
        CardMenu.show(this, v, onPlay = { openVideo(v) })
    }

    private fun onPlayFail(v: Video) {
        tip(getString(R.string.parse_fail))
        CardMenu.openWeb(this, v.id)
    }

    private fun setBusy(b: Boolean) { bar.visibility = if (b) View.VISIBLE else View.GONE }

    private fun openAuthor(name: String) {
        startActivity(Intent(this, AuthorActivity::class.java).putExtra(AuthorActivity.EXTRA_AUTHOR, name))
    }

    private fun openWeb(url: String) {
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url))
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(et.windowToken, 0)
        } catch (e: Exception) {}
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
