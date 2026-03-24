package com.sysmon.monitor.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.io.File

/**
 * 所有小组件共用同一个 DataStore 文件，通过 Preferences key 区分字段。
 * GlanceStateDefinition 使用 Glance 内置的 PreferencesGlanceStateDefinition，
 * 状态变化时 Glance 会自动触发小组件重组，无需手动 updateAll()。
 */
object SysMonWidgetStateDefinition : GlanceStateDefinition<Preferences> by PreferencesGlanceStateDefinition {
    // 覆盖文件名，让三个小组件共享同一份数据
    override fun getLocation(context: Context, fileKey: String): File =
        File(context.applicationContext.filesDir, "datastore/sysmon_widget_state.preferences_pb")
}

// ─── Preferences Keys ─────────────────────────────────────────────────────────

object WidgetStateKeys {
    val NET_RX_KBPS   = floatPreferencesKey("net_rx_kbps")
    val NET_TX_KBPS   = floatPreferencesKey("net_tx_kbps")
    val CPU_PERCENT   = floatPreferencesKey("cpu_percent")
    val MEM_PERCENT   = floatPreferencesKey("mem_percent")
    val MEM_USED_MB   = longPreferencesKey("mem_used_mb")
    val MEM_TOTAL_MB  = longPreferencesKey("mem_total_mb")
    val CONNECTED     = booleanPreferencesKey("connected")
}
