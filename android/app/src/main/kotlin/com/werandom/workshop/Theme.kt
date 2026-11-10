package com.werandom.workshop

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 跟随系统深浅色；安卓 12 以上用壁纸取色（Material You），更早的系统用 Steam 蓝。 */
@Composable
fun WorkshopTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF66C0F4), secondary = Color(0xFF9EC9E8))
        else -> lightColorScheme(primary = Color(0xFF1A73B8), secondary = Color(0xFF3B6E91))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 状态颜色（和电脑版一样：NEW 橙色，已订阅绿色，等待黄色）。 */
object Tones {
    val New = Color(0xFFFF8C42)
    val Ok = Color(0xFF4CAF50)
    val Questionable = Color(0xFFFFD166)
    val Mature = Color(0xFFFF7B7B)
}
