package com.tiger.steamwallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val news: NewsFeed get() = App.instance.news

private fun isEntryKey(k: Any?) = k is String && (k.startsWith("e:") || k.startsWith("r:"))

// ---- 页面 ----

@Composable
fun NewsPage() {
    val feed = news
    if (!ws.me.loggedIn && !feed.loggedIn) {
        Box(Modifier.fillMaxSize().padding(LocalContentPadding.current), contentAlignment = Alignment.Center) {
            EmptyState(
                "登录 Steam 后看新闻",
                "新闻流、关注的发行商和愿望单都要用你的 Steam 账号。\n登录在 Steam 官方的登录页里完成，账号密码不经过这个 App。",
                "登录 Steam",
            ) { LocalActions.login() }
        }
        return
    }
    val pad = LocalContentPadding.current
    val store = feed.store
    val list = feed.list
    val keys = feed.keys
    val divider = feed.dividerIndex()

    // 往下翻到底：加载下一段
    LaunchedEffect(feed) {
        snapshotFlow {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.collect { near -> if (near && feed.keys.isNotEmpty()) feed.loadMore() }
    }
    // 看过：在屏幕上出现过、之后往下滚把它整个滚出了屏幕顶部（顶栏下面），才算看过；还在屏幕上的「新」一直留着
    LaunchedEffect(feed) {
        val shown = HashSet<String>()
        snapshotFlow { list.layoutInfo }.collect { info ->
            val vis = info.visibleItemsInfo
            val firstEntry = vis.firstOrNull { isEntryKey(it.key) && it.offset + it.size > 0 }?.key as? String
            for (it in vis) {
                val k = it.key as? String ?: continue
                if (!isEntryKey(k)) continue
                if (it.offset + it.size <= 0) {
                    if (k in shown) feed.markSeen(k)
                } else {
                    shown.add(k)
                }
            }
            // 滚出可见范围的：在第一条看得见的前面就是往上滚出去了
            if (firstEntry != null) {
                val fi = feed.keys.indexOf(firstEntry)
                val gone = shown.filter { k -> vis.none { v -> v.key == k } }
                for (k in gone) {
                    val i = feed.keys.indexOf(k)
                    if (i in 0 until fi) feed.markSeen(k)
                    shown.remove(k)
                }
            }
        }
    }
    // 打开时回到上次的位置：那一条还在就滚过去，否则停在「上次看到这里」
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(feed.settled) {
        if (!feed.settled || restored) return@LaunchedEffect
        restored = true
        if (list.firstVisibleItemIndex > 1) return@LaunchedEffect
        val layout = layoutKeys(feed)
        val i = layout.indexOf(store.posKey).takeIf { it > 0 } ?: layout.indexOf("divider").takeIf { it > 0 }
        if (i != null) list.scrollToItem(i, if (layout[i] == store.posKey) store.posOffset else 0)
    }
    // 记下现在看到的位置（停下来 0.4 秒后）
    LaunchedEffect(feed) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collectLatest {
            if (!restored) return@collectLatest
            delay(400)
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { isEntryKey(it.key) && it.offset + it.size > 0 } ?: return@collectLatest
            store.posKey = first.key as String
            store.posOffset = -first.offset
        }
    }

    Refreshable(refreshing = feed.refreshing, onRefresh = { feed.refresh(manual = true) }) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("head") { NewsHeader() }
            if (!feed.ready && keys.isEmpty()) {
                item("first") {
                    EmptyState("正在读取…", feed.progress.ifEmpty { "第一次会稍慢：要读新闻流，还要检查每个关注的发行商最近出的游戏" })
                }
            } else if (keys.isEmpty() && feed.done && !feed.loading) {
                item("empty") {
                    if (store.filter == "release") EmptyState("最近没有新发售", "关注的发行商最近发售的游戏会出现在这里。")
                    else EmptyState("没有新闻", "Steam 新闻中心里你的库、愿望单和关注的游戏、发行商发的新闻会出现在这里。")
                }
            }
            keys.forEachIndexed { i, k ->
                if (i == divider) item("divider") { LastSeenDivider() }
                val it = feed.items[k] ?: return@forEachIndexed
                item(k) { NewsCard(it) }
            }
            item("more") { NewsFooter() }
        }
    }
}

