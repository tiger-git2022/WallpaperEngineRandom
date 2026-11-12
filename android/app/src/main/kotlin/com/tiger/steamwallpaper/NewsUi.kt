package com.tiger.steamwallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
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
    // 打开时回到上次的位置：那一条还在就滚过去，否则停在已读线（刚看过的地方）
    LaunchedEffect(feed.settled) {
        if (!feed.settled || feed.restored) return@LaunchedEffect
        feed.restored = true
        if (list.firstVisibleItemIndex > 1) return@LaunchedEffect
        val layout = layoutKeys(feed)
        val i = layout.indexOf(store.posKey).takeIf { it > 0 } ?: layout.indexOf("divider").takeIf { it > 0 }
        if (i != null) list.scrollToItem(i, if (layout[i] == store.posKey) store.posOffset else 0)
    }
    // 记下现在看到的位置（停下来 0.4 秒后）
    LaunchedEffect(feed) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collectLatest {
            if (!feed.restored) return@collectLatest
            delay(400)
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { isEntryKey(it.key) && it.offset + it.size > 0 } ?: return@collectLatest
            store.posKey = first.key as String
            store.posOffset = -first.offset
        }
    }

    Refreshable(refreshing = feed.refreshing && feed.manualRefresh, onRefresh = { feed.refresh(manual = true) }) {
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
                if (i == divider) item("divider") { ReadLine(store.divAt) }
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

/** 和壁纸动态的标题行一样：标题、未读数、全部已读；下面是筛选。 */
@Composable
private fun NewsHeader() {
    val feed = news
    val store = feed.store
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("关注的游戏和发行商的新闻", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (feed.unread > 0 && store.filter == "all") {
                Spacer(Modifier.width(8.dp))
                Badge(if (feed.unread > 99) "99+ 未读" else "${feed.unread} 条未读", background = Tones.New)
                Spacer(Modifier.weight(1f))
                Pill("全部已读", onClick = { feed.markAllRead() }, leading = Icons.Default.DoneAll, shape = ButtonShape)
            }
        }
        PillRow(Modifier.padding(top = 10.dp)) {
            Pill("全部", onClick = { feed.setFilter("all") }, selected = store.filter == "all", shape = ButtonShape)
            Pill("只看新发售", onClick = { feed.setFilter("release") }, selected = store.filter == "release", shape = ButtonShape)
        }
    }
}

