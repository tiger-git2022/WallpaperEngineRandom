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

/** Steam 新闻正文（BBCode）拆成的一块：一段文字、一张图、一个 YouTube 视频、一个视频、一个游戏横幅、一条分隔线。 */
sealed class BBlock {
    class Text(val text: AnnotatedString) : BBlock()

    /** 指向 Steam 商店游戏页的链接：显示成游戏横幅（和 Steam 网页上的商店卡片一样）。 */
    class Game(val appid: Int, val url: String) : BBlock()
    class Image(val url: String, val link: String?) : BBlock()
    class YouTube(val id: String) : BBlock()
    class Video(val url: String, val poster: String) : BBlock()
    data object Rule : BBlock()
}

// 安卓的正则（ICU）里 { } [ ] 在字符组外也要转义
private val TAG = Regex("\\[(/?)([a-zA-Z0-9*]+)((?:=|\\s)[^\\]]*)?\\]")
private val IGNORED = Regex("^(c|color|size|font|align|center|left|right|pullquote|carousel|noparse|h\\d|i|b|u|p|img|video|previewyoutube|dynamiclink)$")

/** 商店游戏页的地址，取 appid。 */
private val STORE_APP = Regex("""^https?://store\.steampowered\.com/app/(\d+)""", RegexOption.IGNORE_CASE)

/** 正文里没加标签的网址。 */
private val BARE_URL = Regex("""https?://[^\s\[\]<>"]+""", RegexOption.IGNORE_CASE)

fun storeAppId(url: String): Int? = STORE_APP.find(url.trim())?.groupValues?.get(1)?.toIntOrNull()

fun safeUrl(u: String?): String? = u?.trim()?.trim('"', '\'')?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }

private fun attr(s: String, key: String): String {
    val m = Regex("$key\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))", RegexOption.IGNORE_CASE).find(s) ?: return ""
    return m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
}

/**
 * 正文去掉标签后的纯文字（摘要用）。[keepLines]：保留分段和换行（收起时的摘要和展开后一样分段），否则压成一行。
 */
fun bbPlain(src: String, keepLines: Boolean = false): String {
    var s = src.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("\\{STEAM_CLAN_IMAGE\\}[^\\s\\[]*"), "")
        .replace(Regex("\\[img[\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[previewyoutube[\\s\\S]*?\\[/previewyoutube\\]", RegexOption.IGNORE_CASE), "")
        // 商店链接卡片、视频的标签里是很长的网址：整个去掉
        .replace(Regex("\\[(dynamiclink|video)[^\\]]*\\][\\s\\S]*?\\[/(dynamiclink|video)\\]", RegexOption.IGNORE_CASE), " ")
    if (keepLines) {
        // 分段、标题、列表项、表格行这些地方换行
        s = s.replace(Regex("\\[(/?p|br|hr|/h[1-6]|\\*|/?list|/?olist|/quote|/tr)\\]", RegexOption.IGNORE_CASE), "\n")
    }
    s = s.replace(Regex("\\[[^\\]]{1,400}\\]"), if (keepLines) "" else " ")
        .replace(Regex("https?://\\S+"), "")
    if (!keepLines) return s.replace(Regex("\\s+"), " ").trim()
    return s.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u3000]+"), " ").trim() }
        .replace(Regex("\\n{3,}"), "\n\n").trim()
}

