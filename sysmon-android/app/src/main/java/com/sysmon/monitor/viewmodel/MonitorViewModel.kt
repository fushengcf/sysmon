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

    val wsState: StateFlow<WsState>        = wsClient.state
    val metrics: StateFlow<SystemMetrics?> = wsClient.metrics

    // ── 历史数据（最多 60 个点）────────────────────────────────────────────────
    private val _cpuHistory   = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory   = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _netRxHistory = MutableStateFlow<List<Double>>(emptyList())
    val netRxHistory: StateFlow<List<Double>> = _netRxHistory.asStateFlow()

    private val _netTxHistory = MutableStateFlow<List<Double>>(emptyList())
    val netTxHistory: StateFlow<List<Double>> = _netTxHistory.asStateFlow()

    // ── 当前输入的 URL ─────────────────────────────────────────────────────────
    private val _wsUrl = MutableStateFlow("ws://192.168.1.100:9001")
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    // ── 当前已连接的 URL（用于图表页显示备注） ─────────────────────────────────
    private val _connectedUrl = MutableStateFlow("")
    val connectedUrl: StateFlow<String> = _connectedUrl.asStateFlow()

    // ── 已保存的链接列表 & 备注 ────────────────────────────────────────────────
    val savedUrls: StateFlow<List<String>> = urlRepo.urls
    val savedRemarks: StateFlow<List<String>> = urlRepo.remarks

    // ── 自动连接状态 ───────────────────────────────────────────────────────────
    private val _autoConnecting = MutableStateFlow(false)
    val autoConnecting: StateFlow<Boolean> = _autoConnecting.asStateFlow()

    private var autoConnectJob: Job? = null

    // 用户主动断开标志：为 true 时禁止自动重连，直到用户主动发起连接
    private var manuallyDisconnected = false

    companion object {
        private const val MAX_HISTORY          = 60
        private const val AUTO_CONNECT_TIMEOUT = 5_000L
        private const val RECONNECT_INTERVAL   = 8_000L
    }

    init {
        // 监听指标更新，追加历史
        viewModelScope.launch {
            wsClient.metrics.collect { m ->
                m ?: return@collect
                _cpuHistory.value   = (_cpuHistory.value   + m.cpuUsagePercent).takeLast(MAX_HISTORY)
                _memHistory.value   = (_memHistory.value   + m.memoryUsagePercent).takeLast(MAX_HISTORY)
                _netRxHistory.value = (_netRxHistory.value + m.netRxKbps).takeLast(MAX_HISTORY)
                _netTxHistory.value = (_netTxHistory.value + m.netTxKbps).takeLast(MAX_HISTORY)
            }
        }

        // 监听连接状态：记录已连接 URL；断线后自动重试
        viewModelScope.launch {
            wsClient.state.collect { state ->
                when (state) {
                    is WsState.Connected -> _connectedUrl.value = _wsUrl.value
                    is WsState.Error, is WsState.Disconnected -> {
                        // 用户主动断开时不自动重连
                        if (!manuallyDisconnected &&
                            !_autoConnecting.value &&
                            urlRepo.urls.value.isNotEmpty()) {
                            delay(RECONNECT_INTERVAL)
                            if (!manuallyDisconnected &&
                                !_autoConnecting.value &&
                                wsClient.state.value !is WsState.Connected &&
                                wsClient.state.value !is WsState.Connecting) {
                                tryAutoConnect()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        tryAutoConnect()
    }

    // ── 自动连接 ───────────────────────────────────────────────────────────────

    private fun tryAutoConnect() {
        val urls = urlRepo.urls.value
        if (urls.isEmpty()) return
        if (wsClient.state.value is WsState.Connected) return

        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            _autoConnecting.value = true
            for (url in urls) {
                if (wsClient.state.value is WsState.Connected) break
                _wsUrl.value = url
                wsClient.connect(url)
                val deadline = System.currentTimeMillis() + AUTO_CONNECT_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    val state = wsClient.state.value
                    if (state is WsState.Connected) break
                    if (state is WsState.Error || state is WsState.Disconnected) break
                    delay(200)
                }
                if (wsClient.state.value is WsState.Connected) break
                wsClient.disconnect()
                delay(300)
            }
            _autoConnecting.value = false
        }
    }

    fun retryAutoConnect() {
        // 用户主动断开后，onResume 不触发自动重连
        if (manuallyDisconnected) return
        if (wsClient.state.value is WsState.Connected ||
            wsClient.state.value is WsState.Connecting ||
            _autoConnecting.value) return
        tryAutoConnect()
    }

    // ── 公开操作 ───────────────────────────────────────────────────────────────

    fun updateUrl(url: String) { _wsUrl.value = url }

    fun connect() {
        manuallyDisconnected = false   // 主动连接，清除手动断开标志
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.connect(_wsUrl.value)
    }

    fun disconnect() {
        manuallyDisconnected = true    // 标记为用户主动断开，禁止自动重连
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.disconnect()
        clearHistory()
    }

    fun saveCurrentUrl() {
        val url = _wsUrl.value.trim()
        if (url.isNotEmpty()) urlRepo.addUrl(url)
    }

    fun removeUrl(url: String) { urlRepo.removeUrl(url) }

    fun connectTo(url: String) {
        manuallyDisconnected = false   // 主动选择链接，清除手动断开标志
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        _wsUrl.value = url
        wsClient.disconnect()
        clearHistory()
        wsClient.connect(url)
    }

    /** 图表页左右滑动：切换到已保存列表中的上一个/下一个链接 */
    fun switchToPrevUrl() {
        val urls = urlRepo.urls.value
        if (urls.size < 2) return
        val cur = _connectedUrl.value
        val idx = urls.indexOf(cur)
        val target = urls[if (idx <= 0) urls.lastIndex else idx - 1]
        connectTo(target)
    }

    fun switchToNextUrl() {
        val urls = urlRepo.urls.value
        if (urls.size < 2) return
        val cur = _connectedUrl.value
        val idx = urls.indexOf(cur)
        val target = urls[if (idx < 0 || idx >= urls.lastIndex) 0 else idx + 1]
        connectTo(target)
    }

    /** 保存/更新备注 */
    fun saveRemark(url: String, remark: String) { urlRepo.saveRemark(url, remark) }

    /** 获取指定 url 的备注 */
    fun getRemarkFor(url: String): String = urlRepo.getRemarkFor(url)

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
