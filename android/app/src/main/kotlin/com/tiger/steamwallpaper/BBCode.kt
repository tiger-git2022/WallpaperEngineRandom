package com.tiger.steamwallpaper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** Steam 新闻正文（BBCode）拆成的一块：一段文字、一张图、一个 YouTube 视频、一个视频、一条分隔线。 */
sealed class BBlock {
    class Text(val text: AnnotatedString) : BBlock()
    class Image(val url: String, val link: String?) : BBlock()
    class YouTube(val id: String) : BBlock()
    class Video(val url: String, val poster: String) : BBlock()
    data object Rule : BBlock()
}

private val TAG = Regex("\\[(/?)([a-zA-Z0-9*]+)((?:=|\\s)[^\\]]*)?]")
private val IGNORED = Regex("^(c|color|size|font|align|center|left|right|pullquote|carousel|noparse|h\\d|i|b|u|p|img|video|previewyoutube|dynamiclink)$")

fun safeUrl(u: String?): String? = u?.trim()?.trim('"', '\'')?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }

private fun attr(s: String, key: String): String {
    val m = Regex("$key\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))", RegexOption.IGNORE_CASE).find(s) ?: return ""
    return m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
}

/** 正文去掉标签后的纯文字（摘要用）。 */
fun bbPlain(src: String): String = src
    .replace(Regex("\\{STEAM_CLAN_IMAGE}[^\\s\\[]*"), "")
    .replace(Regex("\\[img[\\s\\S]*?\\[/img]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\[previewyoutube[\\s\\S]*?\\[/previewyoutube]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\[[^\\]]{1,80}]"), " ")
    .replace(Regex("https?://\\S+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** 正文里的第一张图（没有封面时用）。 */
fun bbFirstImage(body: String): String {
    val m = Regex("\\[img(?:[^\\]]*src=[\"']?([^\"'\\]\\s]+))?[^\\]]*]([^\\[]*)\\[/img]", RegexOption.IGNORE_CASE)
        .find(body.replace("{STEAM_CLAN_IMAGE}", NewsFeed.CLAN_CDN.trimEnd('/'))) ?: return ""
    return safeUrl(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: ""
}

/**
 * BBCode → 几块可以显示的内容，和电脑版 feed.html 的 bb() 一样：粗体、斜体、下划线、删除线、标题、列表、引用、链接、剧透、
 * 表格（压成一行一行的文字）；图片、YouTube、视频、分隔线单独成块。不认识的标签原样显示（像正文里的 [DLC]）。
 */
fun parseBB(raw: String, link: Color, quote: Color, spoiler: Color): List<BBlock> {
    val src = raw.replace("{STEAM_CLAN_IMAGE}", NewsFeed.CLAN_CDN.trimEnd('/')).replace("\r\n", "\n").replace('\r', '\n')
    val blocks = mutableListOf<BBlock>()
    var b = AnnotatedString.Builder()
    // 打开的样式（标签名 → push 的层数），关闭标签时按名字弹出
    val open = ArrayDeque<String>()
    var linkUrl: String? = null
    val olist = ArrayDeque<Int>()
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))

    fun flush() {
        val text = b.toAnnotatedString()
        val trimmed = trimNewlines(text)
        if (trimmed.text.isNotBlank()) blocks.add(BBlock.Text(trimmed))
        b = AnnotatedString.Builder()
        // 跨块的样式在新块里重新打开
        for (tag in open) pushStyle(b, tag, linkUrl, linkStyles, quote, spoiler)
    }

    fun newline(n: Int = 1) {
        val cur = b.toAnnotatedString().text
        val have = cur.length - cur.trimEnd('\n', ' ').length
        repeat(maxOf(0, n - have.coerceAtMost(n))) { b.append('\n') }
    }

    fun text(s: String) {
        if (s.isNotEmpty()) b.append(s)
    }

    var last = 0
    val all = TAG.findAll(src).toList()
    var idx = 0
    while (idx < all.size) {
        val m = all[idx]
        idx++
        if (m.range.first < last) continue
        text(src.substring(last, m.range.first))
        last = m.range.last + 1
        val close = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val arg = m.groupValues[3].removePrefix("=").trim()
        when (name) {
            "img" -> if (!close) {
                val end = src.indexOf("[/img]", last, ignoreCase = true)
                val inner = if (end >= 0) src.substring(last, end) else ""
                if (end >= 0) last = end + 6
                val u = safeUrl(attr(m.value, "src").ifEmpty { inner })
                if (u != null) {
                    flush()
                    blocks.add(BBlock.Image(u, linkUrl))
                }
            }
            "previewyoutube" -> if (!close) {
                val id = arg.substringBefore(';').trim('"', '\'')
                val end = src.indexOf("[/previewyoutube]", last, ignoreCase = true)
                if (end >= 0) last = end + "[/previewyoutube]".length
                if (Regex("^[\\w-]+$").matches(id)) {
                    flush()
                    blocks.add(BBlock.YouTube(id))
                }
            }
            "video" -> if (!close) {
                val end = src.indexOf("[/video]", last, ignoreCase = true)
                if (end >= 0) last = end + 8
                val u = safeUrl(attr(m.value, "mp4")) ?: safeUrl(attr(m.value, "webm"))
                if (u != null) {
                    flush()
                    blocks.add(BBlock.Video(u, safeUrl(attr(m.value, "poster")) ?: ""))
                }
            }
            "dynamiclink" -> if (!close) {
                val end = src.indexOf("[/dynamiclink]", last, ignoreCase = true)
                if (end >= 0) last = end + "[/dynamiclink]".length
                safeUrl(attr(m.value, "href"))?.let { u -> b.withLink(LinkAnnotation.Url(u, linkStyles)) { append(u) } }
            }
            "hr" -> if (!close) {
                flush()
                blocks.add(BBlock.Rule)
            }
            "url" -> {
                if (close) {
                    if ("url" in open) {
                        popTag(b, open, "url")
                        linkUrl = null
                    }
                } else {
                    var u = safeUrl(arg)
                    if (u == null && arg.isEmpty()) {
                        // [url]https://…[/url]
                        val end = src.indexOf("[/url]", last, ignoreCase = true)
                        val inner = if (end >= 0) src.substring(last, end) else ""
                        val s = safeUrl(inner)
                        if (s != null) {
                            b.withLink(LinkAnnotation.Url(s, linkStyles)) { append(s) }
                            last = end + 6
                            continue
                        }
                    }
                    if (u != null) {
                        linkUrl = u
                        open.addLast("url")
                        pushStyle(b, "url", u, linkStyles, quote, spoiler)
                    }
                }
            }
            "b", "i", "u", "strike", "spoiler", "code", "quote", "h1", "h2", "h3" -> {
                val heading = name.startsWith("h")
                if (close) {
                    if (name in open) popTag(b, open, name)
                    if (heading || name == "quote") newline(2)
                } else {
                    if (heading || name == "quote") newline(2)
                    open.addLast(name)
                    pushStyle(b, name, linkUrl, linkStyles, quote, spoiler)
                }
            }
            "p" -> newline(2)
            "list" -> if (!close) newline(1) else newline(2)
            "olist" -> if (!close) {
                olist.addLast(0)
                newline(1)
            } else {
                olist.removeLastOrNull()
                newline(2)
            }
            "*" -> if (!close) {
                newline(1)
                if (olist.isNotEmpty()) {
                    val n = olist.removeLast() + 1
                    olist.addLast(n)
                    text("$n. ")
                } else {
                    text("• ")
                }
            }
            "table" -> newline(2)
            "tr" -> if (close) newline(1)
            "td", "th" -> if (close) text("   ")
            "expand" -> if (!close) newline(1) else newline(1)
            else -> if (!IGNORED.matches(name)) text(m.value)
        }
    }
    text(src.substring(last.coerceAtMost(src.length)))
    // 关掉没关的标签
    while (open.isNotEmpty()) popTag(b, open, open.last())
    flush()
    return blocks
}

private fun pushStyle(b: AnnotatedString.Builder, tag: String, url: String?, links: TextLinkStyles, quote: Color, spoiler: Color) {
    when (tag) {
        "url" -> if (url != null) b.pushLink(LinkAnnotation.Url(url, links)) else b.pushStyle(SpanStyle())
        "b" -> b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        "i" -> b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        "u" -> b.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        "strike" -> b.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        "spoiler" -> b.pushStyle(SpanStyle(background = spoiler))
        "code" -> b.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
        "quote" -> b.pushStyle(SpanStyle(color = quote, fontStyle = FontStyle.Italic))
        "h1" -> b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp))
        "h2" -> b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp))
        else -> b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp))
    }
}