/** LazyColumn 里每一项的 key（和上面的顺序一致）。 */
private fun layoutKeys(feed: NewsFeed): List<String> = buildList {
    add("head")
    val d = feed.dividerIndex()
    feed.keys.forEachIndexed { i, k ->
        if (i == d) add("divider")
        add(k)
    }
}

@Composable
private fun NewsHeader() {
    val feed = news
    val store = feed.store
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        PillRow {
            Pill(if (feed.unread > 0) "全部 · ${if (feed.unread > 99) "99+" else feed.unread} 条未读" else "全部", onClick = { feed.setFilter("all") }, selected = store.filter == "all")
            Pill("只看新发售", onClick = { feed.setFilter("release") }, selected = store.filter == "release")
            if (feed.dividerIndex() > 0) {
                Pill("上次看到这里", leading = Icons.Default.VerticalAlignCenter, onClick = {
                    val i = layoutKeys(feed).indexOf("divider")
                    if (i > 0) scope.launch { feed.list.animateScrollToItem(i) }
                })
            }
            if (feed.unread > 0 && store.filter == "all") Pill("全部已读", leading = Icons.Default.DoneAll, onClick = { feed.markAllRead() })
        }
    }
}

@Composable
private fun LastSeenDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Tones.New.copy(alpha = 0.7f))
        Text("上次看到这里", fontSize = 12.sp, color = Tones.New, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = Tones.New.copy(alpha = 0.7f))
    }
}

@Composable
private fun NewsFooter() {
    val feed = news
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        when {
            feed.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在加载…")
            }
            feed.loadError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("出错了：${feed.loadError}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                GlassButton("重试", onClick = { feed.loadMore() }, modifier = Modifier.padding(top = 8.dp))
            }
            feed.done && feed.keys.isNotEmpty() -> Text("没有更多了", fontSize = 13.sp)
        }
    }
}

// ---- 卡片 ----

@Composable
private fun NewsCard(it: NewsItem) {
    val feed = news
    val open = feed.expanded[it.key] == true
    val toggle: () -> Unit = {
        if (it.kind == "event") {
            if (open) feed.expanded.remove(it.key) else feed.expanded.put(it.key, true)
        }
    }
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp)).padding(14.dp)) {
        SourceRow(it)
        if (it.kind == "release") {
            ReleaseBody(it)
            return@Column
        }
        Text(
            it.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
            modifier = Modifier.padding(top = 10.dp).clickable(onClick = toggle),
        )
        val img = it.image.ifEmpty { bbFirstImage(it.body) }.ifEmpty { it.release?.capsule ?: "" }
        if (img.isNotEmpty() && !open) {
            BigPicture(img, Modifier.padding(top = 10.dp), maxHeight = 420.dp, onLongClick = { LocalActions.lightbox(img) }, onClick = toggle)
        }
        if (open) {
            NewsBody(it.body)
        } else {
            val sum = it.summary.ifEmpty { bbPlain(it.body) }
            if (sum.isNotEmpty()) {
                Text(
                    if (sum.length > 160) sum.take(160) + "…" else sum, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = toggle),
                )
            }
        }
        (it.release ?: it.app?.takeIf { _ -> open || it.release != null })?.let { a -> AppCard(a, Modifier.padding(top = 12.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            WishButton(it.release ?: it.app)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = toggle).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (open) "收起" else "展开全文", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            if (it.votes > 0 || it.comments > 0) Text("  👍 ${it.votes} · 💬 ${it.comments}", fontSize = 12.sp)
        }
        if (it.comments > 0) NewsCommentsView(it)
    }
}

