package com.tiger.steamwallpaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 作者名字和头像的缓存（n、a 和电脑版的 profiles 一样，t 是读取时间）。 */
data class CachedProfile(val name: String, val avatar: String, val time: Long)

/**
 * 保存在 App 私有目录 workshop.json 里的状态，内容和电脑版 we_random.workshop.json 对应：阅读进度（readUpTo + seen）、
 * 在这里订阅过的壁纸、作者名字和头像、关注的作者、筛选、排序、当前标签页。都是 Compose 状态，界面直接读。
 */
class Store(private val file: File, private val scope: CoroutineScope) {
    /** 时间线的已读边界（发布时间，秒）：这个时间及以前发布的都算看过。0 = 还没开始记录。 */
    var readUpTo by mutableLongStateOf(0L)

    /** 边界以上已经单独看过的壁纸。 */
    val seen = mutableStateMapOf<String, Boolean>()

    /** 在这个 App 里订阅的壁纸（ID → 订阅时间，毫秒）。手机上不检测壁纸库，只记自己订阅过的。 */
    val subscribed = mutableStateMapOf<String, Long>()
    val profiles = mutableStateMapOf<String, CachedProfile>()
    val follows = mutableStateListOf<String>()
    val filters = mutableStateMapOf<String, List<String>>()
    var sort by mutableStateOf("trend7")
    var tab by mutableStateOf("timeline")
    var mergePosts by mutableStateOf(false)
    var glass by mutableStateOf(true)

    /** 登录过订阅账号（大号）：之后启动时才去读它的配置文件（不用时不初始化 WebView）。 */
    var subAccount by mutableStateOf(false)

    /** 所有玻璃的模糊（0.1 dp）、底色不透明度（%）和边缘色差（0~100，100 = 红绿蓝错开 12 dp），和 EhViewer 分支的设置一样。 */
    var glassBlur by mutableIntStateOf(25)
    var glassTint by mutableIntStateOf(40)
    var glassFringe by mutableIntStateOf(50)

    /** 时间线上次看到的位置：屏幕最上面那一项的 key（"p:壁纸ID"）和它滚出去的像素。 */
    var timelineKey by mutableStateOf("")
    var timelineOffset by mutableIntStateOf(0)
    var lastUpdateCheck by mutableLongStateOf(0L)

    /** 跳过提示的版本（「以后再说」），有更新的版本时还会提示。 */
    var skippedVersion by mutableStateOf("")

    private var saveJob: Job? = null

    fun load() {
        GROUPS.forEach { filters[it.key] = emptyList() }
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            readUpTo = o.optLong("readUpTo")
            o.optJSONObject("seen")?.let { s -> s.keys().forEach { seen[it] = true } }
            o.optJSONObject("subscribed")?.let { s -> s.keys().forEach { subscribed[it] = s.optLong(it) } }
            o.optJSONObject("profiles")?.let { s ->
                s.keys().forEach { id ->
                    val p = s.optJSONObject(id) ?: return@forEach
                    profiles[id] = CachedProfile(p.optString("n"), p.optString("a"), p.optLong("t"))
                }
            }
            o.optJSONArray("follows")?.let { follows.addAll(it.strings()) }
            o.optJSONObject("filters")?.let { f -> GROUPS.forEach { g -> filters[g.key] = f.optJSONArray(g.key)?.strings() ?: emptyList() } }
            sort = o.optString("sort").takeIf { s -> SORTS.any { it.key == s } } ?: "trend7"
            tab = o.optString("tab").takeIf { it in listOf("timeline", "browse", "following") } ?: "timeline"
            mergePosts = o.optBoolean("mergePosts")
            glass = o.optBoolean("glass", true)
            subAccount = o.optBoolean("subAccount")
            glassBlur = o.optInt("glassBlur", 25).coerceIn(0, 100)
            glassTint = o.optInt("glassTint", 40).coerceIn(0, 100)
            glassFringe = o.optInt("glassFringe", 50).coerceIn(0, 100)
            timelineKey = o.optString("timelineKey")
            timelineOffset = o.optInt("timelineOffset")
            lastUpdateCheck = o.optLong("lastUpdateCheck")
            skippedVersion = o.optString("skippedVersion")
        } catch (e: Exception) {
            Log.e("读取 workshop.json 失败", e)
        }
    }

    /** 0.8 秒内的多次修改合并成一次写入。 */
    fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            val text = snapshot()
            launch(Dispatchers.IO) { write(text) }
        }
    }

    private fun snapshot(): String {
        val o = JSONObject()
        o.put("readUpTo", readUpTo)
        o.put("seen", JSONObject().apply { seen.keys.forEach { put(it, 1) } })
        o.put("subscribed", JSONObject().apply { subscribed.forEach { (k, v) -> put(k, v) } })
        o.put("profiles", JSONObject().apply { profiles.forEach { (k, p) -> put(k, JSONObject().put("n", p.name).put("a", p.avatar).put("t", p.time)) } })
        o.put("follows", JSONArray(follows.toList()))
        o.put("filters", JSONObject().apply { filters.forEach { (k, v) -> put(k, JSONArray(v)) } })
        o.put("sort", sort)
        o.put("tab", tab)
        o.put("mergePosts", mergePosts)
        o.put("glass", glass)
        o.put("subAccount", subAccount)
        o.put("glassBlur", glassBlur)
        o.put("glassTint", glassTint)
        o.put("glassFringe", glassFringe)
        o.put("timelineKey", timelineKey)
        o.put("timelineOffset", timelineOffset)
        o.put("lastUpdateCheck", lastUpdateCheck)
        o.put("skippedVersion", skippedVersion)
        return o.toString()
    }

    @Synchronized
    private fun write(text: String) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e("保存 workshop.json 失败", e)
        }
    }

    // ---- filters ----

    /** QueryFiles 的 taggroups：组内任一个、组间都要满足；一组全选等于没选。 */
    fun tagGroups(): JSONArray {
        val groups = JSONArray()
        for (g in GROUPS) {
            val sel = filters[g.key].orEmpty()
            if (sel.isNotEmpty() && sel.size < g.tags.size) groups.put(JSONObject().put("tags", JSONArray(sel)))
        }
        return groups
    }

    fun passesFilters(it: Item): Boolean {
        for (g in GROUPS) {
            val sel = filters[g.key].orEmpty()
            if (sel.isNotEmpty() && sel.size < g.tags.size && sel.none { t -> t in it.tags }) return false
        }
        return true
    }

    val filterCount: Int get() = GROUPS.sumOf { g -> filters[g.key].orEmpty().size.takeIf { it < g.tags.size } ?: 0 }
}
