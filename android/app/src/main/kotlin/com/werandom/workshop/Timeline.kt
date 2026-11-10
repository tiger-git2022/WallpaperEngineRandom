package com.werandom.workshop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** 时间线的一条：一个壁纸，或者同一作者连续上传的几个（合并时）。 */
class Post(val creator: String, val read: Boolean, val items: List<Item>)

/**
 * 可选的「合并同一作者的连续上传」：同一作者、相邻两条间隔不超过 6 小时、最多 4 个、不跨已读线，合成一条。
 */
fun groupPosts(list: List<Item>, readUpTo: Long, merge: Boolean): List<Post> {
    if (!merge) return list.map { Post(it.creator, it.created <= readUpTo, listOf(it)) }
    val posts = mutableListOf<Post>()
    var cur: MutableList<Item>? = null
    var curRead = false
    for (it in list) {
        val read = it.created <= readUpTo
        val c = cur
        if (c != null && c[0].creator == it.creator && curRead == read && c.size < 4 && c.last().created - it.created < 6 * 3600) {
            c.add(it)
        } else {
            cur = mutableListOf(it)
            curRead = read
            posts.add(Post(it.creator, read, cur))
        }
    }
    return posts
}

@Composable
fun TimelinePage() {
    if (!ws.me.loggedIn) {
        NeedLogin()
        return
    }
    val v = ws.timeline ?: return
    val store = ws.store
    val shown = v.ids.mapNotNull { ws.items[it] }
    val posts = groupPosts(shown, store.readUpTo, store.mergePosts)
    val b = ws.boundaryIndex(v)
    val hasDivider = b > 0 && b < v.ids.size
    val dividerAt = if (hasDivider) posts.indexOfFirst { it.read }.takeIf { it >= 0 } else null
    val scope = rememberCoroutineScope()
    val pad = LocalContentPadding.current

    LaunchedEffect(v) {
        snapshotFlow {
            val info = v.list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.collect { near -> if (near && !v.loading && !v.done && v.error == null && v.ids.isNotEmpty()) ws.loadMore(v) }
    }

    LazyColumn(
        state = v.list,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("head") {
            TimelineHeader(v, hasDivider) {
                val index = (dividerAt ?: return@TimelineHeader) + 1
                scope.launch {
                    val h = v.list.layoutInfo.viewportSize.height
                    v.list.animateScrollToItem(index, -h / 3)
                }
            }
        }
        if (!v.loading && v.error == null && v.done && posts.isEmpty()) {
            item("empty") {
                EmptyState("时间线是空的", "还没有关注任何作者，或者关注的作者还没发布过壁纸。\n在壁纸详情里点作者旁边的「关注」，或在 Steam 里关注创意工坊作者。")
            }
        }
        posts.forEachIndexed { i, post ->
            if (i == dividerAt) {
                item("divider") { ReadDivider(store.readUpTo) }
            }
            item("p:" + post.items[0].id) {
                if (post.items.size == 1) SinglePost(post.items[0], post.read) else GroupPost(post)
            }
        }
        item("more") { MoreFooter(v) }
    }
}

@Composable
private fun TimelineHeader(v: ListView, hasDivider: Boolean, jump: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("关注的作者的新作品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (ws.unread > 0) {
                Spacer(Modifier.width(8.dp))
                Badge(if (ws.unread > 99) "99+" else "${ws.unread} 条未读", background = Tones.New)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ws.store.mergePosts, onClick = { ws.setMerge(!ws.store.mergePosts) }, label = { Text("合并同一作者的连续上传") })
            AssistChip(onClick = jump, enabled = hasDivider, label = { Text("跳到上次看到的位置") })
            AssistChip(onClick = { ws.markAllRead() }, enabled = v.ids.isNotEmpty(), label = { Text("全部标为已读") })
            AssistChip(onClick = { ws.refreshTimeline() }, label = { Text("刷新") })
        }
    }
}

@Composable
private fun ReadDivider(readUpTo: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "以下是已经看过的（${date(readUpTo)} 之前）",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun PostCard(read: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .alpha(if (read) 0.82f else 1f).padding(14.dp),
    ) { content() }
}

@Composable
private fun PostHeader(creator: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(authorAvatar(creator), 40.dp, Modifier.clickable { ws.openAuthor(creator) })
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                authorName(creator), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { ws.openAuthor(creator) },
            )
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FollowButton(creator)
    }
}

@Composable
private fun SinglePost(it: Item, read: Boolean) {
    PostCard(read) {
        val meta = listOf(label(it.type).takeIf { _ -> it.type.isNotEmpty() }, label(it.age).takeIf { _ -> it.age.isNotEmpty() }, it.resolution.replace(" x ", "×").ifEmpty { null })
            .filterNotNull().joinToString(" · ")
        PostHeader(it.creator, ago(it.created) + if (meta.isNotEmpty()) " · $meta" else "")
        WallpaperBody(it, track = true)
    }
}

