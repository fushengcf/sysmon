package com.sysmon.monitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysmon.monitor.data.model.SystemMetrics
import com.sysmon.monitor.data.repository.UrlRepository
import com.sysmon.monitor.data.websocket.SysMonWebSocket
import com.sysmon.monitor.data.websocket.WsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val wsClient = SysMonWebSocket(application)
    private val urlRepo  = UrlRepository(application)

    val wsState: StateFlow<WsState>       = wsClient.state
    val metrics: StateFlow<SystemMetrics?> = wsClient.metrics

    // ── 历史数据（最多 60 个点）────────────────────────────────────────────────
    private val _cpuHistory    = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory    = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _netRxHistory  = MutableStateFlow<List<Double>>(emptyList())
    val netRxHistory: StateFlow<List<Double>> = _netRxHistory.asStateFlow()

    private val _netTxHistory  = MutableStateFlow<List<Double>>(emptyList())
    val netTxHistory: StateFlow<List<Double>> = _netTxHistory.asStateFlow()

    // ── 当前输入的 URL ─────────────────────────────────────────────────────────
    private val _wsUrl = MutableStateFlow("ws://192.168.1.100:9001")
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    // ── 已保存的链接列表 ───────────────────────────────────────────────────────
    val savedUrls: StateFlow<List<String>> = urlRepo.urls

    // ── 自动连接状态（启动时尝试已保存链接） ──────────────────────────────────
    private val _autoConnecting = MutableStateFlow(false)
    val autoConnecting: StateFlow<Boolean> = _autoConnecting.asStateFlow()

    private var autoConnectJob: Job? = null

    companion object {
        private const val MAX_HISTORY          = 60
        private const val AUTO_CONNECT_TIMEOUT = 5_000L   // 每个链接等待 5 秒
        private const val RECONNECT_INTERVAL   = 8_000L   // 断线后重试间隔
    }

    init {
        // 监听指标更新，追加历史
        viewModelScope.launch {
            wsClient.metrics.collect { m ->
                m ?: return@collect
                _cpuHistory.value    = (_cpuHistory.value    + m.cpuUsagePercent).takeLast(MAX_HISTORY)
                _memHistory.value    = (_memHistory.value    + m.memoryUsagePercent).takeLast(MAX_HISTORY)
                _netRxHistory.value  = (_netRxHistory.value  + m.netRxKbps).takeLast(MAX_HISTORY)
                _netTxHistory.value  = (_netTxHistory.value  + m.netTxKbps).takeLast(MAX_HISTORY)
            }
        }

        // 监听断线事件：连接中断后自动重试
        viewModelScope.launch {
            wsClient.state.collect { state ->
                if (state is WsState.Error || state is WsState.Disconnected) {
                    // 只有在没有手动触发自动连接时才自动重试
                    if (!_autoConnecting.value && urlRepo.urls.value.isNotEmpty()) {
                        delay(RECONNECT_INTERVAL)
                        // 再次检查，避免用户已手动操作
                        if (!_autoConnecting.value &&
                            wsClient.state.value !is WsState.Connected &&
                            wsClient.state.value !is WsState.Connecting) {
                            tryAutoConnect()
                        }
                    }
                }
            }
        }

        // App 启动时自动尝试已保存的链接
        tryAutoConnect()
    }

    // ── 自动连接：依次尝试已保存链接，成功则停止 ──────────────────────────────

    private fun tryAutoConnect() {
        val urls = urlRepo.urls.value
        if (urls.isEmpty()) return
        // 已连接则不重复触发
        if (wsClient.state.value is WsState.Connected) return

        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            _autoConnecting.value = true
            for (url in urls) {
                if (wsClient.state.value is WsState.Connected) break

                _wsUrl.value = url
                wsClient.connect(url)

                // 等待连接结果（最多 AUTO_CONNECT_TIMEOUT ms）
                val deadline = System.currentTimeMillis() + AUTO_CONNECT_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    val state = wsClient.state.value
                    if (state is WsState.Connected) break
                    if (state is WsState.Error || state is WsState.Disconnected) break
                    delay(200)
                }

                if (wsClient.state.value is WsState.Connected) break

                // 本次失败，断开后尝试下一个
                wsClient.disconnect()
                delay(300)
            }
            _autoConnecting.value = false
        }
    }

    /** 外部触发重新自动连接（如 Activity onResume 时调用） */
    fun retryAutoConnect() {
        if (wsClient.state.value is WsState.Connected ||
            wsClient.state.value is WsState.Connecting ||
            _autoConnecting.value) return
        tryAutoConnect()
    }

    // ── 公开操作 ───────────────────────────────────────────────────────────────

    fun updateUrl(url: String) {
        _wsUrl.value = url
    }

    fun connect() {
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.connect(_wsUrl.value)
    }

    fun disconnect() {
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.disconnect()
        clearHistory()
    }

    /** 保存当前 URL 到列表（连接成功时调用，或用户手动保存） */
    fun saveCurrentUrl() {
        val url = _wsUrl.value.trim()
        if (url.isNotEmpty()) urlRepo.addUrl(url)
    }

    /** 从列表中删除指定 URL */
    fun removeUrl(url: String) {
        urlRepo.removeUrl(url)
    }

    /** 选择已保存的链接并立即连接 */
    fun connectTo(url: String) {
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        _wsUrl.value = url
        wsClient.disconnect()
        clearHistory()
        wsClient.connect(url)
    }

    private fun clearHistory() {
        _cpuHistory.value   = emptyList()
        _memHistory.value   = emptyList()
        _netRxHistory.value = emptyList()
        _netTxHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}
