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
}

/**
 * 整个创意工坊的状态和逻辑，照搬电脑版 workshop.html 的脚本：列表的加载、时间线的阅读进度、关注、订阅、作者资料。
 * 界面（Compose）只读这里的状态、调这里的方法。只在主线程上用。
 */
class Workshop(val steam: Steam, val store: Store, private val scope: CoroutineScope) {
    var me by mutableStateOf(Me())
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

    fun toast(text: String) {
        toast = text to System.currentTimeMillis()
    }

    // ---- login ----

    /** 重新读登录状态（启动、登录页关闭、回到前台时）。账号变了就重新加载。 */
    fun refreshLogin() {
        val changed = steam.syncCookies()
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

    private fun afterLogin() {
        timeline = null
        browse = null
        stack.clear()
        if (!me.loggedIn) {
            unread = 0
            return
        }
        loadFollows()
        // 时间线是主页面；在别的标签页时也在后台加载，好显示未读数
        openTab(store.tab)
        if (store.tab != "timeline") timelineView()
    }

    fun logout() {
        steam.logout()
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
    fun refresh(v: ListView) {
        if (v.refreshing != null) return
        val n = v.fresh()
        n.replaces = v
        // 刷新后停在最上面，不再跳回上次看到的位置
        n.restored = true
        v.refreshing = n
        loadMore(n)
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

    fun openDetail(id: String, list: List<String> = listOf(id)) {
        detailList = if (id in list) list else listOf(id)
        detail = id
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

    fun isSubscribed(id: String) = store.subscribed.containsKey(id)

    fun subscribe(it: Item, on: Boolean) {
        val key = "sub:${it.id}"
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            try {
                if (!steam.subscribe(it.id, on)) throw Exception("Steam 没有确认")
                if (on) {
                    store.subscribed[it.id] = System.currentTimeMillis()
                    toast("已订阅「${it.title}」，电脑上的 Steam 会自动下载")
                } else {
                    store.subscribed.remove(it.id)
                    toast("已取消订阅")
                }
                store.save()
                Log.i((if (on) "订阅 " else "取消订阅 ") + it.id)
            } catch (e: Exception) {
                toast((if (on) "订阅" else "取消订阅") + "失败：" + errText(e))
            }
            busy.remove(key)
        }
    }

    fun start() {
        refreshLogin()
        if (!me.loggedIn) openTab(store.tab)
    }
}
