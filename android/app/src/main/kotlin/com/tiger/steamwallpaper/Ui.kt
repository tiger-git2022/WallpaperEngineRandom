package com.tiger.steamwallpaper

import androidx.compose.ui.draw.drawBehind
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 列表上下留给浮动的顶栏和底部标签栏的空白。 */
val LocalContentPadding = staticCompositionLocalOf { PaddingValues() }

/** 屏幕上没被顶栏、底栏挡住的区域（根坐标，像素），用来判断时间线的图片露出了多少。 */
val LocalViewport = staticCompositionLocalOf<State<Rect>> { mutableStateOf(Rect.Zero) }

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { App.instance.workshop.toast("打不开链接：$url") }
}

fun workshopUrl(id: String) = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"

val ws: Workshop get() = App.instance.workshop

// ---- images ----

/**
 * 预览图：先显示 Steam CDN 的静态图（按控件实际的像素宽度要，不会比原图大），[animate] 时同时下载原图（动图），
 * 下载完成后换上去。离开屏幕（这个组合项被回收）时原图的请求随之取消。[onRatio] 收到静态图的宽高比。
 * 创意工坊的预览动图本身常常只有两三百像素宽、256 色，放大后会有点糊。
 */
@Composable
fun PreviewImage(
    url: String,
    animate: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onRatio: ((Float) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // 按 256 像素一档取整，同一张图在不同地方能共用缓存
        val px = with(density) { if (constraints.hasBoundedWidth) constraints.maxWidth else 1080.dp.roundToPx() }
            .let { ((it + 255) / 256 * 256).coerceIn(256, 2048) }
        // 还没加载出来时：灰底上一道高光一直从左往右扫，看得出在加载
        var loading by remember(url) { mutableStateOf(url.isNotEmpty()) }
        if (loading) LoadingShimmer(Modifier.fillMaxSize())
        AsyncImage(
            model = small(url, px),
            contentDescription = null,
            contentScale = contentScale,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize(),
            onLoading = { loading = true },
            onSuccess = { s ->
                loading = false
                val img = s.result.image
                if (img.height > 0) onRatio?.invoke(img.width.toFloat() / img.height)
            },
            onError = { loading = false },
        )
        if (animate && url.isNotEmpty()) {
            var ready by remember(url) { mutableStateOf(false) }
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else 0f },
                onSuccess = { ready = true },
            )
        }
    }
}

/** 图片加载中的占位：底色上一道斜着的高光循环扫过（只重画，不重组）。 */
@Composable
fun LoadingShimmer(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val shine = if (isDark()) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.6f)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier.drawBehind {
            drawRect(base)
            val w = size.width
            val c = w * x.value
            drawRect(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color.Transparent, shine, Color.Transparent),
                    start = androidx.compose.ui.geometry.Offset(c - w * 0.45f, 0f),
                    end = androidx.compose.ui.geometry.Offset(c + w * 0.45f, size.height),
                ),
            )
        },
    )
}

/** 读到过的预览图宽高比（这次运行期间）。 */
private val Ratios = HashMap<String, Float>()

/** 整张图按自己的比例显示（不裁成方形），最高 [maxHeight]，居中。 */
@Composable
fun BigPicture(url: String, modifier: Modifier = Modifier, maxHeight: Dp = 480.dp, inner: Modifier = Modifier, onClick: () -> Unit) {
    // 宽高比读到一次就记住（滑出屏幕再回来、在详情里再打开都不用重新撑开）；还不知道时按方形占位，预览图大多是方的
    var ratio by remember(url) { mutableStateOf<Float?>(Ratios[url] ?: 1f) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val r = ratio
        val w: Dp
        val h: Dp
        if (r == null) {
            w = maxWidth
            h = min(maxWidth.value * 0.62f, maxHeight.value).dp
        } else {
            val full = maxWidth.value / r
            if (full <= maxHeight.value) {
                w = maxWidth
                h = full.dp
            } else {
                h = maxHeight
                w = (maxHeight.value * r).dp
            }
        }
        Box(
            Modifier.size(w, h).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .then(inner).clickable(onClick = onClick),
        ) {
            PreviewImage(url, animate = true, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onRatio = {
                val r = it.coerceIn(0.4f, 3f)
                Ratios[url] = r
                ratio = r
            })
        }
    }
}

