package com.werandom.workshop

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

/**
 * 应用内更新：GitHub 上标签为 android-v* 的 Release（电脑版用 releases/latest，安卓版的 Release 不会成为 Latest，
 * 所以这里列出全部 Release 找最新的 android-v*）。接口限流（每个 IP 每小时 60 次）时改读 releases.atom。
 * 启动时最多每 6 小时查一次，也可以手动查。下载 APK 后用 PackageInstaller 安装。
 */
class Updater(private val app: App, private val http: OkHttpClient, private val store: Store, private val scope: CoroutineScope) {
    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    private val repo = BuildConfig.REPO

    /** 查到的新版本；有值时显示更新面板。 */
    var available by mutableStateOf<Release?>(null)
    var checking by mutableStateOf(false)
        private set

    /** 下载进度 0..1；null 表示没在下载。 */
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf("")
        private set

    /** 等用户在系统设置里允许「安装未知应用」后再装的 APK。 */
    private var pendingApk: File? = null
    private var job: Job? = null

    fun autoCheck() {
        if (System.currentTimeMillis() - store.lastUpdateCheck < 6 * 3600_000L) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (checking) return
        checking = true
        message = ""
        scope.launch {
            try {
                val r = latest()
                store.lastUpdateCheck = System.currentTimeMillis()
                store.save()
                when {
                    r == null || compare(r.version, BuildConfig.VERSION_NAME) <= 0 -> {
                        if (manual) app.workshop.toast("已经是最新版本 v${BuildConfig.VERSION_NAME}")
                    }
                    !manual && r.version == store.skippedVersion -> Unit
                    else -> available = r
                }
            } catch (e: Exception) {
                Log.i("检查更新失败：${e.message}")
                if (manual) app.workshop.toast("检查更新失败：${e.message}")
            }
            checking = false
        }
    }

    fun skip() {
        available?.let {
            store.skippedVersion = it.version
            store.save()
        }
        job?.cancel()
        progress = null
        available = null
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            fromApi()
        } catch (e: Exception) {
            Log.i("GitHub 接口读取失败（${e.message}），改读 releases.atom")
            fromAtom()
        }
    }

    private fun fromApi(): Release? {
        val req = Request.Builder().url("https://api.github.com/repos/$repo/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "WERandomWorkshop").build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("GitHub 返回 ${resp.code}")
            resp.body.string()
        }
        val arr = JSONArray(text)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (!tag.startsWith("android-v") || r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val version = tag.removePrefix("android-v")
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || compare(version, best.version) > 0) {
                best = Release(version, r.optString("body"), apk.optString("browser_download_url"), apk.optLong("size"), r.optString("html_url"))
            }
        }
        return best
    }

    /** releases.atom 不占接口的额度；APK 的文件名是固定格式，可以直接拼出下载地址。 */
    private fun fromAtom(): Release? {
        val req = Request.Builder().url("https://github.com/$repo/releases.atom").header("User-Agent", "WERandomWorkshop").build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("GitHub 返回 ${resp.code}")
            resp.body.string()
        }
        val version = Regex("/releases/tag/android-v(\\d+\\.\\d+\\.\\d+)\"").findAll(text).map { it.groupValues[1] }
            .maxWithOrNull { a, b -> compare(a, b) } ?: return null
        val base = "https://github.com/$repo/releases"
        return Release(version, "", "$base/download/android-v$version/${apkName(version)}", 0, "$base/tag/android-v$version")
    }

    fun download(activity: Activity) {
        val r = available ?: return
        if (progress != null) return
        progress = 0f
        message = ""
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetch(r) }
                progress = null
                install(activity, file)
            } catch (e: Exception) {
                progress = null
                message = "下载失败：${e.message}"
                Log.e("下载更新失败", e)
            }
        }
    }

    private fun fetch(r: Release): File {
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, apkName(r.version))
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("GitHub 返回 ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.size
            resp.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - last > 256 * 1024) {
                            last = done
                            val p = done.toFloat() / total
                            scope.launch { if (progress != null) progress = p.coerceIn(0f, 1f) }
                        }
                    }
                }
            }
            if (r.size > 0 && file.length() != r.size) throw Exception("文件不完整（${file.length()} / ${r.size}）")
        }
        return file
    }

    private fun install(activity: Activity, file: File) {
        val pm = app.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // 先让用户在系统设置里允许这个 App 安装应用，回来后（onResume）接着装
            pendingApk = file
            message = "请在系统设置里允许「安装未知应用」，返回后会继续安装"
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return
        }
        try {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java).setPackage(app.packageName), flags)
                s.commit(pi.intentSender)
            }
            message = "正在安装…"
        } catch (e: Exception) {
            message = "安装失败：${e.message}"
            Log.e("安装更新失败", e)
        }
    }

    /** 回到前台：如果刚才去系统设置里允许了安装未知应用，接着装。 */
    fun onResume(activity: Activity) {
        val f = pendingApk ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            message = "没有「安装未知应用」的权限，无法安装更新"
            pendingApk = null
            return
        }
        pendingApk = null
        install(activity, f)
    }

    fun failed(text: String) {
        message = text
    }

    companion object {
        fun apkName(version: String) = "WERandomWorkshop-v$version.apk"

        /** 比较 x.y.z 版本号。 */
        fun compare(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until 3) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/** PackageInstaller 的结果：要用户确认时打开系统的安装确认页，失败时把原因显示在更新面板里。 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure { Log.e("打开安装确认页失败", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("更新已安装")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知错误"
                Log.i("安装更新失败：$status $msg")
                val text = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || "INCOMPATIBLE" in msg) {
                    "安装失败：签名和已装的版本不一致（$msg）。请卸载后重新安装。"
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "已取消安装"
                } else {
                    "安装失败：$msg"
                }
                App.instance.updater.failed(text)
            }
        }
    }
}
