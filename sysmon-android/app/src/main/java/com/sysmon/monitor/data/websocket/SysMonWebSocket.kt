package com.sysmon.monitor.data.websocket

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.google.gson.Gson
import com.sysmon.monitor.data.model.SystemMetrics
import com.sysmon.monitor.widget.CpuWidget
import com.sysmon.monitor.widget.MemWidget
import com.sysmon.monitor.widget.NetSpeedWidget
import com.sysmon.monitor.widget.SysMonWidgetStateDefinition
import com.sysmon.monitor.widget.WidgetStateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SysMonWebSocket(private val context: Context? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    // 协程作用域：用于写入 Glance DataStore
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<WsState>(WsState.Disconnected)
    val state: StateFlow<WsState> = _state

    private val _metrics = MutableStateFlow<SystemMetrics?>(null)
    val metrics: StateFlow<SystemMetrics?> = _metrics

    fun connect(url: String) {
        if (_state.value is WsState.Connected || _state.value is WsState.Connecting) return
        _state.value = WsState.Connecting

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = WsState.Connected
                updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = true }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = gson.fromJson(text, SystemMetrics::class.java)
                    _metrics.value = m
                    // 写入 Glance DataStore → 自动触发小组件重组
                    updateWidgetState { prefs ->
                        prefs[WidgetStateKeys.NET_RX_KBPS]  = m.netRxKbps.toFloat()
                        prefs[WidgetStateKeys.NET_TX_KBPS]  = m.netTxKbps.toFloat()
                        prefs[WidgetStateKeys.CPU_PERCENT]  = m.cpuUsagePercent
                        prefs[WidgetStateKeys.MEM_PERCENT]  = m.memoryUsagePercent
                        prefs[WidgetStateKeys.MEM_USED_MB]  = m.memoryUsedMb
                        prefs[WidgetStateKeys.MEM_TOTAL_MB] = m.memoryTotalMb
                        prefs[WidgetStateKeys.CONNECTED]    = true
                    }
                } catch (_: Exception) { /* 忽略解析错误 */ }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = WsState.Error(t.message ?: "连接失败")
                updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = false }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = WsState.Disconnected
                updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = false }
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "用户断开")
        webSocket = null
        _state.value = WsState.Disconnected
        _metrics.value = null
        updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = false }
    }

    // ── 核心：通过 updateAppWidgetState 写入 Glance DataStore ─────────────────
    // 写入后 Glance 框架会自动对所有使用该 StateDefinition 的小组件触发重组

    private fun updateWidgetState(block: (MutablePreferences) -> Unit) {
        val ctx = context ?: return
        widgetScope.launch {
            try {
                val manager = GlanceAppWidgetManager(ctx)

                val netIds = manager.getGlanceIds(NetSpeedWidget::class.java)
                val cpuIds = manager.getGlanceIds(CpuWidget::class.java)
                val memIds = manager.getGlanceIds(MemWidget::class.java)

                // updateAppWidgetState lambda: (Preferences) -> Preferences
                // 需要先转成 MutablePreferences 修改，再返回
                val updater: suspend (androidx.datastore.preferences.core.Preferences)
                    -> androidx.datastore.preferences.core.Preferences = { prefs ->
                    val mutable = prefs.toMutablePreferences()
                    block(mutable)
                    mutable
                }

                for (id in netIds) {
                    updateAppWidgetState(ctx, SysMonWidgetStateDefinition, id, updater)
                    NetSpeedWidget().update(ctx, id)
                }
                for (id in cpuIds) {
                    updateAppWidgetState(ctx, SysMonWidgetStateDefinition, id, updater)
                    CpuWidget().update(ctx, id)
                }
                for (id in memIds) {
                    updateAppWidgetState(ctx, SysMonWidgetStateDefinition, id, updater)
                    MemWidget().update(ctx, id)
                }
            } catch (_: Exception) { /* 小组件未添加到桌面时忽略 */ }
        }
    }
}
