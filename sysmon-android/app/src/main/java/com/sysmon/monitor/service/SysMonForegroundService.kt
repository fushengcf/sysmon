package com.sysmon.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sysmon.monitor.MainActivity
import com.sysmon.monitor.R
import com.sysmon.monitor.SysMonApp
import com.sysmon.monitor.data.websocket.WsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：
 *  · 持有 PARTIAL_WAKE_LOCK，CPU 不休眠
 *  · 在 serviceScope（独立协程，不受 Activity 生命周期影响）里运行重连循环
 *  · 连接成功 → 发亮屏广播；断线 → 发熄屏广播 + 重启重连循环
 */
class SysMonForegroundService : Service() {

    companion object {
        private const val TAG = "SysMonFg"

        const val CHANNEL_ID      = "sysmon_fg_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START      = "com.sysmon.monitor.START_FG"
        const val ACTION_STOP       = "com.sysmon.monitor.STOP_FG"
        const val ACTION_CONNECT    = "com.sysmon.monitor.CONNECT"
        /** 切换 URL：静默关闭旧连接再重连，不触发断线熄屏逻辑 */
        const val ACTION_RECONNECT  = "com.sysmon.monitor.RECONNECT"
        const val ACTION_DISCONNECT = "com.sysmon.monitor.DISCONNECT"
        const val ACTION_CANCEL     = "com.sysmon.monitor.CANCEL"

        const val ACTION_SCREEN_OFF = "com.sysmon.monitor.SCREEN_OFF"
        const val ACTION_SCREEN_ON  = "com.sysmon.monitor.SCREEN_ON"

        const val EXTRA_URL = "url"

        private const val CONNECT_TIMEOUT    = 2_000L
        private const val RECONNECT_INTERVAL = 30_000L

        fun startIntent(context: Context) =
            Intent(context, SysMonForegroundService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, SysMonForegroundService::class.java).apply { action = ACTION_STOP }
    }

    // 独立协程，不依赖任何 Activity/ViewModel 生命周期
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectJob: Job? = null
    private var stateObserveJob: Job? = null

    private val app get() = application as SysMonApp
    private val wsClient get() = app.wsClient
    private val urlRepo  get() = app.urlRepo

