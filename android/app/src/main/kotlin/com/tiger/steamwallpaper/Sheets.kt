package com.tiger.steamwallpaper

import androidx.compose.ui.draw.alpha
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---- detail ----

@Composable
fun DetailSheet() {
    val id = ws.detail
    var shown by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf(emptyList<String>()) }
    if (id != null) {
        shown = id
        list = ws.detailList
    }
    GlassSheet(visible = id != null, onDismiss = { ws.detail = null }, maxHeight = 0.92f) { scroll ->
        if (list.size > 1) {
            // 合并显示的一组：左右滑动切换
            key(list) {
                val pager = rememberPagerState(initialPage = list.indexOf(shown).coerceAtLeast(0)) { list.size }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.collect { p ->
                        val cur = list.getOrNull(p) ?: return@collect
                        if (ws.detail != null && ws.detail != cur) ws.openDetail(cur, list)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    // 小圆点只在 10 个以内时画（作者页可能有几百个）
                    if (list.size <= 10) repeat(list.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier.padding(horizontal = 3.dp).size(if (on) 8.dp else 6.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (on) 0.9f else 0.3f)),
                        )
                    }
                    Text("  ${pager.currentPage + 1} / ${list.size}，左右滑动切换", fontSize = 12.sp)
                }
                HorizontalPager(pager, verticalAlignment = Alignment.Top, pageSpacing = 16.dp) { p ->
                    val it = ws.items[list[p]]
                    if (it == null) {
                        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        DetailContent(it, scroll)
                    }
                }
            }
        } else {
            val it = shown?.let { ws.items[it] }
            if (it == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                DetailContent(it, scroll)
            }
        }
    }
}

