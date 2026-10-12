package com.seagull.htv.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.seagull.htv.History
import com.seagull.htv.VideoActivity
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video
import java.util.concurrent.Executors

/** 抓详情页 m3u8 → 带 Cookie/Referer/线路列表拉起原生播放页 */
object PlayerLauncher {

    private val pool = Executors.newFixedThreadPool(2)

    fun open(
        act: Activity,
        v: Video,
        launcher: ActivityResultLauncher<Intent>,
        busy: (Boolean) -> Unit = {},
        onFail: (Video) -> Unit = {}
    ) {
        busy(true)
        pool.execute {
            val url = try { Site.streamOf(v.id) } catch (e: Exception) { null }
            act.runOnUiThread {
                busy(false)
                if (url.isNullOrBlank()) { onFail(v); return@runOnUiThread }
                val hist = History.pos(act, v.id)
                val i = Intent(act, VideoActivity::class.java).apply {
                    putExtra(VideoActivity.EXTRA_URL, url)
                    putStringArrayListExtra(VideoActivity.EXTRA_ALTS, ArrayList(Site.altLines(url)))
                    putExtra(VideoActivity.EXTRA_TITLE, v.title)
                    putExtra(VideoActivity.EXTRA_VIDEO_ID, v.id)
                    putExtra(VideoActivity.EXTRA_POS, if (hist > 5000) hist else 0L)
                    putExtra(VideoActivity.EXTRA_REFERER, Site.BASE + "video-${v.id}.htm")
                }
                launcher.launch(i)
            }
        }
    }
}