/** 和壁纸动态一样的已读线：打开时定下位置，下拉刷新后才移动。 */
@Composable
private fun ReadLine(upTo: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "以下是已经看过的（${date(upTo)} 之前）",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** 和壁纸动态的 trackSeen 一样：露出 60% 以上（或露出的部分占了大半个屏幕）并停留 0.7 秒，记为看过。 */
@Composable
private fun Modifier.newsSeen(key: String, enabled: Boolean): Modifier {
    if (!enabled) return this
    val viewport = LocalViewport.current
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, visible) {
        if (visible) {
            delay(700)
            news.markSeen(key)
        }
    }
    return onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        val vp = viewport.value
        val h = c.size.height.toFloat()
        val shown = minOf(b.bottom, vp.bottom) - maxOf(b.top, vp.top)
        visible = h > 0 && shown > 0 && (shown / h >= 0.6f || shown >= vp.height * 0.6f)
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
    val scope = rememberCoroutineScope()
    // 点卡片任意处（标题、封面、摘要、正文）展开 / 收起；收起时这条的开头已经滚出屏幕，就滚回这条的开头
    val toggle: () -> Unit = {
        if (it.kind == "event") {
            if (open) {
                feed.expanded.remove(it.key)
                val info = feed.list.layoutInfo.visibleItemsInfo.firstOrNull { v -> v.key == it.key }
                if (info != null && info.offset < 0) {
                    val i = layoutKeys(feed).indexOf(it.key)
                    if (i >= 0) scope.launch { feed.list.scrollToItem(i) }
                }
            } else {
                feed.expanded[it.key] = true
                feed.markSeen(it.key)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().newsSeen(it.key, feed.isNew(it)).card(RoundedCornerShape(22.dp))
            .clickable(enabled = it.kind == "event", onClick = toggle)
            .padding(14.dp),
    ) {
        SourceRow(it)
        if (it.kind == "release") {
            ReleaseBody(it)
            return@Column
        }
        val tr = feed.tr
        // 自动翻译：出现在屏幕附近、不是目标语言的新闻
        LaunchedEffect(it.key, tr.auto, feed.store.translateTo) { tr.autoQueue(it) }
        Text(tr.title(it), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
        TranslateNote(it)
        val img = it.image.ifEmpty { bbFirstImage(it.body) }.ifEmpty { it.release?.capsule ?: "" }
        if (img.isNotEmpty() && !open) {
            BigPicture(img, Modifier.padding(top = 10.dp), maxHeight = 420.dp, onLongClick = { LocalActions.lightbox(img) }, onClick = toggle)
        }
        if (open) {
            NewsBody(tr.body(it))
            ToggleHint("收起", Icons.Default.ExpandLess)
        } else {
            // 收起时也保留分段和换行（和展开后一样），最多显示 8 行；翻译了就显示译文
            val sum = tr.summary(it, it.summary.ifEmpty { bbPlain(it.body, keepLines = true) })
            if (sum.isNotEmpty()) {
                Text(sum.take(600), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            ToggleHint("展开全文", Icons.Default.ExpandMore)
        }
        (it.release ?: it.app?.takeIf { open })?.let { a -> AppCard(a, Modifier.padding(top = 12.dp)) }
        val stats = listOfNotNull(it.votes.takeIf { v -> v > 0 }?.let { v -> "👍 $v" }, maxOf(it.comments, feed.comments[it.key]?.total ?: 0).takeIf { c -> c > 0 }?.let { c -> "💬 $c" }).joinToString("  ")
        val wish = it.release ?: it.app
        if (wish != null || stats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                WishButton(wish)
                Spacer(Modifier.weight(1f))
                if (stats.isNotEmpty()) Text(stats, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
            }
        }
        // 评论区里点一下不收起（方便看和点链接）
        if (it.comments > 0) {
            Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) { NewsCommentsView(it) }
        }
    }
}

/** 摘要 / 正文下面的一行小字，提示点卡片就能展开或收起（整张卡片都能点，不用点准这一行）。 */
@Composable
private fun ToggleHint(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.frost(ButtonShape).padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
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
                    Badge("NEW", background = Tones.New)
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
        if (it.kind == "event" && it.gid.isNotEmpty() && feed.tr.configured) {
            TranslateButton(it)
            Spacer(Modifier.width(8.dp))
        }
        val url = if (it.kind == "release") it.release?.url ?: "" else it.url
        if (url.isNotEmpty()) {
            GlassIconButton(Icons.AutoMirrored.Filled.OpenInNew, "在浏览器中打开") { openUrl(context, url) }
        }
        if (it.block.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            BlockButton(it)
        }
    }
}

/** 「译」：翻译 / 显示原文；显示译文时亮起主题色。 */
@Composable
private fun TranslateButton(it: NewsItem) {
    val tr = news.tr
    val on = tr.shown(it.key)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(34.dp).frost(CircleShape)
            .then(if (on) Modifier.background(primary.copy(alpha = 0.22f), CircleShape) else Modifier)
            .clickable { tr.click(it.key) },
        contentAlignment = Alignment.Center,
    ) {
        Text("译", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** 标题下面的一行小字：正在翻译 / 机器翻译 · 显示原文 / 翻译失败 · 重试。 */
@Composable
private fun TranslateNote(it: NewsItem) {
    val tr = news.tr
    val s = tr.posts[it.key] ?: return
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            s.error != null -> {
                Text("翻译失败：${s.error}", fontSize = 12.sp, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" · 重试", fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { tr.click(it.key) })
            }
            s.busy -> {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text("  正在翻译…", fontSize = 12.sp)
            }
            s.done -> {
                Text(if (s.orig) "🌐 显示的是原文 · " else "🌐 机器翻译 · ", fontSize = 12.sp)
                Text(if (s.orig) "显示译文" else "显示原文", fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { tr.click(it.key) })
            }
        }
    }
}

@Composable
private fun BlockButton(it: NewsItem) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    Box(
        Modifier.size(34.dp).frost(CircleShape).onGloballyPositioned { c -> bounds = c.boundsInRoot() }.clickable {
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
        Icon(Icons.Default.MoreHoriz, contentDescription = "屏蔽…", modifier = Modifier.size(20.dp))
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
                    a.tags.forEach { t -> GlassTag(t) }
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
    // 放不下时整块换行，不把「≈ ¥」从中间挤断
    FlowRow(
        Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (isNew) Badge("新品", background = Tones.New)
        if (p == null) {
            if (a.free) Text("免费", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            return@FlowRow
        }
        if (p.pct > 0) {
            Text(
                "-${p.pct}%", color = Color(0xFFBEEE11), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF4C6B22)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            if (p.original.isNotEmpty()) Text(p.original, fontSize = 12.sp, maxLines = 1, softWrap = false, textDecoration = TextDecoration.LineThrough)
        }
        Text(p.final, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
        p.cny?.let { Text("≈ ¥" + String.format(java.util.Locale.ROOT, "%.2f", it), fontSize = 13.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.primary) }
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
                is BBlock.Game -> GameBanner(b.appid, b.url)
                is BBlock.YouTube -> VideoThumb("https://i.ytimg.com/vi/${b.id}/hqdefault.jpg", "在 YouTube 观看") { openUrl(context, "https://www.youtube.com/watch?v=${b.id}") }
                is BBlock.Video -> VideoThumb(b.poster, "播放视频") { openUrl(context, b.url) }
                BBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

/** 正文里链接到的游戏：横幅图、名字、价格、愿望单，点一下打开商店页（和 Steam 网页上的商店卡片一样）。 */
@Composable
private fun GameBanner(appid: Int, url: String) {
    val feed = news
    val context = LocalContext.current
    LaunchedEffect(appid) { feed.requestBanner(appid) }
    val a = feed.banners[appid]
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (isDark()) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
            .clickable { openUrl(context, a?.url ?: url) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(460f / 215f)) {
            if (a == null || a.header.isEmpty()) LoadingShimmer(Modifier.fillMaxSize())
            else AsyncImage(model = a.header, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a?.name ?: "Steam 商店", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (a != null) ReviewLine(a)
                if (a != null) PriceLine(a)
            }
            if (a != null) {
                Spacer(Modifier.width(8.dp))
                WishButton(a)
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
    val tr = feed.tr
    // 自动翻译：读到新的评论就翻
    LaunchedEffect(c.list.size, tr.auto, feed.store.translateTo) { if (tr.auto && c.list.isNotEmpty()) tr.translateComments(it.key, auto = true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("💬 $total 条评论", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (tr.configured) {
            val untranslated = remember(c.list.size, c.tr.size, feed.store.translateTo) { c.list.count { m -> c.tr[m.id] == null && tr.needs(m.text) } }
            val link = when {
                c.trLoading -> "正在翻译…"
                untranslated > 0 -> "翻译评论"
                c.tr.isNotEmpty() -> if (c.trOn) "显示原文" else "显示译文"
                else -> null
            }
            if (link != null) {
                Text(
                    link, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !c.trLoading) { tr.translateComments(it.key, auto = false) }.padding(vertical = 4.dp),
                )
            }
        }
    }
    c.trError?.let { e -> Text("翻译失败：$e", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    c.list.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Avatar(m.avatar, 30.dp, Modifier.clickable(enabled = m.url.isNotEmpty()) { openUrl(context, m.url) })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.t > 0) Text("  " + whenText(m.t), fontSize = 12.sp, maxLines = 1)
                }
                Text(c.tr[m.id]?.takeIf { c.trOn } ?: m.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 1.dp))
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
            NewsAccount(onDismiss)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
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
            TranslateSettings()
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

/** 新闻用的账号：可以单独登录一个新闻专用账号（比如另一个地区的号），和壁纸的浏览、订阅账号分开。 */
@Composable
private fun NewsAccount(onDismiss: () -> Unit) {
    val feed = news
    val store = feed.store
    Text("新闻账号", fontWeight = FontWeight.Medium)
    if (feed.loggedIn) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(feed.accountAvatar, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(feed.accountName.ifEmpty { "Steam 账号" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (store.newsAccount) "新闻专用账号" else "和浏览账号是同一个") + if (feed.store.country.isNotEmpty()) " · 商店地区 ${store.country}" else "",
                    fontSize = 12.sp,
                )
            }
            if (store.newsAccount) GlassButton("退出", onClick = { feed.logoutNewsAccount() })
        }
    }
    when {
        store.newsAccount -> Text("新闻流、关注的发行商、愿望单和价格都用这个号；退出后改用浏览账号。", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        !NewsProfile.supported -> Text("这台手机的系统 WebView 太旧，不能单独登录新闻账号；请在应用商店更新「Android System WebView」。", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        else -> {
            Text("现在用浏览账号。可以单独登录一个账号看新闻（比如另一个地区的号），和壁纸用的账号分开，互不影响。", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            GlassButton("登录新闻账号", onClick = { onDismiss(); LocalActions.loginNews() }, primary = true, modifier = Modifier.padding(top = 10.dp))
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

// ---- 翻译设置 ----

/** 翻译（大模型）：和电脑版设置页的「翻译」一样。改了马上保存；API 密钥加密后保存。 */
@Composable
private fun TranslateSettings() {
    val feed = news
    val store = feed.store
    val claude = store.aiFormat == "anthropic"
    val scope = rememberCoroutineScope()
    Text("翻译（大模型）", fontWeight = FontWeight.Medium)
    Text("每条新闻右上角的「译」翻译标题、摘要和正文，评论区可以「翻译评论」；翻过的会记住，不重复花额度。", fontSize = 13.sp)

    SettingLabel("接口格式", "OpenAI 兼容：DeepSeek、通义千问、Kimi、智谱、硅基流动、OpenRouter、OpenAI 等都用这个")
    PillRow(Modifier.padding(top = 8.dp)) {
        listOf("openai" to "OpenAI 兼容", "anthropic" to "Anthropic Claude").forEach { (v, label) ->
            Pill(label, onClick = {
                if (store.aiFormat != v) {
                    // 换了格式：地址和模型回到默认（两边的地址、模型名不通用）
                    store.aiFormat = v
                    store.aiBaseUrl = ""
                    store.aiModel = ""
                    store.aiThinkModel = ""
                    store.save()
                }
            }, selected = store.aiFormat == v)
        }
    }

    SettingLabel(
        "接口地址",
        if (claude) "留空用官方接口 ${Translator.DEFAULT_CLAUDE_BASE}；用中转的话填中转地址"
        else "填到 /v1 为止，例如 https://api.deepseek.com/v1、https://dashscope.aliyuncs.com/compatible-mode/v1",
    )
    SettingInput(store.aiBaseUrl, if (claude) Translator.DEFAULT_CLAUDE_BASE else Translator.DEFAULT_OPENAI_BASE, KeyboardType.Uri) {
        store.aiBaseUrl = it.trim()
        store.save()
    }

    SettingLabel("API 密钥", "用安卓系统的密钥库（Android Keystore）加密后保存，只有这台手机上的这个 App 能解开")
    var key by remember { mutableStateOf(Translator.unprotect(store.aiKeyEnc)) }
    var keyDirty by remember { mutableStateOf(false) }
    val saveKey = {
        if (keyDirty) {
            keyDirty = false
            store.aiKeyEnc = Translator.protect(key.trim())
            store.save()
        }
    }
    LaunchedEffect(key) {
        delay(800)
        saveKey()
    }
    DisposableEffect(Unit) { onDispose { saveKey() } }
    SettingInput(key, "sk-…", KeyboardType.Password, password = true) {
        key = it
        keyDirty = true
    }

    SettingLabel("模型", if (claude) "留空用 ${Translator.DEFAULT_CLAUDE_MODEL}" else "留空用 ${Translator.DEFAULT_OPENAI_MODEL}（DeepSeek）；其他服务商填它的模型名")
    SettingInput(store.aiModel, if (claude) Translator.DEFAULT_CLAUDE_MODEL else Translator.DEFAULT_OPENAI_MODEL) {
        store.aiModel = it.trim()
        store.save()
    }

    SettingLabel(
        "思考",
        if (claude) "思考：开自适应思考；不思考：把 effort 调到最低（新的 Claude 模型思考关不掉，只能让它少想）"
        else "按接口地址自动用对应的开关（通义 / 硅基流动 enable_thinking，智谱 / 火山 thinking，OpenRouter reasoning）；其他服务商用下面的「思考时换用的模型」",
    )
    PillRow(Modifier.padding(top = 8.dp)) {
        listOf("off" to "不思考", "on" to "思考", "auto" to "超过一定字数才思考").forEach { (v, label) ->
            Pill(label, onClick = {
                store.aiThink = v
                store.save()
            }, selected = store.aiThink == v)
        }
    }
    if (store.aiThink == "auto") {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            var words by remember { mutableStateOf(store.aiThinkWords.toString()) }
            SettingInput(words, "800", KeyboardType.Number, modifier = Modifier.width(110.dp)) {
                words = it.filter(Char::isDigit).take(6)
                words.toIntOrNull()?.let { n ->
                    store.aiThinkWords = n
                    store.save()
                }
            }
            Text("词以上才思考（中文一个字算一个词）", fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
        }
    }

    SettingLabel("思考时换用的模型（可选）", "有的服务商靠换模型来开思考，比如 DeepSeek 的 deepseek-reasoner、Kimi 的 thinking 模型。留空就用上面的模型")
    SettingInput(store.aiThinkModel, "") {
        store.aiThinkModel = it.trim()
        store.save()
    }

    Spacer(Modifier.height(14.dp))
    SwitchRow("流式输出", "边翻译边显示；关掉就等整篇翻完一次显示", store.aiStream) {
        store.aiStream = it
        store.save()
    }
    Spacer(Modifier.height(12.dp))
    SwitchRow("自动翻译", "不是目标语言的新闻和评论，出现时自动翻译（会消耗 API 额度）。关掉时用每条右上角的「译」手动翻译", store.autoTranslate) {
        store.autoTranslate = it
        store.save()
    }

    SettingLabel("翻译成", "")
    PillRow(Modifier.padding(top = 8.dp)) {
        Translator.TARGETS.forEach { t ->
            Pill(t, onClick = {
                store.translateTo = t
                store.save()
                feed.tr.checkLang()
            }, selected = store.translateTo == t)
        }
    }

    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("用上面的设置翻译一句话，看能不能用") }
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("测试", fontWeight = FontWeight.Medium)
            Text(result, fontSize = 13.sp, color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        GlassButton(if (testing) "测试中…" else "测试", enabled = !testing, onClick = {
            saveKey()
            testing = true
            failed = false
            result = "正在测试…"
            scope.launch {
                try {
                    result = "可以用：" + Translator.test(feed.tr.config())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    result = "失败：" + Translator.errText(e)
                }
                testing = false
            }
        })
    }
}

@Composable
private fun SettingLabel(title: String, hint: String) {
    Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
    if (hint.isNotEmpty()) Text(hint, fontSize = 13.sp)
}

/** 单行输入框：磨砂玻璃底的圆角框，空的时候显示灰色的默认值。 */
@Composable
private fun SettingInput(
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.padding(top = 6.dp).frost(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = scheme.onSurface.copy(alpha = 0.4f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
