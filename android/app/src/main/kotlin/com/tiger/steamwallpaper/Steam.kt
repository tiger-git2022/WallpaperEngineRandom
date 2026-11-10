package com.tiger.steamwallpaper

import android.text.Html
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SteamException(val status: Int, message: String) : Exception(message)

/**
 * 访问 Steam 创意工坊，和电脑版 SteamWorkshop.cs 一样：
 * - 时间线、搜索：IPublishedFileService/QueryFiles（登录后取的 webapi_token，参数用 input_json）
 * - 作者全部作品：作者的 myworkshopfiles 页面（带登录 cookie 才能看到成人分级的作品）拿 ID，再用公开的
 *   GetPublishedFileDetails 补详情
 * - 订阅 / 关注：Steam 网页上那两个按钮用的接口（sessionid 表单）
 * - 作者名字和头像：公开的个人资料 ?xml=1
 * 登录状态就是登录页 WebView 的 cookie（CookieManager 在整个 App 里共用，存在 App 私有目录）。
 */
class Steam {
    companion object {
        const val APP_ID = "431960"
        const val COMMUNITY = "https://steamcommunity.com"
        private const val API = "https://api.steampowered.com"

        // 桌面版 UA：Steam 返回的网页和电脑版看到的一样，解析规则才能通用
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val STEAM_ID = Regex("^\\d{17}$")

        private val COMMENT_TEXT = Regex("commentthread_comment_text\"[^>]*>(.*?)</div>\\s*<div class=\"comment_footer_ctn", RegexOption.DOT_MATCHES_ALL)
        private val COMMENT_AUTHOR = Regex("commentthread_author_link\"[^>]*>\\s*<bdi>(.*?)</bdi>", RegexOption.DOT_MATCHES_ALL)
        private val COMMENT_AVATAR = Regex("<img (?:src|srcset)=\"([^\"\\s]+)")
        private val COMMENT_TIME = Regex("data-timestamp=\"(\\d+)\"")
        private val COMMENT_MINI = Regex("commentthread_author_link\"[^>]*data-miniprofile=\"(\\d+)\"")
    }

    /** 一条评论；[steamId] 是评论者（从 miniprofile 换算）。 */
    class Comment(val name: String, val avatar: String, val time: Long, val text: String, val steamId: String)

    class Profile(val steamId: String, val name: String, val avatar: String)

    class CreatorPage(val ids: List<String>, val total: Int, val following: Boolean, val hasFollowButton: Boolean)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(
                r.newBuilder()
                    .header("User-Agent", r.header("User-Agent") ?: UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                    .build(),
            )
        }
        .build()

    @Volatile private var cookies: Map<String, String> = emptyMap()

    @Volatile var steamId: String = ""
        private set

    val loggedIn: Boolean get() = steamId.isNotEmpty()

    private var token: String? = null
    private var tokenTime = 0L
    private val profiles = HashMap<String, Profile>()
    private val profileGate = Semaphore(4)

    // ---- login state ----

    /** 从 CookieManager 读 steamcommunity.com 的 cookie，并据此判断登录的是谁。返回登录的账号是否变了。在主线程调用。 */
    fun syncCookies(): Boolean {
        val cm = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        cm.getCookie(COMMUNITY)?.split(';')?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        var id = ""
        map["steamLoginSecure"]?.let { sls ->
            val decoded = runCatching { URLDecoder.decode(sls, "UTF-8") }.getOrDefault(sls)
            val bar = decoded.indexOf('|')
            if (bar > 0 && STEAM_ID.matches(decoded.substring(0, bar))) id = decoded.substring(0, bar)
        }
        // sessionid 只是防跨站的随机值，表单里和 cookie 里一样即可；还没有时自己生成一个
        if (id.isNotEmpty() && "sessionid" !in map) {
            val sid = randomHex(24)
            map["sessionid"] = sid
            cm.setCookie(COMMUNITY, "sessionid=$sid; Path=/; Secure")
            cm.flush()
        }
        cookies = map
        if (id == steamId) return false
        steamId = id
        synchronized(this) { token = null }
        Log.i(if (id.isNotEmpty()) "已登录 Steam $id" else "未登录 Steam")
        return true
    }

    fun logout() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        cookies = emptyMap()
        steamId = ""
        synchronized(this) { token = null }
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private val sessionId: String get() = cookies["sessionid"] ?: ""

    // ---- http ----

