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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── 网速小组件（双折线图样式，与 APP 一致） ──────────────────────────────────

class NetSpeedWidget : GlanceAppWidget() {

    // 使用共享 StateDefinition，状态变化自动触发重组
    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs     = currentState<Preferences>()
            val rxKbps    = prefs[WidgetStateKeys.NET_RX_KBPS]?.toDouble() ?: 0.0
            val txKbps    = prefs[WidgetStateKeys.NET_TX_KBPS]?.toDouble() ?: 0.0
            val rxHistStr = prefs[WidgetStateKeys.NET_RX_HISTORY] ?: "[]"
            val txHistStr = prefs[WidgetStateKeys.NET_TX_HISTORY] ?: "[]"
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            NetSpeedContent(
                rxKbps    = rxKbps,
                txKbps    = txKbps,
                rxHistStr = rxHistStr,
                txHistStr = txHistStr,
                connected = connected
            )
        }
    }
}

private val gson = Gson()
private val listType = object : TypeToken<List<Float>>() {}.type

private fun parseHistory(json: String): List<Float> =
    try { gson.fromJson(json, listType) ?: emptyList() }
    catch (_: Exception) { emptyList() }

@Composable
private fun NetSpeedContent(
    rxKbps: Double,
    txKbps: Double,
    rxHistStr: String,
    txHistStr: String,
    connected: Boolean,
) {
    val rxColor    = ColorProvider(Color(0xFF7B7FEB))  // 蓝紫 RX
    val txColor    = ColorProvider(Color(0xFFFF9C3E))  // 橙   TX
    val mutedColor = ColorProvider(Color(0xFF3D5A7A))

    // 折线图 Bitmap：宽 480px × 高 200px
    val chartW = 480; val chartH = 200
    val rxHistory = if (connected) parseHistory(rxHistStr) else emptyList()
    val txHistory = if (connected) parseHistory(txHistStr) else emptyList()
    val chartBitmap: Bitmap = NetSpeedBitmapDrawer.draw(
        widthPx   = chartW,
        heightPx  = chartH,
        rxHistory = rxHistory,
        txHistory = txHistory
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0x00000000)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment   = Alignment.Top,
        horizontalAlignment = Alignment.Start
    ) {
        // ── 顶部速度信息行 ────────────────────────────────────────────────────
        if (connected) {
            Row(
                modifier           = GlanceModifier.fillMaxWidth(),
                verticalAlignment  = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start
            ) {
                // RX
                val (rxVal, rxUnit) = formatSpeedW(rxKbps)
                Text(
                    text  = "↓ $rxVal $rxUnit",
                    style = TextStyle(
                        color      = rxColor,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(GlanceModifier.width(12.dp))
                // TX
                val (txVal, txUnit) = formatSpeedW(txKbps)
                Text(
                    text  = "↑ $txVal $txUnit",
                    style = TextStyle(
                        color      = txColor,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        } else {
            Row(
                modifier           = GlanceModifier.fillMaxWidth(),
                verticalAlignment  = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text  = "NET",
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
        }

        Spacer(GlanceModifier.height(4.dp))

        // ── 折线图 Bitmap ─────────────────────────────────────────────────────
        Image(
            provider           = ImageProvider(chartBitmap),
            contentDescription = "NET Speed Chart",
            modifier           = GlanceModifier.fillMaxWidth().defaultWeight()
        )
    }
}

// ─── Receiver ─────────────────────────────────────────────────────────────────

class NetSpeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetSpeedWidget()
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

internal fun formatSpeedW(kbps: Double): Pair<String, String> =
    if (kbps >= 1024.0) String.format("%.1f", kbps / 1024.0) to "MB/s"
    else String.format("%.1f", kbps) to "KB/s"