/** 弹出 [name] 和它上面的样式，再把上面那些重新打开（标签没有正确嵌套时也不乱）。 */
private fun popTag(b: AnnotatedString.Builder, open: ArrayDeque<String>, name: String) {
    val i = open.lastIndexOf(name)
    if (i < 0) return
    val above = open.subList(i + 1, open.size).toList()
    repeat(open.size - i) { runCatching { b.pop() } }
    while (open.size > i) open.removeLast()
    // 上面那些只能按原样重开（链接地址已经丢了，按普通文字）
    for (t in above) {
        open.addLast(t)
        b.pushStyle(SpanStyle())
    }
}

private fun trimNewlines(s: AnnotatedString): AnnotatedString {
    var start = 0
    var end = s.length
    while (start < end && s.text[start].isWhitespace()) start++
    while (end > start && s.text[end - 1].isWhitespace()) end--
    val cut = s.subSequence(start, end)
    // 连续 3 个以上的换行压成 2 个
    val text = cut.text
    if (!text.contains("\n\n\n")) return cut
    return buildAnnotatedString {
        var i = 0
        var nl = 0
        val keep = mutableListOf<Int>()
        while (i < text.length) {
            if (text[i] == '\n') nl++ else nl = 0
            if (nl <= 2) keep.add(i)
            i++
        }
        // 按保留的字符重建（样式按区间映射）
        var runStart = -1
        var prev = -2
        for (k in keep) {
            if (k != prev + 1) {
                if (runStart >= 0) append(cut.subSequence(runStart, prev + 1))
                runStart = k
            }
            prev = k
        }
        if (runStart >= 0) append(cut.subSequence(runStart, prev + 1))
    }
}
