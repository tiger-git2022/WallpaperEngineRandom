package com.tiger.steamwallpaper

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Steam 商店（store.steampowered.com）的登录会话，新闻页用，和电脑版 SteamNewsFeed 的 Steam.cs 一样：
 * 用户在 Steam 官方登录页（[LoginActivity]）里自己登录，这里只读 WebView 里商店的 cookie（steamLoginSecure、sessionid），
 * 账号密码不经过 App。登录票据大约一天过期，快过期时用一个看不见的 WebView 打开一次商店页面，Steam 会自己换新票据。
 */
class StoreSession(private val http: OkHttpClient, private val scope: CoroutineScope, private val profile: () -> WebProfile? = { null }) {
    companion object {
        const val STORE = "https://store.steampowered.com"
        const val API = "https://api.steampowered.com"
        private val STEAM_ID = Regex("^\\d{17}$")
    }

    @Volatile private var cookies: Map<String, String> = emptyMap()

    @Volatile var steamId: String = ""
        private set

    /** steamLoginSecure 的到期时间（秒，JWT 里的 exp）；读不出来时当作一直有效。 */
    @Volatile var expires: Long = 0L
        private set

    val loggedIn: Boolean get() = steamId.isNotEmpty()

    private val now: Long get() = System.currentTimeMillis() / 1000

    // ---- cookie ----

    /** 从 WebView 读商店的 cookie，返回登录的账号是否变了。在主线程调用。 */
    fun syncCookies(): Boolean {
        val cm = cookieManager()
        val map = LinkedHashMap<String, String>()
        cm.getCookie(STORE)?.split(';')?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        var id = ""
        var exp = 0L
        map["steamLoginSecure"]?.let { sls ->
            val decoded = runCatching { URLDecoder.decode(sls, "UTF-8") }.getOrDefault(sls)
            val bar = decoded.indexOf('|')
            if (bar > 0 && STEAM_ID.matches(decoded.substring(0, bar))) id = decoded.substring(0, bar)
            exp = jwtExpiry(decoded.substringAfterLast('|'))
        }
        // sessionid 只是防跨站的随机值，表单里和 cookie 里一样即可；还没有时自己生成一个
        if (id.isNotEmpty() && "sessionid" !in map) {
            val sid = randomHex(24)
            map["sessionid"] = sid
            cm.setCookie(STORE, "sessionid=$sid; Path=/; Secure")
            cm.flush()
        }
        cookies = map
        expires = exp
        if (id == steamId) return false
        steamId = id
        Log.i(if (id.isNotEmpty()) "商店已登录 $id（票据到 ${expText(exp)}）" else "商店未登录")
        return true
    }