/** 头像、名字、时间和类型、「新」「新发售」标签、在浏览器打开、屏蔽菜单。 */
@Composable
private fun SourceRow(it: NewsItem) {
    val feed = news
    val context = LocalContext.current
    val s = it.source
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.icon, 40.dp, Modifier.clickable(enabled = s.url.isNotEmpty()) { openUrl(context, s.url) }, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable(enabled = s.url.isNotEmpty()) { openUrl(context, s.url) },
                )
                if (feed.isNew(it)) {
                    Spacer(Modifier.width(6.dp))
                    Badge("新", background = Tones.New)
                }
                it.release?.let { r ->
                    Spacer(Modifier.width(6.dp))
                    Badge("新发售" + if (r.type != 0) " · ${r.typeName}" else "", background = Tones.Ok)
                }
            }
            val meta = buildString {
                append(whenText(it.t))
                append(" · ")
                append(if (it.kind == "release") "新发售" else it.type)
                if (it.fromGamePage) append(" · 来自游戏页面")
                if (it.dups > 0) {
                    append(" · ")
                    if (it.also.isEmpty()) append("另有 ${it.dups} 个账号也发了")
                    else append(it.also.take(2).joinToString("、") + (if (it.also.size > 2) " 等 ${it.also.size} 个" else "") + " 也发了")
                }
            }
            Text(meta, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val url = if (it.kind == "release") it.release?.url ?: "" else it.url
        if (url.isNotEmpty()) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { openUrl(context, url) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "在浏览器中打开", modifier = Modifier.size(20.dp))
            }
        }
        if (it.block.isNotEmpty()) BlockButton(it)
    }
}

@Composable
private fun BlockButton(it: NewsItem) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).onGloballyPositioned { c -> bounds = c.boundsInRoot() }.clickable {
            // 菜单从按钮右边往左展开，不超出屏幕
            val w = with(density) { 250.dp.toPx() }
            val left = (bounds.right - w).coerceAtLeast(with(density) { 12.dp.toPx() })
            val options = it.block.mapIndexed { i, b -> i.toString() to (if (b.kind == "app") "屏蔽游戏《${b.name}》" else "屏蔽账号「${b.name}」") }
            LocalActions.menu(MenuRequest(Rect(left, bounds.top, bounds.right, bounds.bottom), options, "") { pick ->
                val b = it.block[pick.toInt()]
                news.setBlocked(b.kind, b.id, b.name, true)
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MoreHoriz, contentDescription = "屏蔽…", modifier = Modifier.size(22.dp))
    }
}

