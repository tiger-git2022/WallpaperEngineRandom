package com.tiger.steamwallpaper

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
        if (w.me.loggedIn) w.toast("已登录 Steam")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = App.instance
        LocalActions.login = { login.launch(Intent(this, LoginActivity::class.java)) }
        if (!started) {
            started = true
            app.workshop.start()
        }
        setContent { WorkshopTheme { WorkshopApp(this) } }
    }

    override fun onResume() {
        super.onResume()
        val app = App.instance
        app.workshop.refreshLogin()
        app.updater.onResume(this)
        app.updater.autoCheck()
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
                    // 目标是作者页对象或标签页的名字：切换动画里旧页面按自己的目标画，不会跟着变成新页面
                    val target: Any = ws.stack.lastOrNull() ?: ws.store.tab
                    AnimatedContent(targetState = target, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { page ->
                        when (page) {
                            is AuthorScreen -> AuthorPage(page) { filterOpen = true }
                            "browse" -> BrowsePage(
                                onSort = { anchor ->
                                    menu = MenuRequest(anchor, SORTS.map { it.key to it.title }, ws.store.sort) { ws.setSort(it) }
                                },
                                onFilter = { filterOpen = true },
                            )
                            "following" -> FollowingPage()
                            else -> TimelinePage()
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
            Row(
                Modifier.weight(1f).fillMaxHeight().glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(authorAvatar(author.steamId), 38.dp)
                Column(Modifier.padding(horizontal = 10.dp)) {
                    Text(ws.store.profiles[author.steamId]?.name?.ifEmpty { null } ?: "作者", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(author.view.total?.let { "$it 个作品" } ?: "正在读取…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        } else {
            SearchField(Modifier.weight(1f))
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
private fun SearchField(modifier: Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxHeight().glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Control)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = onSurface)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (ws.query.isEmpty()) {
                Text(if (focused) "输入关键词，回车搜索" else "搜索创意工坊壁纸", color = onSurface.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = ws.query,
                onValueChange = { ws.query = it },
                singleLine = true,
                textStyle = TextStyle(color = onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    ws.submitSearch()
                    keyboard?.hide()
                    focus.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (ws.query.isNotEmpty() || ws.search.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { ws.clearSearch(); focus.clearFocus() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "清除搜索", tint = onSurface)
            }
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

private class TabItem(val key: String, val title: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("timeline", "时间线", Icons.Default.DynamicFeed),
    TabItem("browse", "浏览工坊", Icons.Default.Wallpaper),
    TabItem("following", "关注的作者", Icons.Default.People),
)

@Composable
private fun TabBar(modifier: Modifier) {
    val atTabs = ws.stack.isEmpty()
    val scope = rememberCoroutineScope()
    Row(
        modifier.widthIn(max = 460.dp).fillMaxWidth().height(TabBarHeight)
            .glassClip(CircleShapeR, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEach { t ->
            val on = atTabs && ws.store.tab == t.key
            val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShapeR)
                    .background(if (on) glassSelectedColor() else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple()) {
                        if (on) {
                            // 再点一次当前标签：不在顶部就回到顶部，已经在顶部就刷新
                            when (t.key) {
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(24.dp)) {
                    Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
                    if (t.key == "timeline" && ws.unread > 0) {
                        // 角标放在图标右边、和图标居中对齐：不盖住图标，也不会被标签的圆角裁掉
                        Text(
                            if (ws.unread > 99) "99+" else ws.unread.toString(),
                            color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterStart).offset(x = 27.dp)
                                .wrapContentSize(Alignment.CenterStart, unbounded = true)
                                .clip(CircleShapeR).background(Tones.New).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(t.title, fontSize = 11.sp, color = color, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
