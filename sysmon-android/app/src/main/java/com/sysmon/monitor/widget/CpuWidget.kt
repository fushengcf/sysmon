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

// ─── CPU 使用率小组件 ─────────────────────────────────────────────────────────

class CpuWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs     = currentState<Preferences>()
            val cpu       = prefs[WidgetStateKeys.CPU_PERCENT] ?: 0f
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            CpuContent(cpu = cpu, connected = connected)
        }
    }
}

@Composable
private fun CpuContent(cpu: Float, connected: Boolean) {
    val bgColor     = ColorProvider(Color(0xEE0F1623))
    val accentColor = ColorProvider(Color(0xFF00FFB3))
    val mutedColor  = ColorProvider(Color(0xFF3D5A7A))

    val valueColor = when {
        !connected -> mutedColor
        cpu < 40f  -> ColorProvider(Color(0xFF00FFB3))
        cpu < 70f  -> ColorProvider(Color(0xFF00D4FF))
        cpu < 90f  -> ColorProvider(Color(0xFFFF9500))
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
            Text("CPU", style = TextStyle(color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = if (connected) "●" else "○",
                style = TextStyle(color = if (connected) accentColor else mutedColor, fontSize = 8.sp)
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        if (connected) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${cpu.roundToInt()}",
                    style = TextStyle(color = valueColor, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                )
                Text("%", style = TextStyle(color = valueColor, fontSize = 16.sp))
            }

            Spacer(GlanceModifier.height(4.dp))

            val filled = (cpu / 100f * 10).roundToInt().coerceIn(0, 10)
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

class CpuWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CpuWidget()
}
