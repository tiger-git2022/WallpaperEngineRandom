package com.tiger.steamwallpaper

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 订阅账号（大号）的登录环境：系统 WebView 的另一个配置文件，cookie 和浏览账号（默认配置文件）分开存，互不覆盖，
 * 登录过期的刷新也由 WebView 自己处理。要 WebView 支持多配置文件（androidx.webkit 的 MULTI_PROFILE）。
 * 只在主线程用。
 */
object SubProfile {
    private const val NAME = "subscribe"

    val supported: Boolean get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    fun cookieManager(): CookieManager? =
        if (!supported) null else runCatching { ProfileStore.getInstance().getOrCreateProfile(NAME).cookieManager }.getOrNull()

    /** 让这个 WebView 用订阅账号的配置文件；要在它加载任何网页之前调用。 */
    fun use(web: WebView): Boolean = supported && runCatching { WebViewCompat.setProfile(web, NAME) }.isSuccess
}
