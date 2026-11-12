package com.tiger.steamwallpaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

/** 关注的发行商 / 鉴赏家在商店里出过的一个游戏（或 DLC）。 */
class AppRec(val appId: Int) {
    var name = ""

    /** 商店的类型：0 游戏，4 DLC，11 原声带…… */
    var type = 0
    var released = false

    /** Steam 上的发售时间（秒）；没发售时为 0 或将来的时间。 */
    var releaseTs = 0L

    /** 它是这些关注的发行商 / 开发商出的（clan id）。 */
    var creators: List<Int> = emptyList()

    /** 在这些关注对象的列表里出现过（包括鉴赏家推荐）。 */
    var listedBy: List<Int> = emptyList()
    var checkedAt = 0L
}

/** 时间线里的一条「新发售」。 */
class ReleaseRec(val appId: Int, var t: Long, var foundAt: Long) {
    /** 新闻流里已经有这款游戏的发售新闻（那条的 gid）：不再单独显示。 */
    var coveredBy = ""

    /** 游戏自己页面上的发售新闻（新闻流里没有）：直接把这条推过来。 */
    var eventGid = ""
    var eventClan = ""

    /** 不再找发售新闻了（找到了，或者发售超过 3 天还没有）。 */
    var newsDone = false
}

data class ClanRec(val name: String, val avatar: String)

/**
 * 新闻页的设置和进度，存在 App 私有目录的 news.json（和电脑版的 snf.config.json + snf.state.json 对应）。
 * 界面要读的是 Compose 状态。
 */
class NewsStore(private val file: File, private val scope: CoroutineScope) {
    // ---- 设置 ----
    /** 新闻流里也显示 Steam「精选」的热门游戏新闻（Steam 新闻中心默认不显示）。 */
    var showFeatured by mutableStateOf(false)

    /** DLC（包括原声带）也推送。 */
    var includeDlc by mutableStateOf(true)

    /** 只推送关注的发行商 / 开发商自己的游戏（鉴赏家推荐的别家游戏不算）。 */
    var onlyOwnGames by mutableStateOf(true)

    /** 往回找多少天内发售的游戏。 */
    var lookbackDays by mutableIntStateOf(30)

    /** 价格后面按汇率显示人民币。 */
    var showCny by mutableStateOf(true)

    /** 登录了新闻专用账号（[NewsProfile]）：新闻用它，否则用浏览账号。 */
    var newsAccount by mutableStateOf(false)

    /** 自动刷新间隔（分钟）：回到 App 时超过这么久没刷新就刷新。 */
    var refreshMinutes by mutableIntStateOf(30)

    // ---- 翻译（大模型），和电脑版的设置一样 ----
    /** 接口格式：openai（OpenAI 兼容）/ anthropic（Claude）。 */
    var aiFormat by mutableStateOf("openai")

    /** 接口地址；空 = 默认（DeepSeek / Anthropic 官方）。 */
    var aiBaseUrl by mutableStateOf("")

    /** API 密钥：用 Android Keystore 里的 AES 密钥加密后的 Base64（[Translator.protect]），不存明文。 */
    var aiKeyEnc by mutableStateOf("")
    var aiModel by mutableStateOf("")

    /** 思考时换用的模型（可选）。 */
    var aiThinkModel by mutableStateOf("")

    /** 思考：off 不思考 / on 思考 / auto 超过 [aiThinkWords] 个词才思考。 */
    var aiThink by mutableStateOf("auto")
    var aiThinkWords by mutableIntStateOf(800)
    var aiStream by mutableStateOf(true)

    /** 不是目标语言的新闻和评论，出现时自动翻译。 */
    var autoTranslate by mutableStateOf(false)
    var translateTo by mutableStateOf("简体中文")

    /** 屏蔽的游戏 / 账号：id → 名字。 */
    val blockedApps = mutableStateMapOf<Int, String>()
    val blockedClans = mutableStateMapOf<Int, String>()

    // ---- 阅读进度（和电脑版页面的 S 一样）----
    /** 这个时间及以前的都算看过；0 = 还没开始记录。 */
    var readUpTo by mutableLongStateOf(0L)

