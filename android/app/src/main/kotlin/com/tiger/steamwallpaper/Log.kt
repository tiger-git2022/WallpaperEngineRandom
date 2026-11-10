package com.tiger.steamwallpaper

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简单的日志：logcat（标签 SteamWallpaper）加 App 私有目录里的 log.txt（超过 512 KB 时截掉前一半）。 */
object Log {
    private const val TAG = "SteamWallpaper"
    private var file: File? = null

    fun init(dir: File) {
        file = File(dir, "log.txt")
    }

    fun i(msg: String) {
        android.util.Log.i(TAG, msg)
        write(msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg, t)
        write(if (t == null) msg else "$msg：$t")
    }

    /** 日志的全部内容（新的在最后）。 */
    @Synchronized
    fun read(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }

    @Synchronized
    private fun write(msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > 512 * 1024) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
            f.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + msg + "\n")
        }
    }
}
