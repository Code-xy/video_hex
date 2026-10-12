package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seagull.htv.data.Site
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer

/** 原生播放页：GSYVideoPlayer 全屏，长按标题换线路 */
class VideoActivity : AppCompatActivity() {

    private lateinit var player: StandardGSYVideoPlayer

    private var lines: ArrayList<String> = arrayListOf()
    private var lineIdx: Int = 0
    private var curUrl: String = ""
    private var title: String = ""
    private var videoId: String = ""
    private val headers = HashMap<String, String>()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ALTS = "alts"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_POS = "pos"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_UA = "ua"
        const val RES_POS = "res_pos"
        const val RES_URL = "res_url"
        const val RES_ID = "res_id"
        const val RES_TITLE = "res_title"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_video)

        player = findViewById(R.id.player)
        player.keepScreenOn = true

        curUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "播放中" }
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()

        intent.getStringArrayListExtra(EXTRA_ALTS)?.let { lines = ArrayList(it) }
        if (lines.isEmpty() && curUrl.isNotEmpty()) lines.add(curUrl)
        if (lines.isNotEmpty()) lineIdx = lines.indexOf(curUrl).coerceAtLeast(0)

        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
        val ua = intent.getStringExtra(EXTRA_UA).orEmpty()
        // 实测 CDN 无防盗链，带头只是为了站点以后加保护时不用改代码
        if (referer.isNotEmpty()) headers["Referer"] = referer
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        if (ua.isNotEmpty()) headers["User-Agent"] = ua

        play(curUrl, intent.getLongExtra(EXTRA_POS, 0L))

        player.getTitleTextView().text = title
        player.getBackButton().setOnClickListener { finishWithResult() }
        player.getTitleTextView().setOnLongClickListener { switchLine(); true }
        runCatching { player.fullscreenButton?.visibility = View.GONE }
    }

    private fun play(url: String, pos: Long) {
        curUrl = url
        player.setIsTouchWiget(true)
        player.setUp(url, false, null, title)
        if (headers.isNotEmpty()) player.setMapHeadData(headers)
        player.startPlayLogic()
        if (pos > 0) runCatching { player.seekTo(pos.toInt()) }
    }

    private fun switchLine() {
        if (lines.size <= 1) { tip("只有一条线路"); return }
        val at = player.currentPositionWhenPlaying
        lineIdx = (lineIdx + 1) % lines.size
        player.release()
        play(lines[lineIdx], at.toLong())
        tip("已切到线路 ${lineIdx + 1}/${lines.size}")
    }

    /** 退出时把播放位置传回去，用于断点续播 */
    private fun finishWithResult() {
        val i = Intent()
        i.putExtra(RES_POS, player.currentPositionWhenPlaying.toLong())
        i.putExtra(RES_URL, curUrl)
        i.putExtra(RES_ID, videoId)
        i.putExtra(RES_TITLE, title)
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
