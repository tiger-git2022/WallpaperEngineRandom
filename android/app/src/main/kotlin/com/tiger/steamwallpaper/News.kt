package com.tiger.steamwallpaper

import android.text.Html
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---- 新闻流里的数据 ----

/** 新闻流里的一条（只有 id、时间和来源，内容用到时再取）。[source]：Steam 新闻中心的来源标记（库 1、愿望单 2、关注 4、推荐 8、Steam 16、必显示 32、精选 64、鉴赏家 128、转发 256）。 */
class Doc(val gid: String, val clan: Int, val appId: Int, val t: Long, val type: Int, val source: Int)

/** 时间线上的一条：新闻流里的新闻、游戏页上的发售新闻、或者自动生成的「现已正式发售」。 */
class Entry(val key: String, val t: Long, val gid: String = "", val clan: Int = 0, val releaseApp: Int = 0, val fromGamePage: Boolean = false) {
    /** 别的账号发的同一篇（去重时合并进来的 gid）。 */
    val dups = mutableListOf<String>()
    var hidden = false
}

// ---- 界面用的条目（不可变的快照）----

class Source(val name: String, val icon: String, val url: String)

class Price(val final: String, val original: String, val pct: Int, val end: Long, val cny: Double?)

class Reviews(val count: Int, val pct: Int, val positive: Int, val score: Int, val label: String)

class AppInfo(
    val appid: Int, val name: String, val type: Int, val url: String, val capsule: String, val short: String,
    val releaseTs: Long, val comingSoon: Boolean, val free: Boolean, val tags: List<String>, val reviews: Reviews?, val price: Price?,
    /** 横幅图（商店页顶上那张 460×215）。 */
    val header: String = "",
) {
    val typeName: String get() = if (type == 4) "DLC" else if (type == 11) "原声带" else "游戏"
}

/** 屏蔽菜单里的一项：游戏（app）或账号（clan）。 */
class BlockTarget(val kind: String, val id: Int, val name: String)

class NewsItem(
    val key: String,
    val t: Long,
    /** event：新闻；release：自动生成的发售卡片。 */
    val kind: String,
    val gid: String = "",
    val title: String = "",
    val summary: String = "",
    val image: String = "",
    val body: String = "",
    val type: String = "",
    val votes: Int = 0,
    val comments: Int = 0,
    val url: String = "",
    val source: Source,
    val app: AppInfo? = null,
    val release: AppInfo? = null,
    val block: List<BlockTarget> = emptyList(),
    val dups: Int = 0,
    val also: List<String> = emptyList(),
    val fromGamePage: Boolean = false,
)

class NewsComment(val id: String, val name: String, val url: String, val avatar: String, val t: Long, val text: String)

/** 一条新闻已经读到的评论。 */
class NewsComments(total: Int) {
    val list = mutableStateListOf<NewsComment>()
    var total by mutableIntStateOf(total)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 翻译：评论 id → 译文；[trOn] 显示译文还是原文。 */
    val tr = mutableStateMapOf<String, String>()
    var trOn by mutableStateOf(false)
    var trLoading by mutableStateOf(false)
    var trError by mutableStateOf<String?>(null)

    /** 正在翻译的这一批评论的 id（每条下面标「等待翻译」）。 */
    val trWait = mutableStateListOf<String>()
}

/**
 * 新闻页：Steam 新闻流（和 Steam 新闻中心同一个接口、同样的默认筛选）+ 关注的发行商 / 开发商新发售的游戏，照搬电脑版
 * SteamNewsFeed 的 Feed.cs 和 feed.html 的脚本。新游戏的发售新闻已经在新闻流里就不重复；新闻流里没有、但游戏页上有，
 * 就把游戏页上的那条推过来；都没有就生成一张「《X》 – 现已正式发售！」。只在主线程上用（网络在 IO 线程）。
 */
class NewsFeed(val session: StoreSession, val store: NewsStore, private val cacheFile: File, private val scope: CoroutineScope) {
    companion object {
        private const val EVENT_TYPES = "28,9,27,22,23,24,35,25,26,12,13,14,10,29,16,15,32,20,21,31,34"
        private const val DAY = 86400L
        const val CLAN_CDN = "https://clan.akamai.steamstatic.com/images/"
        private const val ASSET_CDN = "https://shared.akamai.steamstatic.com/store_item_assets/"
        private const val AVATAR_CDN = "https://avatars.akamai.steamstatic.com/"
        private const val CLAN_BASE = 103582791429521408L

        fun clanAccount(steamId: String?): Int = steamId?.toLongOrNull()?.takeIf { it > CLAN_BASE }?.let { (it - CLAN_BASE).toInt() } ?: 0

        private val TYPE_NAMES = mapOf(
            28 to "新闻", 12 to "小更新", 13 to "更新", 14 to "重大更新", 10 to "发售", 15 to "发售", 16 to "即将推出",
            11 to "直播", 20 to "直播", 21 to "直播", 22 to "直播", 23 to "特惠", 24 to "特惠", 9 to "活动",
            25 to "游戏内活动", 26 to "游戏内活动", 27 to "游戏内活动", 29 to "游戏内活动", 31 to "免费试玩", 32 to "活动", 34 to "推荐", 35 to "游戏内活动",
        )

        private val RELEASE_WORDS = Regex("发售|發售|开售|開售|发行|發行|上线|上線|推出|发布|發布|正式|上市|登场|登場|解禁|首发|首發|release|launch|out now|available now|now available|is live|now live|発売|リリース|配信開始", RegexOption.IGNORE_CASE)
        private val NOT_RELEASE_WORDS = Regex("demo|試玩|试玩|体验版|體驗版|愿望|願望|wishlist|预告|預告|预约|預約|预购|預購|倒数|倒數|countdown|playtest|即将|即將|coming soon|更新|update|patch|hotfix|修复|修復|特惠|sale|折扣", RegexOption.IGNORE_CASE)
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]")