@Composable
fun Avatar(url: String, size: Dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNotEmpty()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** 作者的名字（还没读到时显示 …）。 */
@Composable
fun authorName(id: String): String = ws.store.profiles[id]?.name?.takeIf { it.isNotEmpty() } ?: "…"

@Composable
fun authorAvatar(id: String): String = ws.store.profiles[id]?.avatar ?: ""

// ---- reading progress ----

/** 时间线上的一张图：露出 60% 以上（或露出的部分占了大半个屏幕）并停留 0.7 秒，记为看过。 */
@Composable
fun Modifier.trackSeen(id: String, enabled: Boolean): Modifier {
    if (!enabled) return this
    val viewport = LocalViewport.current
    var visible by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, visible) {
        if (visible) {
            delay(700)
            ws.markSeen(id)
        }
    }
    return onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        val vp = viewport.value
        val h = c.size.height.toFloat()
        val shown = min(b.bottom, vp.bottom) - max(b.top, vp.top)
        visible = h > 0 && shown > 0 && (shown / h >= 0.6f || shown >= vp.height * 0.6f)
    }
}

// ---- buttons ----

@Composable
fun FollowButton(id: String, modifier: Modifier = Modifier) {
    if (!ws.me.loggedIn || id == ws.me.steamId) return
    val on = ws.isFollowing(id)
    val busy = ws.busy["follow:$id"] == true
    // 关注和已关注一样大小，只是关注是实心的主题色
    if (on) {
        GlassButton(if (busy) "…" else "已关注", onClick = { ws.follow(id, false) }, modifier = modifier)
    } else {
        GlassButton(if (busy) "…" else "+ 关注", onClick = { ws.follow(id, true) }, modifier = modifier, primary = true)
    }
}

val PillHeight = 32.dp

/**
 * 标签、筛选项和次要按钮，照 EhViewer 分支展开的搜索界面里的标签：8 dp 圆角，32 dp 高，文字黑色（深色主题白色）。
 * 在玻璃面板上是一小块玻璃，折射面板那层模糊的玻璃，没有边框，选中时玻璃里亮一块主题色；在页面上（折射不到页面自己）
 * 是浅色主题淡淡的底色、深色主题透明底加白边。[primary] 是实心的主题色按钮（「+ 关注」）。
 */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    primary: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = PillShape,
    accent: Color? = null,
) {
    val dark = isDark()
    val scheme = MaterialTheme.colorScheme
    val tile = LocalTileBackdrop.current
    // [accent]：自己的颜色（「✓ 已订阅」的绿色），文字和玻璃里的那层光都用它
    val content = (accent ?: if (primary) scheme.primary else scheme.onSurface).let { if (enabled) it else it.copy(alpha = 0.4f) }
    // 主要操作（关注、订阅、登录）：玻璃里一层浓一些的主题色；选中的：淡一些的
    val light = when {
        accent != null -> accent.copy(alpha = if (dark) 0.3f else 0.16f)
        primary -> scheme.primary.copy(alpha = if (dark) 0.34f else 0.18f)
        selected -> glassSelectedColor()
        else -> Color.Transparent
    }
    val surface = if (tile != null) {
        Modifier.glass(shape, scheme.surfaceBright, GlassStyle.Tile, tile).clip(shape).background(light, shape)
    } else {
        // 页面里的按钮折射不到页面自己：磨砂的半透明底，浅色主题一圈柔和的阴影，深色主题一圈亮边
        val frost = if (dark) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.78f)
        (if (dark) Modifier else Modifier.shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f)))
            .clip(shape).background(frost, shape).background(light, shape)
            .then(if (dark) Modifier.border(1.dp, Color.White.copy(alpha = if (selected || primary) 0.7f else 0.45f), shape) else Modifier)
    }
    val pad = if (shape === ButtonShape) 18.dp else 14.dp
    Row(
        modifier.heightIn(min = if (shape === ButtonShape) ButtonHeight else PillHeight).then(surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (leading != null) 8.dp else pad, end = if (trailing != null) 8.dp else pad, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = content, fontSize = 14.sp, fontWeight = if (selected || primary || accent != null) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, tint = content, modifier = Modifier.size(18.dp))
        }
    }
}

