package com.werandom.workshop

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
 * 预览图：先显示 Steam CDN 的静态小图（[px] 宽），[animate] 时同时下载原图（动图），下载完成后换上去。
 * 离开屏幕（这个组合项被回收）时原图的请求随之取消。[onRatio] 收到小图的宽高比。
 */
@Composable
fun PreviewImage(
    url: String,
    px: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onRatio: ((Float) -> Unit)? = null,
) {
    Box(modifier) {
        AsyncImage(
            model = small(url, px),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { s ->
                val img = s.result.image
                if (img.height > 0) onRatio?.invoke(img.width.toFloat() / img.height)
            },
        )
        if (animate && url.isNotEmpty()) {
            var ready by remember(url) { mutableStateOf(false) }
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else 0f },
                onSuccess = { ready = true },
            )
        }
    }
}

/** 整张图按自己的比例显示（不裁成方形），最高 [maxHeight]，居中。 */
@Composable
fun BigPicture(url: String, modifier: Modifier = Modifier, maxHeight: Dp = 480.dp, inner: Modifier = Modifier, onClick: () -> Unit) {
    var ratio by remember(url) { mutableStateOf<Float?>(null) }
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
            PreviewImage(url, 960, animate = true, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onRatio = { if (ratio == null) ratio = it.coerceIn(0.4f, 3f) })
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
    val content: @Composable () -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (on) "已关注" else "+ 关注", fontSize = 13.sp)
    }
    if (on) {
        FilledTonalButton(onClick = { ws.follow(id, false) }, modifier = modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { content() }
    } else {
        Button(onClick = { ws.follow(id, true) }, modifier = modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { content() }
    }
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
    if (sub) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓ 已订阅", color = Tones.Ok, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
            TextButton(onClick = { ws.subscribe(it, false) }) { Text("取消订阅", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } else {
        Button(onClick = { ws.subscribe(it, true) }, modifier = if (big) Modifier.fillMaxWidth() else Modifier) { Text("+ 订阅") }
    }
}

@Composable
fun Badge(text: String, color: Color = Color.White, background: Color = Color.Black.copy(alpha = 0.66f), modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

fun ageColor(age: String) = if (age == "Mature") Tones.Mature else Tones.Questionable

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
                Row {
                    TextButton(onClick = { ws.loadMore(v) }) { Text("重试") }
                    if (v.errorStatus == 401) TextButton(onClick = { LocalActions.login() }) { Text("重新登录") }
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
}

@Composable
fun EmptyState(title: String, text: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 22.sp)
        if (button != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onClick) { Text(button) }
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
            run {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * maxHeight)
                        .padding(top = 0.dp)
                        .offset { IntOffset(0, drag.value.roundToInt()) }
                        .bottomSheetGlass(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = GLASS_SHEET_TINT), 28.dp)
                        .clip(shape)
                        .pointerInput(Unit) {}
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
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
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

val Gap = Arrangement.spacedBy(8.dp)