/** 正文里的第一张图（没有封面时用）。 */
fun bbFirstImage(body: String): String {
    val m = Regex("\\[img(?:[^\\]]*src=[\"']?([^\"'\\]\\s]+))?[^\\]]*\\]([^\\[]*)\\[/img\\]", RegexOption.IGNORE_CASE)
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

    // 文字里链到游戏的（超链接）：横幅放到这一段的后面，不把句子从中间切开
    val pending = mutableListOf<BBlock.Game>()

    fun flush() {
        val text = b.toAnnotatedString()
        val trimmed = trimNewlines(text)
        if (trimmed.text.isNotBlank()) blocks.add(BBlock.Text(trimmed))
        blocks.addAll(pending)
        pending.clear()
        b = AnnotatedString.Builder()
        // 跨块的样式在新块里重新打开
        for (tag in open) pushStyle(b, tag, linkUrl, linkStyles, quote, spoiler)
    }

    fun newline(n: Int = 1) {
        // 一段结束：先把这段里链到的游戏横幅放出来
        if (pending.isNotEmpty()) flush()
        val cur = b.toAnnotatedString().text
        val have = cur.length - cur.trimEnd('\n', ' ').length
        repeat(maxOf(0, n - have.coerceAtMost(n))) { b.append('\n') }
    }

    // 一篇里同一个游戏只放一个横幅（Steam 常常一个游戏同时有商店卡片和链接）
    val games = HashSet<Int>()

    /** 游戏横幅；[inline]：链在一段文字上的，等这一段结束再放。 */
    fun game(appid: Int, url: String, inline: Boolean = false) {
        if (!games.add(appid)) return
        if (inline) {
            pending.add(BBlock.Game(appid, url))
        } else {
            flush()
            blocks.add(BBlock.Game(appid, url))
        }
    }

    fun plain(s: String) {
        if (s.isEmpty()) return
        // 有等着放的横幅、这里换行了：在换行处结束这一段
        val nl = if (pending.isNotEmpty()) s.indexOf('\n') else -1
        if (nl < 0) {
            b.append(s)
        } else {
            b.append(s.substring(0, nl))
            flush()
            b.append(s.substring(nl))
        }
    }

    /** 一段文字：里面直接写出来的网址，商店游戏页换成游戏横幅，别的网址去掉（只是一串网址，没有意义）。 */
    fun text(s: String) {
        if (s.isEmpty()) return
        var at = 0
        for (m in BARE_URL.findAll(s)) {
            val u = m.value.trimEnd('.', ',', ')', '。', '，', '）')
            plain(s.substring(at, m.range.first))
            at = m.range.first + u.length
            storeAppId(u)?.let { game(it, u) }
        }
        plain(s.substring(at))
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
                // Steam 网页上是一张单独的商店卡片：游戏的换成横幅，别的（只会显示成一串网址）去掉
                safeUrl(attr(m.value, "href"))?.let { u -> storeAppId(u)?.let { game(it, u) } }
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
                    val u = safeUrl(arg)
                    val end = src.indexOf("[/url]", last, ignoreCase = true)
                    val inner = if (end >= 0) src.substring(last, end) else ""
                    val innerText = inner.replace(Regex("\\[[^\\]]*\\]"), "").trim()
                    if (u == null && arg.isEmpty()) {
                        // [url]https://…[/url]：显示出来就是一串网址。游戏页换成横幅，别的去掉
                        val s = safeUrl(inner)
                        if (s != null) {
                            storeAppId(s)?.let { game(it, s) }
                            last = end + 6
                            continue
                        }
                    }
                    val app = u?.let { storeAppId(it) }
                    // 显示出来就是网址（或者什么都不显示）：游戏页换成横幅，别的（微博、B 站这种）去掉
                    if (u != null && end >= 0 && (innerText.isEmpty() || safeUrl(innerText) != null) && !inner.contains("[img", ignoreCase = true)) {
                        if (app != null) game(app, u)
                        last = end + 6
                        continue
                    }
                    // 超链接（一段普通文字，点了跳转）：文字照常显示；链到游戏的，这一段后面放游戏横幅
                    // （链接里的图片点了还是打开链接）
                    if (u != null && app != null && !inner.contains("[img", ignoreCase = true)) game(app, u, inline = true)
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
        // 文字链接只显示文字（用户要求；指向游戏的另外换成横幅）
        "url" -> b.pushStyle(SpanStyle())
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