val PillShape = RoundedCornerShape(8.dp)

/** 按钮（订阅、关注、检查更新……）：和 Material 按钮一样的圆头，样式是玻璃（同 [Pill]）。 */
val ButtonShape = RoundedCornerShape(50)
val ButtonHeight = 36.dp

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    primary: Boolean = false,
    accent: Color? = null,
) = Pill(text, onClick, modifier, enabled = enabled, leading = leading, primary = primary, shape = ButtonShape, accent = accent)

/** 一排会换行的胶囊，行和行之间留空。 */
@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** 订阅按钮；已订阅时显示「已订阅」和取消订阅。 */
@Composable
fun SubscribeRow(it: Item, big: Boolean = false) {
    if (!ws.me.loggedIn || !it.isWallpaperEngine) return
    val sub = ws.isSubscribed(it.id)
    val busy = ws.busy["sub:${it.id}"] == true
    if (busy) {
        Box(Modifier.height(40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        return
    }
    // 和「已关注」一样：已订阅也是按钮，点一下取消订阅
    val size = if (big) Modifier.fillMaxWidth().heightIn(min = 44.dp) else Modifier
    if (sub) {
        GlassButton("✓ 已订阅", onClick = { ws.subscribe(it, false) }, accent = Tones.Ok, modifier = size)
    } else {
        GlassButton("+ 订阅", onClick = { ws.subscribe(it, true) }, primary = true, modifier = size)
    }
}

@Composable
fun Badge(text: String, color: Color = Color.White, background: Color = Color.Black.copy(alpha = 0.66f), modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}


// ---- list footer ----

@Composable
fun MoreFooter(v: ListView) {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        when {
            v.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            v.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败：${v.error}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("重试", onClick = { ws.loadMore(v) })
                    if (v.errorStatus == 401) GlassButton("重新登录", onClick = { LocalActions.login() })
                }
            }
            v.done && v.ids.isNotEmpty() -> Text("没有更多了", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** 界面上要用到的 Activity 级操作（登录、打开大图）。 */
object LocalActions {
    var login: () -> Unit = {}
    var lightbox: (String) -> Unit = {}
    var log: () -> Unit = {}
    var loginSub: () -> Unit = {}
}

@Composable
fun EmptyState(title: String, text: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 22.sp)
        if (button != null) {
            Spacer(Modifier.height(18.dp))
            GlassButton(button, onClick = onClick, primary = true)
        }
    }
}

@Composable
fun NeedLogin() {
    Box(Modifier.fillMaxSize().padding(LocalContentPadding.current), contentAlignment = Alignment.Center) {
        EmptyState(
            "登录 Steam 后使用创意工坊",
            "时间线、搜索、订阅和关注都要用你的 Steam 账号。\n请登录电脑上 Steam 客户端用的那个账号，订阅的壁纸才会下载到那台电脑。",
            "登录 Steam",
        ) { LocalActions.login() }
    }
}

// ---- pull to refresh ----

/**
 * 下拉刷新的指示（EhViewer 分支的 RefreshIndicator）：转圈的刷新箭头，放在一滴玻璃里。页面里的东西折射不到页面自己，
 * 所以由外层（WorkshopApp）画在页面上面，页面在这里登记它的下拉状态。
 */
@Stable
class RefreshHost {
    var state by mutableStateOf<PullToRefreshState?>(null)
    var refreshing by mutableStateOf(false)
}

val LocalRefreshHost = staticCompositionLocalOf { RefreshHost() }

/** 主界面左右滑动时相邻的页面也在画：只有正对着的那页去控制下拉刷新的那滴玻璃。 */
val LocalPageActive = androidx.compose.runtime.compositionLocalOf { true }

/** 可以下拉刷新的列表：在顶部继续往下拉，松手后 [onRefresh]。 */
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    val host = LocalRefreshHost.current
    val active = LocalPageActive.current
    SideEffect {
        if (active) {
            host.state = state
            host.refreshing = refreshing
        }
    }
    DisposableEffect(host, state) {
        onDispose { if (host.state === state) host.state = null }
    }
    Box(Modifier.fillMaxSize().pullToRefresh(isRefreshing = refreshing, state = state, onRefresh = onRefresh)) { content() }
}

