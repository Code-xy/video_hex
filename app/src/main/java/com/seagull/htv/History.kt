package com.seagull.htv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 播放历史 + 断点续播，用 SharedPreferences 存，零额外依赖 */
object History {

    private const val SP = "htv_history"
    private const val KEY = "items"
    private const val MAX = 200

    data class Item(val id: String, val title: String, val pos: Long, val ts: Long)

    fun save(ctx: Context, id: String, title: String, pos: Long) {
        if (id.isEmpty()) return
        val arr = raw(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue          // 老的先删，新的追加到最后
            out.put(o)
        }
        out.put(JSONObject().apply {
            put("id", id)
            put("title", title)
            put("pos", pos)
            put("ts", System.currentTimeMillis())
        })
        val trimmed = JSONArray()
        val from = (out.length() - MAX).coerceAtLeast(0)
        for (i in from until out.length()) trimmed.put(out.get(i))
        sp(ctx).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun pos(ctx: Context, id: String): Long {
        val arr = raw(ctx)
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optLong("pos", 0L)
        }
        return 0L
    }

    /** 新的在前 */
    fun list(ctx: Context): List<Item> {
        val arr = raw(ctx)
        val out = ArrayList<Item>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out.add(Item(id, o.optString("title"), o.optLong("pos", 0L), o.optLong("ts", 0L)))
        }
        return out
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun raw(ctx: Context): JSONArray = try {
        JSONArray(sp(ctx).getString(KEY, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }
}
