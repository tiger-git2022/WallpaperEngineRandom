package com.tiger.steamwallpaper

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

enum class Kind { Timeline, Browse, Author }

/** 一个列表（时间线、浏览 / 搜索、作者页）：已加载的壁纸 ID 和分页状态，和电脑版的 view 一样。 */
class ListView(val kind: Kind, val search: String = "", val author: String = "", val sortKey: String = "") {
    val ids = mutableStateListOf<String>()
    var cursor = "*"
    var page = 0
    var done by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var total by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var errorStatus = 0
    var following by mutableStateOf(false)
    var hasFollow by mutableStateOf(false)

    /** 换标签页再回来时保持滚动位置。 */
    val list = LazyListState()
    val grid = LazyGridState()

    /** 下拉刷新时在后台加载的新列表（加载完换上来，加载期间旧列表照常显示）。 */
    var refreshing by mutableStateOf<ListView?>(null)
    var replaces: ListView? = null

    /** 时间线：第一次加载完（读到已读边界）后滚到上次看到的位置，只做一次。 */
    var settled by mutableStateOf(false)
    var restored = false

    /**
     * 时间线：「以下是已经看过的」那条线的位置（发布时间），加载这个列表时定下来，之后不动；
     * 往上翻看时阅读进度照常往上走，但线和分组要等下拉刷新才更新（像微博的「上次看到这里」）。
     */
    var dividerUpTo by mutableStateOf<Long?>(null)

    fun fresh() = ListView(kind, search, author, sortKey)
}

/** 一个壁纸已经读到的评论。 */
class Comments {
    val list = mutableStateListOf<Steam.Comment>()
    var total by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

data class Me(val loggedIn: Boolean = false, val steamId: String = "", val name: String = "", val avatar: String = "")

/** 作者页（从标签页或别的作者页打开，返回键回到上一层）。 */
class AuthorScreen(val steamId: String) {
    var view by mutableStateOf(ListView(Kind.Author, author = steamId))

    /** 顶栏换成了搜索框。 */
    var searching by mutableStateOf(false)

