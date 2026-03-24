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

// ─── 网速小组件 ───────────────────────────────────────────────────────────────

class NetSpeedWidget : GlanceAppWidget() {

    // 使用共享 StateDefinition，状态变化自动触发重组
    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // currentState() 在每次状态更新时自动重新读取，驱动重组
            val prefs     = currentState<Preferences>()
            val rxKbps    = prefs[WidgetStateKeys.NET_RX_KBPS]?.toDouble() ?: 0.0
            val txKbps    = prefs[WidgetStateKeys.NET_TX_KBPS]?.toDouble() ?: 0.0
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            NetSpeedContent(rxKbps = rxKbps, txKbps = txKbps, connected = connected)
        }
    }
}

@Composable
private fun NetSpeedContent(rxKbps: Double, txKbps: Double, connected: Boolean) {
    val bgColor    = ColorProvider(Color(0xEE0F1623))
    val accentRx   = ColorProvider(Color(0xFFFF9500))
    val accentTx   = ColorProvider(Color(0xFFFF3CAC))
    val mutedColor = ColorProvider(Color(0xFF3D5A7A))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start
    ) {
        // 标题行
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NET",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF00D4FF)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = if (connected) "●" else "○",
                style = TextStyle(
                    color = if (connected) ColorProvider(Color(0xFF00FFB3)) else mutedColor,
                    fontSize = 8.sp
                )
            )
        }

        Spacer(GlanceModifier.height(6.dp))

        if (connected) {
            val (rxVal, rxUnit) = formatSpeed(rxKbps)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("↓ ", style = TextStyle(color = accentRx, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Text(rxVal, style = TextStyle(color = accentRx, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Text(" $rxUnit", style = TextStyle(color = accentRx, fontSize = 10.sp))
            }

            Spacer(GlanceModifier.height(4.dp))

            val (txVal, txUnit) = formatSpeed(txKbps)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("↑ ", style = TextStyle(color = accentTx, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Text(txVal, style = TextStyle(color = accentTx, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Text(" $txUnit", style = TextStyle(color = accentTx, fontSize = 10.sp))
            }
        } else {
            Text("未连接", style = TextStyle(color = mutedColor, fontSize = 12.sp))
        }
    }
}

// ─── Receiver ─────────────────────────────────────────────────────────────────

class NetSpeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetSpeedWidget()
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

internal fun formatSpeed(kbps: Double): Pair<String, String> =
    if (kbps >= 1024.0) String.format("%.1f", kbps / 1024.0) to "MB/s"
    else String.format("%.1f", kbps) to "KB/s"
