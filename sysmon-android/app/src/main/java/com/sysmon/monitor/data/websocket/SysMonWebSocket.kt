package com.sysmon.monitor.data.websocket

import android.content.Context
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong

class SysMonWebSocket(private val context: Context? = null) {

    companion object {
        private const val TAG       = "SysMonWS"
        /** 网速历史最大保留条数 */
        private const val MAX_HIST  = 30
    }

    // 网速历史（内存维护）
    private val rxHistory = ArrayDeque<Float>(MAX_HIST)
    private val txHistory = ArrayDeque<Float>(MAX_HIST)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    // 每次建立新连接时递增；Listener 捕获创建时的 generation，
    // 回调时对比当前值，不匹配则说明是旧连接残留，直接丢弃。
    private val generation = AtomicLong(0)

    // 协程作用域：用于写入 Glance DataStore
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<WsState>(WsState.Disconnected)
    val state: StateFlow<WsState> = _state

    private val _metrics = MutableStateFlow<SystemMetrics?>(null)
    val metrics: StateFlow<SystemMetrics?> = _metrics

    /**
     * 切换连接：静默关闭旧 WebSocket（不触发 onClosed/_state 变化），直接发起新连接。
     * 用于用户主动切换 URL，避免触发断线熄屏逻辑。
     * @param url    目标 WebSocket 地址
     * @param cookie 可选 Cookie 字符串（格式："key1=value1; key2=value2"），为空时不附加
     */
    fun reconnect(url: String, cookie: String = "") {
        // 先递增 generation，令所有旧 Listener 的回调自动失效
        val gen = generation.incrementAndGet()
        Log.d(TAG, "reconnect() gen=$gen url=$url cookie=${cookie.isNotEmpty()}")
        // cancel() 直接终止连接，不触发 onClosed
        webSocket?.cancel()
        webSocket = null
        _metrics.value = null
        // 状态设为 Connecting，跳过 connect() 里的守卫
        _state.value = WsState.Connecting
        val request = buildRequest(url, cookie)
        webSocket = client.newWebSocket(request, makeListener(gen))
    }

    /**
     * @param url    目标 WebSocket 地址
     * @param cookie 可选 Cookie 字符串（格式："key1=value1; key2=value2"），为空时不附加
     */
    fun connect(url: String, cookie: String = "") {
        if (_state.value is WsState.Connected || _state.value is WsState.Connecting) return
        val gen = generation.incrementAndGet()
        Log.d(TAG, "connect() gen=$gen url=$url cookie=${cookie.isNotEmpty()}")
        _state.value = WsState.Connecting
        val request = buildRequest(url, cookie)
        webSocket = client.newWebSocket(request, makeListener(gen))
    }

    /**
     * 构建 WebSocket 握手请求，若 cookie 不为空则附加到 Cookie 请求头。
     */
    private fun buildRequest(url: String, cookie: String): Request {
        val builder = Request.Builder().url(url)
        if (cookie.isNotBlank()) {
            builder.addHeader("Cookie", cookie)
        }
        return builder.build()
    }

    /**
     * 生成带 generation 标记的 Listener。
     * 每个回调都先比对 myGen 与当前 generation，不一致则丢弃，
     * 彻底杜绝旧连接回调污染新连接状态。
     */
    private fun makeListener(myGen: Long) = object : WebSocketListener() {

        private fun isStale() = generation.get() != myGen

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale()) { Log.d(TAG, "onOpen 忽略(stale gen=$myGen)"); return }
            Log.d(TAG, "onOpen gen=$myGen")
            _state.value = WsState.Connected
            updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = true }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale()) return
            try {
                val m = gson.fromJson(text, SystemMetrics::class.java)
                _metrics.value = m
                // 更新内存中的历史队列
                if (rxHistory.size >= MAX_HIST) rxHistory.removeFirst()
                if (txHistory.size >= MAX_HIST) txHistory.removeFirst()
                rxHistory.addLast(m.netRxKbps.toFloat())
                txHistory.addLast(m.netTxKbps.toFloat())
                // 序列化为 JSON 字符串
                val rxJson = gson.toJson(rxHistory.toList())
                val txJson = gson.toJson(txHistory.toList())
                // 写入 Glance DataStore → 自动触发小组件重组
                updateWidgetState { prefs ->
                    prefs[WidgetStateKeys.NET_RX_KBPS]    = m.netRxKbps.toFloat()
                    prefs[WidgetStateKeys.NET_TX_KBPS]    = m.netTxKbps.toFloat()
                    prefs[WidgetStateKeys.NET_RX_HISTORY] = rxJson
                    prefs[WidgetStateKeys.NET_TX_HISTORY] = txJson
                    prefs[WidgetStateKeys.CPU_PERCENT]    = m.cpuUsagePercent
                    prefs[WidgetStateKeys.MEM_PERCENT]    = m.memoryUsagePercent
                    prefs[WidgetStateKeys.MEM_USED_MB]    = m.memoryUsedMb
                    prefs[WidgetStateKeys.MEM_TOTAL_MB]   = m.memoryTotalMb
                    prefs[WidgetStateKeys.CONNECTED]      = true
                }
            } catch (_: Exception) { /* 忽略解析错误 */ }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isStale()) { Log.d(TAG, "onFailure 忽略(stale gen=$myGen)"); return }
            Log.d(TAG, "onFailure gen=$myGen ${t.message}")
            _state.value = WsState.Error(t.message ?: "连接失败")
            updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = false }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale()) { Log.d(TAG, "onClosed 忽略(stale gen=$myGen)"); return }
            Log.d(TAG, "onClosed gen=$myGen")
            _state.value = WsState.Disconnected
            updateWidgetState { prefs -> prefs[WidgetStateKeys.CONNECTED] = false }
        }
    }

    fun disconnect() {
        // 递增 generation，令所有在途回调失效
        val gen = generation.incrementAndGet()
        Log.d(TAG, "disconnect() gen=$gen")
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
