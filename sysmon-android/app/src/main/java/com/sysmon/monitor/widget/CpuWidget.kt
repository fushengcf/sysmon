package com.sysmon.monitor.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import androidx.glance.unit.ColorProvider
import kotlin.math.roundToInt

// ─── CPU 使用率小组件（速度计仪表盘样式） ────────────────────────────────────

class CpuWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = SysMonWidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs     = currentState<Preferences>()
            val cpu       = prefs[WidgetStateKeys.CPU_PERCENT] ?: 0f
            val connected = prefs[WidgetStateKeys.CONNECTED] ?: false

            CpuContent(context = context, cpu = cpu, connected = connected)
        }
    }
}

@Composable
private fun CpuContent(context: Context, cpu: Float, connected: Boolean) {
    // 仪表盘 Bitmap 尺寸：240px 保证清晰度
    val bitmapSize = 240
    val displayValue = if (connected) cpu else 0f
    val gaugeBitmap: Bitmap = SpeedometerBitmapDrawer.draw(bitmapSize, displayValue)

    // 背景透明，仪表盘图片铺满，不叠加任何文案
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0x00000000))),  // 完全透明
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider           = ImageProvider(gaugeBitmap),
            contentDescription = "CPU ${cpu.roundToInt()}%",
            modifier           = GlanceModifier
                .fillMaxSize()
                .padding(2.dp)
        )
    }
}

// ─── Receiver ─────────────────────────────────────────────────────────────────

class CpuWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CpuWidget()
}
