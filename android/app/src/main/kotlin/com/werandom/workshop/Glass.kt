package com.werandom.workshop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/*
 * 液态玻璃，照用户的 EhViewer 分支（ehviewer-onedrive 的 LiquidGlass.kt、FloatingGlass.kt、GlassFringe.kt）：
 * 浮动的部件（搜索栏、底部标签栏、按钮、面板）折射它们下面的内容。页面内容用 [glassContent] 录下来，浮动部件用
 * [glass] 画成玻璃。折射要安卓 13，模糊要安卓 12；更早的系统只有底色和边。
 */

/** 玻璃的样式，和 EhViewer 分支的 GlassStyle 一样。 */
enum class GlassStyle {
    /** 小部件（按钮、搜索框）：清透，边缘折射强。 */
    Control,

    /** 带文字的大部件（顶栏、标签栏）：多一点模糊，文字在花哨的图片上也看得清。 */
    Panel,

    /** 面板（详情、筛选、菜单）：模糊的玻璃，宽而柔的边缘弯曲，边缘有色散，阴影更深。 */
    Sheet,

    /** 盖住整个屏幕的玻璃：只有面板的模糊，没有弯曲的边。 */
    Backdrop,

    /** 面板上的小卡片、标签：没有自己的模糊，只有边缘的弯曲和亮边，阴影很淡。 */
    Tile,
}

/** 页面内容（玻璃要折射的东西）；关掉液态玻璃时为 null。 */
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 录页面内容用的 backdrop；关掉液态玻璃时为 null。 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled) return null
    // 录在页面底色上：没画东西的地方（列表顶上留给搜索栏的空白）也有颜色，玻璃边缘不会折射出一个洞
    val background = MaterialTheme.colorScheme.background
    val onDraw: ContentDrawScope.() -> Unit = remember(background) {
        {
            drawRect(background)
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/** 把这块内容录下来，给它上面的玻璃折射。 */
fun Modifier.glassContent(backdrop: LayerBackdrop?): Modifier = if (backdrop == null) this else layerBackdrop(backdrop)

// 暗色主题：亮边比库默认的 0.5 dp 宽而亮，看起来才像玻璃。亮色主题没有亮边（浅色页面上是一圈白边），
// 改用一圈柔和的阴影勾出轮廓，玻璃用白色调（用灰色的 surface 会比页面还暗）。
private val DarkGlassEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.6f)))
private val LightGlassShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.12f))
private val DarkSheetEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.5f)))
private val SheetShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.3f))
private val TileShadow = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 1.dp), color = Color.Black.copy(alpha = 0.1f))

/** 面板的模糊（dp）和边缘色散的宽度（dp），和 EhViewer 分支的默认设置一样。 */
private const val SHEET_BLUR = 2.5f
private const val SHEET_FRINGE = 6f

const val GLASS_BAR_TINT = 0.2f
const val GLASS_CONTROL_TINT = 0.25f
const val GLASS_SHEET_TINT = 0.4f
const val GLASS_TILE_TINT = 0.3f

/**
 * [shape] 的液态玻璃，[tint] 是盖在玻璃上的底色（保证上面的文字看得清）。没有液态玻璃时画成普通的半透明底色。
 * 折射只支持圆角矩形（CornerBasedShape），别的形状在安卓 13 以上会抛异常。
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color, style: GlassStyle = GlassStyle.Control): Modifier {
    val backdrop = LocalPageBackdrop.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    if (backdrop == null) {
        // 没有液态玻璃：不透明一些的底色，再加阴影
        val solid = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (style == GlassStyle.Backdrop) 0.9f else 0.96f)
        return (if (style == GlassStyle.Backdrop) this else shadow(if (style == GlassStyle.Sheet) 12.dp else 4.dp, shape, clip = false))
            .background(solid, shape)
    }
    // 安卓 12 以前没有模糊，底色要更不透明才看得清
    val boost = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 0.75f else 0f
    val alpha = (tint.alpha + boost).coerceAtMost(0.95f)
    val surface = if (dark) tint.copy(alpha = alpha) else Color.White.copy(alpha = alpha)
    val edge = when {
        !dark || style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet -> DarkSheetEdge
        else -> DarkGlassEdge
    }
    val shadow = when {
        style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet -> SheetShadow
        style == GlassStyle.Tile -> TileShadow
        dark -> Shadow.Default
        else -> LightGlassShadow
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (style != GlassStyle.Tile) vibrancy()
            when (style) {
                // 只有边缘弯曲：太深的折射会把搜索栏下面的文字拉长
                GlassStyle.Control -> {
                    blur(1.5f.dp.toPx())
                    lens(12f.dp.toPx(), 20f.dp.toPx(), chromaticAberration = true)
                }
                GlassStyle.Panel -> {
                    blur(4f.dp.toPx())
                    lens(16f.dp.toPx(), 28f.dp.toPx(), chromaticAberration = true)
                }
                GlassStyle.Sheet -> {
                    blur(SHEET_BLUR.dp.toPx())
                    lens(25f.dp.toPx(), 10f.dp.toPx())
                    edgeFringe(24f.dp.toPx(), SHEET_FRINGE.dp.toPx())
                }
                GlassStyle.Backdrop -> blur(SHEET_BLUR.dp.toPx())
                GlassStyle.Tile -> {
                    val height = minOf(16f.dp.toPx(), size.minDimension * 0.3f)
                    lens(height, height * 1.4f, chromaticAberration = true)
                }
            }
        },
        highlight = edge?.let { { it } },
        shadow = shadow?.let { { it } },
        onDrawSurface = { drawRect(surface) },
    )
}

/**
 * 贴着屏幕底边的面板（详情、筛选）：只露出上边。玻璃向左、右、下伸出屏幕外 [GLASS_BLEED]，那几条边的亮边、阴影和弯曲
 * 都在屏幕外，面板在那里和屏幕融成一体（EhViewer 分支的 edgeGlass）。
 */
