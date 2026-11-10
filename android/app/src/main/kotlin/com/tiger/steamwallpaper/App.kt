package com.tiger.steamwallpaper

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.MainScope

class App : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var instance: App
            private set
    }

    val scope = MainScope()
    lateinit var steam: Steam
        private set
    lateinit var store: Store
        private set
    lateinit var workshop: Workshop
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.init(filesDir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("崩溃", e)
            previous?.uncaughtException(t, e)
        }
        Log.i("启动 ${BuildConfig.VERSION_NAME}（安卓 ${Build.VERSION.RELEASE}，${Build.MODEL}）")
        steam = Steam()
        store = Store(java.io.File(filesDir, "workshop.json"), scope)
        store.load()
        // 订阅账号（大号）：cookie 在 WebView 的另一个配置文件里，没登录过时不碰它
        val sub = Steam(cookieSource = { if (store.subAccount) SubProfile.cookieManager() else null }, client = steam.http)
        workshop = Workshop(steam, sub, store, scope)
        updater = Updater(this, steam.http, store, scope)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { steam.http }))
            // 动图：安卓 9 以上用系统的 ImageDecoder（省内存，硬件解码）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L * 1024 * 1024).build() }
        .crossfade(150)
        .build()
}