    /** JWT 里的 exp（秒）；读不出来时当作一直有效。 */
    private fun jwtExpiry(jwt: String): Long = runCatching {
        val part = jwt.split('.')[1]
        val json = String(android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        JSONObject(json).optLong("exp").takeIf { it > 0 }
    }.getOrNull() ?: Long.MAX_VALUE

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 存这个账号 cookie 的 CookieManager：新闻专用账号在另一个配置文件里。 */
    private fun cookieManager(): CookieManager = profile()?.cookieManager() ?: CookieManager.getInstance()

    /** 社区（steamcommunity.com）那边登录了没有：商店的票据可以从它换。 */
    fun communityLoggedIn(): Boolean = cookieManager().getCookie(Steam.COMMUNITY)?.contains("steamLoginSecure=") == true

    /** 退出这个账号（只用于新闻专用账号）。 */
    fun logoutProfile() {
        val p = profile() ?: return
        p.cookieManager()?.let {
            it.removeAllCookies(null)
            it.flush()
        }
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    // ---- 续期 ----

    private var renewing: Deferred<Unit>? = null

    /** 上次续期的时间（开机以来的毫秒，单调递增，不会因为改系统时间或长时间开机变成负数）；null = 还没续过。 */
    private var lastRenewAt: Long? = null

    /**
     * 票据快到期（或已过期，或社区登录了但商店还没有票据）时续期；几个请求同时发现时只续一次，失败后 10 分钟内不再试。
     * [force]：不管到期时间，马上续一次（登录页关闭后）。
     */
    suspend fun ensureFresh(force: Boolean = false) {
        if (!force && loggedIn && expires > now + 600) return
        val job = withContext(Dispatchers.Main) {
            renewing?.takeIf { it.isActive } ?: run {
                val last = lastRenewAt
                if (!force && last != null && SystemClock.elapsedRealtime() - last < 10 * 60_000L) return@withContext null
                lastRenewAt = SystemClock.elapsedRealtime()
                scope.async(Dispatchers.Main) { renewOnce() }.also { renewing = it }
            }
        }
        job?.await()
    }

    /** 在看不见的 WebView 里打开一次商店的账号页面：票据过期时 Steam 会跳到 login.steampowered.com 换一张新的再跳回来。 */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renewOnce() {
        val before = expires
        val back = CompletableDeferred<Unit>()
        val web = runCatching { WebView(App.instance) }.getOrElse {
            Log.e("续期商店登录：建不了 WebView", it)
            return
        }
        try {
            profile()?.use(web)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            cookieManager().setAcceptThirdPartyCookies(web, true)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith(STORE) && "/login" !in url) back.complete(Unit)
                }
            }
            web.loadUrl("$STORE/account/")
            withTimeoutOrNull(30_000) { back.await() }
            cookieManager().flush()
        } finally {
            web.stopLoading()
            web.destroy()
        }
        syncCookies()
        Log.i(if (loggedIn && expires > now + 600) "商店登录已续期，到 ${expText(expires)}" else "商店登录续期失败（票据到 ${expText(before)}）")
    }

    // ---- http ----

    /** GET 商店（带登录 cookie）。 */
    suspend fun get(pathOrUrl: String): String {
        ensureFresh()
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else STORE + pathOrUrl
        val req = Request.Builder().url(url).header("Cookie", cookieHeader()).header("Referer", "$STORE/news/").build()
        return send(req)
    }

    suspend fun getJson(pathOrUrl: String): JSONObject {
        val text = get(pathOrUrl)
        return withContext(Dispatchers.Default) { parseJson(text, pathOrUrl) }
    }

    /** GET 不需要登录的地址（公开接口、汇率）。 */
    suspend fun getPublicJson(url: String): JSONObject {
        val text = send(Request.Builder().url(url).build())
        return withContext(Dispatchers.Default) { parseJson(text, url) }
    }

    /** POST 不需要登录的表单（社区的评论）。 */
    suspend fun postPublic(url: String, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return parseJson(send(Request.Builder().url(url).post(body).build()), url)
    }

    /** POST 商店的表单（自动带上 sessionid）。 */
    suspend fun postStore(path: String, form: Map<String, String>): JSONObject {
        if (!loggedIn) throw SteamException(401, "还没有登录 Steam")
        ensureFresh()
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
            add("sessionid", cookies["sessionid"] ?: "")
        }.build()
        val req = Request.Builder().url(STORE + path).post(body)
            .header("Cookie", cookieHeader()).header("Origin", STORE).header("Referer", "$STORE/news/").build()
        return parseJson(send(req), path)
    }

    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw SteamException(resp.code, if (resp.code == 429) "Steam 说请求太频繁了，过一会儿再试" else "Steam 返回 ${resp.code}")
            text
        }
    }

    private fun parseJson(text: String, what: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        if (text.trimStart().startsWith("<")) throw SteamException(0, "Steam 返回了网页而不是数据（可能需要重新登录）")
        throw SteamException(0, "看不懂 Steam 的返回：$what")
    }

    /** 加入 / 移出愿望单。 */
    suspend fun setWishlist(appid: Int, on: Boolean) {
        val r = postStore(if (on) "/api/addtowishlist" else "/api/removefromwishlist", mapOf("appid" to appid.toString()))
        if (!r.optBoolean("success")) throw SteamException(0, if (on) "加入愿望单失败（可能需要重新登录）" else "移出愿望单失败（可能需要重新登录）")
    }
}

private fun expText(t: Long) = if (t <= 0 || t == Long.MAX_VALUE) "未知" else whenText(t).ifEmpty { date(t) }
