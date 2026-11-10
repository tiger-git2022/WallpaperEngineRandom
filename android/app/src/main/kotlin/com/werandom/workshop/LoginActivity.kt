package com.werandom.workshop

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
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cm = CookieManager.getInstance()
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
                                Text("登录 Steam", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "请登录电脑上 Steam 客户端用的账号，订阅的壁纸才会下载到那台电脑",
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
        val ck = CookieManager.getInstance().getCookie(Steam.COMMUNITY)
        if (ck != null && "steamLoginSecure=" in ck) {
            CookieManager.getInstance().flush()
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
