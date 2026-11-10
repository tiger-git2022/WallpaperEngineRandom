package com.werandom.workshop

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
    if (id != null) shown = id
    GlassSheet(visible = id != null, onDismiss = { ws.detail = null }, maxHeight = 0.92f) { scroll ->
        val it = shown?.let { ws.items[it] }
        if (it == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            DetailContent(it, scroll)
        }
    }
}

@Composable
private fun DetailContent(it: Item, scroll: Modifier) {
    val context = LocalContext.current
    var cur by remember(it.id) { mutableStateOf(it.preview) }
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
                Text("作者的全部作品", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            FollowButton(it.creator)
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SubscribeRow(it, big = true) }
            OutlinedButton(onClick = { openUrl(context, workshopUrl(it.id)) }) { Text("网页") }
        }
        if (ws.isSubscribed(it.id)) {
            Text("已订阅，电脑上的 Steam 客户端会自动下载（Steam 要在运行）", fontSize = 12.sp, color = Tones.Ok, modifier = Modifier.padding(top = 6.dp))
        } else if (!ws.me.loggedIn) {
            TextButton(onClick = { LocalActions.login() }) { Text("登录 Steam 后可以直接订阅") }
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
                Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp), fontSize = 14.sp)
                SelectionContainer { Text(value, fontSize = 14.sp) }
            }
        }
        val tags = it.tags.filter { t -> t != "Wallpaper" }
        if (tags.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t -> SuggestionChip(onClick = { ws.searchTag(t) }, label = { Text(label(t), fontSize = 12.sp) }, modifier = Modifier.height(30.dp)) }
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
            Text(desc.ifEmpty { if (it.description != null) "没有简介" else "" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

// ---- filters ----

@Composable
fun FilterSheet(open: Boolean, onDismiss: () -> Unit) {
    GlassSheet(open, onDismiss, maxHeight = 0.85f) { scroll ->
        SheetTitle("筛选") {
            TextButton(onClick = { ws.clearFilters() }, enabled = ws.store.filterCount > 0) { Text("清除筛选") }
            TextButton(onClick = onDismiss) { Text("完成") }
        }
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text("组内任选一个即可，组和组之间都要满足；时间线和浏览都按这里筛选。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GROUPS.forEach { g ->
                Section(g.title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sel = ws.store.filters[g.key].orEmpty()
                    g.tags.forEach { (tag, name) ->
                        val on = tag in sel
                        FilterChip(
                            selected = on,
                            onClick = { ws.toggleFilter(g.key, tag) },
                            label = { Text(name) },
                            leadingIcon = if (on) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                selectedContainerColor = glassSelectedColor(),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
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
                        Text(me.steamId, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { ws.logout(); onDismiss() }) { Text("退出登录") }
                }
                Text(
                    "订阅会加到这个账号上，由登录同一账号的电脑 Steam 客户端下载。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text("还没有登录 Steam", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("请登录电脑上 Steam 客户端用的那个账号，订阅的壁纸才会下载到那台电脑。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { onDismiss(); LocalActions.login() }) { Text("登录 Steam") }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("液态玻璃效果", fontWeight = FontWeight.Medium)
                    Text("关掉后顶栏、标签栏和面板改用普通的半透明底色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ws.store.glass, onCheckedChange = { ws.store.glass = it; ws.store.save() })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("版本 v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Medium)
                    Text("从 GitHub 检查新版本（启动时每 6 小时最多查一次）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (updater.checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { updater.check(manual = true) }) { Text("检查更新") }
                }
            }
            TextButton(onClick = { openUrl(context, "https://github.com/${BuildConfig.REPO}/releases") }, modifier = Modifier.padding(top = 4.dp)) { Text("在 GitHub 查看所有版本") }
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
        SheetTitle("发现新版本 v${rel.version}")
        Column(scroll.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text("当前版本 v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rel.notes.isNotBlank()) {
                Text(rel.notes.trim(), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
            }
            val p = updater.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                Text("正在下载 ${(p * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (updater.message.isNotEmpty()) {
                Text(updater.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { updater.download(activity) }, enabled = p == null) { Text("下载并安装") }
                OutlinedButton(onClick = { updater.skip() }) { Text("以后再说") }
            }
            TextButton(onClick = { openUrl(context, rel.page) }) { Text("打开发布页") }
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
                PreviewImage(last, 1080, animate = true, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
        delay(2800)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        val shape = RoundedCornerShape(50)
        Text(
            text, fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 340.dp).glassClip(shape, MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f), GlassStyle.Panel)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
