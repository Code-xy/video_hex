package com.seagull.htv.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.seagull.htv.R
import com.seagull.htv.WebActivity
import com.seagull.htv.data.FavStore
import com.seagull.htv.data.Site
import com.seagull.htv.data.Video

/** 长按卡片的操作菜单。收藏的入口就在这里（GSY 标题栏不好塞自定义按钮）。 */
object CardMenu {

    fun show(
        act: Activity,
        v: Video,
        onPlay: () -> Unit,
        onRemove: (() -> Unit)? = null
    ) {
        val fav = FavStore.has(act, v.id)

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(act.getString(R.string.mn_play));      actions.add { onPlay() }
        labels.add(act.getString(if (fav) R.string.mn_unfav else R.string.mn_fav)); actions.add {
            val now = FavStore.toggle(act, v)
            Toast.makeText(
                act,
                act.getString(if (now) R.string.fav_added else R.string.fav_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
        if (onRemove != null) {
            labels.add(act.getString(R.string.mn_remove_hist)); actions.add { onRemove() }
        }
        labels.add(act.getString(R.string.mn_web));       actions.add { openWeb(act, v.id) }
        labels.add(act.getString(R.string.mn_copy));      actions.add { copy(act, v.id) }

        AlertDialog.Builder(act)
            .setTitle(v.title)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    fun openWeb(act: Activity, id: String) {
        act.startActivity(
            Intent(act, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_URL, Site.BASE + "video-" + id + ".htm")
        )
    }

    private fun copy(act: Activity, id: String) {
        val url = Site.BASE + "video-" + id + ".htm"
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(act, act.getString(R.string.copied) + ": " + url, Toast.LENGTH_SHORT).show()
    }
}
