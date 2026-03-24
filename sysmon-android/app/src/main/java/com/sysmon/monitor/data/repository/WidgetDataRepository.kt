package com.sysmon.monitor.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 小组件数据仓库：将最新的系统指标写入 SharedPreferences，
 * 供 Glance 小组件在 onUpdate 时读取（跨进程安全）。
 *
 * 写入方：SysMonWebSocket（每次收到消息时调用 update）
 * 读取方：NetSpeedWidget / CpuWidget / MemWidget
 */
object WidgetDataRepository {

    private const val PREFS_NAME   = "sysmon_widget_data"
    private const val KEY_NET_RX   = "net_rx_kbps"
    private const val KEY_NET_TX   = "net_tx_kbps"
    private const val KEY_CPU      = "cpu_percent"
    private const val KEY_MEM      = "mem_percent"
    private const val KEY_MEM_USED = "mem_used_mb"
    private const val KEY_MEM_TOT  = "mem_total_mb"
    private const val KEY_CONNECTED = "connected"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 写入（由 WebSocket 消息回调调用）─────────────────────────────────────

    fun update(
        context: Context,
        netRxKbps: Double,
        netTxKbps: Double,
        cpuPercent: Float,
        memPercent: Float,
        memUsedMb: Long,
        memTotalMb: Long,
        connected: Boolean = true,
    ) {
        prefs(context).edit().apply {
            putFloat(KEY_NET_RX,    netRxKbps.toFloat())
            putFloat(KEY_NET_TX,    netTxKbps.toFloat())
            putFloat(KEY_CPU,       cpuPercent)
            putFloat(KEY_MEM,       memPercent)
            putLong(KEY_MEM_USED,   memUsedMb)
            putLong(KEY_MEM_TOT,    memTotalMb)
            putBoolean(KEY_CONNECTED, connected)
            apply()
        }
    }

    fun setConnected(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply()
    }

    // ── 读取（由小组件调用）──────────────────────────────────────────────────

    fun getNetRxKbps(context: Context): Float  = prefs(context).getFloat(KEY_NET_RX, 0f)
    fun getNetTxKbps(context: Context): Float  = prefs(context).getFloat(KEY_NET_TX, 0f)
    fun getCpuPercent(context: Context): Float = prefs(context).getFloat(KEY_CPU, 0f)
    fun getMemPercent(context: Context): Float = prefs(context).getFloat(KEY_MEM, 0f)
    fun getMemUsedMb(context: Context): Long   = prefs(context).getLong(KEY_MEM_USED, 0L)
    fun getMemTotalMb(context: Context): Long  = prefs(context).getLong(KEY_MEM_TOT, 0L)
    fun isConnected(context: Context): Boolean = prefs(context).getBoolean(KEY_CONNECTED, false)
}
