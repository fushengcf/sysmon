package com.sysmon.monitor.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlin.math.roundToInt

// ─── 内存使用率小组件（进度条样式，与 APP 一致） ──────────────────────────────

class MemWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs     = currentState<Preferences>()
            val mem       = prefs[WidgetStateKeys.MEM_PERCENT] ?: 0f
            val usedMb    = prefs[WidgetStateKeys.MEM_USED_MB] ?: 0L
            val totalMb   = prefs[WidgetStateKeys.MEM_TOTAL_MB] ?: 0L
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            MemContent(
                context   = context,
                mem       = mem,
                usedMb    = usedMb,
                totalMb   = totalMb,
                connected = connected
            )
        }
    }
}

@Composable
private fun MemContent(
    context: Context,
    mem: Float,
    usedMb: Long,
    totalMb: Long,
    connected: Boolean,
) {
    val mutedColor  = ColorProvider(Color(0xFF3D5A7A))
    val purpleColor = ColorProvider(Color(0xFFBF5FFF))

    // 进度条 Bitmap：宽 480px × 高 56px（对应 ~28dp @2x，足够清晰）
    val barW = 480; val barH = 56
    val barBitmap: Bitmap = MemProgressBitmapDrawer.draw(
        widthPx  = barW,
        heightPx = barH,
        percent  = if (connected) mem else 0f,
        usedMb   = usedMb,
        totalMb  = totalMb
    )

    // 透明背景
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0x00000000)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start
    ) {
        if (connected) {
            // ── 顶部信息行：xx/xx  xx% ───────────────────────────────────────
            Row(
                modifier           = GlanceModifier.fillMaxWidth(),
                verticalAlignment  = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start
            ) {
                if (totalMb > 0) {
                    Text(
                        text  = "${formatMbW(usedMb)} / ${formatMbW(totalMb)}",
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF8A9BBF)),
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text  = "${mem.roundToInt()}%",
                    style = TextStyle(
                        color      = purpleColor,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }

            Spacer(GlanceModifier.height(4.dp))

            // ── 进度条图片 ────────────────────────────────────────────────────
            Image(
                provider           = ImageProvider(barBitmap),
                contentDescription = "MEM ${mem.roundToInt()}%",
                modifier           = GlanceModifier.fillMaxWidth().height(22.dp)
            )
        } else {
            // ── 未连接：显示空进度条 + 提示 ──────────────────────────────────
            Row(
                modifier           = GlanceModifier.fillMaxWidth(),
                verticalAlignment  = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text  = "MEM",
                    style = TextStyle(
                        color      = mutedColor,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text  = "未连接",
                    style = TextStyle(color = mutedColor, fontSize = 10.sp)
                )
            }

            Spacer(GlanceModifier.height(4.dp))

            Image(
                provider           = ImageProvider(barBitmap),
                contentDescription = "MEM 未连接",
                modifier           = GlanceModifier.fillMaxWidth().height(22.dp)
            )
        }
    }
}

// ─── Receiver ─────────────────────────────────────────────────────────────────

class MemWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemWidget()
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

private fun formatMbW(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
