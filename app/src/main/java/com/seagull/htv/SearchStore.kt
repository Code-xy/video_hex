package com.seagull.htv

import android.content.Context

/** 搜索历史，本地存，最多 30 条 */
object SearchStore {

    private const val SP = "htv_search"
    private const val KEY = "words"
    private const val MAX = 30

    fun add(ctx: Context, w: String) {
        val s = w.trim()
        if (s.isEmpty()) return
        val cur = list(ctx).toMutableList()
        cur.remove(s)
        cur.add(0, s)
        while (cur.size > MAX) cur.removeAt(cur.size - 1)
        sp(ctx).edit().putString(KEY, cur.joinToString("\u0001")).apply()
    }

    fun list(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\u0001").filter { it.isNotBlank() }
    }

    fun clear(ctx: Context) { sp(ctx).edit().remove(KEY).apply() }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
}
