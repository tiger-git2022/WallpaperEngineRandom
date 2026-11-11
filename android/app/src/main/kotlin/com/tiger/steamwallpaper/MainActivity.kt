package com.tiger.steamwallpaper

import kotlin.math.roundToInt
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val w = App.instance.workshop
        w.refreshLogin()
        App.instance.news.refreshLogin(force = true)
        if (w.me.loggedIn) w.toast("已登录 Steam")
    }

    private val subLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        App.instance.workshop.afterSubLogin()
    }

    private val newsLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        App.instance.news.afterNewsLogin()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = App.instance
        LocalActions.login = { login.launch(Intent(this, LoginActivity::class.java)) }
        LocalActions.loginSub = { subLogin.launch(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SUB, true)) }
        LocalActions.loginNews = { newsLogin.launch(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_NEWS, true)) }
        if (!started) {
            started = true
            app.workshop.start()
            app.news.start()
        }
        setContent { WorkshopTheme { WorkshopApp(this) } }
    }

    override fun onResume() {
        super.onResume()
        val app = App.instance
        app.workshop.refreshLogin()
        app.news.refreshLogin()
        app.updater.onResume(this)
        app.updater.autoCheck()
    }

    override fun onStop() {
        super.onStop()
        // 离开（切到后台、锁屏、退出）：记下「上次看到这里」
        App.instance.news.leave()
    }

    companion object {
        private var started = false
    }
}

private val BarHeight = 56.dp
private val TabBarHeight = 64.dp

@Composable
fun WorkshopApp(activity: MainActivity) {
    val backdrop = rememberGlassBackdrop(ws.store.glass)
    var filterOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf<String?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    LocalActions.log = { logOpen = true }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    LocalActions.lightbox = { lightbox = it }
    LocalActions.menu = { menu = it }
    var newsSettingsOpen by remember { mutableStateOf(false) }
    LocalActions.newsSettings = { newsSettingsOpen = true }

    val status = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val contentPadding = PaddingValues(
        top = status.calculateTopPadding() + 8.dp + BarHeight + 12.dp,
        bottom = nav.calculateBottomPadding() + 12.dp + TabBarHeight + 16.dp,
    )
    var topBottom by remember { mutableStateOf(0f) }
    var barTop by remember { mutableStateOf(0f) }
    var width by remember { mutableStateOf(0f) }
    val viewport = remember { derivedStateOf { Rect(0f, topBottom, width, barTop) } }

    BackHandler(ws.stack.isNotEmpty()) { ws.back() }
    val refreshHost = remember { RefreshHost() }

    CompositionLocalProvider(
        LocalContentPadding provides contentPadding, LocalViewport provides viewport, LocalPageBackdrop provides backdrop,
        LocalRefreshHost provides refreshHost,
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onGloballyPositioned { width = it.size.width.toFloat() },
        ) {
            // 页面内容：录下来给浮动的玻璃折射。页面里的部件不能折射它自己，所以里面没有玻璃
            CompositionLocalProvider(LocalPageBackdrop provides null) {
                Box(Modifier.fillMaxSize().glassContent(backdrop)) {
                    // 作者页盖在标签页上面；标签页之间左右滑动切换
                    val target: Any = ws.stack.lastOrNull() ?: "tabs"
                    AnimatedContent(targetState = target, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { page ->
                        if (page is AuthorScreen) {
                            AuthorPage(page) { filterOpen = true }
                        } else {
                            TabPages(
                                onSort = { anchor ->
                                    menu = MenuRequest(anchor, SORTS.map { it.key to it.title }, ws.store.sort) { ws.setSort(it) }
                                },
                                onFilter = { filterOpen = true },
                            )
                        }
                    }
                }
            }

            // 状态栏后面的渐变：列表滚上去时，文字不会和时间、电量叠在一起
            val bg = MaterialTheme.colorScheme.background
            Box(
                Modifier.fillMaxWidth().height(status.calculateTopPadding() + 16.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.92f), 0.65f to bg.copy(alpha = 0.75f), 1f to bg.copy(alpha = 0f),
                    ),
                ),
            )

            // 下拉刷新的那滴玻璃：画在页面上面，才能折射页面
            RefreshIndicator(refreshHost, Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()))

            TopBar(
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    .onGloballyPositioned { topBottom = it.positionInRoot().y + it.size.height },
                onAccount = { accountOpen = true },
            )
            TabBar(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp, start = 24.dp, end = 24.dp)
                    .onGloballyPositioned { barTop = it.positionInRoot().y },
            )
            Toast(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp + TabBarHeight + 14.dp))

            GlassMenu(menu) { menu = null }
            DetailSheet()
            FilterSheet(filterOpen) { filterOpen = false }
            AccountSheet(accountOpen) { accountOpen = false }
            NewsSettingsSheet(newsSettingsOpen) { newsSettingsOpen = false }
            LogSheet(logOpen) { logOpen = false }
            UpdateSheet(activity)
            Lightbox(lightbox) { lightbox = null }
        }
    }
}