        private fun norm(s: String?) = (s ?: "").lowercase().replace(NOT_WORD, "")
    }

    private val now: Long get() = System.currentTimeMillis() / 1000

    // ---- 内部数据 ----
    private var followed: Map<Int, ClanRec> = emptyMap()
    private val docs = HashMap<String, Doc>()
    private var oldestDoc = Long.MAX_VALUE
    private var backDone = false
    private val events = HashMap<String, JSONObject>()
    private val dupKeys = HashMap<String, String?>()
    private val appsInfo = HashMap<Int, JSONObject>()
    private val appsAt = HashMap<Int, Long>()
    private var tags: Map<Int, String>? = null
    private val clanTried = HashSet<Int>()
    private val docsGate = Mutex()
    private var owned: Set<Int> = emptySet()

    // ---- 界面状态 ----
    var loggedIn by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** 这次刷新是手动的（下拉、点标签）：只有这时才显示下拉刷新的那滴玻璃，后台自动刷新只在顶栏转圈。 */
    var manualRefresh by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var followedCount by mutableIntStateOf(0)
        private set

    /** 有内容可以显示了（读过缓存或第一次刷新完）。 */
    var ready by mutableStateOf(false)
        private set

    /** 当前列表（按 [NewsStore.filter]）。 */
    val keys = mutableStateListOf<String>()
    val items = mutableStateMapOf<String, NewsItem>()
    var loading by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 第一次加载完（读到上次的分界线）：界面据此回到上次的位置，只做一次。 */
    var settled by mutableStateOf(false)
        private set
    var unread by mutableIntStateOf(0)
        private set

    /** 已经回到过上次的位置（每次启动只做一次）。 */
    var restored = false

    val wish = mutableStateMapOf<Int, Boolean>()
    val busy = mutableStateMapOf<String, Boolean>()
    val comments = mutableStateMapOf<String, NewsComments>()
    val expanded = mutableStateMapOf<String, Boolean>()

    /** 大模型翻译（新闻和评论）。 */
    val tr by lazy { NewsTranslate(this, store, File(App.instance.filesDir, "translations.json"), scope) }
    val list = LazyListState()

    private var loadJob: Job? = null

    fun isOwned(appid: Int) = appid in owned

    private fun showProgress(text: String) {
        progress = text
    }

    // ---- 启动、登录 ----

    private var started = false

    /** 社区登录了但商店没有票据时，已经用看不见的 WebView 试过一次（登录页关闭后再试）。 */
    private var renewTried = false

    fun start() {
        store.load()
        scope.launch {
            // 缓存可能有几 MB：在后台读，读完再显示
            val cache = withContext(Dispatchers.IO) { readCache() }
            if (cache != null) applyCache(cache)
            started = true
            refreshLogin()
        }
    }

    /** 读登录状态（启动、登录页关闭、回到前台时）。 */
    fun refreshLogin(force: Boolean = false) {
        if (!started) return
        if (force) renewTried = false
        val changed = session.syncCookies()
        val community = session.communityLoggedIn()
        if (changed && session.loggedIn && cachedOwner.isNotEmpty() && cachedOwner != session.steamId) {
            // 换了账号：和账号有关的都重新开始
            reset()
            store.resetAccount()
        }
        loggedIn = session.loggedIn
        if (changed || (session.loggedIn && accountId != session.steamId)) loadAccount()
        if (!session.loggedIn && community) {
            // 社区登录了，商店还没有票据：用看不见的 WebView 打开一次商店，让 Steam 发一张（只试一次）
            if (renewTried) return
            renewTried = true
            progress = "正在登录 Steam 商店…"
            scope.launch {
                session.ensureFresh(force = true)
                progress = ""
                if (session.loggedIn) refreshLogin() else error = "Steam 商店的登录没有拿到，请在账号面板退出后重新登录"
            }
            return
        }
        if (force && session.loggedIn) scope.launch { session.ensureFresh(force = true) }
        if (loggedIn && (changed || keys.isEmpty())) {
            if (ready) reload()
        }
        if (loggedIn) autoRefresh()
    }

    /** 回到前台：超过设定的间隔没刷新就刷新。 */
    fun autoRefresh() {
        if (!loggedIn || refreshing) return
        val minutes = store.refreshMinutes
        if (store.lastRefresh == 0L || (minutes > 0 && now - store.lastRefresh > minutes * 60)) refresh(manual = false)
    }

    /** 浏览账号退出了：新闻用的是浏览账号时一起清掉（新闻专用账号不受影响）。 */
    fun logout() {
        if (!store.newsAccount) {
            reset()
            loggedIn = false
        }
        refreshLogin()
    }

    // ---- 新闻专用账号 ----

    /** 新闻现在用的账号的名字和头像（公开的个人资料）。 */
    var accountId by mutableStateOf("")
        private set
    var accountName by mutableStateOf("")
        private set
    var accountAvatar by mutableStateOf("")
        private set

    private fun loadAccount() {
        val id = session.steamId
        accountId = id
        accountName = ""
        accountAvatar = ""
        if (id.isEmpty()) return
        scope.launch {
            val p = runCatching { App.instance.workshop.steam.profile(id) }.getOrNull() ?: return@launch
            if (accountId == id) {
                accountName = p.name
                accountAvatar = p.avatar
            }
        }
    }

    /** 新闻账号的登录页关闭后：登录成功就改用它。 */
    fun afterNewsLogin() {
        val ok = NewsProfile.cookieManager()?.getCookie(Steam.COMMUNITY)?.contains("steamLoginSecure=") == true
        if (!ok) return
        store.newsAccount = true
        store.save()
        refreshLogin(force = true)
        App.instance.workshop.toast("新闻改用新登录的账号")
    }

    /** 退出新闻账号，新闻改回用浏览账号。 */
    fun logoutNewsAccount() {
        session.logoutProfile()
        store.newsAccount = false
        store.save()
        refreshLogin(force = true)
        App.instance.workshop.toast("已退出新闻账号，新闻改用浏览账号")
    }

    private fun reset() {
        docs.clear()
        events.clear()
        dupKeys.clear()
        oldestDoc = Long.MAX_VALUE
        backDone = false
        followed = emptyMap()
        followedCount = 0
        wish.clear()
        owned = emptySet()
        keys.clear()
        items.clear()
        comments.clear()
        ready = false
        settled = false
        unread = 0
        File(cacheFile.path).delete()
    }

    // ---- 刷新 ----

    /** [manual]：手动刷新（下拉、点标签）：「上次看到这里」先隐藏，下次离开时再记。 */
    fun refresh(manual: Boolean) {
        if (refreshing) return
        // 手动刷新（下拉、点标签）：已读线等刷新完重新加载时移到现在的阅读进度（和壁纸动态一样）
        if (manual) dividerFixed = false
        if (!session.loggedIn) {
            error = "还没有登录 Steam"
            return
        }
        refreshing = true
        manualRefresh = manual
        error = ""
        scope.launch {
            val found = mutableListOf<String>()
            try {
                showProgress("正在刷新…")
                session.ensureFresh()
                showProgress("正在读取关注列表…")
                loadUserData()
                loadCountry()
                loadRate()
                showProgress("正在读取新闻…")
                fetchTopDocs(now - (store.lookbackDays + 3) * DAY)
                showProgress("正在读取新闻内容…")
                loadAllDetails()
                checkReleases(found)
                store.lastRefresh = now
                store.save()
                error = ""
                Log.i("新闻刷新完成：新闻 ${docs.size} 条，发售 ${store.releases.size} 个" + if (found.isNotEmpty()) "，新发现 " + found.joinToString("、") else "")
                saveCache()
            } catch (e: Exception) {
                error = if (e is SteamException) e.message ?: "刷新失败" else "刷新失败：" + errText(e)
                Log.e("新闻刷新失败", e)
            }
            refreshing = false
            progress = ""
            ready = ready || docs.isNotEmpty()
            reload()
            if (found.isNotEmpty()) App.instance.workshop.toast("关注的发行商有新发售：" + found.take(3).joinToString("、") { "《$it》" } + if (found.size > 3) " 等 ${found.size} 个" else "")
        }
    }

    /** 关注的发行商 / 鉴赏家、愿望单、已拥有的游戏。 */
    private suspend fun loadUserData() {
        val j = session.getJson("/dynamicstore/userdata/?t=$now")
        val f = HashMap<Int, ClanRec>()
        j.optJSONObject("rgCurators")?.let { c ->
            c.keys().forEach { k ->
                val o = c.optJSONObject(k) ?: return@forEach
                val id = k.toIntOrNull() ?: return@forEach
                f[id] = ClanRec(o.optString("name"), o.optString("avatar"))
            }
        }
        followed = f
        followedCount = f.size
        f.forEach { (k, v) -> store.clans[k] = v }
        val w = j.optJSONArray("rgWishlist").ints().toSet()
        wish.keys.retainAll(w)
        w.forEach { wish[it] = true }
        owned = j.optJSONArray("rgOwnedApps").ints().toSet()
    }

    /** 账号的商店地区（决定价格的货币），半天查一次。 */
    private suspend fun loadCountry() {
        if (store.country.isNotEmpty() && store.currency.isNotEmpty() && now - store.countryCheckedAt < DAY / 2) return
        try {
            val html = session.get("/news/?l=schinese")
            val country = (Regex("COUNTRY&quot;:&quot;([A-Z]{2})&quot;").find(html) ?: Regex("\"COUNTRY\":\"([A-Z]{2})\"").find(html))?.groupValues?.get(1) ?: ""
            var currency = ""
            if (country.isNotEmpty()) {
                for (probe in listOf(292030, 1091500, 271590, 1245620)) {
                    currency = runCatching {
                        session.getJson("/api/appdetails?appids=$probe&cc=$country&filters=price_overview")
                            .optJSONObject(probe.toString())?.optJSONObject("data")?.optJSONObject("price_overview")?.optString("currency") ?: ""
                    }.getOrDefault("")
                    if (currency.isNotEmpty()) break
                }
            }
            if (country.isNotEmpty()) store.country = country
            if (currency.isNotEmpty()) store.currency = currency
            store.countryCheckedAt = now
            Log.i("商店地区：$country，货币：$currency")
        } catch (e: Exception) {
            Log.i("读取商店地区失败：${e.message}")
        }
    }

    /** 当前货币 → 人民币的汇率，半天更新一次。 */
    private suspend fun loadRate() {
        val cur = store.currency
        if (!store.showCny || cur.isEmpty() || cur == "CNY" || (store.cnyRateCurrency == cur && store.cnyRate > 0 && now - store.cnyRateAt < DAY / 2)) return
        var rate = 0.0
        try {
            rate = session.getPublicJson("https://open.er-api.com/v6/latest/$cur").optJSONObject("rates")?.optDouble("CNY", 0.0) ?: 0.0
        } catch (e: Exception) {
            Log.i("汇率（open.er-api.com）读取失败：${e.message}")
        }
        if (rate <= 0) {
            try {
                val c = cur.lowercase()
                rate = session.getPublicJson("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$c.json").optJSONObject(c)?.optDouble("cny", 0.0) ?: 0.0
            } catch (e: Exception) {
                Log.i("汇率（备用）读取失败：${e.message}")
            }
        }
        if (rate > 0) {
            store.cnyRate = rate
            store.cnyRateCurrency = cur
            store.cnyRateAt = now
            Log.i("汇率：1 $cur = $rate CNY")
        }
    }

    // ---- 新闻流 ----

    private fun calendarUrl(maxTime: Long) =
        "/events/ajaxgetusereventcalendarrange/?minTime=0&maxTime=$maxTime&ascending=false&maxResults=250&populateEvents=0" +
            "&appTypes=library,wishlist,following,recommended,steam,curator&eventTypes=$EVENT_TYPES&l=schinese"

    /** 读一页（最多 250 条，比 maxTime 早的），返回 (最老的时间, 是不是到头了)。 */
    private suspend fun fetchDocPage(maxTime: Long): Pair<Long, Boolean> {
        val j = session.getJson(calendarUrl(maxTime))
        val s = j.opt("success")
        if (s is Number && s.toInt() != 1) throw SteamException(0, "Steam 新闻读取失败（$s）")
        val list = j.optJSONArray("documents") ?: JSONArray()
        val appSrc = HashMap<Int, Int>()
        val clanSrc = HashMap<Int, Int>()
        j.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { appSrc[it.optInt("appid")] = it.optInt("source") } }
        j.optJSONArray("clans")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { clanSrc[it.optInt("clanid")] = it.optInt("source") } }
        var oldest = Long.MAX_VALUE
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val gid = d.optString("unique_id")
            if (gid.isEmpty()) continue
            val clan = d.optInt("clanid")
            val app = d.optInt("appid")
            // 和 Steam 新闻中心一样：游戏有来源标记就用游戏的，否则用发新闻的 clan 的
            val src = if (app != 0 && appSrc.containsKey(app)) appSrc[app]!! else clanSrc[clan] ?: 0
            val doc = Doc(gid, clan, app, d.optLong("start_time"), d.optInt("event_type"), src)
            docs[gid] = doc
            oldest = min(oldest, doc.t)
        }
        val end = list.length() == 0 || j.optBoolean("backwardComplete")
        return (if (list.length() == 0) maxTime else oldest) to end
    }

    /** 从现在往回读，读到 [until] 并且和已经读过的接上为止。 */
    private suspend fun fetchTopDocs(until: Long) = docsGate.withLock {
        val newestBefore = docs.values.maxOfOrNull { it.t } ?: 0L
        var maxT = now
        var oldest = maxT
        var joined = false
        for (i in 0 until 12) {
            val (pageOldest, end) = fetchDocPage(maxT)
            oldest = min(oldest, pageOldest)
            if (end) {
                backDone = true
                joined = true
                break
            }
            if (newestBefore > 0 && oldest <= newestBefore) joined = true
            if (oldest <= until && (newestBefore == 0L || joined)) break
            maxT = oldest - 1
        }
        if (newestBefore > 0 && !joined) {
            // 中间断开了：扔掉更老的那一段，往下翻时重新读
            docs.values.filter { it.t < oldest }.map { it.gid }.forEach { docs.remove(it) }
            oldestDoc = oldest
            backDone = false
        } else {
            oldestDoc = min(oldestDoc, oldest)
        }
    }

    /** 往下翻：再读一页更老的新闻。 */
    private suspend fun fetchOlderDocs() = docsGate.withLock {
        if (backDone) return@withLock
        val from = if (oldestDoc == Long.MAX_VALUE) now else oldestDoc - 1
        val (pageOldest, end) = fetchDocPage(from)
        oldestDoc = min(oldestDoc, pageOldest)
        if (end) backDone = true
    }

    /** 取新闻的内容（标题、正文、图片），一次最多 20 条，同时 4 批。 */
    private suspend fun loadEventDetails(wanted: Collection<Pair<Int, String>>) {
        val todo = wanted.filter { it.second.isNotEmpty() && !events.containsKey(it.second) }.distinct()
        if (todo.isEmpty()) return
        val gate = Semaphore(4)
        todo.chunked(20).map { batch ->
            scope.async {
                gate.withPermit {
                    try {
                        val j = session.getJson(
                            "/events/ajaxgeteventdetails/?clanid_list=" + batch.joinToString(",") { it.first.toString() } +
                                "&uniqueid_list=" + batch.joinToString(",") { it.second } + "&lang_list=6_0",
                        )
                        val got = withContext(Dispatchers.Default) {
                            val a = j.optJSONArray("events") ?: JSONArray()
                            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { ev -> Triple(ev.optString("gid"), ev, dupKey(ev)) } }
                        }
                        for ((gid, ev, k) in got) {
                            if (gid.isEmpty()) continue
                            events[gid] = ev
                            dupKeys[gid] = k
                        }
                        for (b in batch) if (!events.containsKey(b.second)) putEvent(b.second, JSONObject().put("missing", true))
                    } catch (e: Exception) {
                        Log.i("读取新闻内容失败：${e.message}")
                    }
                }
            }
        }.awaitAll()
    }

    private fun putEvent(gid: String, ev: JSONObject) {
        events[gid] = ev
        dupKeys[gid] = dupKey(ev)
    }

    private suspend fun loadAllDetails() = loadEventDetails(docs.values.map { it.clan to it.gid })

    /** 去重用的指纹：标题和正文都一样，就当成同一篇。 */
    private fun dupKey(ev: JSONObject): String? {
        if (ev.has("missing")) return null
        val title = norm(ev.optString("event_name"))
        if (title.length < 4) return null
        val body = norm(ev.optJSONObject("announcement_body")?.optString("body"))
        return title + "|" + body.length + "|" + body.take(200)
    }

    // ---- 游戏信息 ----

    /** 用公开的 GetItems 一次取多个游戏的名字、图片、标签、发售时间、价格、评测、发行商。 */
    private suspend fun fetchItems(ids: Collection<Int>) {
        val list = ids.filter { it > 0 }.distinct()
        val country = store.country.ifEmpty { "US" }
        for (batch in list.chunked(30)) {
            val input = JSONObject()
                .put("ids", JSONArray(batch.map { JSONObject().put("appid", it) }))
                .put("context", JSONObject().put("language", "schinese").put("country_code", country).put("steam_realm", 1))
                .put("data_request", JSONObject().put("include_assets", true).put("include_release", true).put("include_tag_count", 8).put("include_basic_info", true).put("include_reviews", true))
            try {
                val j = session.getPublicJson("${StoreSession.API}/IStoreBrowseService/GetItems/v1?input_json=" + URLEncoder.encode(input.toString(), "UTF-8"))
                j.optJSONObject("response")?.optJSONArray("store_items")?.let { a ->
                    for (i in 0 until a.length()) {
                        val it = a.optJSONObject(i) ?: continue
                        val appid = it.optInt("appid").takeIf { x -> x > 0 } ?: it.optInt("id")
                        if (appid <= 0) continue
                        appsInfo[appid] = if (it.optInt("success") == 1) it else JSONObject().put("missing", true)
                        appsAt[appid] = now
                    }
                }
            } catch (e: Exception) {
                Log.i("读取游戏信息失败：${e.message}")
            }
        }
    }

    private suspend fun ensureApps(ids: Collection<Int>, maxAge: Long) {
        val todo = ids.filter { a -> a > 0 && (appsAt[a]?.let { now - it > maxAge } != false) }.distinct()
        if (todo.isNotEmpty()) fetchItems(todo)
    }

    private suspend fun ensureTags() {
        if (tags != null) return
        try {
            val j = session.getPublicJson("${StoreSession.API}/IStoreService/GetTagList/v1/?language=schinese")
            val m = HashMap<Int, String>()
            j.optJSONObject("response")?.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { m.putIfAbsent(it.optInt("tagid"), it.optString("name")) } }
            tags = m
        } catch (e: Exception) {
            Log.i("读取标签名失败：${e.message}")
        }
    }

    /** 不在关注列表里的 clan（偶尔有官方或别人的新闻）：从它的商店页读名字和头像，记下来。 */
    private suspend fun ensureClans(clans: Collection<Int>) {
        val todo = clans.filter { it > 0 && !store.clans.containsKey(it) && it !in clanTried }.distinct().take(6)
        clanTried.addAll(todo)
        for (c in todo) {
            try {
                val html = session.get("/curator/$c/?l=schinese")
                val title = Html.fromHtml(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim() ?: "", Html.FROM_HTML_MODE_LEGACY).toString()
                val colon = max(title.lastIndexOf('：'), title.lastIndexOf(':'))
                val name = if (colon >= 0) title.substring(colon + 1).trim() else title
                val avatar = Regex("avatars\\.[a-z]+\\.steamstatic\\.com/([0-9a-f]{40})").find(html)?.groupValues?.get(1) ?: ""
                if (name.isNotEmpty() && !name.startsWith("Steam", ignoreCase = true)) store.clans[c] = ClanRec(name, avatar)
            } catch (e: Exception) {
                Log.i("读取组 $c 的名字失败：${e.message}")
            }
        }
    }

    // ---- 新发售 ----

    private fun typeOk(type: Int, dlc: Boolean) = type == 0 || (dlc && (type == 4 || type == 11))

    /** 按现在的设置，这个游戏要不要推送。 */
    private fun qualifies(rec: AppRec?): Boolean = rec != null && rec.released && typeOk(rec.type, store.includeDlc) &&
        (!store.onlyOwnGames || rec.creators.isNotEmpty()) && !blockedRelease(rec)

    /** 屏蔽了这个游戏，或者把它算进来的关注对象都被屏蔽了。 */
    private fun blockedRelease(rec: AppRec): Boolean {
        if (store.blockedApps.containsKey(rec.appId)) return true
        val who = (rec.creators + rec.listedBy).distinct()
        return who.isNotEmpty() && who.all { store.blockedClans.containsKey(it) }
    }

    private suspend fun checkReleases(found: MutableList<String>) {
        val f = followed
        // 1. 每个关注对象最新的游戏
        val listed = HashMap<Int, HashSet<Int>>()
        var n = 0
        for ((clan, rec) in f) {
            showProgress("正在检查新游戏：${++n} / ${f.size}")
            try {
                val j = session.getJson("/curator/$clan/ajaxgetfilteredrecommendations/?query=&start=0&count=12&dynamic_data=&tagids=&sort=newreleases&app_types=&curations=&reset=false&l=schinese")
                Regex("data-ds-appid=\"(\\d+)\"").findAll(j.optString("results_html")).forEach { m ->
                    listed.getOrPut(m.groupValues[1].toInt()) { HashSet() }.add(clan)
                }
            } catch (e: Exception) {
                Log.i("读取 ${rec.name} 的游戏失败：${e.message}")
            }
            delay(120)
        }
        // 2. 没见过的、还没发售的（每 3 小时）重新查一次
        val t0 = now
        val check = listed.keys.filter { a -> store.apps[a]?.let { r -> r.checkedAt == 0L || (!r.released && t0 - r.checkedAt > 3 * 3600) } != false }
        if (check.isNotEmpty()) {
            showProgress("正在读取 ${check.size} 个游戏的信息…")
            fetchItems(check)
        }
        val lookback = store.lookbackDays
        val baseline = store.baselineDone
        for ((appid, by) in listed) {
            val rec = store.apps.getOrPut(appid) { AppRec(appid) }
            rec.listedBy = (rec.listedBy + by).distinct()
            val it = appsInfo[appid]
            if (appid in check && it != null && !it.has("missing")) {
                rec.name = it.optString("name").ifEmpty { rec.name }
                rec.type = it.optInt("type")
                val rel = it.optJSONObject("release")
                val ts = rel?.optLong("steam_release_date")?.takeIf { x -> x > 0 } ?: rel?.optLong("original_release_date") ?: 0L
                val soon = rel?.optBoolean("is_coming_soon") == true || ts == 0L || ts > t0
                rec.released = !soon
                rec.releaseTs = ts
                val makers = HashSet<Int>()
                for (group in listOf("developers", "publishers", "franchises")) {
                    it.optJSONObject("basic_info")?.optJSONArray(group)?.let { a ->
                        for (i in 0 until a.length()) {
                            val cid = a.optJSONObject(i)?.optInt("creator_clan_account_id") ?: 0
                            if (cid > 0 && f.containsKey(cid)) makers.add(cid)
                        }
                    }
                }
                rec.creators = makers.toList()
                rec.checkedAt = t0
            }
            if (rec.released && rec.releaseTs >= t0 - lookback * DAY && typeOk(rec.type, true) && !store.releases.containsKey(appid)) {
                // 第一次检查时按发售时间排；之后发售了一阵才发现的（比如刚关注的发行商）放到现在，免得落在已读的部分
                val t = if (baseline && t0 - rec.releaseTs > 6 * 3600) t0 else rec.releaseTs
                store.releases[appid] = ReleaseRec(appid, t, t0)
                if (baseline && qualifies(rec)) found.add(rec.name)
            }
        }
        store.baselineDone = true
        store.releases.values.filter { it.t < t0 - (lookback + 60) * DAY }.map { it.appId }.forEach { store.releases.remove(it) }
        store.save()

        // 3. 给要推送的发售找发售新闻
        val pending = store.releases.values.filter { !it.newsDone && qualifies(store.apps[it.appId]) }
        var k = 0
        for (rel in pending) {
            val app = store.apps[rel.appId] ?: continue
            showProgress("正在找发售新闻：${++k} / ${pending.size}")
            try {
                matchNews(rel, app)
            } catch (e: Exception) {
                Log.i("找《${app.name}》的发售新闻失败：${e.message}")
            }
        }
        store.save()
    }

    private fun nameMatch(title: String, name: String): Boolean {
        val t = norm(title)
        val n = norm(name)
        if (n.length < 2) return false
        return n.take(6) in t || (n.length >= 4 && n.take(4) in t)
    }

    /** 这条新闻是不是这个游戏的发售新闻。 */
    private fun isReleaseNews(ev: JSONObject?, app: AppRec): Boolean {
        if (ev == null || ev.has("missing")) return false
        val title = ev.optString("event_name").ifEmpty { ev.optJSONObject("announcement_body")?.optString("headline") ?: "" }
        val body = ev.optJSONObject("announcement_body")?.optString("body") ?: ""
        var about = ev.optInt("appid") == app.appId || nameMatch(title, app.name) || "/app/${app.appId}/" in body || "/app/${app.appId}]" in body
        if (!about) {
            about = runCatching { JSONObject(ev.optString("jsondata", "{}")).optJSONArray("referenced_appids").ints().contains(app.appId) }.getOrDefault(false)
        }
        if (!about) return false
        if (ev.optInt("event_type") == 10) return true
        return RELEASE_WORDS.containsMatchIn(title) && !NOT_RELEASE_WORDS.containsMatchIn(title)
    }

    private suspend fun matchNews(rel: ReleaseRec, app: AppRec) {
        val lo = app.releaseTs - 3 * DAY
        val hi = app.releaseTs + 14 * DAY
        // 1. 新闻流里已经有了吗（游戏自己的新闻，或者发行商发的）
        val candidates = docs.values.filter { d -> d.t in lo..hi && (d.appId == app.appId || (d.appId == 0 && (d.clan in app.creators || d.clan in app.listedBy))) }.sortedBy { it.t }
        loadEventDetails(candidates.map { it.clan to it.gid })
        for (d in candidates) {
            if (isReleaseNews(events[d.gid], app)) {
                rel.coveredBy = d.gid
                rel.newsDone = true
                return
            }
        }
        // 2. 游戏自己页面上的新闻
        if (now >= lo) {
            val j = session.getJson("/events/ajaxgetpartnereventspageable/?clan_accountid=0&appid=${app.appId}&offset=0&count=15&l=schinese")
            var best: JSONObject? = null
            j.optJSONArray("events")?.let { a ->
                for (i in 0 until a.length()) {
                    val ev = a.optJSONObject(i) ?: continue
                    val t = ev.optLong("rtime32_start_time")
                    if (t in lo..hi && isReleaseNews(ev, app) && (best == null || t < best!!.optLong("rtime32_start_time"))) best = ev
                }
            }
            best?.let { b ->
                val gid = b.optString("gid")
                putEvent(gid, b)
                rel.eventGid = gid
                rel.eventClan = clanAccount(b.optString("clan_steamid")).toString()
                rel.newsDone = true
                Log.i("《${app.name}》的发售新闻在游戏页面上：${b.optString("event_name")}")
                return
            }
        }
        if (now > app.releaseTs + 3 * DAY) rel.newsDone = true
    }

    // ---- 时间线 ----

    /** 和 Steam 新闻中心默认的筛选一样：只因为是「精选」（Steam 推的热门游戏）才出现的不显示（设置里可以打开）。 */
    private fun visible(d: Doc): Boolean {
        val mask = 1 or 2 or 4 or 8 or 16 or 32 or 128 or 256 or (if (store.showFeatured) 64 else 0)
        return (d.source and mask) != 0
    }

    private fun blockedDoc(d: Doc) = (d.appId != 0 && store.blockedApps.containsKey(d.appId)) || store.blockedClans.containsKey(d.clan)

    /** 把新闻流和发售合起来，按时间从新到旧。[filter]：all / release。 */
    private fun build(filter: String): List<Entry> {
        val list = mutableListOf<Entry>()
        val covered = HashMap<String, Int>()
        for (r in store.releases.values) {
            val app = store.apps[r.appId]
            if (!qualifies(app)) continue
            when {
                r.coveredBy.isNotEmpty() && docs.containsKey(r.coveredBy) -> covered[r.coveredBy] = r.appId
                r.eventGid.isNotEmpty() && docs.containsKey(r.eventGid) -> covered[r.eventGid] = r.appId
                r.eventGid.isNotEmpty() -> list.add(Entry("e:" + r.eventGid, r.t, r.eventGid, r.eventClan.toIntOrNull() ?: 0, r.appId, fromGamePage = true))
                r.coveredBy.isEmpty() -> list.add(Entry("r:" + r.appId, r.t, releaseApp = r.appId))
            }
        }
        // 多个账号发的同一篇（标题、正文都一样，前后 3 天内）只留一条：留最早发的，带发售卡片的优先
        val groups = HashMap<String, Entry>()
        for (d in docs.values.sortedBy { it.t }) {
            val rel = covered[d.gid] ?: 0
            if ((rel == 0 && !visible(d)) || blockedDoc(d)) continue
            val en = Entry("e:" + d.gid, d.t, d.gid, d.clan, rel)
            val k = dupKeys[d.gid]
            val keep = k?.let { groups[it] }
            if (k != null && keep != null && d.t - keep.t <= 3 * DAY) {
                if (keep.releaseApp == 0 && rel != 0) {
                    en.dups.add(keep.gid)
                    en.dups.addAll(keep.dups)
                    keep.dups.clear()
                    keep.hidden = true
                    groups[k] = en
                    list.add(en)
                } else {
                    keep.dups.add(d.gid)
                }
                continue
            }
            if (k != null) groups[k] = en
            list.add(en)
        }
        list.removeAll { it.hidden || (filter == "release" && it.releaseApp == 0) }
        return list.sortedWith(compareByDescending<Entry> { it.t }.thenBy { it.key })
    }

    /** 时间线的一段（往下翻时一段一段地要）。返回 (这一段, 是不是到头了)。 */
    private suspend fun entries(offset: Int, count: Int, filter: String): Pair<List<NewsItem>, Boolean> {
        loadAllDetails()
        var list = build(filter)
        var i = 0
        while (i < 6 && offset + count > list.size && !backDone && session.loggedIn) {
            fetchOlderDocs()
            loadAllDetails()
            list = build(filter)
            i++
        }
        val slice = list.drop(offset).take(count)
        loadEventDetails(slice.filter { it.gid.isNotEmpty() }.map { it.clan to it.gid })
        val appIds = mutableListOf<Int>()
        val clans = mutableListOf<Int>()
        for (e in slice) {
            if (e.releaseApp > 0) appIds.add(e.releaseApp)
            for (g in listOf(e.gid) + e.dups) {
                val ev = events[g] ?: continue
                if (g.isEmpty() || ev.has("missing")) continue
                ev.optInt("appid").takeIf { it > 0 }?.let { appIds.add(it) }
                clans.add(clanAccount(ev.optString("clan_steamid")))
            }
        }
        ensureTags()
        ensureApps(appIds, 6 * 3600)
        ensureClans(clans)
        val items = slice.mapNotNull { itemOf(it) }
        return items to (backDone && offset + count >= list.size)
    }

    private fun loc(arr: Any?): String? {
        val a = arr as? JSONArray ?: return null
        fun pick(i: Int) = if (i < a.length()) (a.opt(i) as? String)?.takeIf { it.isNotEmpty() } else null
        return pick(6) ?: pick(0) ?: (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } }.firstOrNull()
    }

    private fun avatarUrl(hash: String) = if (hash.isEmpty()) "" else "$AVATAR_CDN${hash}_full.jpg"

    private fun appIcon(appid: Int, app: JSONObject): String {
        val icon = app.optJSONObject("assets")?.optString("community_icon") ?: ""
        return if (icon.isEmpty()) "" else "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appid/$icon.jpg"
    }

    private fun itemOf(e: Entry): NewsItem? {
        val release = if (e.releaseApp > 0) appInfo(e.releaseApp) ?: return null else null
        if (e.gid.isEmpty()) {
            val rec = store.apps[e.releaseApp]
            val who = (if (rec?.creators?.isNotEmpty() == true) rec.creators else rec?.listedBy ?: emptyList()).filter { store.clans.containsKey(it) }
            val makers = who.map { c -> Source(store.clans[c]!!.name, avatarUrl(store.clans[c]!!.avatar), "${StoreSession.STORE}/curator/$c/") }
            val block = listOf(BlockTarget("app", e.releaseApp, rec?.name ?: "")) + who.map { BlockTarget("clan", it, store.clans[it]!!.name) }
            return NewsItem(e.key, e.t, "release", source = makers.firstOrNull() ?: Source("Steam", "", ""), release = release, block = block)
        }
        val ev = events[e.gid]?.takeIf { !it.has("missing") } ?: return null
        val clan = clanAccount(ev.optString("clan_steamid"))
        val appid = ev.optInt("appid")
        val jd = runCatching { JSONObject(ev.optString("jsondata", "{}")) }.getOrDefault(JSONObject())
        val capsule = loc(jd.opt("localized_capsule_image"))
        val titleImg = loc(jd.opt("localized_title_image"))
        val body = ev.optJSONObject("announcement_body")
        val info = appsInfo[appid]?.takeIf { appid > 0 && !it.has("missing") }
        val clanRec = store.clans[clan]?.takeIf { it.name.isNotEmpty() }
        val source = when {
            info != null -> Source(info.optString("name"), appIcon(appid, info), "${StoreSession.STORE}/app/$appid/")
            clanRec != null -> Source(clanRec.name, avatarUrl(clanRec.avatar), "${StoreSession.STORE}/curator/$clan/")
            else -> Source(if (appid > 0) "App $appid" else "Steam", "", "")
        }
        val app = if (info != null) appInfo(appid) else null
        val block = mutableListOf<BlockTarget>()
        if (appid > 0) block.add(BlockTarget("app", appid, app?.name ?: "App $appid"))
        if (clanRec != null) block.add(BlockTarget("clan", clan, clanRec.name)) else if (appid == 0) block.add(BlockTarget("clan", clan, source.name))
        if (e.releaseApp > 0 && e.releaseApp != appid) block.add(0, BlockTarget("app", e.releaseApp, release?.name ?: "App ${e.releaseApp}"))
        // 同一篇也被别的账号发了：列出来
        val also = mutableListOf<String>()
        for (dg in e.dups) {
            val dev = events[dg]?.takeIf { !it.has("missing") } ?: continue
            val da = dev.optInt("appid")
            val dc = clanAccount(dev.optString("clan_steamid"))
            val dn = appsInfo[da]?.takeIf { da > 0 && !it.has("missing") }?.optString("name") ?: store.clans[dc]?.name?.takeIf { it.isNotEmpty() }
            if (!dn.isNullOrEmpty() && dn != source.name && dn !in also) also.add(dn)
        }
        return NewsItem(
            key = e.key, t = e.t, kind = "event", gid = e.gid,
            title = ev.optString("event_name").ifEmpty { body?.optString("headline") ?: "" },
            // 摘要只用简体 / 繁体中文的；只有英文摘要时用正文（正文按简体中文取）开头的一段
            summary = (jd.opt("localized_summary") as? JSONArray)?.let { a -> listOf(6, 7).firstNotNullOfOrNull { i -> (a.opt(i) as? String)?.takeIf { it.isNotBlank() } } } ?: "",
            image = when {
                capsule != null -> "$CLAN_CDN$clan/$capsule"
                titleImg != null -> "$CLAN_CDN$clan/$titleImg"
                else -> ""
            },
            body = body?.optString("body") ?: "",
            type = TYPE_NAMES[ev.optInt("event_type")] ?: "活动",
            votes = ev.optInt("votes_up"),
            comments = max(ev.optInt("comment_count"), body?.optInt("commentcount") ?: 0),
            url = if (appid > 0) "${StoreSession.STORE}/news/app/$appid/view/${e.gid}" else "${StoreSession.STORE}/news/group/$clan/view/${e.gid}",
            source = source, app = app, release = release, block = block, dups = e.dups.size, also = also, fromGamePage = e.fromGamePage,
        )
    }

    /** 游戏卡片要的全部信息。 */
    private fun appInfo(appid: Int): AppInfo? {
        val it = appsInfo[appid]?.takeIf { x -> !x.has("missing") } ?: return null
        val assets = it.optJSONObject("assets")
        val fmt = assets?.optString("asset_url_format") ?: ""
        fun u(key: String): String {
            val file = assets?.optString(key) ?: ""
            return if (file.isEmpty() || fmt.isEmpty()) "" else ASSET_CDN + fmt.replace("\${FILENAME}", file)
        }
        val rel = it.optJSONObject("release")
        val comingSoon = rel?.optBoolean("is_coming_soon") == true
        val tagIds = it.optJSONArray("tagids").ints().take(5)
        val tagNames = tags?.let { t -> tagIds.mapNotNull { id -> t[id] } } ?: emptyList()
        // 评测：已发售的才显示（等级、好评率、总数；好评数按好评率算）
        val reviews = it.optJSONObject("reviews")?.optJSONObject("summary_filtered")?.takeIf { !comingSoon }?.let { rv ->
            val count = rv.optInt("review_count")
            val pct = rv.optInt("percent_positive")
            Reviews(count, pct, (count * pct / 100.0).roundToInt(), rv.optInt("review_score"), rv.optString("review_score_label"))
        }
        val price = it.optJSONObject("best_purchase_option")?.let { bpo ->
            val cents = bpo.optString("final_price_in_cents").toLongOrNull() ?: 0L
            val cny = if (store.showCny && cents > 0 && store.currency.isNotEmpty() && store.currency != "CNY" && store.cnyRate > 0 && store.cnyRateCurrency == store.currency) {
                Math.round(cents / 100.0 * store.cnyRate * 100) / 100.0
            } else {
                null
            }
            Price(
                bpo.optString("formatted_final_price"), bpo.optString("formatted_original_price"), bpo.optInt("discount_pct"),
                bpo.optJSONArray("active_discounts")?.optJSONObject(0)?.optLong("discount_end_date") ?: 0L, cny,
            )
        }
        return AppInfo(
            appid = appid, name = it.optString("name"), type = it.optInt("type"), url = "${StoreSession.STORE}/app/$appid/",
            header = u("header").ifEmpty { u("main_capsule") },
            capsule = u("main_capsule").ifEmpty { u("header") }, short = it.optJSONObject("basic_info")?.optString("short_description") ?: "",
            releaseTs = rel?.optLong("steam_release_date")?.takeIf { x -> x > 0 } ?: rel?.optLong("original_release_date") ?: 0L,
            comingSoon = comingSoon, free = it.optBoolean("is_free"), tags = tagNames, reviews = reviews, price = price,
        )
    }

    // ---- 列表 ----

    fun setFilter(f: String) {
        if (store.filter == f) return
        store.filter = f
        store.save()
        reload()
    }

    /** 重新从头加载（刷新完、换筛选、屏蔽后）；已经加载的条数照旧，列表按 key 保持滚动位置。 */
    fun reload() {
        if (!loggedIn) return
        loadJob?.cancel()
        val count = max(30, keys.size)
        loading = true
        loadError = null
        loadJob = scope.launch {
            try {
                val (list, end) = entries(0, count, store.filter)
                list.forEach { items[it.key] = it }
                keys.clear()
                keys.addAll(list.map { it.key }.distinct())
                done = end || list.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = errText(e)
            }
            loading = false
            afterLoad()
        }
    }

    fun loadMore() {
        if (loading || done || !loggedIn || (!ready && keys.isEmpty())) return
        loading = true
        loadError = null
        loadJob = scope.launch {
            try {
                val (list, end) = entries(keys.size, 30, store.filter)
                for (it in list) {
                    items[it.key] = it
                    if (it.key !in keys) keys.add(it.key)
                }
                done = end || list.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = errText(e)
            }
            loading = false
            afterLoad()
        }
    }

    // ---- 阅读进度 ----
    // readUpTo：这个时间及以前的都算已读。比它新的，在屏幕上出现过、之后往下滚把它滚出了屏幕顶部，就记为看过（seen）；
    // 从最新一条到分界线之间都看过了，分界线就往上移。

    private fun boundaryIndex(t: Long = store.readUpTo): Int {
        val i = keys.indexOfFirst { (items[it]?.t ?: 0) <= t }
        return if (i < 0) keys.size else i
    }

    /** 「上次看到这里」在列表里的位置（在这一条前面）；不显示时为 -1。 */
    fun dividerIndex(): Int {
        if (store.filter != "all" || store.divAt <= 0) return -1
        val b = boundaryIndex(store.divAt)
        return if (b in 1 until keys.size) b else -1
    }

    private fun afterLoad() {
        if (store.filter != "all" || keys.isEmpty()) {
            if (keys.isNotEmpty() || done) settled = true
            updateUnread()
            return
        }
        if (store.readUpTo == 0L) {
            store.readUpTo = items[keys[0]]?.t ?: 0
            store.save()
            App.instance.workshop.toast("从现在开始记录新闻的阅读进度")
        }
        // 一直加载到上次的分界线出现，这样未读数才准（最多 600 条）
        val last = items[keys.last()]
        if (!done && loadError == null && last != null && last.t > store.readUpTo && keys.size < 600) {
            loadMore()
            return
        }
        // 已读线：每次打开（和下拉刷新后）加载完时定下来，之后不跟着阅读进度动
        if (!dividerFixed) {
            store.divAt = store.readUpTo
            dividerFixed = true
        }
        updateBoundary()
        settled = true
    }

    private var dividerFixed = false

    private fun updateBoundary() {
        val b = boundaryIndex()
        var i = b - 1
        while (i >= 0 && store.seen[keys[i]] == true) i--
        if (i + 1 < b) {
            store.readUpTo = items[keys[i + 1]]?.t ?: store.readUpTo
            for (k in store.seen.keys.toList()) {
                val it = items[k]
                if (it == null || it.t <= store.readUpTo) store.seen.remove(k)
            }
            store.save()
        }
        updateUnread()
    }

    private fun updateUnread() {
        if (store.readUpTo <= 0 || !loggedIn) {
            unread = 0
            return
        }
        unread = build("all").count { it.t > store.readUpTo && store.seen[it.key] != true }
    }

    fun isNew(it: NewsItem) = store.filter == "all" && store.readUpTo > 0 && it.t > store.readUpTo && store.seen[it.key] != true

    private var boundaryJob: Job? = null

    fun markSeen(key: String) {
        if (store.seen[key] == true || store.filter != "all") return
        val it = items[key] ?: return
        if (it.t <= store.readUpTo) return
        store.seen[key] = true
        store.save()
        boundaryJob?.cancel()
        boundaryJob = scope.launch {
            delay(300)
            updateBoundary()
        }
    }

    fun markAllRead() {
        if (store.filter != "all" || keys.isEmpty()) return
        store.readUpTo = max(store.readUpTo, items[keys[0]]?.t ?: 0)
        store.seen.clear()
        store.save()
        updateUnread()
    }

    /** 离开 App（切到后台、退出）：马上保存阅读进度和位置。 */
    fun leave() {
        store.saveNow()
    }

    // ---- 操作 ----

    fun toggleWish(appid: Int) {
        val key = "wish:$appid"
        if (busy[key] == true) return
        val on = wish[appid] != true
        busy[key] = true
        scope.launch {
            try {
                session.setWishlist(appid, on)
                if (on) wish[appid] = true else wish.remove(appid)
                App.instance.workshop.toast(if (on) "已加入愿望单" else "已移出愿望单")
            } catch (e: Exception) {
                App.instance.workshop.toast(errText(e), long = true)
            }
            busy.remove(key)
        }
    }

    /** 屏蔽 / 取消屏蔽一个游戏（app）或账号（clan）。 */
    fun setBlocked(kind: String, id: Int, name: String, on: Boolean) {
        val map = if (kind == "app") store.blockedApps else store.blockedClans
        if (on) map[id] = name.trim().ifEmpty { if (kind == "app") "App $id" else "账号 $id" } else map.remove(id)
        store.save()
        Log.i((if (on) "屏蔽 " else "取消屏蔽 ") + (if (kind == "app") "游戏 " else "账号 ") + "$id $name")
        if (on) {
            // 把受影响的几条马上从列表上拿掉
            val gone = keys.filter { k ->
                val x = items[k] ?: return@filter false
                x.block.any { it.kind == kind && it.id == id } || (kind == "app" && (x.release?.appid == id || x.app?.appid == id))
            }.toSet()
            keys.removeAll(gone)
            App.instance.workshop.toast("已屏蔽" + (if (kind == "app") "游戏《$name》" else "账号「$name」") + "，可以在新闻设置里取消")
            updateUnread()
        } else {
            reload()
        }
    }

    fun setShowFeatured(on: Boolean) {
        store.showFeatured = on
        store.save()
        reload()
    }

    fun setIncludeDlc(on: Boolean) {
        store.includeDlc = on
        store.save()
        reload()
    }

    fun setOnlyOwnGames(on: Boolean) {
        store.onlyOwnGames = on
        store.save()
        reload()
    }

    fun setShowCny(on: Boolean) {
        store.showCny = on
        store.save()
        if (on) scope.launch { loadRate(); reload() } else reload()
    }

    // ---- 正文里的游戏横幅 ----

    /** 正文里链接到的游戏（appid → 横幅要的信息）；读到了才有。 */
    val banners = mutableStateMapOf<Int, AppInfo>()
    private val bannerQueue = LinkedHashSet<Int>()
    private var bannerJob: Job? = null

    /** 横幅出现在屏幕附近时要它的信息：读过的直接给，没读过的 150 ms 内要的合成一批，用公开的 GetItems 读。 */
    fun requestBanner(appid: Int) {
        if (banners.containsKey(appid)) return
        appInfo(appid)?.let {
            banners[appid] = it
            return
        }
        bannerQueue.add(appid)
        if (bannerJob?.isActive == true) return
        bannerJob = scope.launch {
            delay(150)
            while (bannerQueue.isNotEmpty()) {
                val batch = bannerQueue.take(30)
                bannerQueue.removeAll(batch.toSet())
                fetchItems(batch)
                for (id in batch) appInfo(id)?.let { banners[id] = it }
            }
        }
    }

    // ---- 评论 ----

    /** 读评论：第一次读最新 3 条，[more] 时再往下读 10 条。 */
    fun loadComments(key: String, more: Boolean = false) {
        val it = items[key] ?: return
        if (it.gid.isEmpty()) return
        val c = comments.getOrPut(key) { NewsComments(it.comments) }
        if (c.loading || (!more && c.error == null && c.list.isNotEmpty())) return
        c.loading = true
        c.error = null
        scope.launch {
            try {
                val (total, got) = fetchComments(it.gid, c.list.size, if (c.list.isEmpty()) 3 else 10)
                val add = got.filter { x -> c.list.none { y -> y.id == x.id } }
                c.list.addAll(add)
                c.total = if (add.isEmpty()) c.list.size else max(total, c.list.size)
            } catch (e: Exception) {
                c.error = errText(e)
            }
            c.loading = false
        }
    }

    /** 一条新闻的评论（Steam 社区的讨论串，新的在前），不需要登录。 */
    private suspend fun fetchComments(gid: String, start: Int, count: Int): Pair<Int, List<NewsComment>> {
        val ev = events[gid]
        val type = ev?.optString("comment_type") ?: ""
        val owner = ev?.optString("clan_steamid") ?: ""
        val feature = ev?.optString("gidfeature") ?: ""
        val feature2 = ev?.optString("gidfeature2") ?: ""
        if (type.isEmpty() || owner.isEmpty() || feature.isEmpty() || feature == "0" || !Regex("^[A-Za-z0-9]+$").matches(type + owner + feature + feature2)) return 0 to emptyList()
        val j = session.postPublic(
            "https://steamcommunity.com/comment/$type/render/$owner/$feature/",
            mapOf("start" to start.toString(), "count" to count.toString(), "feature2" to feature2, "extended_data" to "{\"topic_permissions\":{\"can_view\":1}}"),
        )
        if (!j.optBoolean("success")) throw SteamException(0, "评论读取失败：" + j.optString("error").ifEmpty { "Steam 没有返回内容" })
        val list = withContext(Dispatchers.Default) { parseComments(j.optString("comments_html")) }
        return j.optInt("total_count") to list
    }

    private fun parseComments(html: String): List<NewsComment> =
        html.split("class=\"commentthread_comment ").drop(1).mapNotNull { part ->
            val id = Regex("^[^>]*id=\"(comment_\\d+)\"").find(part)?.groupValues?.get(1) ?: Regex("id=\"(comment_\\d+)\"").find(part)?.groupValues?.get(1) ?: return@mapNotNull null
            val textHtml = Regex("commentthread_comment_text\"[^>]*>(.*?)</div>\\s*(?:<div class=\"comment_footer_ctn|<div class=\"commentthread_comment_footer|</div>)", RegexOption.DOT_MATCHES_ALL)
                .find(part)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = Html.fromHtml(
                textHtml.replace(Regex("<div class=\"needs_content_check\">.*?</div>", RegexOption.DOT_MATCHES_ALL), "（这条评论还在等 Steam 审核）")
                    .replace(Regex("<img[^>]*alt=\"([^\"]*)\"[^>]*>"), "$1"),
                Html.FROM_HTML_MODE_LEGACY,
            ).toString().trim()
            // 作者链接的 class 后面可能跟着别的 class 和空格；名字的 <bdi> 里还套着一个下拉菜单的 <span>
            val link = Regex("commentthread_author_link[^\"]*\"[^>]*href=\"([^\"]+)\"|href=\"([^\"]+)\"[^>]*class=\"[^\"]*commentthread_author_link").find(part)
            val url = link?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
            val name = Regex("commentthread_author_link[^\"]*\"[^>]*>\\s*<bdi>(.*?)</bdi>", RegexOption.DOT_MATCHES_ALL).find(part)?.groupValues?.get(1)
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: ""
            // 头像在名字前面那个带 data-miniprofile 的链接里（外面那张是头像框）
            val head = part.substringBefore("author_name_group")
            val avatar = Regex("data-miniprofile=\"\\d+\"[^>]*>\\s*<img (?:src|srcset)=\"([^\"\\s]+)").find(head)?.groupValues?.get(1) ?: ""
            val time = Regex("data-timestamp=\"(\\d+)\"").find(part)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            NewsComment(id, name.ifEmpty { "（匿名）" }, url.takeIf { it.startsWith("http") } ?: "", avatar, time, text)
        }

    // ---- 缓存（下次打开先显示上次的内容，后台再刷新）----

    private var cachedOwner = ""

    private class Cache(
        val owner: String, val docs: List<Doc>, val events: Map<String, Pair<JSONObject, String?>>, val apps: Map<Int, JSONObject>, val appsAt: Long,
        val tags: Map<Int, String>?, val wish: List<Int>, val owned: Set<Int>, val followed: Int,
    )

    /** 在 IO 线程读缓存文件（连同去重指纹一起算好）。 */
    private fun readCache(): Cache? {
        if (!cacheFile.exists()) return null
        return try {
            val o = JSONObject(cacheFile.readText())
            val docs = mutableListOf<Doc>()
            o.optJSONArray("docs")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.optJSONArray(i) ?: continue
                    docs.add(Doc(d.optString(0), d.optInt(1), d.optInt(2), d.optLong(3), d.optInt(4), d.optInt(5)))
                }
            }
            val evs = HashMap<String, Pair<JSONObject, String?>>()
            o.optJSONObject("events")?.let { e -> e.keys().forEach { k -> e.optJSONObject(k)?.let { evs[k] = it to dupKey(it) } } }
            val apps = HashMap<Int, JSONObject>()
            o.optJSONObject("apps")?.let { e -> e.keys().forEach { k -> e.optJSONObject(k)?.let { apps[k.toInt()] = it } } }
            val tags = o.optJSONObject("tags")?.let { e -> e.keys().asSequence().associate { it.toInt() to e.optString(it) } }
            Cache(o.optString("owner"), docs, evs, apps, o.optLong("appsAt"), tags, o.optJSONArray("wish").ints(), o.optJSONArray("owned").ints().toSet(), o.optInt("followed"))
        } catch (e: Exception) {
            Log.e("读取新闻缓存失败", e)
            null
        }
    }

    private fun applyCache(c: Cache) {
        cachedOwner = c.owner
        c.docs.forEach { docs[it.gid] = it }
        c.events.forEach { (k, v) ->
            events[k] = v.first
            dupKeys[k] = v.second
        }
        c.apps.forEach { (k, v) ->
            appsInfo[k] = v
            appsAt[k] = c.appsAt
        }
        tags = c.tags
        c.wish.forEach { wish[it] = true }
        owned = c.owned
        followedCount = c.followed
        // 缓存里的是一段接在现在的新闻：往下翻时从最老的那条接着读
        oldestDoc = docs.values.minOfOrNull { it.t } ?: Long.MAX_VALUE
        ready = docs.isNotEmpty()
        Log.i("读到新闻缓存：${docs.size} 条")
    }

    /** 最近 lookback + 10 天的新闻和它们的内容、游戏信息写进缓存。 */
    private fun saveCache() {
        val keepFrom = now - (store.lookbackDays + 10) * DAY
        val keep = docs.values.filter { it.t >= keepFrom }
        val gids = keep.map { it.gid }.toHashSet()
        store.releases.values.forEach { if (it.eventGid.isNotEmpty()) gids.add(it.eventGid) }
        val owner = session.steamId
        val evs = gids.mapNotNull { g -> events[g]?.takeIf { !it.has("missing") }?.let { g to it } }
        val apps = appsInfo.filterValues { !it.has("missing") }
        val tagMap = tags
        val wishList = wish.keys.toList()
        val ownedList = owned.toList()
        val followedN = followedCount
        val at = now
        cachedOwner = owner
        scope.launch(Dispatchers.IO) {
            runCatching {
                val o = JSONObject()
                o.put("owner", owner)
                o.put("docs", JSONArray(keep.map { JSONArray(listOf(it.gid, it.clan, it.appId, it.t, it.type, it.source)) }))
                o.put("events", JSONObject().apply { evs.forEach { (g, e) -> put(g, e) } })
                o.put("apps", JSONObject().apply { apps.forEach { (k, v) -> put(k.toString(), v) } })
                o.put("appsAt", at)
                tagMap?.let { t -> o.put("tags", JSONObject().apply { t.forEach { (k, v) -> put(k.toString(), v) } }) }
                o.put("wish", JSONArray(wishList))
                o.put("owned", JSONArray(ownedList))
                o.put("followed", followedN)
                val tmp = File(cacheFile.path + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.delete()
                    tmp.renameTo(cacheFile)
                }
            }.onFailure { Log.e("保存新闻缓存失败", it) }
        }
    }
}

// ---- 显示用的小工具 ----

/** 时间：刚刚、x 分钟前、x 小时前，更早的显示几月几日几点。 */
fun whenText(t: Long): String {
    if (t <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - t
    if (diff in 0..59) return "刚刚"
    if (diff in 60..3599) return "${diff / 60} 分钟前"
    if (diff in 3600..86399) return "${diff / 3600} 小时前"
    val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
    val nowYear = Calendar.getInstance().get(Calendar.YEAR)
    val y = if (c.get(Calendar.YEAR) != nowYear) "${c.get(Calendar.YEAR)}年" else ""
    return y + "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

fun dayText(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
    return "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
}

fun shortDayText(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
    return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
}
