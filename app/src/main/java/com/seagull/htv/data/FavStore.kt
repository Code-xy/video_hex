package com.seagull.htv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 收藏，SharedPreferences 存 JSON。跟播放历史分开，互不影响。 */
object FavStore {

    private const val SP = "htv_fav"
    private const val KEY = "items"
    private const val MAX = 500

    /** @return true = 现在处于"已收藏"状态 */
    fun toggle(ctx: Context, v: Video): Boolean {
        val cur = raw(ctx)
        var removed = false
        val out = JSONArray()
        for (i in 0 until cur.length()) {
            val o = cur.optJSONObject(i) ?: continue
            if (o.optString("id") == v.id) { removed = true; continue }
            out.put(o)
        }
        if (!removed) out.put(toJson(v))

        // 超上限就丢最老的（数组前面是最老）
        if (out.length() > MAX) {
            val trimmed = JSONArray()
            for (i in (out.length() - MAX) until out.length()) trimmed.put(out.get(i))
            save(ctx, trimmed)
        } else {
            save(ctx, out)
        }
        return !removed
    }

    fun has(ctx: Context, id: String): Boolean {
        val cur = raw(ctx)
        for (i in 0 until cur.length()) {
            if (cur.optJSONObject(i)?.optString("id") == id) return true
        }
        return false
    }

    /** 新的在前 */
    fun list(ctx: Context): List<Video> {
        val cur = raw(ctx)
        val out = ArrayList<Video>(cur.length())
        for (i in cur.length() - 1 downTo 0) out.add(fromJson(cur.optJSONObject(i) ?: continue))
        return out
    }

    fun remove(ctx: Context, id: String) {
        val cur = raw(ctx)
        val out = JSONArray()
        for (i in 0 until cur.length()) {
            val o = cur.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue
            out.put(o)
        }
        save(ctx, out)
    }

    fun clear(ctx: Context) { sp(ctx).edit().remove(KEY).apply() }

    private fun toJson(v: Video) = JSONObject().apply {
        put("id", v.id); put("title", v.title); put("thumb", v.thumb)
        put("dur", v.duration); put("hd", v.hd); put("author", v.author)
        put("authorUrl", v.authorUrl); put("ts", System.currentTimeMillis())
    }

    private fun fromJson(o: JSONObject) = Video(
        id = o.optString("id"),
        title = o.optString("title"),
        thumb = o.optString("thumb"),
        duration = o.optString("dur"),
        hd = o.optBoolean("hd", false),
        author = o.optString("author"),
        authorUrl = o.optString("authorUrl")
    )

    private fun save(ctx: Context, arr: JSONArray): Boolean {
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
        return true
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun raw(ctx: Context): JSONArray = try {
        JSONArray(sp(ctx).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }
}
