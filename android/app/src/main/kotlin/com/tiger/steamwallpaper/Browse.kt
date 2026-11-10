package com.tiger.steamwallpaper

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 浏览 / 搜索页和作者页用的网格。 */
@Composable
private fun ItemGrid(v: ListView, header: @Composable () -> Unit, filter: Boolean, unsubscribedOnly: Boolean = false) {
    val pad = LocalContentPadding.current
    val shown = v.ids.mapNotNull { ws.items[it] }.filter { (!filter || ws.store.passesFilters(it)) && (!unsubscribedOnly || !ws.isSubscribed(it.id)) }
    // 打开详情后可以左右滑动，按页面上的顺序切换
    val ids = shown.map { it.id }
    // 快滚到底就加载下一页。加载状态也算在条件里：一页读完、筛选后还是不够一屏（比如这页全订阅了），会接着读下一页
    LaunchedEffect(v) {
        snapshotFlow {
            val info = v.grid.layoutInfo
            val near = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            near && !v.loading && !v.done && v.error == null && v.ids.isNotEmpty()
        }.collect { if (it) ws.loadMore(v) }
    }
    // 详情开着时，新加载的作品接到详情后面
    LaunchedEffect(ids) { ws.extendDetail(v, ids) }
    Refreshable(refreshing = v.refreshing != null, onRefresh = { ws.refresh(v) }) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = v.grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full("head") { header() }
            if (!v.loading && v.error == null && v.done && shown.isEmpty()) {
                full("empty") { EmptyState(if (v.ids.isNotEmpty()) "筛选条件下没有作品" else "没有找到壁纸", if (v.ids.isNotEmpty()) "试试清除筛选。" else "换个关键词或筛选条件试试。") }
            }
            items(shown, key = { it.id }) { Tile(it, v, ids) }
            full("more") { MoreFooter(v) }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Tile(it: Item, v: ListView, ids: List<String>) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { ws.openDetail(it.id, ids, v) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            PreviewImage(it.preview, animate = false, modifier = Modifier.fillMaxSize())
            TileBadges(it, timeline = false)
        }
        Text(it.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        val who = if (v.kind == Kind.Author) "" else authorName(it.creator) + " · "
        val tail = if (v.kind == Kind.Browse && v.sortKey == "subs") num(it.subs) + " 订阅" else ago(it.created)
        Text(who + tail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
    }
}

/** 浏览工坊：搜索、排序、筛选。 */
@Composable
fun BrowsePage(onSort: (Rect) -> Unit, onFilter: () -> Unit) {
    if (!ws.me.loggedIn) {
        NeedLogin()
        return
    }
    LaunchedEffect(Unit) { ws.browseView() }
    val v = ws.browse ?: return
    ItemGrid(v, header = {
        Column(Modifier.padding(horizontal = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (v.search.isNotEmpty()) "搜索「${v.search}」" else "全部壁纸") + (v.total?.let { "，共 ${num(it.toLong())} 个" } ?: ""),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (v.search.isNotEmpty()) GlassButton("清除搜索", onClick = { ws.clearSearch() })
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip(onSort)
                FilterChipButton(onFilter)
            }
        }
    }, filter = false)
}

@Composable
private fun SortChip(onSort: (Rect) -> Unit) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var key = ws.store.sort
    if (key == "text" && ws.search.isEmpty()) key = "trend7"
    val title = SORTS.firstOrNull { it.key == key }?.title ?: ""
    Pill(
        title, onClick = { onSort(anchor) },
        leading = Icons.AutoMirrored.Filled.Sort, trailing = Icons.Default.ArrowDropDown,
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() },
    )
}

@Composable
private fun FilterChipButton(onFilter: () -> Unit) {
    val n = ws.store.filterCount
    Pill(if (n > 0) "筛选（$n）" else "筛选", onClick = onFilter, selected = n > 0, leading = Icons.Default.FilterList)
}

/** 作者页：作者上传的全部壁纸（筛选在本地做）。名字、头像和作品数在顶栏。 */
@Composable
fun AuthorPage(s: AuthorScreen, onFilter: () -> Unit) {
    val v = s.view
    ItemGrid(v, header = {
        // 固定一行，不换行（字大放不下时可以左右滑）
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FollowButton(s.steamId)
            val only = ws.store.unsubscribedOnly
            Pill("只看未订阅", onClick = { ws.store.unsubscribedOnly = !only; ws.store.save() }, selected = only, leading = if (only) Icons.Default.Check else null)
            FilterChipButton(onFilter)
        }
    }, filter = true, unsubscribedOnly = ws.store.unsubscribedOnly)
}

/** 关注的作者。 */
@Composable
fun FollowingPage() {
    if (!ws.me.loggedIn) {
        NeedLogin()
        return
    }
    val pad = LocalContentPadding.current
    // 取消关注后先留在列表里（按钮变成「+ 关注」），点错了还能关注回来；刷新后才去掉
    val list = remember(ws.followsLoading, ws.store.follows.size == 0) { ws.store.follows.toList() }
    Refreshable(refreshing = ws.followsLoading, onRefresh = { ws.loadFollows() }) {
    LazyColumn(
        state = ws.followingList,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("head") {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text("关注的作者（${list.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("点一个查看 TA 上传的全部壁纸，下拉刷新", fontSize = 13.sp)
            }
        }
        if (list.isEmpty() && !ws.followsLoading) {
            item("empty") { EmptyState("还没有关注任何作者", "在壁纸详情里点作者旁边的「关注」，作者的新作品就会出现在时间线里。") }
        }
        items(list, key = { it }) { id ->
            Row(
                Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp))
                    .clickable { ws.openAuthor(id) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(authorAvatar(id), 44.dp, shape = RoundedCornerShape(12.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(ws.store.profiles[id]?.name?.ifEmpty { null } ?: id, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("查看全部作品", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                FollowButton(id)
            }
        }
    }
    }
}
