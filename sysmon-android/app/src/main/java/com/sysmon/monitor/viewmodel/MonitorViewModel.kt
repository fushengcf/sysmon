package com.sysmon.monitor.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysmon.monitor.data.model.SystemMetrics
import com.sysmon.monitor.data.repository.UrlRepository
import com.sysmon.monitor.data.websocket.SysMonWebSocket
import com.sysmon.monitor.data.websocket.WsState
import com.sysmon.monitor.service.SysMonForegroundService
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

    // 重连任务：唯一实例，所有重连逻辑都走这个 Job
    private var autoConnectJob: Job? = null

    // 用户主动断开标志：为 true 时禁止自动重连，直到用户主动发起连接
    private var manuallyDisconnected = false

    // 是否曾经成功连接过：只有连接成功过后断线，才触发熄屏逻辑
    // 首次启动找不到连接时不熄屏（避免看起来像闪退）
    private var hasEverConnected = false

    companion object {
        private const val MAX_HISTORY          = 60
        private const val AUTO_CONNECT_TIMEOUT = 2_000L   // 单个 URL 连接超时 2s
        private const val RECONNECT_INTERVAL   = 30_000L  // 全部失败后 30s 重试一次
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

        // 监听连接状态
        viewModelScope.launch {
            wsClient.state.collect { state ->
                when (state) {
                    is WsState.Connected -> {
                        _connectedUrl.value = _wsUrl.value
                        // 曾断线后重连成功 → 亮屏（首次连接屏幕本来就是亮的，不需要）
                        if (hasEverConnected) {
                            broadcastScreenOn()
                        }
                        hasEverConnected = true
                    }
                    is WsState.Disconnected, is WsState.Error -> {
                        // 只有曾经连接成功过、且不是用户主动断开、且当前没有重连任务在跑
                        // 才触发断线重连循环
                        if (hasEverConnected && !manuallyDisconnected && !_autoConnecting.value) {
                            startReconnectLoop()
                        }
                    }
                    else -> {}
                }
            }
        }

        // App 启动时尝试自动连接
        tryAutoConnect()
    }

    // ── 重连主循环 ─────────────────────────────────────────────────────────────
    // 规则：
    //   1. 遍历所有已保存链接，每条最多等待 AUTO_CONNECT_TIMEOUT
    //   2. 若其中任意一条成功 → 退出循环；若是断线重连（hasEverConnected==true）则发亮屏广播
    //   3. 若全部失败：
    //      - 断线重连（hasEverConnected==true）→ 发熄屏广播，等 30s 再重试
    //      - 首次启动未连接过（hasEverConnected==false）→ 不熄屏，直接停止（界面留在前台供用户操作）
    //   4. manuallyDisconnected==true 则立刻停止

    private fun startReconnectLoop() {
        // 防止重复启动
        if (_autoConnecting.value) return
        val urls = urlRepo.urls.value
        if (urls.isEmpty()) return
        if (wsClient.state.value is WsState.Connected) return

        // 记录进入循环时是否已曾连接过（断线重连 vs 首次启动）
        val isReconnect = hasEverConnected

        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            _autoConnecting.value = true

            outer@ while (!manuallyDisconnected) {
                val currentUrls = urlRepo.urls.value
                if (currentUrls.isEmpty()) break

                for (url in currentUrls) {
                    if (manuallyDisconnected) break@outer
                    if (wsClient.state.value is WsState.Connected) break@outer

                    _wsUrl.value = url
                    wsClient.connect(url)

                    // 等待连接结果（最多 AUTO_CONNECT_TIMEOUT）
                    val deadline = System.currentTimeMillis() + AUTO_CONNECT_TIMEOUT
                    while (System.currentTimeMillis() < deadline) {
                        val s = wsClient.state.value
                        if (s is WsState.Connected) break
                        if (s is WsState.Error || s is WsState.Disconnected) break
                        delay(200)
                    }

                    if (wsClient.state.value is WsState.Connected) break@outer

                    // 当前 URL 失败，断开后尝试下一个
                    wsClient.disconnect()
                    delay(300)
                }

                if (wsClient.state.value is WsState.Connected) break@outer

                // ── 所有链接均失败 ────────────────────────────────────────────
                if (isReconnect) {
                    // 断线重连失败 → 熄屏退后台，30s 后继续重试
                    broadcastScreenOff()
                    var waited = 0L
                    while (waited < RECONNECT_INTERVAL && !manuallyDisconnected) {
                        delay(1_000)
                        waited += 1_000
                    }
                } else {
                    // 首次启动未连接过 → 不熄屏，停止自动尝试，界面留在前台
                    break@outer
                }
            }

            _autoConnecting.value = false
        }
    }

    // ── 自动连接（首次启动 / onResume 时调用） ─────────────────────────────────

    private fun tryAutoConnect() {
        if (urlRepo.urls.value.isEmpty()) return
        if (wsClient.state.value is WsState.Connected) return
        startReconnectLoop()
    }

    fun retryAutoConnect() {
        if (manuallyDisconnected) return
        if (wsClient.state.value is WsState.Connected ||
            wsClient.state.value is WsState.Connecting ||
            _autoConnecting.value) return
        tryAutoConnect()
    }

    // ── 公开操作 ───────────────────────────────────────────────────────────────

    fun updateUrl(url: String) { _wsUrl.value = url }

    fun connect() {
        manuallyDisconnected = false
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.connect(_wsUrl.value)
    }

    fun disconnect() {
        manuallyDisconnected = true   // 标记手动断开，永不自动重连
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.disconnect()
        clearHistory()
    }

    /** 取消正在进行的自动连接（不标记 manuallyDisconnected，后续可继续自动重连） */
    fun cancelAutoConnect() {
        autoConnectJob?.cancel()
        _autoConnecting.value = false
        wsClient.disconnect()
    }

    fun saveCurrentUrl() {
        val url = _wsUrl.value.trim()
        if (url.isNotEmpty()) urlRepo.addUrl(url)
    }

    fun removeUrl(url: String) { urlRepo.removeUrl(url) }

    fun connectTo(url: String) {
        manuallyDisconnected = false
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

    // ── 屏幕控制广播 ──────────────────────────────────────────────────────────

    private fun broadcastScreenOff() {
        val ctx = getApplication<Application>()
        ctx.sendBroadcast(Intent(SysMonForegroundService.ACTION_SCREEN_OFF).apply {
            setPackage(ctx.packageName)
        })
    }

    private fun broadcastScreenOn() {
        val ctx = getApplication<Application>()
        ctx.sendBroadcast(Intent(SysMonForegroundService.ACTION_SCREEN_ON).apply {
            setPackage(ctx.packageName)
        })
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}