@Composable
private fun TopBar(modifier: Modifier, onAccount: () -> Unit) {
    val author = ws.stack.lastOrNull()
    Row(modifier.fillMaxWidth().height(BarHeight), verticalAlignment = Alignment.CenterVertically) {
        if (author != null) {
            GlassCircle(onClick = { ws.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Spacer(Modifier.width(10.dp))
            val name = ws.store.profiles[author.steamId]?.name?.ifEmpty { null }
            if (author.searching) {
                // 在这个作者的作品里搜索：本地筛选，边输边搜；关掉搜索框就清空
                SearchField(
                    Modifier.weight(1f), value = author.query, onValueChange = { author.query = it },
                    hint = if (name != null) "搜索 $name 的作品" else "搜索 TA 的作品", focusedHint = "标题、标签或简介里的词",
                    showClear = true, onClear = { author.searching = false; author.query = "" }, autoFocus = true,
                )
            } else {
                Row(
                    Modifier.weight(1f).fillMaxHeight().glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(authorAvatar(author.steamId), 38.dp)
                    Column(Modifier.padding(horizontal = 10.dp)) {
                        Text(name ?: "作者", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(author.view.total?.let { "$it 个作品" } ?: "正在读取…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(10.dp))
                GlassCircle(onClick = { author.searching = true }) { Icon(Icons.Default.Search, contentDescription = "搜索这个作者的作品") }
            }
        } else if (ws.store.tab == "news") {
            NewsBar(Modifier.weight(1f))
        } else {
            SearchField(
                Modifier.weight(1f), value = ws.query, onValueChange = { ws.query = it },
                hint = "搜索创意工坊壁纸", focusedHint = "输入关键词，回车搜索",
                onSearch = { ws.submitSearch() },
                showClear = ws.query.isNotEmpty() || ws.search.isNotEmpty(), onClear = { ws.clearSearch() },
            )
        }
        Spacer(Modifier.width(10.dp))
        GlassCircle(onClick = onAccount) {
            if (ws.me.loggedIn && ws.me.avatar.isNotEmpty()) Avatar(ws.me.avatar, 40.dp)
            else Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "账号")
        }
    }
}

private val CircleShapeR = RoundedCornerShape(50)

@Composable
private fun SearchField(
    modifier: Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    focusedHint: String,
    showClear: Boolean,
    onClear: () -> Unit,
    onSearch: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { requester.requestFocus() }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxHeight().glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Control)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = onSurface)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(if (focused) focusedHint else hint, color = onSurface.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearch()
                    keyboard?.hide()
                    focus.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused },
            )
        }
        if (showClear) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClear(); focus.clearFocus() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "清除搜索", tint = onSurface)
            }
        }
    }
}

/** 新闻页的顶栏：标题和刷新状态，右边是新闻设置。 */
@Composable
private fun NewsBar(modifier: Modifier) {
    val feed = news
    val store = feed.store
    Row(
        modifier.fillMaxHeight().glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Steam 新闻", fontWeight = FontWeight.SemiBold, maxLines = 1)
            val status = when {
                feed.refreshing -> feed.progress.ifEmpty { "正在刷新…" }
                feed.error.isNotEmpty() -> feed.error
                store.lastRefresh > 0 -> "上次刷新 " + whenText(store.lastRefresh) + if (feed.followedCount > 0) " · 关注 ${feed.followedCount} 个发行商" else ""
                else -> "下拉刷新"
            }
            Text(
                status, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (feed.error.isNotEmpty() && !feed.refreshing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (feed.refreshing) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { LocalActions.newsSettings() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Settings, contentDescription = "新闻设置")
        }
    }
}

@Composable
private fun GlassCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(BarHeight).glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_CONTROL_TINT), GlassStyle.Control)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 三个标签页：左右滑动切换；点底部标签栏时也滑过去。 */
@Composable
private fun TabPages(onSort: (Rect) -> Unit, onFilter: () -> Unit) {
    val keys = TABS.map { it.key }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = keys.indexOf(ws.store.tab).coerceAtLeast(0)) { keys.size }
    // 标签栏的高亮跟着手指走：滑到一半就停在两个标签中间
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { tabPos.floatValue = it }
    }
    // 点标签栏：滑到那一页
    LaunchedEffect(pager) {
        snapshotFlow { ws.store.tab }.collect { t ->
            val i = keys.indexOf(t)
            if (i >= 0 && i != pager.targetPage) pager.animateScrollToPage(i)
        }
    }
    // 滑到一页停下：就是切到那个标签
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            val t = keys[p]
            if (t != ws.store.tab && !pager.isScrollInProgress) ws.openTab(t)
        }
    }
    androidx.compose.foundation.pager.HorizontalPager(pager, modifier = Modifier.fillMaxSize(), key = { keys[it] }) { p ->
        CompositionLocalProvider(LocalPageActive provides (p == pager.currentPage)) {
            when (keys[p]) {
                "news" -> NewsPage()
                "browse" -> BrowsePage(onSort, onFilter)
                "following" -> FollowingPage()
                else -> TimelinePage()
            }
        }
    }
}