    // 状态标志（AtomicBoolean 保证多协程并发安全）
    private val manuallyDisconnected = AtomicBoolean(false)
    private val hasEverConnected     = AtomicBoolean(false)
    // isScreenOff：用 compareAndSet 确保熄屏/亮屏广播各只发一次
    private val isScreenOff          = AtomicBoolean(false)
    // 用户主动点击「连接」按钮触发的连接（非断线重连循环）
    // 失败时不循环重试、不熄屏，直接停在失败状态供用户重新操作
    private val isManualConnect      = AtomicBoolean(false)
    // 上次连接成功的时间戳，用于过滤重连循环里残留的 OkHttp Error 回调
    @Volatile private var lastConnectedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 监听 WS 状态：驱动熄屏/亮屏广播 + 重连循环
        stateObserveJob = serviceScope.launch {
            wsClient.state.collect { state ->
                Log.d(TAG, "状态→$state  hasEver=${hasEverConnected.get()} manually=${manuallyDisconnected.get()}")
                when (state) {
                    is WsState.Connected -> {
                        // isScreenOff CAS: true→false，只有第一个成功的才发亮屏广播
                        if (hasEverConnected.get() && isScreenOff.compareAndSet(true, false)) {
                            Log.d(TAG, "重连成功 → broadcastScreenOn")
                            broadcastScreenOn()
                        }
                        hasEverConnected.set(true)
                        isManualConnect.set(false)   // 连接成功，清除手动连接标志
                        lastConnectedAt = System.currentTimeMillis()
                        reconnectJob?.cancel()
                    }
                    is WsState.Disconnected, is WsState.Error -> {
                        val manual = isManualConnect.get()
                        val hasEver = hasEverConnected.get()
                        val mDis = manuallyDisconnected.get()
                        val timeSince = System.currentTimeMillis() - lastConnectedAt
                        Log.d(TAG, "断线/失败: state=$state manual=$manual hasEver=$hasEver mDis=$mDis timeSince=${timeSince}ms isScreenOff=${isScreenOff.get()}")
                        // 用户手动点击「连接」失败 → 不重试、不熄屏，直接停在失败状态
                        if (isManualConnect.compareAndSet(true, false)) {
                            Log.d(TAG, "手动连接失败，不重试")
                            return@collect
                        }
                        // 只有距上次成功连接超过 2s 才视为"真正断线"
                        // （过滤重连循环里其他 URL 的残留 OkHttp 回调）
                        val stableConnection = System.currentTimeMillis() - lastConnectedAt > 2_000L
                        if (hasEverConnected.get() && !manuallyDisconnected.get() && stableConnection) {
                            // isScreenOff CAS: false→true，只有第一次断线才发熄屏广播
                            if (isScreenOff.compareAndSet(false, true)) {
                                Log.d(TAG, "断线(稳定) → broadcastScreenOff + 启动重连")
                                broadcastScreenOff()
                            }
                            startReconnectLoop()
                        } else if (hasEverConnected.get() && !manuallyDisconnected.get() && !stableConnection) {
                            Log.d(TAG, "断线(冷却期内，忽略) stateScreenOff=${isScreenOff.get()}")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manuallyDisconnected.set(true)
                reconnectJob?.cancel()
                wsClient.disconnect()
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_STICKY
                manuallyDisconnected.set(false)
                reconnectJob?.cancel()
                isScreenOff.set(false)
                // 先设标志，再用 reconnect() 静默切换：
                // reconnect() 不触发 onClosed/Disconnected 回调，
                // 不会提前消费掉 isManualConnect 标志
                isManualConnect.set(true)
                wsClient.reconnect(url)
            }
            ACTION_RECONNECT -> {
                // 用户主动切换 URL：静默换连，不触发断线逻辑
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_STICKY
                manuallyDisconnected.set(false)
                reconnectJob?.cancel()
                wsClient.reconnect(url)
            }
            ACTION_DISCONNECT -> {
                manuallyDisconnected.set(true)
                reconnectJob?.cancel()
                wsClient.disconnect()
            }
            ACTION_CANCEL -> {
                reconnectJob?.cancel()
                wsClient.disconnect()
            }
            else -> {
                // ACTION_START 或其他：启动前台服务并开始自动连接
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                acquireWakeLock()
                // 如果没有活跃连接且没在重连，则启动首次自动连接
                if (wsClient.state.value !is WsState.Connected &&
                    wsClient.state.value !is WsState.Connecting &&
                    reconnectJob?.isActive != true) {
                    startReconnectLoop()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startService(startIntent(applicationContext))
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        stateObserveJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── 重连循环（serviceScope，独立于 Activity 生命周期）──────────────────────

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        val urls = urlRepo.urls.value
        if (urls.isEmpty()) return
        if (wsClient.state.value is WsState.Connected) return

        val isReconnect = hasEverConnected.get()

        reconnectJob = serviceScope.launch {
            outer@ while (!manuallyDisconnected.get()) {
                val roundStart = System.currentTimeMillis()
                val currentUrls = urlRepo.urls.value
                if (currentUrls.isEmpty()) break

                for (url in currentUrls) {
                    if (manuallyDisconnected.get()) break@outer
                    if (wsClient.state.value is WsState.Connected) break@outer

                    Log.d(TAG, "尝试连接: $url")
                    wsClient.connect(url)

                    val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT
                    while (System.currentTimeMillis() < deadline) {
                        val s = wsClient.state.value
                        if (s is WsState.Connected) break
                        if (s is WsState.Error || s is WsState.Disconnected) break
                        delay(200)
                    }

                    if (wsClient.state.value is WsState.Connected) break@outer

                    wsClient.disconnect()
                    delay(300)
                }

                if (wsClient.state.value is WsState.Connected) break@outer

                if (isReconnect) {
                    val elapsed = System.currentTimeMillis() - roundStart
                    val remaining = RECONNECT_INTERVAL - elapsed
                    Log.d(TAG, "本轮失败，等待 ${remaining.coerceAtLeast(0)}ms")
                    if (remaining > 0) {
                        var waited = 0L
                        while (waited < remaining && !manuallyDisconnected.get()) {
                            delay(1_000)
                            waited += 1_000
                        }
                    }
                } else {
                    Log.d(TAG, "首次启动未连接，停止自动重连")
                    break@outer
                }
            }
        }
    }

    // ── 广播 ──────────────────────────────────────────────────────────────────

    private fun broadcastScreenOff() {
        Log.d(TAG, "broadcastScreenOff()")
        sendBroadcast(Intent(ACTION_SCREEN_OFF).apply { setPackage(packageName) })
    }

    private fun broadcastScreenOn() {
        Log.d(TAG, "broadcastScreenOn()")
        sendBroadcast(Intent(ACTION_SCREEN_ON).apply { setPackage(packageName) })
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SysMon 后台监控", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 WebSocket 连接，实时推送系统指标"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SysMon 运行中")
            .setContentText("正在监控系统指标，保持后台连接")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SysMon::WsKeepAlive")
            .also { it.acquire(0L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }
}
