package com.tiger.steamwallpaper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 一条新闻的翻译状态（和电脑版页面的 trState 一样）。 */
class PostTr {
    var busy by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var summary by mutableStateOf("")
    var body by mutableStateOf("")

    /** 翻译完了，但用户点了「显示原文」。 */
    var orig by mutableStateOf(false)
}

/**
 * 新闻页的大模型翻译：每条右上角的「译」、评论的「翻译评论」、自动翻译。译文存在 translations.json（新闻 300 篇、评论 1000 条），
 * 同一篇换个时间再看不再花额度。只在主线程上用。
 */
class NewsTranslate(private val feed: NewsFeed, private val store: NewsStore, private val file: File, private val scope: CoroutineScope) {
    /** 新闻 key → 翻译状态（对应 [lang]）。 */
    val posts = mutableStateMapOf<String, PostTr>()
    private var lang = store.translateTo
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private var running = 0
    private val main = Handler(Looper.getMainLooper())

    /** 缓存：「gid|目标语言」→ {title, summary, body, t}；「c:评论id|目标语言」→ {text, t}。 */
    private val cache = HashMap<String, JSONObject>()
    private var cacheLoaded = false
    private val cacheLock = Mutex()
    private var saveJob: Job? = null

    val configured: Boolean get() = store.aiKeyEnc.isNotEmpty()
    val auto: Boolean get() = store.autoTranslate && configured

    fun config() = AiConfig(
        store.aiFormat, store.aiBaseUrl, store.aiKeyEnc, store.aiModel, store.aiThinkModel, store.aiThink, store.aiThinkWords, store.aiStream, store.translateTo,
    )

    /** 换了目标语言：已经显示的译文作废（缓存按语言分开，不丢）。 */
    fun checkLang() {
        if (lang == store.translateTo) return
        lang = store.translateTo
        posts.clear()
        feed.comments.values.forEach { c ->
            c.tr.clear()
            c.trOn = false
            c.trError = null
        }
    }

    fun needs(text: String) = Translator.needsTranslation(text, store.translateTo)

    /** 这条现在显示的是译文（翻译中已经出来一部分也算）。 */
    fun shown(key: String): Boolean {
        val s = posts[key] ?: return false
        return !s.orig && (s.done || s.busy) && (s.title.isNotEmpty() || s.summary.isNotEmpty() || s.body.isNotEmpty())
    }

    fun title(item: NewsItem): String = posts[item.key]?.takeIf { shown(item.key) }?.title?.ifEmpty { null } ?: item.title
    fun body(item: NewsItem): String = posts[item.key]?.takeIf { shown(item.key) }?.body?.ifEmpty { null } ?: item.body

    /** 收起时的摘要：译文的摘要，没有就用译文正文的开头。 */
    fun summary(item: NewsItem, original: String): String {
        val s = posts[item.key]?.takeIf { shown(item.key) } ?: return original
        return s.summary.ifEmpty { if (s.body.isNotEmpty()) bbPlain(s.body) else original }
    }

    /** 点「译」：没翻过就翻；翻完了在译文和原文之间切换；出错了重试。 */
    fun click(key: String) {
        checkLang()
        val s = posts[key]
        if (s != null && s.done && s.error == null) {
            s.orig = !s.orig
            return
        }
        if (s != null && s.busy) return
        queue(key, force = s?.error != null)
    }

    /** 自动翻译：出现在屏幕附近、不是目标语言的新闻（出过错的不自动重试）。 */
    fun autoQueue(it: NewsItem) {
        if (!auto || it.kind != "event" || it.gid.isEmpty()) return
        checkLang()
        if (posts[it.key] != null) return
        if (needs(it.title + " " + it.summary.ifEmpty { bbPlain(it.body).take(400) })) queue(it.key, false)
    }

    private fun queue(key: String, force: Boolean) {
        val it = feed.items[key] ?: return
        if (it.gid.isEmpty()) return
        val old = posts[key]
        if (old != null && (old.busy || (old.done && !force))) return
        posts[key] = PostTr().apply { busy = true }
        pending.addLast(key to force)
        pump()
    }

