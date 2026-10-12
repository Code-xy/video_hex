package com.seagull.htv.data

data class Video(
    val id: String,
    val title: String,
    val thumb: String,
    val duration: String,
    val hd: Boolean,
    val author: String
)

/** 站点分区。实测所有 *_list-N.htm 都是 24 卡/页，index-N.htm 是 48 卡/页。 */
enum class Section(val label: String, private val tmpl: String) {
    RECOMMEND("推荐", "index-%d.htm"),
    NEWEST("最新", "list-%d.htm"),
    TOP("排行", "top_list-%d.htm"),
    TOP7("7日榜", "top7_list-%d.htm"),
    SHORT("5分钟", "5min_list-%d.htm"),
    LONG("长片", "long_list-%d.htm");

    fun urlOf(page: Int): String = tmpl.format(page)

    companion object {
        /** 底栏「排行」点开后可选的四榜 */
        val rankGroup = listOf(TOP, TOP7, SHORT, LONG)
    }
}
