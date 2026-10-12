package com.seagull.htv.data

/**
 * 内存列表缓存：让"切分区再切回来"和"从播放器返回"都不重新联网。
 * 进程被回收后失效，此时靠 onSaveInstanceState 存的分区/关键词回到原位（重新拉第 1 页）。
 */
object ListCache {

    class Entry(
        val items: List<Video>,
        val page: Int,
        val reachedEnd: Boolean,
        val scroll: Int
    )

    private val map = HashMap<String, Entry>()

    fun key(section: Section, keyword: String): String =
        if (keyword.isEmpty()) "s:" + section.name else "q:" + keyword

    fun get(k: String): Entry? = map[k]

    fun put(k: String, e: Entry) {
        // 只留最近 12 个，防止长会话内存膨胀
        if (map.size >= 12 && !map.containsKey(k)) {
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
        map[k] = e
    }

    fun drop(k: String) { map.remove(k) }

    fun clear() { map.clear() }
}
