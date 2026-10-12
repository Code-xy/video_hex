package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seagull.htv.data.FavStore
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.seagull.htv.ui.CardMenu
import com.seagull.htv.ui.PlayerLauncher
import com.seagull.htv.ui.VideoAdapter

/** 「我的」：播放历史 + 收藏 */
class MineActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var bar: ProgressBar
    private lateinit var empty: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabFav: TextView
    private lateinit var adapter: VideoAdapter

    private var showFav = false
    private var lastVideo: Video? = null

    private val playerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data ?: return@registerForActivityResult
            val id = d.getStringExtra(VideoActivity.RES_ID).orEmpty()
            val t = d.getStringExtra(VideoActivity.RES_TITLE).orEmpty()
            val pos = d.getLongExtra(VideoActivity.RES_POS, 0L)
            val v = lastVideo
            if (v != null && id.isNotEmpty() && pos > 3000) History.save(this, v, pos)
            regenerate()
        }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_mine)

        list = findViewById(R.id.list)
        bar = findViewById(R.id.bar)
        empty = findViewById(R.id.empty)
        tabHistory = findViewById(R.id.tab_history)
        tabFav = findViewById(R.id.tab_fav)

        showFav = s?.getBoolean("showFav", false) ?: false

        adapter = VideoAdapter(
            onClick = { PlayerLauncher.open(this, it, playerResult, busy = ::setBusy, onFail = ::onPlayFail) },
            onLongClick = { onCard(it) },
            onAuthorClick = { v ->
                if (v.author.isNotBlank()) {
                    startActivity(Intent(this, AuthorActivity::class.java)
                        .putExtra(AuthorActivity.EXTRA_AUTHOR, v.author))
                }
            }
        )
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null

        tabHistory.setOnClickListener { switchTab(false) }
        tabFav.setOnClickListener { switchTab(true) }

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.web).setOnClickListener {
            startActivity(Intent(this, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_URL, Site.BASE))
        }
        // 清空按钮借用返回图标位置不合适，改成点标题清空 —— 这里用长按标题清空
        findViewById<ImageButton>(R.id.clear).setOnClickListener { confirmClear() }

        switchTab(showFav)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("showFav", showFav)
    }

    private fun switchTab(fav: Boolean) {
        showFav = fav
        paintTabs()
        regenerate()
    }

    private fun paintTabs() {
        tabHistory.setTextColor(if (!showFav) 0xFF3DDC97.toInt() else 0x99FFFFFF.toInt())
        tabFav.setTextColor(if (showFav) 0xFF3DDC97.toInt() else 0x99FFFFFF.toInt())
    }

    /** 重新读一遍数据（进播放器回来、删除、切换 tab 都走这里） */
    private fun regenerate() {
        val items = if (showFav) FavStore.list(this) else History.list(this).map { it.video }
        adapter.add(items, false)
        empty.text = getString(if (showFav) R.string.empty_fav else R.string.empty_history)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onCard(v: Video) {
        CardMenu.show(this, v, onPlay = {
            lastVideo = v
            PlayerLauncher.open(this, v, playerResult, busy = ::setBusy, onFail = ::onPlayFail)
        }, onRemove = {
            if (showFav) FavStore.remove(this, v.id) else History.remove(this, v.id)
            regenerate()
            tip(getString(R.string.mn_remove_hist))
        })
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (showFav) FavStore.clear(this) else History.clear(this)
                regenerate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onPlayFail(v: Video) {
        tip(getString(R.string.parse_fail))
        CardMenu.openWeb(this, v.id)
    }

    private fun setBusy(b: Boolean) { bar.visibility = if (b) View.VISIBLE else View.GONE }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