@Composable
fun Modifier.bottomSheetGlass(tint: Color, corner: androidx.compose.ui.unit.Dp): Modifier {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = corner, topEnd = corner)
    var bleed = 0
    return layout { measurable, constraints ->
        bleed = GLASS_BLEED.roundToPx()
        val placeable = measurable.measure(constraints.offset(bleed * 2, bleed))
        layout(placeable.width - bleed * 2, placeable.height - bleed) { placeable.place(-bleed, 0) }
    }.glass(shape, tint, GlassStyle.Sheet).layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.offset(-bleed * 2, -bleed))
        layout(placeable.width + bleed * 2, placeable.height + bleed) { placeable.place(bleed, 0) }
    }
}

private val GLASS_BLEED = 48.dp

/** 玻璃上选中的项（标签、开关）：玻璃里的一块主题色的光，不是一整块实心色。 */
@Composable
fun glassSelectedColor() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

/** 玻璃上的部件：玻璃加裁剪。 */
@Composable
fun Modifier.glassClip(shape: Shape, tint: Color, style: GlassStyle = GlassStyle.Control): Modifier = glass(shape, tint, style).clip(shape)

// ---- 面板边缘的色散（GlassFringe.kt）----
// 库自带的 lens 色散随边缘弯曲的程度变化，面板的弯曲很柔，几乎看不到；这里在边缘把红、绿、蓝错开 [spread]，
// 在 [band] 宽度内渐渐消失。

private fun BackdropEffectScope.edgeFringe(band: Float, spread: Float) {
    // RuntimeShader 要安卓 13；更早的系统只有模糊和弯曲
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || band <= 0f || spread <= 0f) return
    val radii = cornerRadii() ?: return
    runtimeShaderEffect("EdgeFringe", EDGE_FRINGE_SHADER, "content") {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", radii)
        setFloatUniform("band", band)
        setFloatUniform("spread", spread)
    }
}

private fun BackdropEffectScope.cornerRadii(): FloatArray? {
    val shape = shape as? CornerBasedShape ?: return null
    val max = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    fun radius(start: Boolean, top: Boolean): Float {
        val corner = when {
            top && start -> shape.topStart
            top -> shape.topEnd
            start -> shape.bottomStart
            else -> shape.bottomEnd
        }
        return corner.toPx(size, this).coerceIn(0f, max)
    }
    return floatArrayOf(radius(ltr, true), radius(!ltr, true), radius(!ltr, false), radius(ltr, false))
}

private const val EDGE_FRINGE_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float band;
uniform float spread;

float radiusAt(float2 c, float4 r) {
    if (c.x >= 0.0) {
        if (c.y <= 0.0) return r.y;
        return r.z;
    }
    if (c.y <= 0.0) return r.x;
    return r.w;
}

float sdRoundedRect(float2 c, float2 halfSize, float radius) {
    float2 cornerCoord = abs(c) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 outward(float2 c, float2 halfSize, float radius) {
    float2 cornerCoord = abs(c) - (halfSize - float2(radius));
    if (cornerCoord.x > 0.0 && cornerCoord.y > 0.0) {
        return sign(c) * normalize(cornerCoord);
    }
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(c) * float2(gradX, 1.0 - gradX);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 c = (coord + offset) - halfSize;
    float radius = radiusAt(c, cornerRadii);
    float sd = sdRoundedRect(c, halfSize, radius);
    if (-sd >= band) {
        return content.eval(coord);
    }
    float t = 1.0 - clamp(-sd / band, 0.0, 1.0);
    float2 shift = outward(c, halfSize, min(radius * 1.5, min(halfSize.x, halfSize.y))) * spread * t * t;
    half4 red = content.eval(coord);
    half4 green = content.eval(coord - shift * 0.5);
    half4 blue = content.eval(coord - shift);
    return half4(red.r, green.g, blue.b, green.a);
}
"""