@Composable
private fun DetailContent(it: Item, scroll: Modifier) {
    var cur by remember(it.id) { mutableStateOf(it.preview) }
    LaunchedEffect(it.id) { ws.loadComments(it.id) }
    Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
        BigPicture(cur, maxHeight = 380.dp) { LocalActions.lightbox(cur) }
        if (it.images.isNotEmpty() || it.videos.isNotEmpty()) PreviewStrip(it, cur) { url -> cur = url }
        SelectionContainer {
            Text(it.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(authorAvatar(it.creator), 36.dp, Modifier.clickable { ws.openAuthor(it.creator) })
            Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { ws.openAuthor(it.creator) }) {
                Text(authorName(it.creator), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("作者的全部作品 ›", fontSize = 12.sp)
            }
            FollowButton(it.creator)
        }
        Box(Modifier.padding(top = 14.dp)) { SubscribeRow(it, big = true) }
        if (ws.subMe.loggedIn && ws.me.loggedIn) {
            Text("订阅到订阅账号「${ws.subMe.name.ifEmpty { ws.subMe.steamId }}」", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (ws.isSubscribed(it.id)) {
            Text("已订阅，电脑上的 Steam 客户端会自动下载（Steam 要在运行）", fontSize = 12.sp, color = Tones.Ok, modifier = Modifier.padding(top = 6.dp))
        } else if (!ws.me.loggedIn) {
            GlassButton("登录 Steam 后可以直接订阅", onClick = { LocalActions.login() }, primary = true, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        val total = (it.votesUp ?: 0) + (it.votesDown ?: 0)
        val rows = listOfNotNull(
            "发布" to (if (it.created > 0) "${date(it.created)}（${ago(it.created)}）" else "-"),
            if (it.updated - it.created > 3600) "更新" to date(it.updated) else null,
            "大小" to (if (it.size > 0) size(it.size) else "-"),
            "订阅" to num(it.subs),
            if (it.votesUp != null) "评价" to (if (total > 0) "${(it.votesUp) * 100 / total}% 好评（$total 票）" else "暂无评价") else null,
            "ID" to it.id,
        )
        rows.forEach { (k, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp), fontSize = 14.sp)
                SelectionContainer { Text(value, fontSize = 14.sp) }
            }
        }
        val tags = it.tags.filter { t -> t != "Wallpaper" }
        if (tags.isNotEmpty()) {
            PillRow(Modifier.padding(top = 12.dp)) {
                tags.forEach { t -> Pill(label(t), onClick = { ws.searchTag(t) }) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        val desc = bbcode(it.description ?: it.short)
        if (it.description == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("  正在读取简介…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SelectionContainer {
            Text(desc.ifEmpty { if (it.description != null) "没有简介" else "" }, fontSize = 14.sp, lineHeight = 21.sp)
        }
        CommentList(it.id)
    }
}

@Composable
private fun CommentList(id: String) {
    val c = ws.comments[id] ?: return
    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 4.dp))
    Text(if (c.total > 0) "评论（${c.total}）" else "评论", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
    if (c.list.isEmpty() && !c.loading && c.error == null) {
        Text("还没有评论", fontSize = 14.sp)
    }
    c.list.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Avatar(m.avatar, 36.dp, Modifier.clickable(enabled = m.steamId.isNotEmpty()) { ws.openAuthor(m.steamId) })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.time > 0) Text("  " + ago(m.time), fontSize = 12.sp, maxLines = 1)
                }
                SelectionContainer { Text(m.text, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
    when {
        c.loading -> Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  正在读取评论…", fontSize = 13.sp)
        }
        c.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评论读取失败：${c.error}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            GlassButton("重试", onClick = { ws.loadComments(id) })
        }
        !c.done -> GlassButton("更多评论", onClick = { ws.loadComments(id) }, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---- filters ----

@Composable
fun FilterSheet(open: Boolean, onDismiss: () -> Unit) {
    GlassSheet(open, onDismiss, maxHeight = 0.85f) { scroll ->
        SheetTitle("筛选") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("清除筛选", onClick = { ws.clearFilters() }, enabled = ws.store.filterCount > 0)
                GlassButton("完成", onClick = onDismiss, primary = true)
            }
        }
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text("组内任选一个即可，组和组之间都要满足；时间线和浏览都按这里筛选。", fontSize = 13.sp)
            GROUPS.forEach { g ->
                Section(g.title)
                PillRow {
                    val sel = ws.store.filters[g.key].orEmpty()
                    g.tags.forEach { (tag, name) ->
                        val on = tag in sel
                        Pill(name, onClick = { ws.toggleFilter(g.key, tag) }, selected = on, leading = if (on) Icons.Default.Check else null)
                    }
                }
            }
        }
    }
}

// ---- account ----

@Composable
fun AccountSheet(open: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = App.instance.updater
    GlassSheet(open, onDismiss, maxHeight = 0.8f) { scroll ->
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            val me = ws.me
            if (me.loggedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me.avatar, 56.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(me.name.ifEmpty { "Steam 账号" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(me.steamId, fontSize = 12.sp)
                    }
                    GlassButton("退出登录", onClick = { ws.logout(); onDismiss() })
                }
                Text(
                    if (ws.subMe.loggedIn) "浏览账号：时间线、浏览、关注都用这个号。" else "时间线、浏览、关注和订阅都用这个号；订阅可以在下面另外指定一个账号。",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                SubAccount()
            } else {
                Text("还没有登录 Steam", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("请登录电脑上 Steam 客户端用的那个账号，订阅的壁纸才会下载到那台电脑。", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                GlassButton("登录 Steam", onClick = { onDismiss(); LocalActions.login() }, primary = true)
            }
            if (me.loggedIn) {
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                SubsStatus()
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            SwitchRow("合并同一作者的连续上传", "时间线里同一作者 6 小时内连着发的（最多 4 个）合成一条", ws.store.mergePosts) { ws.setMerge(it) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            val store = ws.store
            SwitchRow("液态玻璃效果", "关掉后顶栏、标签栏和面板改用普通的底色", store.glass) { store.glass = it; store.save() }
            AnimatedVisibility(store.glass) {
                Column {
                    GlassSlider("模糊", "玻璃后面的内容模糊多少", store.glassBlur, { String.format(java.util.Locale.ROOT, "%.1f dp", it / 10f) }) { store.glassBlur = it; store.save() }
                    GlassSlider("不透明度", "玻璃底色的浓淡，越高字越清楚", store.glassTint, { "$it%" }) { store.glassTint = it; store.save() }
                    GlassSlider("边缘色差", "玻璃边缘红、绿、蓝错开的程度，0 为没有", store.glassFringe, { "$it" }) { store.glassFringe = it; store.save() }
                    Text("所有的玻璃（顶栏、标签栏、按钮、面板）都按这里显示，拖动时就能看到效果。", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("版本 v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Medium)
                    Text("从 GitHub 检查新版本（启动时每 6 小时最多查一次）", fontSize = 13.sp)
                }
                // 检查时按钮不换成转圈，只是变灰：这一行的高度和宽度都不变
                GlassButton(if (updater.checking) "检查中…" else "检查更新", onClick = { updater.check(manual = true) }, enabled = !updater.checking)
            }
            // 字大时一行放不下：自动换行
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("在 GitHub 查看所有版本", onClick = { openUrl(context, "https://github.com/${BuildConfig.REPO}/releases") })
                GlassButton("查看日志", onClick = { onDismiss(); LocalActions.log() })
            }
        }
    }
}

/** 订阅状态：从订阅账号的 Steam 订阅列表读的，在列表和详情里显示「已订阅」。 */
@Composable
private fun SubsStatus() {
    val store = ws.store
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("订阅状态", fontWeight = FontWeight.Medium)
            val known = store.remoteSubsOwner.isNotEmpty() && store.remoteSubsOwner == ws.subscriberId
            Text(
                when {
                    ws.subsLoading -> ws.subsStatus.ifEmpty { "正在读取订阅列表…" }
                    known -> "订阅列表里有 ${store.remoteSubs.size} 个（" + ago(store.remoteSubsTime / 1000) + "读取）；打开详情时还会单独核对"
                    else -> "还没读取订阅列表"
                },
                fontSize = 13.sp,
            )
            ws.subsError?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp)) }
        }
        GlassButton(if (ws.subsLoading) "读取中" else "刷新", onClick = { ws.loadRemoteSubs(force = true) }, enabled = !ws.subsLoading)
    }
    // 进度条一直占着位置（不读时看不见），读的时候下面的内容不会被挤下去
    val p = ws.subsProgress
    LinearProgressIndicator(progress = { p ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (p != null) 1f else 0f))
    Text(
        if (p != null) "在后台读，可以关掉这个面板；读完会提示。" else "Steam 网页可能不列出账号看不到的成人作品，这些只能在打开详情时核对。",
        fontSize = 12.sp, minLines = 2, modifier = Modifier.padding(top = 4.dp),
    )
}

/** 订阅账号（大号）：只负责订阅，比如看不了成人内容的大号；可以把它的关注导入浏览账号。 */
@Composable
private fun SubAccount() {
    val s = ws.subMe
    Text("订阅账号", fontWeight = FontWeight.Medium)
    when {
        s.loggedIn -> {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.avatar, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.name.ifEmpty { "Steam 账号" }, fontWeight = FontWeight.SemiBold)
                    Text(s.steamId, fontSize = 12.sp)
                }
                GlassButton("退出", onClick = { ws.logoutSub() })
            }
            Text("订阅会加到这个号上，由登录这个号的电脑 Steam 客户端下载。", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            val busy = ws.importing
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(busy ?: "把它的关注导入浏览账号", onClick = { ws.importFollows() }, enabled = busy == null)
            }
            Text("用浏览账号关注订阅账号关注的作者（只加不减），之后关注不同步。", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        !SubProfile.supported -> Text("这台手机的系统 WebView 太旧，不能同时登录第二个账号；请在应用商店更新「Android System WebView」。", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        else -> {
            Text("没有登录时订阅用上面的账号。如果电脑上用的大号看不了成人内容，可以用小号浏览，在这里登录大号只负责订阅。", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            GlassButton("登录订阅账号", onClick = { LocalActions.loginSub() }, primary = true, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 0~100 的设置，一格 5。 */
@Composable
private fun GlassSlider(title: String, summary: String, value: Int, show: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(show(value), fontWeight = FontWeight.SemiBold)
        }
        Text(summary, fontSize = 13.sp)
        Slider(
            value = value.toFloat(), onValueChange = { onChange((it / 5f).roundToInt() * 5) },
            valueRange = 0f..100f, steps = 19,
        )
    }
}

// ---- log ----

/** 日志面板：App 的运行记录（读作者作品、订阅、出错……），可以复制发给开发者。 */
@Composable
fun LogSheet(open: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(open) { if (open) text = Log.read().takeLast(60_000) }
    GlassSheet(open, onDismiss, maxHeight = 0.9f) { scroll ->
        SheetTitle("日志") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("清空", onClick = { Log.clear(); text = "" })
                GlassButton("复制", primary = true, onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("SteamWallpaper 日志", text))
                    ws.toast("已复制日志")
                })
            }
        }
        val listState = rememberScrollState()
        LaunchedEffect(text) { listState.scrollTo(listState.maxValue) }
        SelectionContainer(scroll.verticalScroll(listState).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(text.ifEmpty { "还没有日志" }, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

// ---- update ----

@Composable
fun UpdateSheet(activity: Activity) {
    val updater = App.instance.updater
    val r = updater.available
    var shown by remember { mutableStateOf<Updater.Release?>(null) }
    if (r != null) shown = r
    val context = LocalContext.current
    GlassSheet(r != null, onDismiss = { if (updater.progress == null) updater.skip() }, maxHeight = 0.8f) { scroll ->
        val rel = shown ?: return@GlassSheet
        SheetTitle("发现新版本 ${rel.title}")
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text("当前版本 v${BuildConfig.VERSION_NAME}", fontSize = 13.sp)
            if (rel.notes.isNotBlank()) {
                Text(rel.notes.trim(), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
            }
            // 进度条和状态一直占着位置：开始下载时按钮不会被顶上去
            val p = updater.progress
            var last by remember { mutableStateOf(0f) }
            if (p != null) last = p
            LinearProgressIndicator(progress = { last }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            Text(
                when {
                    updater.message.isNotEmpty() -> updater.message
                    p != null -> "正在下载 ${(p * 100).roundToInt()}%"
                    else -> "点「下载并安装」后在这里显示进度"
                },
                fontSize = 13.sp, minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (updater.message.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Unspecified,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton("下载并安装", onClick = { updater.download(activity) }, enabled = p == null, primary = true)
                GlassButton("以后再说", onClick = { updater.skip() })
            }
            GlassButton("打开发布页", onClick = { openUrl(context, rel.page) }, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ---- menu ----

class MenuRequest(val anchor: Rect, val options: List<Pair<String, String>>, val selected: String, val onPick: (String) -> Unit)

/** 玻璃下拉菜单，画在页面里（这样才能折射页面）。 */
@Composable
fun GlassMenu(req: MenuRequest?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<MenuRequest?>(null) }
    if (req != null) last = req
    BackHandler(req != null, onDismiss)
    if (req != null) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    val m = last ?: return
    val density = LocalDensity.current
    AnimatedVisibility(
        req != null,
        enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = TransformOrigin(0f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0f, 0f)),
        modifier = Modifier.offset { IntOffset(m.anchor.left.roundToInt(), (m.anchor.bottom + with(density) { 6.dp.toPx() }).roundToInt()) },
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.widthIn(min = 160.dp, max = 260.dp).width(IntrinsicSize.Max)
                .glassClip(shape, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = GLASS_SHEET_TINT), GlassStyle.Sheet)
                .padding(vertical = 6.dp),
        ) {
            m.options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, fontWeight = if (key == m.selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        onDismiss()
                        m.onPick(key)
                    },
                    trailingIcon = if (key == m.selected) ({ Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

// ---- lightbox ----

@Composable
fun Lightbox(url: String?, onDismiss: () -> Unit) {
    BackHandler(url != null, onDismiss)
    var last by remember { mutableStateOf("") }
    if (url != null) last = url
    AnimatedVisibility(url != null, enter = fadeIn(), exit = fadeOut()) {
        var scale by remember(last) { mutableFloatStateOf(1f) }
        var offset by remember(last) { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(last) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(last) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }) {
                PreviewImage(last, animate = true, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

// ---- toast ----

@Composable
fun Toast(modifier: Modifier = Modifier) {
    val t = ws.toast
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(t) {
        if (t == null) return@LaunchedEffect
        text = t.first
        visible = true
        delay(if (ws.toastLong) 6000 else 2800)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier.padding(horizontal = 24.dp), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        // 圆角矩形：字多换成几行时，四角的字不会碰到圆头的边
        val shape = RoundedCornerShape(22.dp)
        Text(
            text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp).glassClip(shape, MaterialTheme.colorScheme.surfaceContainerHighest, GlassStyle.Panel)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