    /**
     * 在这个作者的作品里搜索的关键词（边输边筛）。作者网页不支持搜索，所以在本地按标题、标签和简介匹配；
     * 有关键词时列表会自动把作者的作品全部读完。
     */
    var query by mutableStateOf("")
}

/** 作者页的本地搜索：空格分开的每个词都要在标题、标签或简介里出现（不分大小写）。 */
fun Item.matches(query: String): Boolean {
    val words = query.trim().split(' ', '　', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = (listOf(title, short, description.orEmpty()) + tags).joinToString("\n")
    return words.all { text.contains(it, ignoreCase = true) }
}

/**
 * 整个创意工坊的状态和逻辑，照搬电脑版 workshop.html 的脚本：列表的加载、时间线的阅读进度、关注、订阅、作者资料。
 * 界面（Compose）只读这里的状态、调这里的方法。只在主线程上用。
 */
class Workshop(val steam: Steam, val sub: Steam, val store: Store, private val scope: CoroutineScope) {
    var me by mutableStateOf(Me())
        private set

    /** 订阅账号（大号）：只负责订阅；没登录时订阅用浏览账号。 */
    var subMe by mutableStateOf(Me())
        private set

    /** 订阅用的账号。 */
    private val subscriber: Steam get() = if (sub.loggedIn) sub else steam

    /** 正在从订阅账号导入关注：进度文字；null = 没在导入。 */
    var importing by mutableStateOf<String?>(null)
        private set

    /** 读到过的壁纸，ID → 壁纸。 */
    val items = mutableStateMapOf<String, Item>()

    var timeline by mutableStateOf<ListView?>(null)
        private set
    var browse by mutableStateOf<ListView?>(null)
        private set

    /** 打开的作者页，最后一个在最上面。 */
    val stack = mutableStateListOf<AuthorScreen>()

    /** 详情面板显示的壁纸。 */
    var detail by mutableStateOf<String?>(null)

    /** 详情面板里可以左右滑动切换的壁纸（合并显示的一组）；只有一个时不能滑。 */
    var detailList by mutableStateOf<List<String>>(emptyList())
        private set

    /** 搜索框里的文字和已经提交的搜索。 */
    var query by mutableStateOf("")
    var search by mutableStateOf("")
        private set

    var unread by mutableIntStateOf(0)
        private set

    /** 正在订阅 / 取消订阅、关注 / 取消关注的（显示转圈）。 */
    val busy = mutableStateMapOf<String, Boolean>()

    var followsLoading by mutableStateOf(false)
        private set

    /** 关注的作者列表的滚动位置。 */
    val followingList = LazyListState()

    var toast by mutableStateOf<Pair<String, Long>?>(null)
        private set

    /** 屏幕下方的提示；[long] 的（出错）显示久一点。 */
    fun toast(text: String, long: Boolean = false) {
        toast = text to System.currentTimeMillis()
        toastLong = long
    }

    var toastLong = false
        private set

    // ---- login ----

    /** 重新读登录状态（启动、登录页关闭、回到前台时）。账号变了就重新加载。 */
    fun refreshLogin() {
        refreshSub()
        val changed = steam.syncCookies()
        scope.launch {
            // 登录状态都读完后（下面的 me 也更新了）再看订阅列表要不要读：换了账号、或者一天没读过完整的就全部重读，
            // 否则只同步第一页（新订阅的、刚取消的）
            loadRemoteSubs(force = store.remoteSubsOwner.isNotEmpty() && store.remoteSubsOwner != subscriber.steamId)
            syncRecentSubs()
        }
        if (!changed && me.steamId == steam.steamId) return
        me = Me(steam.loggedIn, steam.steamId, store.profiles[steam.steamId]?.name ?: "", store.profiles[steam.steamId]?.avatar ?: "")
        if (steam.loggedIn) {
            scope.launch {
                val p = steam.profile(steam.steamId)
                if (p.name.isNotEmpty() && me.steamId == p.steamId) {
                    me = me.copy(name = p.name, avatar = p.avatar)
                    store.profiles[p.steamId] = CachedProfile(p.name, p.avatar, System.currentTimeMillis())
                    store.save()
                }
            }
        }
        if (changed) afterLogin()
    }

    private fun refreshSub() {
        if (!sub.syncCookies() && subMe.steamId == sub.steamId) return
        val id = sub.steamId
        subMe = Me(sub.loggedIn, id, store.profiles[id]?.name ?: "", store.profiles[id]?.avatar ?: "")
        if (!sub.loggedIn) return
        scope.launch {
            val p = steam.profile(id)
            if (p.name.isNotEmpty() && subMe.steamId == p.steamId) {
                subMe = subMe.copy(name = p.name, avatar = p.avatar)
                store.profiles[p.steamId] = CachedProfile(p.name, p.avatar, System.currentTimeMillis())
                store.save()
            }
        }
    }

    /** 订阅账号登录页关闭后。 */
    fun afterSubLogin() {
        store.subAccount = true
        store.save()
        refreshSub()
        if (subMe.loggedIn) toast(if (subMe.steamId == me.steamId) "订阅账号和浏览账号是同一个号" else "已登录订阅账号，订阅会加到这个号上")
    }

    fun logoutSub() {
        sub.logout()
        refreshSub()
        toast("已退出订阅账号，订阅改用浏览账号")
    }

    /** 把订阅账号（大号）关注的作者用浏览账号（小号）也关注上；只加不减。 */
    fun importFollows() {
        if (!sub.loggedIn || !me.loggedIn || importing != null) return
        importing = "正在读取订阅账号的关注…"
        scope.launch {
            try {
                val theirs = sub.following()
                val todo = theirs.filter { it !in store.follows && it != me.steamId }
                ensureProfiles(todo)
                var ok = 0
                var failed = 0
                for ((i, id) in todo.withIndex()) {
                    importing = "正在关注 ${i + 1} / ${todo.size}"
                    val done = runCatching { steam.follow(id, true) }.getOrDefault(false)
                    if (done) {
                        ok++
                        setFollow(id, true)
                    } else {
                        failed++
                    }
                    delay(400)
                }
                Log.i("从订阅账号导入关注：它关注 ${theirs.size} 个，新关注 $ok 个，失败 $failed 个")
                toast(
                    if (todo.isEmpty()) "订阅账号关注的 ${theirs.size} 个作者，浏览账号都已经关注了"
                    else "导入了 $ok 个关注" + (if (failed > 0) "，$failed 个失败" else "") + "（订阅账号共关注 ${theirs.size} 个）",
                )
                if (ok > 0) {
                    timeline = null
                    timelineView()
                    loadFollows()
                }
            } catch (e: Exception) {
                toast("导入关注失败：" + errText(e))
            }
            importing = null
        }
    }

    private fun afterLogin() {
        timeline = null
        browse = null
        stack.clear()
        if (!me.loggedIn) {
            unread = 0
            return
        }
        loadFollows()
        // 壁纸时间线在别的标签页时也在后台加载，好显示未读数
        openTab(store.tab)
        if (store.tab != "timeline") timelineView()
        App.instance.news.refreshLogin()
    }

    fun logout() {
        steam.logout()
        App.instance.news.logout()
        me = Me()
        afterLogin()
        toast("已退出 Steam")
    }

    // ---- tabs ----

    fun openTab(tab: String) {
        store.tab = tab
        store.save()
        stack.clear()
        when (tab) {
            "timeline" -> timelineView()
            "browse" -> browseView()
            "news" -> App.instance.news.autoRefresh()
            "following" -> if (store.follows.isEmpty() && me.loggedIn && !followsLoading) loadFollows()
        }
    }

    fun timelineView(): ListView {
        timeline?.let { return it }
        val v = ListView(Kind.Timeline)
        timeline = v
        loadMore(v)
        return v
    }

    fun browseView(): ListView {
        browse?.takeIf { it.search == search && it.sortKey == store.sort }?.let { return it }
        val v = ListView(Kind.Browse, search = search, sortKey = store.sort)
        browse = v
        loadMore(v)
        return v
    }

    fun refreshTimeline() {
        timeline = null
        timelineView()
    }

    /** 下拉刷新（或在顶部再点一次标签）：在后台重新加载第一页，加载完换掉旧列表。 */
    fun refresh(v: ListView, reposition: Boolean = false) {
        if (v.refreshing != null) return
        val n = v.fresh()
        n.replaces = v
        // 下拉刷新后停在最上面，不再跳回上次看到的位置；[reposition]（从后台回来时的刷新）回到「以下是已经看过的」
        n.restored = !reposition
        v.refreshing = n
        loadMore(n)
        // 顺便同步一下最近订阅的
        syncRecentSubs()
    }

    /** 切到后台的时间。 */
    private var leftAt = 0L

    fun leave() {
        leftAt = System.currentTimeMillis()
    }

    /**
     * 回到前台：在后台放了半小时以上，时间线重新加载（和重新打开一样）：新的排在上面，「以下是已经看过的」移到现在的阅读进度，
     * 加载完回到这条线。
     */
    fun resumeTimeline() {
        val left = leftAt
        leftAt = 0L
        if (left == 0L || System.currentTimeMillis() - left < 30 * 60_000L) return
        val v = timeline ?: return
        if (!me.loggedIn || !v.restored || v.refreshing != null || v.loading) return
        refresh(v, reposition = true)
    }

    private fun swapIn(n: ListView) {
        val old = n.replaces ?: return
        n.replaces = null
        old.refreshing = null
        if (n.error != null && n.ids.isEmpty()) {
            toast("刷新失败：${n.error}")
            return
        }
        if (timeline === old) timeline = n
        if (browse === old) browse = n
        stack.firstOrNull { it.view === old }?.view = n
    }

    fun submitSearch() {
        val t = query.trim()
        if (t.isNotEmpty() && store.sort.startsWith("trend")) store.sort = "text"
        search = t
        browse = null
        detail = null
        openTab("browse")
    }

    fun clearSearch() {
        query = ""
        search = ""
        browse = null
        if (store.tab == "browse") browseView()
    }

    fun setSort(key: String) {
        store.sort = key
        store.save()
        browse = null
        openTab("browse")
    }

    /** 点标签：属于筛选里的组就只筛这一个，否则当成搜索词。 */
    fun searchTag(tag: String) {
        detail = null
        val g = GROUPS.firstOrNull { gr -> gr.tags.any { it.first == tag } }
        if (g != null) {
            GROUPS.forEach { store.filters[it.key] = emptyList() }
            store.filters[g.key] = listOf(tag)
            store.save()
            timeline = null
            browse = null
            if (me.loggedIn) timelineView()
            openTab("browse")
            return
        }
        query = tag
        submitSearch()
    }

    private var filterJob: Job? = null

    fun toggleFilter(key: String, tag: String) {
        val cur = store.filters[key].orEmpty()
        store.filters[key] = if (tag in cur) cur - tag else cur + tag
        store.save()
        filtersChanged()
    }

    fun clearFilters() {
        GROUPS.forEach { store.filters[it.key] = emptyList() }
        store.save()
        filtersChanged()
    }

    private fun filtersChanged() {
        filterJob?.cancel()
        filterJob = scope.launch {
            delay(350)
            timeline = null
            browse = null
            if (me.loggedIn) timelineView()
            if (store.tab == "browse" && stack.isEmpty()) browseView()
        }
    }

    // ---- authors ----

    fun openAuthor(steamId: String) {
        detail = null
        if (stack.lastOrNull()?.steamId == steamId) return
        val s = AuthorScreen(steamId)
        stack.add(s)
        ensureProfiles(listOf(steamId))
        loadMore(s.view)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        // 先收起搜索框，再返回上一层
        stack.last().let { if (it.searching) { it.searching = false; it.query = ""; return true } }
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun loadFollows() {
        if (!me.loggedIn || followsLoading) return
        followsLoading = true
        scope.launch {
            try {
                val list = steam.following()
                store.follows.clear()
                store.follows.addAll(list)
                store.save()
                ensureProfiles(list)
            } catch (e: Exception) {
                toast("读取关注列表失败：" + errText(e))
            }
            followsLoading = false
        }
    }

    fun isFollowing(id: String) = id in store.follows

    private fun setFollow(id: String, on: Boolean) {
        if (on && id !in store.follows) store.follows.add(id)
        if (!on) store.follows.remove(id)
        store.save()
    }

    fun follow(id: String, on: Boolean) {
        val key = "follow:$id"
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            try {
                if (!steam.follow(id, on)) throw Exception("Steam 没有确认")
                setFollow(id, on)
                stack.filter { it.steamId == id }.forEach { it.view.following = on }
                toast(if (on) "已关注，新作品会出现在时间线里" else "已取消关注")
                // 时间线的内容变了，下次打开时重新加载
                if (store.tab != "timeline") timeline = null
            } catch (e: Exception) {
                toast((if (on) "关注" else "取消关注") + "失败：" + errText(e))
            }
            busy.remove(key)
        }
    }

    private val profileQueue = LinkedHashSet<String>()
    private var profileJob: Job? = null

    /** 读作者名字和头像（缓存一周），120 ms 内要的合并成一批。 */
    fun ensureProfiles(ids: Collection<String>) {
        val week = System.currentTimeMillis() - 7 * 86400_000L
        for (id in ids) if (id.isNotEmpty() && (store.profiles[id]?.let { it.time < week } != false)) profileQueue.add(id)
        if (profileQueue.isEmpty() || profileJob?.isActive == true) return
        profileJob = scope.launch {
            delay(120)
            while (profileQueue.isNotEmpty()) {
                val batch = profileQueue.take(60)
                profileQueue.removeAll(batch.toSet())
                val list = batch.map { id -> async { runCatching { steam.profile(id) }.getOrNull() } }.awaitAll()
                val now = System.currentTimeMillis()
                for (p in list) if (p != null && p.name.isNotEmpty()) store.profiles[p.steamId] = CachedProfile(p.name, p.avatar, now)
                store.save()
            }
        }
    }

    // ---- lists ----

    private fun remember(f: Item): Item {
        val old = items[f.id]
        val n = old?.merge(f) ?: f
        items[f.id] = n
        return n
    }

    fun loadMore(v: ListView) {
        if (v.loading || v.done) return
        if (!me.loggedIn && v.kind != Kind.Author) return
        v.loading = true
        v.error = null
        scope.launch {
            try {
                if (v.kind == Kind.Author) {
                    val r = steam.creatorFiles(v.author, v.page + 1)
                    v.page++
                    v.total = r.total
                    v.following = r.following
                    v.hasFollow = r.hasFollowButton
                    if (me.loggedIn && r.hasFollowButton) setFollow(v.author, r.following)
                    if (r.ids.isNotEmpty()) {
                        val order = r.ids.withIndex().associate { it.value to it.index }
                        Item.parseList(steam.details(r.ids)).sortedBy { order[it.id] ?: 0 }.forEach {
                            val it2 = remember(it)
                            if (it2.id !in v.ids) v.ids.add(it2.id)
                        }
                    }
                    if (r.ids.isEmpty() || v.page * 30 >= r.total) v.done = true
                } else {
                    val input = JSONObject()
                    input.put("cursor", v.cursor)
                    input.put("numperpage", if (v.kind == Kind.Timeline) 50 else 30)
                    val groups = store.tagGroups()
                    if (groups.length() > 0) input.put("taggroups", groups)
                    if (v.kind == Kind.Timeline) {
                        input.put("query_type", 7)
                    } else {
                        var sort = SORTS.firstOrNull { it.key == v.sortKey } ?: SORTS[0]
                        if (sort.key == "text" && v.search.isEmpty()) sort = SORTS[0]
                        if (v.search.isNotEmpty() && v.sortKey.startsWith("trend")) sort = SORTS.first { it.key == "text" }
                        sort.input.forEach { (k, x) -> input.put(k, x) }
                        if (v.search.isNotEmpty()) input.put("search_text", v.search)
                    }
                    val text = steam.query(input)
                    val r = JSONObject(text).optJSONObject("response") ?: JSONObject()
                    v.total = r.optInt("total")
                    val list = Item.parseList(text)
                    for (f in list) {
                        val it = remember(f)
                        if (it.id !in v.ids) v.ids.add(it.id)
                    }
                    val next = r.optString("next_cursor")
                    if (next.isEmpty() || next == v.cursor || r.optJSONArray("publishedfiledetails")?.length() == 0 || !r.has("publishedfiledetails")) v.done = true
                    if (next.isNotEmpty()) v.cursor = next
                }
            } catch (e: Exception) {
                v.error = errText(e)
                v.errorStatus = (e as? SteamException)?.status ?: 0
                Log.i("加载 ${v.kind} 失败：${e.message}")
            }
            v.loading = false
            ensureProfiles(v.ids.mapNotNull { items[it]?.creator }.toSet())
            if (v.replaces != null) swapIn(v)
            if (v.kind == Kind.Timeline && v === timeline) afterTimelineLoad(v)
        }
    }

    // ---- reading progress (timeline) ----
    // readUpTo：发布时间；这个时间及以前的都算看过。边界以上的壁纸在屏幕上露出来就一个个记为看过（seen）；
    // 从最新的一条到边界之间全部看过时，边界上移。

    fun boundaryIndex(v: ListView): Int {
        val i = v.ids.indexOfFirst { (items[it]?.created ?: 0) <= store.readUpTo }
        return if (i < 0) v.ids.size else i
    }

    private fun afterTimelineLoad(v: ListView) {
        if (v.ids.isEmpty()) {
            unread = 0
            return
        }
        v.settled = true
        if (store.readUpTo == 0L) {
            store.readUpTo = items[v.ids[0]]?.created ?: 0
            store.save()
            toast("从现在开始记录时间线的浏览进度")
        }
        if (v.dividerUpTo == null) v.dividerUpTo = store.readUpTo
        // 一直加载到旧边界出现在列表里，未读数才准（最多大约 600 条）
        val last = items[v.ids.last()]
        if (!v.done && v.error == null && last != null && last.created > store.readUpTo && v.ids.size < 600) {
            v.settled = false
            loadMore(v)
            return
        }
        updateBoundary(v)
    }

    private fun updateBoundary(v: ListView) {
        val b = boundaryIndex(v)
        var i = b - 1
        while (i >= 0 && store.seen[v.ids[i]] == true) i--
        if (i + 1 < b) {
            store.readUpTo = items[v.ids[i + 1]]?.created ?: store.readUpTo
            for (id in store.seen.keys.toList()) {
                val it = items[id]
                if (it == null || it.created <= store.readUpTo) store.seen.remove(id)
            }
            store.save()
        }
        unread = v.ids.take(boundaryIndex(v)).count { id -> store.seen[id] != true && items[id]?.let(store::passesFilters) == true }
    }

    private var boundaryJob: Job? = null

    /** 时间线里的一张图在屏幕上露出 60% 以上、停了 0.7 秒。 */
    fun markSeen(id: String) {
        if (store.seen[id] == true) return
        val v = timeline ?: return
        val it = items[id] ?: return
        if (it.created <= store.readUpTo || id !in v.ids) return
        store.seen[id] = true
        store.save()
        boundaryJob?.cancel()
        boundaryJob = scope.launch {
            delay(300)
            if (timeline === v) updateBoundary(v)
        }
    }

    fun isNew(it: Item) = it.created > store.readUpTo && store.seen[it.id] != true

    /** 把时间线上现在所有的壁纸记为看过（已读线移到最新的一条）。 */
    fun markAllRead() {
        val v = timeline ?: return
        if (v.ids.isEmpty() || unread == 0) return
        store.readUpTo = items[v.ids[0]]?.created ?: return
        store.seen.clear()
        store.save()
        updateBoundary(v)
        toast("已全部标为已读")
    }

    fun setMerge(on: Boolean) {
        store.mergePosts = on
        store.save()
    }

    // ---- details ----

    /** 详情是从哪个列表（作者页 / 浏览工坊）打开的：滑到最后时接着加载它的下一页。 */
    var detailSource: ListView? = null
        private set

    /** 每次从页面上打开详情加一（详情里左右滑动切换不算）：面板按它重建翻页。 */
    var detailSession by mutableStateOf(0)
        private set

    fun openDetail(id: String, list: List<String> = listOf(id), source: ListView? = null) {
        detailList = if (id in list) list else listOf(id)
        detailSource = source
        detailSession++
        showDetail(id)
    }

    /** 列表加载了下一页：把新的作品接到详情的末尾（已有的不动，翻到的位置不变）。 */
    fun extendDetail(source: ListView, ids: List<String>) {
        if (detail == null || detailSource !== source) return
        val have = detailList.toHashSet()
        val add = ids.filter { it !in have }
        if (add.isNotEmpty()) detailList = detailList + add
    }

    /** 详情里左右滑到另一个作品。 */
    fun showDetail(id: String) {
        detail = id
        checkSubscribed(id)
        if (timeline?.ids?.contains(id) == true) markSeen(id)
        val it = items[id]
        if (it == null || it.description == null) {
            scope.launch {
                try {
                    Item.parseList(steam.details(listOf(id))).firstOrNull()?.let { f ->
                        remember(f.copy(description = f.description ?: ""))
                        ensureProfiles(listOf(f.creator))
                    }
                } catch (e: Exception) {
                    if (items[id] == null) toast("读取失败：" + errText(e))
                }
            }
        }
        it?.let { ensureProfiles(listOf(it.creator)) }
    }

    /** 时间线卡片里的「展开全文」：读完整简介。 */
    fun expandText(id: String) {
        val key = "text:$id"
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            try {
                val f = Item.parseList(steam.details(listOf(id))).firstOrNull()
                val cur = items[id]
                if (cur != null) items[id] = cur.copy(description = f?.description ?: cur.short)
            } catch (e: Exception) {
                toast("读取简介失败：" + errText(e))
            }
            busy.remove(key)
        }
    }

    // ---- comments ----

    /** 壁纸的评论，ID → 已读到的评论。 */
    val comments = mutableStateMapOf<String, Comments>()

    /** 读评论的下一页（第一次打开详情时读第一页）。 */
    fun loadComments(id: String) {
        val it = items[id] ?: return
        val c = comments.getOrPut(id) { Comments() }
        if (c.loading || c.done) return
        c.loading = true
        c.error = null
        scope.launch {
            try {
                val (total, list) = steam.comments(it.creator, id, c.list.size, 20)
                c.total = total
                c.list.addAll(list)
                if (list.isEmpty() || c.list.size >= total) c.done = true
            } catch (e: Exception) {
                c.error = errText(e)
            }
            c.loading = false
        }
    }

    // ---- subscribe ----

    /** 订阅用的账号的 Steam ID（Compose 状态，界面里读它会跟着变）。 */
    val subscriberId: String get() = if (subMe.loggedIn) subMe.steamId else me.steamId

    /**
     * 订阅了没有：刚在 App 里点过的以点的为准；否则读过订阅账号在 Steam 上的订阅列表就按列表（在电脑上取消的也能看出来），
     * 没读过才用 App 里记下的。
     */
    fun isSubscribed(id: String): Boolean {
        val owner = subscriberId
        localSub["$owner/$id"]?.let { return it }
        return if (store.remoteSubsOwner.isNotEmpty() && store.remoteSubsOwner == owner) store.remoteSubs.containsKey(id) else store.subscribed.containsKey(id)
    }

    /**
     * 在 App 里刚订阅 / 取消订阅的（「账号/壁纸 ID」→ 订阅了没有）和时间。点之前开始读的、或点完一分钟内读到的网页和订阅列表
     * 可能还是旧的（Steam 的列表要过一会才更新），不能拿来盖掉它。
     */
    private val localSub = mutableStateMapOf<String, Boolean>()
    private val localSubAt = HashMap<String, Long>()

    /** 正在到网页上核对订阅状态的壁纸（打开详情时；值是同时在核对的次数）。 */
    val checkingSub = mutableStateMapOf<String, Int>()

    private fun changedSince(owner: String, id: String, started: Long) = (localSubAt["$owner/$id"] ?: 0L) >= started - 60_000L

    var subsLoading by mutableStateOf(false)
        private set

    /**
     * 读订阅账号（没有时是浏览账号）在 Steam 上的完整订阅列表，在所有列表和详情里显示「已订阅」。一天内读过就不重读
     * （很久以前的订阅很少取消，平时由 [syncRecentSubs] 只同步第一页），[force] 时（手动刷新、换了账号、第一页对不上）一定读。
     */
    fun loadRemoteSubs(force: Boolean = false, quiet: Boolean = false) {
        val acct = subscriber
        if (!acct.loggedIn || subsLoading) return
        val owner = acct.steamId
        if (!force && store.remoteSubsOwner == owner && System.currentTimeMillis() - store.remoteSubsTime < 24 * 3600_000L) return
        // 换了账号或手动刷新时读完要告诉用户；自动重读不打扰
        val announce = !quiet && (force || store.remoteSubsOwner != owner)
        subsLoading = true
        subsError = null
        subsProgress = 0f
        subsStatus = "正在读取第 1 页…"
        val started = System.currentTimeMillis()
        scope.launch {
            try {
                val all = LinkedHashSet<String>()
                // 第一页拿到总数，剩下的几页同时读（每次 4 页），1000 多个订阅也只要十几秒
                val first = acct.subscriptions(1)
                all.addAll(first.ids)
                val total = first.total
                val pages = if (total > 0 && first.ids.isNotEmpty()) minOf(400, (total + first.ids.size - 1) / first.ids.size) else 1
                var done = 1
                var failed = 0
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                fun progress() {
                    subsProgress = done.toFloat() / pages
                    subsStatus = "正在读取：$done / $pages 页，已读到 ${all.size} 个" + if (total > 0) "（共 $total 个）" else ""
                }
                progress()
                (2..pages).map { p ->
                    async {
                        gate.withPermit {
                            // 失败的页重试一次
                            val r = runCatching { acct.subscriptions(p) }.recoverCatching { delay(1500); acct.subscriptions(p) }.getOrNull()
                            if (r == null) failed++ else all.addAll(r.ids)
                            done++
                            progress()
                        }
                    }
                }.awaitAll()
                if (acct.steamId == owner) {
                    // 有几页没读到：以前读到的先留着，免得把订阅了的显示成没订阅
                    if (failed > 0 && store.remoteSubsOwner == owner) all.addAll(store.remoteSubs.keys)
                    store.remoteSubs.clear()
                    all.forEach { store.remoteSubs[it] = true }
                    // 读的时候（或之前一分钟内）在 App 里点过的以点的为准；更早点的，列表已经是新的了，按列表
                    for ((k, at) in localSubAt) {
                        if (!k.startsWith("$owner/")) continue
                        val id = k.substringAfter('/')
                        if (at >= started - 60_000L) {
                            if (localSub[k] == true) store.remoteSubs[id] = true else store.remoteSubs.remove(id)
                        } else {
                            localSub.remove(k)
                        }
                    }
                    // App 里记下的、但已经不在订阅列表里的（在电脑上或网页上取消了）：去掉
                    if (failed == 0) store.subscribed.keys.filter { it !in store.remoteSubs }.forEach { store.subscribed.remove(it) }
                    store.remoteSubsOwner = owner
                    store.remoteSubsTime = System.currentTimeMillis()
                    store.remoteSubsTotal = total
                    store.remoteSubsHead = first.ids
                    store.save()
                    Log.i("读取订阅列表（$owner）：网页上写共 $total 个，读到 ${all.size} 个（$pages 页，$failed 页失败）")
                    if (failed > 0) subsError = "有 $failed 页没读到，订阅状态可能不全，可以再刷新一次"
                    if (announce) toast("订阅列表读取完成：${all.size} 个" + if (failed > 0) "，有 $failed 页没读到" else "", long = failed > 0)
                }
            } catch (e: Exception) {
                Log.i("读取订阅列表失败（$owner）：${e.message}")
                subsError = "读取失败：" + errText(e)
                if (announce) toast("读取订阅列表失败：" + errText(e), long = true)
            }
            subsLoading = false
            subsProgress = null
            subsStatus = ""
        }
    }

    /** 正在同步订阅列表的第一页；上次同步完的时间（毫秒）。 */
    var recentSyncing by mutableStateOf(false)
        private set
    var recentSyncedAt by mutableStateOf(0L)
        private set
    private var fullAutoAt = 0L

    /**
     * 只读订阅列表的第一页（30 个），同步新订阅的和第一页里刚取消的（回到前台时，一分钟最多一次）。
     * 网页上的总数和这次看出来的增减对不上时（比如在电脑上取消了很早以前的订阅），在后台重读完整的列表（十分钟最多一次）。
     */
    fun syncRecentSubs() {
        val acct = subscriber
        if (!acct.loggedIn || subsLoading || recentSyncing) return
        val owner = acct.steamId
        if (store.remoteSubsOwner != owner) return
        val started = System.currentTimeMillis()
        if (started - recentSyncedAt < 60_000L) return
        recentSyncing = true
        scope.launch {
            try {
                val page = acct.subscriptions(1)
                if (acct.steamId != owner || store.remoteSubsOwner != owner || subsLoading) return@launch
                val fresh = page.ids
                val freshSet = fresh.toHashSet()
                val old = store.remoteSubsHead
                var added = 0
                var removed = 0
                for (id in fresh) {
                    if (changedSince(owner, id, started)) continue
                    if (!store.remoteSubs.containsKey(id)) {
                        store.remoteSubs[id] = true
                        added++
                    }
                }
                old.forEachIndexed { i, id ->
                    if (id in freshSet || changedSince(owner, id, started)) return@forEachIndexed
                    // 上次第一页里排在它后面的还在第一页：它是被取消了（不是被新订阅的挤到第二页）
                    if (old.subList(i + 1, old.size).any { it in freshSet } && store.remoteSubs.remove(id) != null) {
                        store.subscribed.remove(id)
                        removed++
                    }
                }
                store.remoteSubsHead = fresh
                // 两分钟内在 App 里点过订阅：网页上的总数可能还没跟上，这次不比
                val recentLocal = localSubAt.any { (k, at) -> k.startsWith("$owner/") && at >= started - 120_000L }
                var mismatch = false
                if (!recentLocal && page.total > 0) {
                    mismatch = store.remoteSubsTotal > 0 && page.total - store.remoteSubsTotal != added - removed
                    store.remoteSubsTotal = page.total
                }
                store.save()
                recentSyncedAt = System.currentTimeMillis()
                if (added > 0 || removed > 0 || mismatch) {
                    Log.i("同步订阅列表第一页：新增 $added 个，取消 $removed 个" + if (mismatch) "；总数对不上（网页上 ${page.total} 个），重读完整的列表" else "")
                }
                if (mismatch && started - fullAutoAt > 10 * 60_000L) {
                    fullAutoAt = started
                    recentSyncing = false
                    loadRemoteSubs(force = true, quiet = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("同步订阅列表第一页失败（$owner）：${e.message}")
            } finally {
                recentSyncing = false
            }
        }
    }

    /** 读订阅列表的进度（0~1，没在读时为 null）、进度文字和上次的错误。 */
    var subsProgress by mutableStateOf<Float?>(null)
        private set
    var subsStatus by mutableStateOf("")
        private set
    var subsError by mutableStateOf<String?>(null)
        private set

    /** 打开详情时到壁纸的网页上核对一下订阅账号订阅了没有（网页看不到时不改）。 */
    private fun checkSubscribed(id: String) {
        val acct = subscriber
        if (!acct.loggedIn) return
        val owner = acct.steamId
        val started = System.currentTimeMillis()
        checkingSub[id] = (checkingSub[id] ?: 0) + 1
        scope.launch {
            val on = (
                try {
                    runCatching { acct.subscribedOnPage(id) }.getOrNull()
                } finally {
                    val n = (checkingSub[id] ?: 1) - 1
                    if (n <= 0) checkingSub.remove(id) else checkingSub[id] = n
                }
                ) ?: return@launch
            // 读网页的时候（或之前一分钟内）在 App 里点过订阅 / 取消订阅：网页可能还是旧的，以点的为准
            if (changedSince(owner, id, started)) return@launch
            localSub.remove("$owner/$id")
            // App 里的记录也按网页改（没读过订阅列表时按它显示）
            if (on && !store.subscribed.containsKey(id)) store.subscribed[id] = System.currentTimeMillis()
            if (!on) store.subscribed.remove(id)
            if (store.remoteSubsOwner != owner) {
                store.save()
                return@launch
            }
            val was = store.remoteSubs.containsKey(id)
            if (on && !was) store.remoteSubs[id] = true
            if (!on && was) store.remoteSubs.remove(id)
            if (!on) store.subscribed.remove(id)
            if (on != was) {
                Log.i("壁纸 $id 的订阅状态按网页改为 " + (if (on) "已订阅" else "没订阅"))
                store.save()
            }
        }
    }

    fun subscribe(it: Item, on: Boolean) {
        val key = "sub:${it.id}"
        if (busy[key] == true) return
        busy[key] = true
        val acct = subscriber
        val owner = subscriberId
        fun done() {
            localSub["$owner/${it.id}"] = on
            localSubAt["$owner/${it.id}"] = System.currentTimeMillis()
            if (store.remoteSubsOwner == owner || store.remoteSubsOwner == acct.steamId) {
                val was = store.remoteSubs.containsKey(it.id)
                if (on) store.remoteSubs[it.id] = true else store.remoteSubs.remove(it.id)
                if (on && !was) store.remoteSubsTotal++
                if (!on && was) store.remoteSubsTotal = maxOf(0, store.remoteSubsTotal - 1)
                if (!on) store.remoteSubsHead = store.remoteSubsHead - it.id
            }
            if (on) {
                store.subscribed[it.id] = System.currentTimeMillis()
                val name = if (it.title.length > 18) it.title.take(17) + "…" else it.title
                toast("已订阅「$name」" + (if (sub.loggedIn) "到订阅账号" else "") + "，电脑上的 Steam 会自动下载")
            } else {
                store.subscribed.remove(it.id)
                toast("已取消订阅")
            }
            store.save()
            Log.i((if (on) "订阅 " else "取消订阅 ") + it.id)
        }
        scope.launch {
            try {
                acct.subscribe(it.id, on)
                done()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 超时或者返回的不是「成功」，但 Steam 其实已经办好了：到网页上核对一下
                if (runCatching { acct.subscribedOnPage(it.id) }.getOrNull() == on) {
                    Log.i((if (on) "订阅 " else "取消订阅 ") + it.id + " 返回失败（${errText(e)}），网页上已经是" + (if (on) "已订阅" else "没订阅"))
                    done()
                    busy.remove(key)
                    return@launch
                }
                val who = if (sub.loggedIn) "（订阅账号「${subMe.name.ifEmpty { subMe.steamId }}」）" else ""
                val hint = if (sub.loggedIn && (e as? SteamException)?.status.let { it == 15 || it == 21 || it == 401 || it == 0 }) "；可以在账号面板退出订阅账号再重新登录" else ""
                Log.i((if (on) "订阅 " else "取消订阅 ") + it.id + " 失败$who：" + errText(e))
                toast((if (on) "订阅" else "取消订阅") + "失败$who：" + errText(e) + hint, long = true)
            }
            busy.remove(key)
        }
    }

    fun start() {
        refreshLogin()
        if (!me.loggedIn) openTab(store.tab)
    }
}
