package com.tiger.steamwallpaper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Steam 官方登录页（账号密码、手机令牌确认、扫码都在 Steam 页面里完成）。登录成功（cookie 里有 steamLoginSecure、
 * 页面离开了 /login）后自动关闭；按返回键或 × 取消。
 */
class LoginActivity : ComponentActivity() {
    companion object {
        /** true：登录订阅账号（大号），存在 WebView 的另一个配置文件里（[SubProfile]）。 */
        const val EXTRA_SUB = "sub"

        /** true：登录新闻专用账号，存在 WebView 的另一个配置文件里（[NewsProfile]）。 */
        const val EXTRA_NEWS = "news"
    }

    private var web: WebView? = null
    private val sub by lazy { intent.getBooleanExtra(EXTRA_SUB, false) }
    private val newsAccount by lazy { intent.getBooleanExtra(EXTRA_NEWS, false) }
    private val profile: WebProfile? by lazy { if (sub) SubProfile else if (newsAccount) NewsProfile else null }

    private fun cookies(): CookieManager = profile?.cookieManager() ?: CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cm = cookies()
        cm.setAcceptCookie(true)
        setContent {
            WorkshopTheme {
                var loading by remember { mutableStateOf(true) }
                var canGoBack by remember { mutableStateOf(false) }
                BackHandler {
                    val w = web
                    if (canGoBack && w != null && w.canGoBack()) w.goBack() else finish()
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, contentDescription = "取消") }
                            Column(Modifier.weight(1f)) {
                                Text(if (sub) "登录订阅账号" else if (newsAccount) "登录新闻账号" else "登录 Steam", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (sub) "请登录电脑上 Steam 客户端用的账号（大号），订阅会加到这个号上"
                                    else if (newsAccount) "新闻流、关注的发行商、愿望单和价格地区用这个号；在 Steam 官方页面登录，账号密码不经过这个 App"
                                    else "新闻、壁纸的浏览和关注都用这个账号；在 Steam 官方页面登录，账号密码不经过这个 App",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).also { w ->
                                    web = w
                                    // 订阅账号、新闻账号：用另一个配置文件，不覆盖浏览账号的登录
                                    val p = profile
                                    if (p != null && !p.use(w)) {
                                        finish()
                                        return@also
                                    }
                                    w.settings.javaScriptEnabled = true
                                    w.settings.domStorageEnabled = true
                                    cm.setAcceptThirdPartyCookies(w, true)
                                    w.webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                            loading = true
                                            check(url)
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            loading = false
                                            canGoBack = view.canGoBack()
                                            check(url)
                                        }
                                    }
                                    w.loadUrl(Steam.COMMUNITY + "/login/home/?goto=my/")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun check(url: String?) {
        if (isFinishing || url == null || "/login" in url) return
        val cm = cookies()
        val ck = cm.getCookie(Steam.COMMUNITY)
        if (ck != null && "steamLoginSecure=" in ck) {
            cm.flush()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        web?.let {
            it.stopLoading()
            it.destroy()
        }
        web = null
        super.onDestroy()
    }
}
