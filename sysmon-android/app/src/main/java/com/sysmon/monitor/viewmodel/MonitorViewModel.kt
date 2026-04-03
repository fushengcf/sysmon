package com.sysmon.monitor.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysmon.monitor.SysMonApp
import com.sysmon.monitor.data.model.SystemMetrics
import com.sysmon.monitor.data.websocket.WsState
import com.sysmon.monitor.service.SysMonForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel 只负责 UI 状态管理：
 *  · 直接读取 SysMonApp.wsClient 的 state/metrics（与 Service 共享同一实例）
 *  · 用户操作通过 startService + Action 转发给 SysMonForegroundService 执行
 *  · 不再持有重连循环，不依赖 Activity 生命周期
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val app      = application as SysMonApp
    private val wsClient = app.wsClient
    private val urlRepo  = app.urlRepo

    // ── 直接桥接 wsClient 的状态（与 Service 共享同一实例，状态天然一致）────────
    val wsState: StateFlow<WsState>        = wsClient.state
    val metrics: StateFlow<SystemMetrics?> = wsClient.metrics

    // ── 历史数据 ───────────────────────────────────────────────────────────────
    private val _cpuHistory   = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory   = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _netRxHistory = MutableStateFlow<List<Double>>(emptyList())
    val netRxHistory: StateFlow<List<Double>> = _netRxHistory.asStateFlow()

    private val _netTxHistory = MutableStateFlow<List<Double>>(emptyList())
    val netTxHistory: StateFlow<List<Double>> = _netTxHistory.asStateFlow()

    // ── 当前 URL 和连接列表 ────────────────────────────────────────────────────
    private val _wsUrl = MutableStateFlow("")
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    private val _connectedUrl = MutableStateFlow("")
    val connectedUrl: StateFlow<String> = _connectedUrl.asStateFlow()

    val savedUrls: StateFlow<List<String>>    = urlRepo.urls
    val savedRemarks: StateFlow<List<String>> = urlRepo.remarks

    // cookie：全局 WebSocket 握手时附加，持久化在 UrlRepository
    val cookie: StateFlow<String> = urlRepo.cookie

    // autoConnecting：Connecting 状态时为 true，供 UI 显示"连接中"
    private val _autoConnecting = MutableStateFlow(false)
    val autoConnecting: StateFlow<Boolean> = _autoConnecting.asStateFlow()

    companion object {
        private const val TAG = "SysMonVM"
    }

    init {
        // 监听指标，追加历史
        viewModelScope.launch {
            wsClient.metrics.collect { m ->
                m ?: return@collect
                _cpuHistory.value   = (_cpuHistory.value   + m.cpuUsagePercent).takeLast(60)
                _memHistory.value   = (_memHistory.value   + m.memoryUsagePercent).takeLast(60)
                _netRxHistory.value = (_netRxHistory.value + m.netRxKbps).takeLast(60)
                _netTxHistory.value = (_netTxHistory.value + m.netTxKbps).takeLast(60)
            }
        }

        // 监听连接状态，更新 UI 标志
        viewModelScope.launch {
            wsClient.state.collect { state ->
                Log.d(TAG, "UI state: $state")
                when (state) {
                    is WsState.Connected   -> {
                        _connectedUrl.value = _wsUrl.value
                        _autoConnecting.value = false
                    }
                    is WsState.Connecting  -> _autoConnecting.value = true
                    is WsState.Disconnected,
                    is WsState.Error       -> _autoConnecting.value = false
                    else                   -> {}
                }
            }
        }

        // 启动前台 Service（Service 负责实际连接和重连循环）
        ensureServiceRunning()
    }

    // ── 公开操作 ───────────────────────────────────────────────────────────────

    fun updateUrl(url: String) { _wsUrl.value = url }

    /** 用户手动点击连接 */
    fun connect() {
        val url = _wsUrl.value
        sendToService(SysMonForegroundService.ACTION_CONNECT, url, urlRepo.cookie.value)
    }

    /** 用户手动断开，不再自动重连 */
    fun disconnect() {
        sendToService(SysMonForegroundService.ACTION_DISCONNECT, null)
        clearHistory()
    }

    /** 取消自动连接（不标记 manually，后续可继续） */
    fun cancelAutoConnect() {
        sendToService(SysMonForegroundService.ACTION_CANCEL, null)
    }

    /** 切换到指定 URL（静默换连，不触发熄屏逻辑） */
    fun connectTo(url: String) {
        _wsUrl.value = url
        _connectedUrl.value = url
        clearHistory()
        sendToService(SysMonForegroundService.ACTION_RECONNECT, url, urlRepo.cookie.value)
    }

    /** 保存/更新 Cookie */
    fun saveCookie(cookie: String) { urlRepo.saveCookie(cookie) }

    /**
     * onResume 时调用。
     * Service 已在后台自主管理重连循环，这里只做一次轻量检查：
     * 若 Service 不在运行则重新启动（异常重启恢复场景）。
     */
    fun retryAutoConnect() {
        ensureServiceRunning()
    }

    fun saveCurrentUrl() {
        val url = _wsUrl.value.trim()
        if (url.isNotEmpty()) urlRepo.addUrl(url)
    }

    fun removeUrl(url: String) { urlRepo.removeUrl(url) }

    fun switchToPrevUrl() {
        val urls = urlRepo.urls.value
        if (urls.size < 2) return
        val baseUrl = currentSwitchBaseUrl(urls)
        val idx = urls.indexOf(baseUrl)
        val target = urls[if (idx <= 0) urls.lastIndex else idx - 1]
        connectTo(target)
    }

    fun switchToNextUrl() {
        val urls = urlRepo.urls.value
        if (urls.size < 2) return
        val baseUrl = currentSwitchBaseUrl(urls)
        val idx = urls.indexOf(baseUrl)
        val target = urls[if (idx < 0 || idx >= urls.lastIndex) 0 else idx + 1]
        connectTo(target)
    }

    fun saveRemark(url: String, remark: String) { urlRepo.saveRemark(url, remark) }
    fun getRemarkFor(url: String): String = urlRepo.getRemarkFor(url)

    // ── 内部工具 ───────────────────────────────────────────────────────────────


    private fun currentSwitchBaseUrl(urls: List<String>): String {
        val pendingUrl = _wsUrl.value
        if (pendingUrl in urls) return pendingUrl

        val connectedUrl = _connectedUrl.value
        if (connectedUrl in urls) return connectedUrl

        return urls.first()
    }

    private fun ensureServiceRunning() {
        val ctx = getApplication<Application>()
        val intent = SysMonForegroundService.startIntent(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun sendToService(action: String, url: String?, cookie: String = "") {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SysMonForegroundService::class.java).apply {
            this.action = action
            url?.let { putExtra(SysMonForegroundService.EXTRA_URL, it) }
            if (cookie.isNotEmpty()) putExtra(SysMonForegroundService.EXTRA_COOKIE, cookie)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun clearHistory() {
        _cpuHistory.value   = emptyList()
        _memHistory.value   = emptyList()
        _netRxHistory.value = emptyList()
        _netTxHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时不断开连接，连接由 Service 持有
    }
}
