package com.sysmon.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sysmon.monitor.MainActivity
import com.sysmon.monitor.R

/**
 * 前台服务：保持应用在后台持续运行，维持 WebSocket 长连接。
 *
 * 保活策略（多层叠加）：
 *  1. startForeground() 显示持久通知 → 系统不会轻易 OOM Kill 前台服务
 *  2. PARTIAL_WAKE_LOCK → 防止 CPU 进入深度休眠导致 WebSocket 断连
 *  3. START_STICKY → 被系统杀死后自动重启（系统资源充足时）
 *  4. onTaskRemoved() 重启自身 → 用户划掉任务卡片后也能恢复
 *  5. 通知优先级 PRIORITY_MAX + 持久化 → 减少被系统降级的概率
 */
class SysMonForegroundService : Service() {

    companion object {
        const val CHANNEL_ID      = "sysmon_fg_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.sysmon.monitor.START_FG"
        const val ACTION_STOP     = "com.sysmon.monitor.STOP_FG"

        fun startIntent(context: Context) =
            Intent(context, SysMonForegroundService::class.java).apply {
                action = ACTION_START
            }

        fun stopIntent(context: Context) =
            Intent(context, SysMonForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                // STOP_FOREGROUND_REMOVE 是 API 24+，低版本用 ServiceCompat
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 立即进入前台，避免 ANR
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
            }
        }
        // START_STICKY：被系统杀死后自动重启，intent 为 null
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务划掉 App 时触发。
     * 重新发送 startIntent 让服务在短暂停止后自动重启。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 重新启动服务（延迟 1s 避免系统节流）
        val restartIntent = startIntent(applicationContext)
        startService(restartIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        // NotificationChannel 是 API 26+，低版本无需创建
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SysMon 后台监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 WebSocket 连接，实时推送系统指标"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
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
            // FOREGROUND_SERVICE_IMMEDIATE 是 API 31+，低版本不设置也没有影响
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
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SysMon::WsKeepAlive"
        ).also {
            // 无限期持有，直到手动释放（服务停止时释放）
            it.acquire(0L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }
}