/** 关注的发行商新发售、但没有发售新闻的游戏：按 Steam 发售通知的样子生成一张。 */
@Composable
private fun ReleaseBody(it: NewsItem) {
    val a = it.release ?: return
    val context = LocalContext.current
    val dlc = a.type != 0
    Text(
        "《${a.name}》 – " + (if (dlc) "现已推出！" else "现已正式发售！"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
        modifier = Modifier.padding(top = 10.dp).clickable { openUrl(context, a.url) },
    )
    if (a.capsule.isNotEmpty()) {
        BigPicture(a.capsule, Modifier.padding(top = 10.dp), maxHeight = 420.dp, onLongClick = { LocalActions.lightbox(a.capsule) }) { openUrl(context, a.url) }
    }
    val p = a.price
    var notice = "《${a.name}》" + if (dlc) "现已推出！" else "正式发售啦！"
    if (p != null && p.pct > 0) notice += "为庆祝发售，现在购买可享 ${p.pct}% 优惠" + (if (p.end > 0) "（到 ${shortDayText(p.end)}）" else "") + "哦！"
    Text(notice, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
    AppCard(a, Modifier.padding(top = 12.dp))
    if (a.short.isNotEmpty()) Text(a.short, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.padding(top = 10.dp)) { WishButton(a) }
}

@Composable
private fun WishButton(a: AppInfo?) {
    if (a == null) return
    val feed = news
    when {
        feed.isOwned(a.appid) -> GlassButton("已拥有", onClick = {}, enabled = false)
        feed.busy["wish:${a.appid}"] == true -> Box(Modifier.height(ButtonHeight).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        feed.wish[a.appid] == true -> GlassButton("✓ 已在愿望单", onClick = { feed.toggleWish(a.appid) }, accent = Tones.Ok)
        else -> GlassButton("＋ 愿望单", onClick = { feed.toggleWish(a.appid) }, primary = true)
    }
}

/** 游戏卡片：封面、名字、简介、标签、发售日、评测、价格；点一下在浏览器打开商店页。 */
@Composable
private fun AppCard(a: AppInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isDark()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
            .clickable { openUrl(context, a.url) },
    ) {
        if (a.capsule.isNotEmpty()) {
            AsyncImage(
                model = a.capsule, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(616f / 353f).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.type != 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge(a.typeName, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                }
            }
            if (a.short.isNotEmpty()) Text(a.short, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (a.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.tags.forEach { t ->
                        Text(
                            t, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (a.releaseTs > 0) Text("发售日期：" + dayText(a.releaseTs) + if (a.comingSoon) "（即将推出）" else "", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            ReviewLine(a)
            PriceLine(a)
        }
    }
}

@Composable
private fun ReviewLine(a: AppInfo) {
    val r = a.reviews ?: return
    val dark = isDark()
    if (r.count == 0) {
        Text("暂无评测", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        return
    }
    val tone = when {
        r.score >= 6 -> 0
        r.score == 5 -> 1
        r.score >= 1 -> 2
        r.pct >= 70 -> 0
        r.pct >= 40 -> 1
        else -> 2
    }
    val color = when (tone) {
        0 -> if (dark) Color(0xFF66C0F4) else Color(0xFF1A73B8)
        1 -> if (dark) Color(0xFFD7B56D) else Color(0xFF8A6A1F)
        else -> if (dark) Color(0xFFE0846B) else Color(0xFFB0412A)
    }
    val label = if (r.score > 0 && r.label.isNotEmpty()) r.label else "评测较少"
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("  好评率 ${r.pct}% · 好评 ${"%,d".format(r.positive)} / ${"%,d".format(r.count)} 篇", fontSize = 12.sp)
    }
}

@Composable
private fun PriceLine(a: AppInfo) {
    val p = a.price
    val isNew = a.releaseTs > 0 && System.currentTimeMillis() / 1000 - a.releaseTs < 14 * 86400 && !a.comingSoon
    if (p == null && !a.free && !isNew) return
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isNew) {
            Badge("新品", background = Tones.New)
            Spacer(Modifier.width(6.dp))
        }
        if (p == null) {
            if (a.free) Text("免费", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            return@Row
        }
        if (p.pct > 0) {
            Text(
                "-${p.pct}%", color = Color(0xFFBEEE11), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF4C6B22)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (p.original.isNotEmpty()) {
                Text(p.original, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text(p.final, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        p.cny?.let { Text("  ≈ ¥" + String.format(java.util.Locale.ROOT, "%.2f", it), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
    }
}

// ---- 正文 ----

@Composable
private fun NewsBody(body: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val blocks = remember(body, scheme.primary) {
        parseBB(body, link = scheme.primary, quote = scheme.onSurface.copy(alpha = 0.75f), spoiler = scheme.onSurface.copy(alpha = 0.25f))
    }
    if (blocks.isEmpty()) {
        Text("（没有正文）", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) {
            when (b) {
                is BBlock.Text -> Text(b.text, fontSize = 15.sp, lineHeight = 22.sp)
                is BBlock.Image -> BigPicture(b.url, maxHeight = 640.dp, onLongClick = { LocalActions.lightbox(b.url) }) {
                    if (b.link != null) openUrl(context, b.link) else LocalActions.lightbox(b.url)
                }
                is BBlock.YouTube -> VideoThumb("https://i.ytimg.com/vi/${b.id}/hqdefault.jpg", "在 YouTube 观看") { openUrl(context, "https://www.youtube.com/watch?v=${b.id}") }
                is BBlock.Video -> VideoThumb(b.poster, "播放视频") { openUrl(context, b.url) }
                BBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VideoThumb(poster: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (poster.isNotEmpty()) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

// ---- 评论 ----

@Composable
private fun NewsCommentsView(it: NewsItem) {
    val feed = news
    val context = LocalContext.current
    // 卡片出现在屏幕附近时读最新的几条
    LaunchedEffect(it.key) { feed.loadComments(it.key) }
    val c = feed.comments[it.key]
    HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp))
    if (c == null || (c.list.isEmpty() && c.loading)) {
        Text("💬 ${it.comments} 条评论 · 正在读取…", fontSize = 13.sp)
        return
    }
    if (c.error != null && c.list.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评论读取失败：${c.error}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            GlassButton("重试", onClick = { feed.loadComments(it.key, more = true) })
        }
        return
    }
    val total = maxOf(c.total, c.list.size)
    Text("💬 $total 条评论", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    c.list.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Avatar(m.avatar, 30.dp, Modifier.clickable(enabled = m.url.isNotEmpty()) { openUrl(context, m.url) })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.t > 0) Text("  " + whenText(m.t), fontSize = 12.sp, maxLines = 1)
                }
                Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
    when {
        c.loading -> Text("正在读取…", fontSize = 13.sp)
        c.error != null -> Text("${c.error}（点这里重试）", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { feed.loadComments(it.key, more = true) })
        total > c.list.size -> GlassButton("查看更多评论（还有 ${total - c.list.size} 条）", onClick = { feed.loadComments(it.key, more = true) }, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---- 设置 ----

@Composable
fun NewsSettingsSheet(open: Boolean, onDismiss: () -> Unit) {
    val feed = news
    val store = feed.store
    GlassSheet(open, onDismiss, maxHeight = 0.85f) { scroll ->
        SheetTitle("新闻设置")
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            SwitchRow("显示 Steam「精选」的热门游戏新闻", "不在你的库、愿望单和关注里、只因为 Steam 推荐才出现的；Steam 新闻中心默认也不显示", store.showFeatured) { feed.setShowFeatured(it) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow("关注的发行商出的 DLC 也推送", "包括原声带", store.includeDlc) { feed.setIncludeDlc(it) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow("只推送发行商 / 开发商自己的游戏", "关掉后，关注的鉴赏家推荐的别家游戏也算新发售", store.onlyOwnGames) { feed.setOnlyOwnGames(it) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow(
                "价格后面显示人民币",
                "按账号的商店地区显示价格（" + store.currency.ifEmpty { "还没读到" } + "），后面按当天汇率换算" + if (store.cnyRate > 0 && store.currency.isNotEmpty() && store.currency != "CNY") "（1 ${store.currency} ≈ ${String.format(java.util.Locale.ROOT, "%.4f", store.cnyRate)} 元）" else "",
                store.showCny,
            ) { feed.setShowCny(it) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            NumberSlider("往回找多少天内发售的游戏", "${store.lookbackDays} 天", store.lookbackDays.toFloat(), 7f..90f) {
                store.lookbackDays = it.roundToInt()
                store.save()
            }
            NumberSlider(
                "自动刷新", if (store.refreshMinutes == 0) "不自动刷新" else "回到 App 时，超过 ${store.refreshMinutes} 分钟没刷新就刷新",
                store.refreshMinutes.toFloat(), 0f..180f, steps = 11,
            ) {
                store.refreshMinutes = ((it / 15f).roundToInt() * 15)
                store.save()
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("屏蔽的游戏和账号", fontWeight = FontWeight.Medium)
            Text("在每条右上角的「⋯」里屏蔽；屏蔽后它的新闻和新发售都不再显示。", fontSize = 13.sp)
            if (store.blockedApps.isEmpty() && store.blockedClans.isEmpty()) {
                Text("还没有屏蔽任何东西", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            store.blockedApps.toList().sortedBy { it.second }.forEach { (id, name) ->
                BlockedRow("游戏《$name》") { feed.setBlocked("app", id, name, false) }
            }
            store.blockedClans.toList().sortedBy { it.second }.forEach { (id, name) ->
                BlockedRow("账号「$name」") { feed.setBlocked("clan", id, name, false) }
            }
        }
    }
}

@Composable
private fun BlockedRow(label: String, onUnblock: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        GlassButton("取消屏蔽", onClick = onUnblock)
    }
}

@Composable
private fun NumberSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp)
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