    private suspend fun get(url: String, withCookies: Boolean = true): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        if (withCookies && cookies.isNotEmpty()) b.header("Cookie", cookieHeader())
        http.newCall(b.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw SteamException(resp.code, "Steam 返回 ${resp.code}")
            text
        }
    }

    private suspend fun postCommunity(url: String, form: Map<String, String>): String = withContext(Dispatchers.IO) {
        requireLogin()
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(body)
            .header("Cookie", cookieHeader())
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw SteamException(resp.code, "Steam 返回 ${resp.code}")
            text
        }
    }

    private fun requireLogin() {
        if (!loggedIn) throw SteamException(401, "需要先登录 Steam")
    }

    /** 网页接口用的 webapi_token（登录后由社区页面发放，有效期约一天；这里每小时重取一次）。 */
    private suspend fun token(refresh: Boolean): String {
        requireLogin()
        synchronized(this) {
            val t = token
            if (!refresh && t != null && System.currentTimeMillis() - tokenTime < 3600_000) return t
        }
        val text = get("$COMMUNITY/pointssummary/ajaxgetasyncconfig")
        val t = runCatching { JSONObject(text).optJSONObject("data")?.optString("webapi_token") }.getOrNull().orEmpty()
        if (t.isEmpty()) throw SteamException(401, "Steam 登录已失效，请重新登录")
        synchronized(this) {
            token = t
            tokenTime = System.currentTimeMillis()
        }
        return t
    }

    // ---- workshop queries ----

    /**
     * IPublishedFileService/QueryFiles。input 是 JSON 对象（query_type、cursor、search_text、taggroups…），
     * appid 和需要返回的字段在这里补上。返回 Steam 原始的 JSON 文本。
     */
    suspend fun query(input: JSONObject): String {
        input.put("appid", 431960)
        input.put("return_tags", true)
        input.put("return_previews", true)
        input.put("return_vote_data", true)
        input.put("return_short_description", true)
        if (!input.has("numperpage")) input.put("numperpage", 30)
        if (!input.has("cursor")) input.put("cursor", "*")
        var attempt = 0
        while (true) {
            val t = token(attempt > 0)
            val url = "$API/IPublishedFileService/QueryFiles/v1/".toHttpUrl().newBuilder()
                .addQueryParameter("access_token", t)
                .addQueryParameter("input_json", input.toString())
                .build()
            val result = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val text = resp.body.string()
                    when {
                        resp.code == 401 && attempt == 0 -> null
                        !resp.isSuccessful -> throw SteamException(resp.code, "Steam 接口返回 ${resp.code}")
                        else -> text
                    }
                }
            }
            if (result != null) return result
            attempt++
        }
    }

    /** 公开的 ISteamRemoteStorage/GetPublishedFileDetails（不需要登录），一次最多 100 个。返回原始 JSON。 */
    suspend fun details(ids: List<String>): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("itemcount", ids.size.toString())
        ids.forEachIndexed { i, id -> form.add("publishedfileids[$i]", id) }
        val req = Request.Builder().url("$API/ISteamRemoteStorage/GetPublishedFileDetails/v1/").post(form.build()).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw SteamException(resp.code, "Steam 接口返回 ${resp.code}")
            text
        }
    }

    /**
     * 壁纸下面的评论（公开接口，不需要登录），从新到旧，从第 [start] 条起最多 [count] 条。
     * 返回评论总数和这一页的评论；作者主页不公开时 Steam 不给评论，抛出异常。
     */
    suspend fun comments(owner: String, id: String, start: Int, count: Int): Pair<Int, List<Comment>> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("start", start.toString()).add("count", count.toString()).build()
        val req = Request.Builder().url("$COMMUNITY/comment/PublishedFile_Public/render/$owner/$id/").post(form).build()
        val text = http.newCall(req).execute().use { resp ->
            val t = resp.body.string()
            if (!resp.isSuccessful) throw SteamException(resp.code, "Steam 返回 ${resp.code}")
            t
        }
        val o = JSONObject(text)
        if (!o.optBoolean("success")) throw SteamException(0, if (o.optString("error").contains("private")) "作者的主页不公开，看不到评论" else o.optString("error").ifEmpty { "读取评论失败" })
        o.optInt("total_count") to parseComments(o.optString("comments_html"))
    }

    private fun parseComments(html: String): List<Comment> =
        html.split("class=\"commentthread_comment ").drop(1).mapNotNull { part ->
            val textHtml = COMMENT_TEXT.find(part)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = Html.fromHtml(textHtml.replace(Regex("<div class=\"needs_content_check\">.*?</div>", RegexOption.DOT_MATCHES_ALL), "（这条评论还在等 Steam 审核）"), Html.FROM_HTML_MODE_LEGACY)
                .toString().trim()
            val name = COMMENT_AUTHOR.find(part)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: ""
            val head = part.substringBefore("author_name_group")
            // 头像在作者链接里（动态头像只有 srcset）；外面的是头像框
            val avatar = COMMENT_AVATAR.find(head.substringAfter("data-miniprofile", ""))?.groupValues?.get(1) ?: ""
            val time = COMMENT_TIME.find(part)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val mini = COMMENT_MINI.find(part)?.groupValues?.get(1)?.toLongOrNull()
            Comment(name, avatar, time, text, mini?.let { (76561197960265728L + it).toString() } ?: "")
        }

    /** 作者上传的 WE 壁纸，按发布时间从新到旧，每页 30 个（page 从 1 开始）。 */
    /** 作者的创意工坊网页，每页 30 个（按作者查作品的接口 GetUserFiles 只认开发者 key，用登录令牌调返回 401）。 */
    suspend fun creatorFiles(steamId: String, page: Int): CreatorPage {
        checkSteamId(steamId)
        val r = scrapeCreator(steamId, page)
        Log.i("作者 $steamId 第 $page 页：共 ${r.total} 个，这页 ${r.ids.size} 个")
        return r
    }

    /** 抓作者的创意工坊网页（不登录也能用，但看不到成人作品）；也读关注按钮的状态。 */
    private suspend fun scrapeCreator(steamId: String, page: Int): CreatorPage {
        Log.i("抓作者网页 $steamId（登录 Cookie：${if (cookies.containsKey("steamLoginSecure")) "有" else "没有"}）")
        val html = get("$COMMUNITY/profiles/$steamId/myworkshopfiles/?appid=$APP_ID&browsefilter=myfiles&sortmethod=creationorder&numperpage=30&p=${maxOf(1, page)}")
        val ids = Regex("data-publishedfileid=\"(\\d+)\"").findAll(html).map { it.groupValues[1] }.distinct().toList()
        // “正在显示第 1 - 30 项，共 133 项条目” / “Showing 1-30 of 133 entries”
        var total = 0
        Regex("workshopBrowsePagingInfo\">([^<]*)<").find(html)?.let { m ->
            Regex("\\d+").findAll(m.groupValues[1].replace(",", "")).lastOrNull()?.let { total = it.value.toIntOrNull() ?: 0 }
        }
        var following = false
        var hasButton = false
        Regex("id=\"FollowUserBtn\"[^>]*class=\"([^\"]*)\"|class=\"([^\"]*)\"[^>]*id=\"FollowUserBtn\"").find(html)?.let { m ->
            hasButton = true
            val cls = m.groupValues[1].ifEmpty { m.groupValues[2] }
            following = "btn_active" in cls
        }
        return CreatorPage(ids, total, following, hasButton)
    }

    /** 我在 Steam 上关注的玩家（创意工坊作者）。 */
    suspend fun following(): List<String> {
        requireLogin()
        val me = steamId
        val html = get("$COMMUNITY/profiles/$me/following/")
        var ids = Regex("data-steamid=\"(\\d{17})\"").findAll(html).map { it.groupValues[1] }.filter { it != me }.distinct().toList()
        if (ids.isEmpty()) {
            ids = Regex("data-miniprofile=\"(\\d+)\"").findAll(html)
                .map { (76561197960265728L + it.groupValues[1].toLong()).toString() }
                .filter { it != me }.distinct().toList()
        }
        return ids
    }

    suspend fun subscribe(id: String, on: Boolean): Boolean {
        if (!Regex("^\\d{1,20}$").matches(id)) throw SteamException(400, "壁纸 ID 不对")
        val text = postCommunity(
            "$COMMUNITY/sharedfiles/" + (if (on) "subscribe" else "unsubscribe"),
            mapOf("id" to id, "appid" to APP_ID, "sessionid" to sessionId),
        )
        return successOf(text)
    }

    suspend fun follow(steamId: String, on: Boolean): Boolean {
        checkSteamId(steamId)
        val text = postCommunity(
            "$COMMUNITY/profiles/$steamId/" + (if (on) "followuser" else "unfollowuser") + "/",
            mapOf("steamid" to steamId, "sessionid" to sessionId),
        )
        return successOf(text)
    }

    /** Steam 返回 {"success":1}（订阅）或 {"success":true}（关注）。 */
    private fun successOf(text: String): Boolean = runCatching {
        when (val s = JSONObject(text).opt("success")) {
            is Boolean -> s
            is Number -> s.toInt() == 1
            else -> false
        }
    }.getOrDefault(false)

    /** 作者名字和头像（公开的 ?xml=1），内存里缓存。读取失败时名字为空。 */
    suspend fun profile(steamId: String): Profile {
        checkSteamId(steamId)
        synchronized(profiles) { profiles[steamId]?.let { return it } }
        return profileGate.withPermit {
            try {
                val xml = get("$COMMUNITY/profiles/$steamId/?xml=1", withCookies = false)
                val name = Html.fromHtml(cdata(xml, "steamID"), Html.FROM_HTML_MODE_LEGACY).toString()
                val p = Profile(steamId, name, cdata(xml, "avatarMedium"))
                synchronized(profiles) { profiles[steamId] = p }
                p
            } catch (e: Exception) {
                Log.i("读取作者资料失败 $steamId：${e.message}")
                Profile(steamId, "", "")
            }
        }
    }

    private fun cdata(xml: String, tag: String): String {
        val m = Regex("<$tag>\\s*(?:<!\\[CDATA\\[(.*?)]]>|([^<]*))\\s*</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return ""
        return (m.groups[1]?.value ?: m.groups[2]?.value ?: "").trim()
    }

    private fun checkSteamId(id: String) {
        if (!STEAM_ID.matches(id)) throw SteamException(400, "Steam ID 不对")
    }
}

/** 错误提示：401 统一成“需要重新登录”。 */
fun errText(e: Throwable): String = when {
    e is SteamException && e.status == 401 -> "需要重新登录 Steam"
    e is java.net.UnknownHostException -> "连不上 Steam（${e.message}），请检查网络或代理"
    e is java.net.SocketTimeoutException -> "连接 Steam 超时，请检查网络或代理"
    else -> e.message ?: e.toString()
}
