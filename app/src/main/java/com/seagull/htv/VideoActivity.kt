package com.seagull.htv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.seagull.htv.data.FavStore
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer

/**
 * 原生播放页。
 * 全屏：OrientationUtils 直接旋转 Activity（不是 GSY 的 dialog 全屏），
 *       并跟随设备旋转自动进出全屏。
 * 手势：GSY 自带（左半屏亮度 / 右半屏音量 / 双击暂停 / 横向滑进度 / 长按倍速），
 *       默认就是开的，这里显式再设一遍并首次给提示。
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var player: StandardGSYVideoPlayer
    private var orientationUtils: OrientationUtils? = null
    private lateinit var speedBtn: TextView
    private lateinit var favBtn: ImageButton

    private var lines: ArrayList<String> = arrayListOf()
    private var lineIdx = 0
    private var curUrl = ""
    private var title = ""
    private var videoId = ""
    private var thumb = ""
    private var duration = ""
    private var author = ""
    private var hd = false
    private val headers = HashMap<String, String>()

    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 2

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ALTS = "alts"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_POS = "pos"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_UA = "ua"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_DUR = "dur"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_HD = "hd"

        const val RES_POS = "res_pos"
        const val RES_URL = "res_url"
        const val RES_ID = "res_id"
        const val RES_TITLE = "res_title"

        private const val PREFS = "htv_prefs"
        private const val K_HINT = "gesture_hint_shown"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_video)

        player = findViewById(R.id.player)
        speedBtn = findViewById(R.id.btn_speed)
        favBtn = findViewById(R.id.btn_fav)
        player.keepScreenOn = true

        curUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "播放中" }
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        thumb = intent.getStringExtra(EXTRA_THUMB).orEmpty()
        duration = intent.getStringExtra(EXTRA_DUR).orEmpty()
        author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
        hd = intent.getBooleanExtra(EXTRA_HD, false)

        intent.getStringArrayListExtra(EXTRA_ALTS)?.let { lines = ArrayList(it) }
        if (lines.isEmpty() && curUrl.isNotEmpty()) lines.add(curUrl)
        if (lines.isNotEmpty()) lineIdx = lines.indexOf(curUrl).coerceAtLeast(0)

        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
        val ua = intent.getStringExtra(EXTRA_UA).orEmpty()
        // 实测 CDN 无防盗链，带头只为站点以后加保护时不用改代码
        if (referer.isNotEmpty()) headers["Referer"] = referer
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        if (ua.isNotEmpty()) headers["User-Agent"] = ua

        setupPlayer(intent.getLongExtra(EXTRA_POS, 0L))
        setupOrientation()
        setupOverlay()
        hintOnce()
    }

    // ───── 播放 ─────

    private fun setupPlayer(pos: Long) {
        player.setIsTouchWiget(true)        // 手势：左右半屏 亮度/音量
        player.setIsTouchWigetFull(true)    // 横屏全屏下同样启用
        player.setDismissControlTime(3000)
        player.setShowFullAnimation(true)
        player.setRotateViewAuto(false)
        player.setLockLand(false)

        player.setUp(curUrl, false, null, title)
        if (headers.isNotEmpty()) player.setMapHeadData(headers)
        player.startPlayLogic()
        if (pos > 0) runCatching { player.seekTo(pos) }   // GSY14 的 seekTo 收 Long

        player.getTitleTextView().text = title
        player.getTitleTextView().setOnLongClickListener { showMore(); true }
    }

    // ───── 全屏（旋转 Activity）─────

    private fun setupOrientation() {
        val ou = OrientationUtils(this, player)
        ou.setEnable(true)              // 跟随设备旋转自动进出全屏
        orientationUtils = ou

        player.getFullscreenButton()?.apply {
            visibility = View.VISIBLE    // 之前被我藏了，这里是"没全屏"的根因
            setOnClickListener { ou.resolveByClick() }
        }
        player.getBackButton()?.setOnClickListener { handleBack() }
    }

    private fun inLandscape(): Boolean = (orientationUtils?.getIsLand() ?: 0) > 0

    private fun handleBack() {
        if (inLandscape()) {
            orientationUtils?.backToProtVideo()
            return
        }
        finishWithResult()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    // ───── 叠加操作条 ─────

    private fun setupOverlay() {
        paintSpeed()
        speedBtn.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            applySpeed()
        }

        paintFav(FavStore.has(this, videoId))
        favBtn.setOnClickListener {
            if (videoId.isEmpty()) return@setOnClickListener
            val now = FavStore.toggle(this, currentVideo())
            paintFav(now)
            tip(getString(if (now) R.string.fav_added else R.string.fav_removed))
        }

        findViewById<ImageButton>(R.id.btn_lines).setOnClickListener { switchLine() }
    }

    private fun currentVideo() = Video(
        id = videoId, title = title, thumb = thumb,
        duration = duration, hd = hd, author = author
    )

    /** 1.0 -> "1.0×", 0.75 -> "0.75×", 1.5 -> "1.5×" */
    private fun fmtSpeed(f: Float): String {
        var t = "%.2f".format(f)
        while (t.endsWith("0")) t = t.dropLast(1)
        if (t.endsWith(".")) t += "0"
        return t + "×"
    }

    private fun paintSpeed() { speedBtn.text = fmtSpeed(speeds[speedIdx]) }

    private fun applySpeed() {
        val sp = speeds[speedIdx]
        runCatching { player.setSpeedPlaying(sp, false) }
        paintSpeed()
        tip(getString(R.string.speed_now, fmtSpeed(sp)))
    }

    private fun paintFav(on: Boolean) {
        favBtn.alpha = if (on) 1f else 0.45f
        favBtn.setColorFilter(if (on) 0xFF3DDC97.toInt() else 0xFFCCCCCC.toInt())
    }

    private fun switchLine() {
        if (lines.size <= 1) { tip(getString(R.string.only_one_line)); return }
        val at = player.currentPositionWhenPlaying
        lineIdx = (lineIdx + 1) % lines.size
        player.release()
        curUrl = lines[lineIdx]
        player.setUp(curUrl, false, null, title)
        if (headers.isNotEmpty()) player.setMapHeadData(headers)
        player.startPlayLogic()
        if (at > 0) runCatching { player.seekTo(at) }
        tip(getString(R.string.line_now, lineIdx + 1, lines.size))
    }

    private fun showMore() {
        val labels = arrayOf(
            getString(R.string.mn_lines),
            getString(R.string.mn_speed),
            getString(if (FavStore.has(this, videoId)) R.string.mn_unfav else R.string.mn_fav),
            getString(R.string.mn_web)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.mn_more)
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> switchLine()
                    1 -> applySpeed()
                    2 -> {
                        val now = FavStore.toggle(this, currentVideo())
                        paintFav(now)
                        tip(getString(if (now) R.string.fav_added else R.string.fav_removed))
                    }
                    3 -> startActivity(
                        Intent(this, WebActivity::class.java)
                            .putExtra(WebActivity.EXTRA_URL, Site.BASE + "video-" + videoId + ".htm")
                    )
                }
            }
            .show()
    }

    private fun hintOnce() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (sp.getBoolean(K_HINT, false)) return
        sp.edit().putBoolean(K_HINT, true).apply()
        Toast.makeText(this, R.string.gesture_hint, Toast.LENGTH_LONG).show()
    }

    // ───── 生命周期 / 回传 ─────

    private fun finishWithResult() {
        val i = Intent()
        i.putExtra(RES_POS, player.currentPositionWhenPlaying)
        i.putExtra(RES_URL, curUrl)
        i.putExtra(RES_ID, videoId)
        i.putExtra(RES_TITLE, title)
        setResult(RESULT_OK, i)
        finish()
    }

    override fun onPause() { super.onPause(); runCatching { player.onVideoPause() } }
    override fun onResume() { super.onResume(); runCatching { player.onVideoResume() } }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { orientationUtils?.releaseListener() }
        runCatching { player.release() }
    }

    private fun tip(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