    /** 同时最多翻两篇，其余排队。 */
    private fun pump() {
        while (running < 2 && pending.isNotEmpty()) {
            val (key, force) = pending.removeFirst()
            val it = feed.items[key]
            val s = posts[key]
            if (s == null) continue
            if (it == null) {
                if (posts[key] === s) posts.remove(key)
                continue
            }
            running++
            scope.launch {
                try {
                    val r = translate(it, s, force)
                    s.title = r.title
                    s.summary = r.summary
                    s.body = r.body
                    s.done = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("翻译新闻失败：${it.title}", e)
                    s.error = Translator.errText(e)
                } finally {
                    s.busy = false
                    running--
                    pump()
                }
            }
        }
    }

    private suspend fun translate(it: NewsItem, s: PostTr, force: Boolean): PostTranslation {
        val c = config()
        val ck = it.gid + "|" + c.target
        loadCache()
        if (!force) {
            cache[ck]?.let { j -> return PostTranslation(j.optString("title"), j.optString("summary"), j.optString("body")) }
        }
        // 流式：边翻边显示，最多每 0.12 秒刷新一次。用开机以来的单调时钟，不会倒退、不会变成负数
        var last = 0L
        val r = Translator.translatePost(c, it.title, it.summary, it.body) { text ->
            val now = SystemClock.elapsedRealtime()
            if (last != 0L && now - last < 120) return@translatePost
            last = now
            val part = Translator.splitPost(text, partial = true)
            main.post {
                if (s.busy) {
                    s.title = part.title
                    s.summary = part.summary
                    s.body = part.body
                }
            }
        }
        cache[ck] = JSONObject().put("title", r.title).put("summary", r.summary).put("body", r.body).put("t", System.currentTimeMillis() / 1000)
        saveCache()
        return r
    }

    // ---- 评论 ----

    /** 「翻译评论」：翻还没翻的；都翻过了就在译文和原文之间切换。[auto] 时不切换。 */
    fun translateComments(key: String, auto: Boolean) {
        checkLang()
        val c = feed.comments[key] ?: return
        if (c.trLoading) return
        val todo = c.list.filter { c.tr[it.id] == null && needs(it.text) }
        if (todo.isEmpty()) {
            if (!auto && c.tr.isNotEmpty()) c.trOn = !c.trOn
            return
        }
        if (auto && c.trError != null) return
        c.trLoading = true
        c.trError = null
        scope.launch {
            try {
                val conf = config()
                loadCache()
                fun ck(id: String) = "c:$id|${conf.target}"
                val need = todo.filter { cache[ck(it.id)] == null }
                if (need.isNotEmpty()) {
                    val r = Translator.translateTexts(conf, need.map { it.text })
                    val now = System.currentTimeMillis() / 1000
                    need.forEachIndexed { i, m -> if (r[i].isNotEmpty()) cache[ck(m.id)] = JSONObject().put("text", r[i]).put("t", now) }
                    saveCache()
                }
                if (conf.target == lang) {
                    todo.forEach { m -> cache[ck(m.id)]?.optString("text")?.takeIf { it.isNotEmpty() }?.let { c.tr[m.id] = it } }
                    c.trOn = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("翻译评论失败", e)
                c.trError = Translator.errText(e)
            } finally {
                c.trLoading = false
            }
        }
    }

    // ---- 缓存 ----

    private suspend fun loadCache() = cacheLock.withLock {
        if (cacheLoaded) return@withLock
        val o = withContext(Dispatchers.IO) { runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull() }
        o?.keys()?.forEach { k -> o.optJSONObject(k)?.let { if (k !in cache) cache[k] = it } }
        cacheLoaded = true
    }

    /** 新闻留最近 300 篇、评论 1000 条；2 秒内的多次修改合并成一次写入。 */
    private fun saveCache() {
        fun trim(comments: Boolean, keep: Int) {
            val keys = cache.entries.filter { it.key.startsWith("c:") == comments }.sortedByDescending { it.value.optLong("t") }.drop(keep).map { it.key }
            keys.forEach { cache.remove(it) }
        }
        trim(false, 300)
        trim(true, 1000)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            val text = JSONObject().apply { cache.forEach { (k, v) -> put(k, v) } }.toString()
            withContext(Dispatchers.IO) {
                try {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                } catch (e: Exception) {
                    Log.e("保存 translations.json 失败", e)
                }
            }
        }
    }
}
