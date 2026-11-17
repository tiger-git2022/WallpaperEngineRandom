package com.tiger.steamwallpaper

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 另一个账号的登录环境：系统 WebView 的另一个配置文件，cookie 和浏览账号（默认配置文件）分开存，互不覆盖，
 * 登录过期的刷新也由 WebView 自己处理。要 WebView 支持多配置文件（androidx.webkit 的 MULTI_PROFILE）。
 * 只在主线程用。
 */
open class WebProfile(private val name: String) {
    val supported: Boolean get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    fun cookieManager(): CookieManager? =
        if (!supported) null else runCatching { ProfileStore.getInstance().getOrCreateProfile(name).cookieManager }.getOrNull()

    /** 让这个 WebView 用这个配置文件；要在它加载任何网页之前调用。 */
    fun use(web: WebView): Boolean = supported && runCatching { WebViewCompat.setProfile(web, name) }.isSuccess
}

/** 订阅账号（大号）。 */
object SubProfile : WebProfile("subscribe")

/** 新闻专用账号：新闻流、关注的发行商、愿望单和价格地区用它；没登录时新闻用浏览账号。 */
object NewsProfile : WebProfile("news")

/**
 * 换登录票据：steamLoginSecure 大约一天过期，App 直接发的请求不会自动换新。在看不见的 WebView 里用这个账号的配置文件
 * （[profile]，null = 默认配置文件，即浏览账号）打开一次 [url]，票据过期时 Steam 会跳到 login.steampowered.com
 * 用长期的刷新票据换一张新的再跳回来；回到 [home] 开头、不是登录页的页面才算完（最多等 30 秒）。在主线程调用；
 * 换完后调用方自己重新读 cookie。
 */
@SuppressLint("SetJavaScriptEnabled")
suspend fun renewInHiddenWebView(profile: WebProfile?, url: String, home: String) {
    val cm = profile?.cookieManager() ?: if (profile == null) CookieManager.getInstance() else return
    val web = runCatching { WebView(App.instance) }.getOrElse {
        Log.e("续期登录：建不了 WebView", it)
        return
    }
    try {
        if (profile != null && !profile.use(web)) return
        val back = CompletableDeferred<Unit>()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        cm.setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(home) && "/login" !in url) back.complete(Unit)
            }
        }
        web.loadUrl(url)
        withTimeoutOrNull(30_000) { back.await() }
        cm.flush()
    } finally {
        web.stopLoading()
        web.destroy()
    }
}
