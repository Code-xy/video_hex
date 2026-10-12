package com.seagull.htv

import android.content.Context
import com.seagull.htv.data.Video
import org.json.JSONArray
import org.json.JSONObject

/** 播放历史：存完整 Video + 播放位置，支持断点续播和进度条显示 */
object History {

    private const val SP = "htv_history"
    private const val KEY = "items"
    private const val MAX = 300

    data class Item(val video: Video, val pos: Long, val ts: Long)

    /** 内存缓存，避免列表每 bind 一次就读一遍 SharedPreferences */
    @Volatile private var cache: Map<String, Long>? = null

    fun save(ctx: Context, v: Video, pos: Long) {
        if (v.id.isEmpty()) return
        val arr = raw(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == v.id) continue
            out.put(o)
        }
        out.put(JSONObject().apply {
            put("id", v.id)
            put("title", v.title)
            put("thumb", v.thumb)
            put("dur", v.duration)
            put("hd", v.hd)
            put("author", v.author)
            put("authorUrl", v.authorUrl)
            put("pos", pos)
            put("ts", System.currentTimeMillis())
        })
        val from = (out.length() - MAX).coerceAtLeast(0)
        if (from > 0) {
            val t = JSONArray()
            for (i in from until out.length()) t.put(out.get(i))
            saveJson(ctx, t)
        } else {
            saveJson(ctx, out)
        }
        cache = null
    }

    /** 断点位置（毫秒）。命中内存缓存，供列表画进度条用。 */
    fun pos(ctx: Context, id: String): Long {
        val m = cache ?: loadPosMap(ctx).also { cache = it }
        return m[id] ?: 0L
    }

    private fun loadPosMap(ctx: Context): Map<String, Long> {
        val arr = raw(ctx)
        val m = HashMap<String, Long>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotEmpty()) m[id] = o.optLong("pos", 0L)
        }
        return m
    }

    /** 新的在前 */
    fun list(ctx: Context): List<Item> {
        val arr = raw(ctx)
        val out = ArrayList<Item>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id").isEmpty()) continue
            out.add(
                Item(
                    Video(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        thumb = o.optString("thumb"),
                        duration = o.optString("dur"),
                        hd = o.optBoolean("hd", false),
                        author = o.optString("author"),
                        authorUrl = o.optString("authorUrl")
                    ),
                    o.optLong("pos", 0L),
                    o.optLong("ts", 0L)
                )
            )
        }
        return out
    }

    fun remove(ctx: Context, id: String) {
        val arr = raw(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue
            out.put(o)
        }
        saveJson(ctx, out)
        cache = null
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
        cache = null
    }

    /** "28:24" / "1:02:33" -> 毫秒，用于算进度条比例 */
    fun durationMs(d: String): Long {
        val p = d.trim().split(":")
        return try {
            when (p.size) {
                2 -> (p[0].toLong() * 60 + p[1].toLong()) * 1000
                3 -> ((p[0].toLong() * 60 + p[1].toLong()) * 60 + p[2].toLong()) * 1000
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun saveJson(ctx: Context, arr: JSONArray) {
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
        cache = null
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun raw(ctx: Context): JSONArray = try {
        JSONArray(sp(ctx).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }
}