private class TabItem(val key: String, val title: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("news", "新闻", Icons.Default.Newspaper),
    TabItem("timeline", "壁纸动态", Icons.Default.DynamicFeed),
    TabItem("browse", "浏览工坊", Icons.Default.Wallpaper),
    TabItem("following", "关注的作者", Icons.Default.People),
)

@Composable
private fun TabBar(modifier: Modifier) {
    val atTabs = ws.stack.isEmpty()
    val scope = rememberCoroutineScope()
    val gap = 4.dp
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.widthIn(max = 460.dp).fillMaxWidth().height(TabBarHeight)
            .glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel)
            .padding(5.dp),
    ) {
    val itemWidth = (maxWidth - gap * (TABS.size - 1)) / TABS.size
    // 选中的高亮：一块，跟着页面左右滑动（作者页上不显示）
    val pos = tabPos.floatValue
    if (atTabs) {
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(((itemWidth + gap).toPx() * pos).roundToInt(), 0) }
                .width(itemWidth).fillMaxHeight().clip(CircleShapeR).background(glassSelectedColor()),
        )
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        TABS.forEachIndexed { i, t ->
            // 离高亮最近的那个标签算选中：滑过一半时文字颜色就换过去
            val on = atTabs && kotlin.math.abs(pos - i) < 0.5f
            val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            // 只裁点按的高亮（圆头），标签里的内容不裁：未读角标伸到圆角外面也完整显示
            Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier.matchParentSize().clip(CircleShapeR)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple()) {
                        if (on) {
                            // 再点一次当前标签：不在顶部就回到顶部，已经在顶部就刷新
                            when (t.key) {
                                "news" -> with(news) {
                                    if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) {
                                        refresh(manual = true)
                                    } else {
                                        // 点标签回到顶部：中间跳过的也都算看过
                                        markAllRead()
                                        scope.launch { list.animateScrollToItem(0) }
                                    }
                                }
                                "timeline" -> ws.timeline?.let { v ->
                                    if (v.list.firstVisibleItemIndex == 0 && v.list.firstVisibleItemScrollOffset == 0) {
                                        ws.refresh(v)
                                    } else {
                                        // 点标签回到顶部：中间跳过的也都算看过
                                        ws.markAllRead()
                                        scope.launch { v.list.animateScrollToItem(0) }
                                    }
                                }
                                "browse" -> ws.browse?.let { v ->
                                    if (v.grid.firstVisibleItemIndex == 0 && v.grid.firstVisibleItemScrollOffset == 0) ws.refresh(v)
                                    else scope.launch { v.grid.animateScrollToItem(0) }
                                }
                                else -> with(ws.followingList) {
                                    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) ws.loadFollows()
                                    else scope.launch { animateScrollToItem(0) }
                                }
                            }
                        } else {
                            ws.detail = null
                            ws.openTab(t.key)
                        }
                    },
            )
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(24.dp)) {
                    Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
                    val badge = when (t.key) {
                        "news" -> news.unread
                        "timeline" -> ws.unread
                        else -> 0
                    }
                    if (badge > 0) {
                        // 角标压在图标右上角：四个标签时每个标签比较窄，放在图标右边会超出选中高亮的圆头
                        Text(
                            if (badge > 99) "99+" else badge.toString(),
                            color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.align(Alignment.TopStart).offset(x = 15.dp, y = (-5).dp)
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .clip(CircleShapeR).background(Tones.New).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(t.title, fontSize = 11.sp, color = color, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
            }
        }
    }
    }
}

/** 标签页左右滑动时的实时位置（0 = 新闻，1 = 壁纸动态，2 = 浏览工坊，3 = 关注的作者，中间值 = 正在滑）。 */
private val tabPos = androidx.compose.runtime.mutableFloatStateOf(
    TABS.indexOfFirst { it.key == ws.store.tab }.coerceAtLeast(0).toFloat(),
)
