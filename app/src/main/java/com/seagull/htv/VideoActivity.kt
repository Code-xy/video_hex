package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer

class VideoActivity : AppCompatActivity() {

    private lateinit var player: StandardGSYVideoPlayer

    private var lines: ArrayList<String> = arrayListOf()
    private var lineIdx: Int = 0
    private var curUrl: String = ""
    private var title: String = ""
    private val headers = HashMap<String, String>()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ALTS = "alts"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POS = "pos"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_UA = "ua"
        const val RES_POS = "res_pos"
        const val RES_URL = "res_url"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_video)

        player = findViewById(R.id.player)
        player.keepScreenOn = true

        curUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "播放中" }

        intent.getStringArrayListExtra(EXTRA_ALTS)?.let {
            lines = ArrayList(it)
        }
        if (lines.isEmpty()) lines.add(curUrl)
        lineIdx = lines.indexOf(curUrl).coerceAtLeast(0)

        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
        val ua = intent.getStringExtra(EXTRA_UA).orEmpty()
        if (referer.isNotEmpty()) headers["Referer"] = referer
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        if (ua.isNotEmpty()) headers["User-Agent"] = ua

        buildPlayer()

        player.getTitleTextView().text = title
        player.getBackButton().setOnClickListener { finishWithResult() }
        // 长按标题 = 换线路
        player.getTitleTextView().setOnLongClickListener {
            switchLine(); true
        }
        runCatching { player.fullscreenButton?.visibility = View.GONE }
    }

    /** 关键：Cookie/Referer/UA 必须带，否则 CDN 直接 403 */
    private fun buildPlayer() {
        player.setIsTouchWiget(true)
        player.setUp(curUrl, false, null, title)
        if (headers.isNotEmpty()) player.setMapHeadData(headers)
        player.startPlayLogic()

        val pos = intent.getLongExtra(EXTRA_POS, 0L)
        if (pos > 0) {
            runCatching { player.seekTo(pos.toInt()) }
        }
    }

    private fun switchLine() {
        if (lines.size <= 1) { tip("只有一条线路"); return }
        val at = player.currentPositionWhenPlaying
        lineIdx = (lineIdx + 1) % lines.size
        curUrl = lines[lineIdx]
        player.release()
        player.setUp(curUrl, false, null, title)
        if (headers.isNotEmpty()) player.setMapHeadData(headers)
        player.startPlayLogic()
        if (at > 0) runCatching { player.seekTo(at) }
        tip("已切到线路 ${lineIdx + 1}/${lines.size}")
    }

    private fun finishWithResult() {
        val i = Intent()
        i.putExtra(RES_POS, player.currentPositionWhenPlaying.toLong())
        i.putExtra(RES_URL, curUrl)
        setResult(RESULT_OK, i)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishWithResult()

    override fun onPause() { super.onPause(); player.onVideoPause() }
    override fun onResume() { super.onResume(); player.onVideoResume() }
    override fun onDestroy() { super.onDestroy(); runCatching { player.release() } }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
