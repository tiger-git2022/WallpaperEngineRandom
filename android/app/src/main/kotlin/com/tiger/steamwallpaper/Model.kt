package com.tiger.steamwallpaper

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 一张附加预览：图片（url）或 YouTube 视频（youtube）。 */
data class Preview(val url: String, val youtube: String)

/** 一个创意工坊壁纸，字段和电脑版 workshop.html 的 norm() 一样。 */
data class Item(
    val id: String,
    val title: String,
    val creator: String,
    val created: Long,
    val updated: Long,
    val preview: String,
    val previews: List<Preview>,
    val tags: List<String>,
    val short: String,
    /** 完整简介；查询结果里没有（null），点「展开全文」或打开详情时再读。 */
    val description: String?,
    val subs: Long,
    val fav: Long,
    val size: Long,
    val votesUp: Long?,
    val votesDown: Long?,
    val app: Int,
) {
    val type: String get() = TYPE.firstOrNull { it.first in tags }?.first ?: ""
    val age: String get() = AGE.firstOrNull { it.first in tags }?.first ?: ""
    val resolution: String get() = tags.firstOrNull { RES_TAG.containsMatchIn(it) } ?: ""
    val images: List<String> get() = previews.filter { it.url.isNotEmpty() }.map { it.url }
    val videos: List<String> get() = previews.filter { it.youtube.isNotEmpty() }.map { it.youtube }
    val isWallpaperEngine: Boolean get() = app == 431960

    /** 新读到的字段合并进旧的：空的列表、null 不覆盖已有的。 */
    fun merge(n: Item): Item = n.copy(
        previews = if (n.previews.isEmpty()) previews else n.previews,
        tags = if (n.tags.isEmpty()) tags else n.tags,
        short = n.short.ifEmpty { short },
        description = n.description ?: description,
        votesUp = n.votesUp ?: votesUp,
        votesDown = n.votesDown ?: votesDown,
        preview = n.preview.ifEmpty { preview },
    )

    companion object {
        fun parse(f: JSONObject): Item {
            val previews = mutableListOf<Preview>()
            f.optJSONArray("previews")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    when (p.optInt("preview_type", -1)) {
                        0 -> p.optString("url").takeIf { it.isNotEmpty() }?.let { previews += Preview(it, "") }
                        1 -> p.optString("youtubevideoid").takeIf { it.isNotEmpty() }?.let { previews += Preview("", it) }
                    }
                }
            }
            val tags = mutableListOf<String>()
            f.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotEmpty() }?.let { tags += it }
            }
            val votes = f.optJSONObject("vote_data")
            val app = f.optString("consumer_appid").ifEmpty { f.optString("consumer_app_id") }.toIntOrNull() ?: 431960
            return Item(
                id = f.optString("publishedfileid"),
                title = f.optString("title").ifEmpty { "(无标题)" },
                creator = f.optString("creator"),
                created = f.optString("time_created").toLongOrNull() ?: 0,
                updated = f.optString("time_updated").toLongOrNull() ?: 0,
                preview = f.optString("preview_url"),
                previews = previews,
                tags = tags,
                short = f.optString("short_description"),
                description = if (f.has("description")) f.optString("description") else null,
                subs = f.optString("subscriptions").toLongOrNull() ?: 0,
                fav = f.optString("favorited").toLongOrNull() ?: 0,
                size = f.optString("file_size").toLongOrNull() ?: 0,
                votesUp = votes?.optLong("votes_up"),
                votesDown = votes?.optLong("votes_down"),
                app = app,
            )
        }

        /** Steam 返回的 response.publishedfiledetails 里 result == 1 的条目。 */
        fun parseList(text: String): List<Item> {
            val arr = JSONObject(text).optJSONObject("response")?.optJSONArray("publishedfiledetails") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.takeIf { it.optInt("result") == 1 }?.let(::parse)
            }
        }
    }
}

// ---- tags (same groups as Wallpaper Engine) ----