@Composable
fun RefreshIndicator(host: RefreshHost, modifier: Modifier = Modifier) {
    val state = host.state ?: return
    val refreshing = host.refreshing
    if (state.distanceFraction <= 0f && !refreshing) return
    val spin = rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val size = 42.dp
    Box(
        modifier.offset {
            val travel = state.distanceFraction * PullToRefreshDefaults.PositionalThreshold.toPx() - size.toPx()
            IntOffset(0, travel.roundToInt())
        }
            .graphicsLayer { alpha = if (refreshing) 1f else (state.distanceFraction * 2f).coerceIn(0f, 1f) }
            .size(size)
            .glass(CircleShape, MaterialTheme.colorScheme.surfaceContainerHigh, GlassStyle.Control),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (refreshing) angle else state.distanceFraction.coerceIn(0f, 1f) * 270f },
        )
    }
}

// ---- glass sheet ----

/**
 * 从底部升起的玻璃面板（详情、筛选、账号、更新），画在页面里（弹出窗口在页面上面，折射不到页面）。
 * 点外面、返回键、往下拖都能关掉。[content] 里的可滚动内容要接上 [sheetScroll]，滚到顶后继续往下拉就拖动面板。
 */
@Composable
fun GlassSheet(visible: Boolean, onDismiss: () -> Unit, maxHeight: Float = 0.9f, content: @Composable ColumnScope.(sheetScroll: Modifier) -> Unit) {
    BackHandler(visible, onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val density = LocalDensity.current
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val dismissAt = with(density) { 110.dp.toPx() }
            fun settle(velocity: Float = 0f) {
                scope.launch {
                    if (drag.value > dismissAt || velocity > 2500f) onDismiss() else drag.animateTo(0f)
                }
            }
            val connection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (drag.value > 0f && available.y < 0f) {
                            val use = max(available.y, -drag.value)
                            scope.launch { drag.snapTo(drag.value + use) }
                            return Offset(0f, use)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (available.y > 0f && source == NestedScrollSource.UserInput) {
                            scope.launch { drag.snapTo(drag.value + available.y) }
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (drag.value > 0f) {
                            settle(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            // 面板的玻璃单独一层，录下来给面板上的标签折射（EhViewer 分支展开的搜索界面也是这样）
            val sheetBackdrop = if (LocalPageBackdrop.current != null) rememberLayerBackdrop() else null
            Box(
                Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * maxHeight)
                    .offset { IntOffset(0, drag.value.roundToInt()) },
            ) {
                Box(
                    Modifier.matchParentSize().then(if (sheetBackdrop != null) Modifier.layerBackdrop(sheetBackdrop) else Modifier)
                        .bottomSheetGlass(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = GLASS_SHEET_TINT), 28.dp),
                )
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .pointerInput(Unit) {}
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                  CompositionLocalProvider(LocalTileBackdrop provides sheetBackdrop) {
                    // 拖动条
                    Box(
                        Modifier.fillMaxWidth().height(28.dp).draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d -> scope.launch { drag.snapTo(max(0f, drag.value + d)) } },
                            onDragStopped = { v -> settle(v) },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                    }
                    content(Modifier.nestedScroll(connection))
                  }
                }
            }
        }
    }
}

@Composable
fun SheetTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

val SheetPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

val Gap = Arrangement.spacedBy(8.dp)
