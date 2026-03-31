package com.sysmon.monitor.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlin.math.roundToInt

// ─── 内存使用率小组件 ─────────────────────────────────────────────────────────

class MemWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs     = currentState<Preferences>()
            val mem       = prefs[WidgetStateKeys.MEM_PERCENT] ?: 0f
            val usedMb    = prefs[WidgetStateKeys.MEM_USED_MB] ?: 0L
            val totalMb   = prefs[WidgetStateKeys.MEM_TOTAL_MB] ?: 0L
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            MemContent(mem = mem, usedMb = usedMb, totalMb = totalMb, connected = connected)
        }
    }
}

@Composable
private fun MemContent(mem: Float, usedMb: Long, totalMb: Long, connected: Boolean) {
    val bgColor     = ColorProvider(Color(0xEE0F1623))
    val accentColor = ColorProvider(Color(0xFFBF5FFF))
    val mutedColor  = ColorProvider(Color(0xFF3D5A7A))

    val valueColor = when {
        !connected -> mutedColor
        mem < 60f  -> ColorProvider(Color(0xFFBF5FFF))
        mem < 80f  -> ColorProvider(Color(0xFFFF9500))
        else       -> ColorProvider(Color(0xFFFF453A))
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MEM", style = TextStyle(color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = if (connected) "●" else "○",
                style = TextStyle(color = if (connected) accentColor else mutedColor, fontSize = 8.sp)
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        if (connected) {
            Text(
                "${mem.roundToInt()}",
                style = TextStyle(color = valueColor, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            )

            if (totalMb > 0) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = "${formatMb(usedMb)} / ${formatMb(totalMb)}",
                    style = TextStyle(color = ColorProvider(Color(0xFF7A9CC0)), fontSize = 9.sp)
                )
            }

            Spacer(GlanceModifier.height(4.dp))

            val filled = (mem / 100f * 10).roundToInt().coerceIn(0, 10)
            Text(
                text = "█".repeat(filled) + "░".repeat(10 - filled),
                style = TextStyle(color = valueColor, fontSize = 9.sp)
            )
        } else {
            Text("未连接", style = TextStyle(color = mutedColor, fontSize = 12.sp))
        }
    }
}

// ─── Receiver ─────────────────────────────────────────────────────────────────

class MemWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemWidget()
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

private fun formatMb(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