val TYPE = listOf("Scene" to "场景", "Video" to "视频", "Web" to "网页", "Application" to "应用")
val AGE = listOf("Everyone" to "全年龄", "Questionable" to "轻度成人", "Mature" to "成人")
val RES = listOf(
    "1920 x 1080" to "1920×1080", "2560 x 1440" to "2560×1440", "3840 x 2160" to "3840×2160", "3440 x 1440" to "3440×1440 带鱼屏",
    "1080 x 1920" to "1080×1920 竖屏", "2160 x 3840" to "2160×3840 竖屏", "Dynamic resolution" to "自适应分辨率",
)
val GENRE = listOf(
    "Abstract" to "抽象", "Animal" to "动物", "Anime" to "动漫", "Cartoon" to "卡通", "CGI" to "CGI", "Cyberpunk" to "赛博朋克",
    "Fantasy" to "奇幻", "Game" to "游戏", "Girls" to "女孩", "Guys" to "男孩", "Landscape" to "风景", "Medieval" to "中世纪",
    "Memes" to "梗图", "MMD" to "MMD", "Music" to "音乐", "Nature" to "自然", "Pixel art" to "像素", "Relaxing" to "放松",
    "Retro" to "复古", "Sci-Fi" to "科幻", "Sports" to "运动", "Technology" to "科技", "Television" to "影视", "Vehicle" to "载具",
    "Unspecified" to "未分类",
)

class TagGroup(val key: String, val title: String, val tags: List<Pair<String, String>>)

val GROUPS = listOf(TagGroup("type", "类型", TYPE), TagGroup("age", "年龄分级", AGE), TagGroup("res", "分辨率", RES), TagGroup("genre", "标签", GENRE))

private val LABELS: Map<String, String> = (TYPE + AGE + RES + GENRE).toMap() + mapOf(
    "Wallpaper" to "壁纸", "Preset" to "预设", "Audio responsive" to "音频响应", "Customizable" to "可自定义", "Approved" to "官方推荐",
)

fun label(tag: String): String = LABELS[tag] ?: tag

private val RES_TAG = Regex(" x \\d")

fun isResolutionTag(tag: String) = RES_TAG.containsMatchIn(tag)

class Sort(val key: String, val title: String, val input: Map<String, Int>)

val SORTS = listOf(
    Sort("trend7", "本周最热", mapOf("query_type" to 3, "days" to 7)),
    Sort("trend30", "本月最热", mapOf("query_type" to 3, "days" to 30)),
    Sort("trend365", "今年最热", mapOf("query_type" to 3, "days" to 365)),
    Sort("new", "最新发布", mapOf("query_type" to 1)),
    Sort("updated", "最近更新", mapOf("query_type" to 21)),
    Sort("subs", "订阅最多", mapOf("query_type" to 9)),
    Sort("vote", "评分最高", mapOf("query_type" to 0)),
    Sort("text", "最相关", mapOf("query_type" to 12)),
)

// ---- formatting ----

/** Steam 图片 CDN 的缩小版：只给宽度，Steam 按原比例缩放（同时给高度会变形）。动图的缩小版是静态图。 */
fun small(url: String, px: Int = 320): String =
    if (url.isEmpty()) "" else url + (if ('?' in url) "&" else "?") + "imw=$px&ima=fit"

fun ago(sec: Long): String {
    if (sec <= 0) return ""
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86400 -> "${d / 3600} 小时前"
        d < 86400 * 30 -> "${d / 86400} 天前"
        else -> date(sec)
    }
}

fun date(sec: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = sec * 1000 }
    return "%d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

fun num(n: Long): String = when {
    n >= 100000 -> "%.0f 万".format(n / 10000.0)
    n >= 10000 -> "%.1f 万".format(n / 10000.0)
    else -> n.toString()
}

fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${(b + 1023) / 1024} KB"
}

/** 去掉简介里的 BBCode（链接保留文字和地址，图片去掉）。 */
fun bbcode(s: String?): String = (s ?: "")
    .replace(Regex("\\[url=([^\\]]*)]([\\s\\S]*?)\\[/url]", RegexOption.IGNORE_CASE), "$2 ($1)")
    .replace(Regex("\\[img][\\s\\S]*?\\[/img]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\[/?[a-z*][^\\]]*]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
