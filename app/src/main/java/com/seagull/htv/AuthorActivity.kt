package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.seagull.htv.ui.CardMenu
import com.seagull.htv.ui.PlayerLauncher
import com.seagull.htv.ui.VideoAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 作者页：user-N.htm?author=X（20 卡/页，路径式分页） */
class AuthorActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var bar: ProgressBar
    private lateinit var head: TextView
    private lateinit var adapter: VideoAdapter

    private val pool = Executors.newFixedThreadPool(3)
    private val busy = AtomicBoolean(false)

    private var author: String = ""
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

    companion object { const val EXTRA_AUTHOR = "author" }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_author)

        list = findViewById(R.id.list)
        swipe = findViewById(R.id.swipe)
        bar = findViewById(R.id.bar)
        head = findViewById(R.id.head)

        author = s?.getString(EXTRA_AUTHOR) ?: intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
        head.text = "@" + author

        adapter = VideoAdapter(
            onClick = { openVideo(it) },
            onLongClick = { onCard(it) },
            onAuthorClick = { v -> if (v.author.isNotBlank() && v.author != author) openAuthor(v.author) }
        )
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.size() - 6) fetchNext()
            }
        })

        swipe.setColorSchemeColors(0xFF3DDC97.toInt())
        swipe.setOnRefreshListener {
            page = 0; reachedEnd = false; seen.clear()
            adapter.add(emptyList(), false)
            fetchNext()
        }

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.web).setOnClickListener {
            openWeb(Site.BASE + "user.htm?author=" + java.net.URLEncoder.encode(author, "UTF-8"))
        }

        fetchNext()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_AUTHOR, author)
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun fetchNext() {
        if (reachedEnd || busy.get() || author.isEmpty()) return
        busy.set(true)
        bar.visibility = View.VISIBLE
        val p = page + 1
        pool.execute {
            val res: List<Video>? = try { Site.byAuthor(author, p) } catch (e: Exception) { null }
            runOnUiThread {
                busy.set(false)
                bar.visibility = View.GONE
                swipe.isRefreshing = false
                if (res == null) { tip(getString(R.string.load_fail)); return@runOnUiThread }
                if (res.isEmpty()) {
                    reachedEnd = true
                    if (p > 1) tip(getString(R.string.no_more)) else tip(getString(R.string.no_result))
                    return@runOnUiThread
                }
                page = p
                val fresh = res.filter { seen.add(it.id) }
                if (fresh.isEmpty()) { reachedEnd = true; return@runOnUiThread }
                adapter.add(fresh, p > 1)
                head.text = "@" + author + "   " + getString(R.string.loaded_n, adapter.size())
            }
        }
    }

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
        startActivity(Intent(this, AuthorActivity::class.java).putExtra(EXTRA_AUTHOR, name))
    }

    private fun openWeb(url: String) {
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url))
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