@Composable
private fun GroupPost(post: Post) {
    var open by remember(post.items[0].id) { mutableStateOf<String?>(null) }
    PostCard(post.read) {
        val first = post.items[0]
        val last = post.items.last()
        PostHeader(post.creator, ago(first.created) + if (first.created - last.created > 600) " ~ " + ago(last.created) else "")
        Text(buildString { append("发布了 "); append(post.items.size); append(" 个新壁纸") }, modifier = Modifier.padding(bottom = 8.dp))
        val cols = if (post.items.size == 2 || post.items.size == 4) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            post.items.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        val selected = open == item.id
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .then(if (selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp)) else Modifier)
                                    .trackSeen(item.id, ws.isNew(item))
                                    .clickable {
                                        open = if (selected) null else item.id
                                        if (!selected) ws.markSeen(item.id)
                                    },
                            ) {
                                PreviewImage(item.preview, 320, animate = true, modifier = Modifier.fillMaxSize())
                                TileBadges(item, timeline = true)
                            }
                            Text(
                                item.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (ws.isNew(item)) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                            )
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val opened = open?.let { ws.items[it] }
        if (opened != null) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            WallpaperBody(opened, track = false)
        } else {
            HorizontalDivider(Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("点一张图查看详情和订阅", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { ws.openAuthor(post.creator) }) { Text("作者的全部作品") }
            }
        }
    }
}

/** 一个壁纸的标题、简介、大图（和其他预览图）、标签、数字和操作栏。 */
@Composable
fun WallpaperBody(it: Item, track: Boolean) {
    val context = LocalContext.current
    val desc = bbcode(it.description ?: it.short)
    val more = it.description == null && desc.length >= 120
    Text(it.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    if (desc.isNotEmpty()) {
        Text(
            desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp,
            maxLines = if (it.description == null) 3 else 40, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (more) {
        val busy = ws.busy["text:${it.id}"] == true
        Text(
            if (busy) "正在读取…" else "展开全文", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 4.dp).clickable { ws.expandText(it.id) },
        )
    }
    Spacer(Modifier.height(10.dp))
    var cur by remember(it.id) { mutableStateOf(it.preview) }
    BigPicture(cur, inner = Modifier.trackSeen(it.id, track && ws.isNew(it))) { LocalActions.lightbox(cur) }
    if (it.images.isNotEmpty() || it.videos.isNotEmpty()) {
        PreviewStrip(it, cur) { url -> cur = url }
    }
    val tags = it.tags.filter { t -> t != "Wallpaper" && t != it.type && t != it.age && !isResolutionTag(t) }
    if (tags.isNotEmpty()) {
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t -> SuggestionChip(onClick = { ws.searchTag(t) }, label = { Text(label(t), fontSize = 12.sp) }, modifier = Modifier.height(30.dp)) }
        }
    }
    val total = (it.votesUp ?: 0) + (it.votesDown ?: 0)
    val nums = listOfNotNull(
        if (it.size > 0) size(it.size) else null,
        num(it.subs) + " 订阅",
        if (total > 0) "${(it.votesUp ?: 0) * 100 / total}% 好评" else null,
    ).joinToString(" · ")
    Text(nums, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    HorizontalDivider(Modifier.padding(top = 10.dp))
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        SubscribeRow(it)
        TextButton(onClick = { ws.openAuthor(it.creator) }) { Text("作者的全部作品") }
        TextButton(onClick = { ws.openDetail(it.id) }) { Text("详情") }
        TextButton(onClick = { openUrl(context, workshopUrl(it.id)) }) { Text("网页") }
    }
}

/** 其他预览图：点一张在大图里显示，视频在 YouTube 打开。 */
@Composable
fun PreviewStrip(it: Item, current: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val images = listOf(it.preview) + it.images
    LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(images) { url ->
            val on = url == current
            AsyncImage(
                model = small(url, 160), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier.alpha(0.75f))
                    .clickable { onPick(url) },
            )
        }
        items(it.videos) { y ->
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/$y/mqdefault.jpg", contentDescription = "YouTube 视频", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { openUrl(context, "https://www.youtube.com/watch?v=$y") },
                )
                Text("▶", color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp)
            }
        }
    }
}

/** 图片上的角标：NEW、年龄分级、类型、已订阅。 */
@Composable
fun TileBadges(it: Item, timeline: Boolean) {
    Box(Modifier.fillMaxSize().padding(6.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (timeline && ws.isNew(it)) Badge("NEW", background = Tones.New)
            if (it.age.isNotEmpty() && it.age != "Everyone") Badge(label(it.age), color = ageColor(it.age))
        }
        if (!timeline && it.type.isNotEmpty()) Badge(label(it.type), modifier = Modifier.align(Alignment.TopEnd))
        if (ws.isSubscribed(it.id)) Badge("✓ 已订阅", background = Tones.Ok.copy(alpha = 0.92f), modifier = Modifier.align(Alignment.BottomEnd))
    }
}