    /** 比 readUpTo 新、已经看过的条目。 */
    val seen = mutableStateMapOf<String, Boolean>()

    /** 「上次看到这里」分界线：离开 App 时记下的阅读进度；0 = 不显示（手动刷新后）。 */
    var divAt by mutableLongStateOf(0L)

    /** 上次的位置：最上面那条的 key 和它滚出去的像素。 */
    var posKey = ""
    var posOffset = 0
    var filter by mutableStateOf("all")

    // ---- 运行状态 ----
    val apps = HashMap<Int, AppRec>()
    val releases = HashMap<Int, ReleaseRec>()
    val clans = mutableStateMapOf<Int, ClanRec>()
    var baselineDone = false
    var country = ""
    var currency = ""
    var countryCheckedAt = 0L
    var cnyRate = 0.0
    var cnyRateCurrency = ""
    var cnyRateAt = 0L
    var lastRefresh by mutableLongStateOf(0L)

    private var saveJob: Job? = null

    fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            showFeatured = o.optBoolean("showFeatured")
            includeDlc = o.optBoolean("includeDlc", true)
            onlyOwnGames = o.optBoolean("onlyOwnGames", true)
            lookbackDays = o.optInt("lookbackDays", 30).coerceIn(3, 180)
            showCny = o.optBoolean("showCny", true)
            refreshMinutes = o.optInt("refreshMinutes", 30).coerceIn(0, 24 * 60)
            newsAccount = o.optBoolean("newsAccount")
            aiFormat = o.optString("aiFormat").takeIf { it == "anthropic" } ?: "openai"
            aiBaseUrl = o.optString("aiBaseUrl")
            aiKeyEnc = o.optString("aiKeyEnc")
            aiModel = o.optString("aiModel")
            aiThinkModel = o.optString("aiThinkModel")
            aiThink = o.optString("aiThink").takeIf { it == "off" || it == "on" } ?: "auto"
            aiThinkWords = o.optInt("aiThinkWords", 800).coerceAtLeast(0)
            aiStream = o.optBoolean("aiStream", true)
            autoTranslate = o.optBoolean("autoTranslate")
            translateTo = o.optString("translateTo").ifEmpty { "简体中文" }
            o.optJSONObject("blockedApps")?.let { b -> b.keys().forEach { k -> k.toIntOrNull()?.let { blockedApps[it] = b.optString(k) } } }
            o.optJSONObject("blockedClans")?.let { b -> b.keys().forEach { k -> k.toIntOrNull()?.let { blockedClans[it] = b.optString(k) } } }
            readUpTo = o.optLong("readUpTo")
            o.optJSONObject("seen")?.let { s -> s.keys().forEach { seen[it] = true } }
            divAt = o.optLong("divAt", readUpTo)
            posKey = o.optString("posKey")
            posOffset = o.optInt("posOffset")
            filter = o.optString("filter").takeIf { it == "release" } ?: "all"
            o.optJSONObject("apps")?.let { a ->
                a.keys().forEach { k ->
                    val j = a.optJSONObject(k) ?: return@forEach
                    val r = AppRec(k.toIntOrNull() ?: return@forEach)
                    r.name = j.optString("name")
                    r.type = j.optInt("type")
                    r.released = j.optBoolean("released")
                    r.releaseTs = j.optLong("releaseTs")
                    r.creators = j.optJSONArray("creators").ints()
                    r.listedBy = j.optJSONArray("listedBy").ints()
                    r.checkedAt = j.optLong("checkedAt")
                    apps[r.appId] = r
                }
            }
            o.optJSONObject("releases")?.let { a ->
                a.keys().forEach { k ->
                    val j = a.optJSONObject(k) ?: return@forEach
                    val r = ReleaseRec(k.toIntOrNull() ?: return@forEach, j.optLong("t"), j.optLong("foundAt"))
                    r.coveredBy = j.optString("coveredBy")
                    r.eventGid = j.optString("eventGid")
                    r.eventClan = j.optString("eventClan")
                    r.newsDone = j.optBoolean("newsDone")
                    releases[r.appId] = r
                }
            }
            o.optJSONObject("clans")?.let { a ->
                a.keys().forEach { k ->
                    val j = a.optJSONObject(k) ?: return@forEach
                    clans[k.toIntOrNull() ?: return@forEach] = ClanRec(j.optString("n"), j.optString("a"))
                }
            }
            baselineDone = o.optBoolean("baselineDone")
            country = o.optString("country")
            currency = o.optString("currency")
            countryCheckedAt = o.optLong("countryCheckedAt")
            cnyRate = o.optDouble("cnyRate", 0.0)
            cnyRateCurrency = o.optString("cnyRateCurrency")
            cnyRateAt = o.optLong("cnyRateAt")
            lastRefresh = o.optLong("lastRefresh")
        } catch (e: Exception) {
            Log.e("读取 news.json 失败", e)
        }
    }

    /** 1 秒内的多次修改合并成一次写入。在主线程调用。 */
    fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1000)
            val text = snapshot()
            launch(Dispatchers.IO) { write(text) }
        }
    }

    /** 马上写（离开 App 时）。 */
    fun saveNow() {
        saveJob?.cancel()
        val text = snapshot()
        scope.launch(Dispatchers.IO) { write(text) }
    }

    private fun snapshot(): String {
        val o = JSONObject()
        o.put("showFeatured", showFeatured)
        o.put("includeDlc", includeDlc)
        o.put("onlyOwnGames", onlyOwnGames)
        o.put("lookbackDays", lookbackDays)
        o.put("showCny", showCny)
        o.put("refreshMinutes", refreshMinutes)
        o.put("newsAccount", newsAccount)
        o.put("aiFormat", aiFormat)
        o.put("aiBaseUrl", aiBaseUrl)
        o.put("aiKeyEnc", aiKeyEnc)
        o.put("aiModel", aiModel)
        o.put("aiThinkModel", aiThinkModel)
        o.put("aiThink", aiThink)
        o.put("aiThinkWords", aiThinkWords)
        o.put("aiStream", aiStream)
        o.put("autoTranslate", autoTranslate)
        o.put("translateTo", translateTo)
        o.put("blockedApps", JSONObject().apply { blockedApps.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("blockedClans", JSONObject().apply { blockedClans.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("readUpTo", readUpTo)
        o.put("seen", JSONObject().apply { seen.keys.forEach { put(it, 1) } })
        o.put("divAt", divAt)
        o.put("posKey", posKey)
        o.put("posOffset", posOffset)
        o.put("filter", filter)
        o.put("apps", JSONObject().apply {
            apps.values.forEach { r ->
                put(r.appId.toString(), JSONObject().put("name", r.name).put("type", r.type).put("released", r.released).put("releaseTs", r.releaseTs)
                    .put("creators", JSONArray(r.creators)).put("listedBy", JSONArray(r.listedBy)).put("checkedAt", r.checkedAt))
            }
        })
        o.put("releases", JSONObject().apply {
            releases.values.forEach { r ->
                put(r.appId.toString(), JSONObject().put("t", r.t).put("foundAt", r.foundAt).put("coveredBy", r.coveredBy).put("eventGid", r.eventGid)
                    .put("eventClan", r.eventClan).put("newsDone", r.newsDone))
            }
        })
        o.put("clans", JSONObject().apply { clans.forEach { (k, c) -> put(k.toString(), JSONObject().put("n", c.name).put("a", c.avatar)) } })
        o.put("baselineDone", baselineDone)
        o.put("country", country)
        o.put("currency", currency)
        o.put("countryCheckedAt", countryCheckedAt)
        o.put("cnyRate", cnyRate)
        o.put("cnyRateCurrency", cnyRateCurrency)
        o.put("cnyRateAt", cnyRateAt)
        o.put("lastRefresh", lastRefresh)
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
            Log.e("保存 news.json 失败", e)
        }
    }

    /** 换了账号：和账号有关的发现记录、进度重新开始。 */
    fun resetAccount() {
        apps.clear()
        releases.clear()
        baselineDone = false
        readUpTo = 0
        seen.clear()
        divAt = 0
        posKey = ""
        posOffset = 0
        country = ""
        currency = ""
        countryCheckedAt = 0
        lastRefresh = 0
        save()
    }
}

fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
