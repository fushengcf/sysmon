package com.sysmon.monitor

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sysmon.monitor.data.websocket.WsState
import com.sysmon.monitor.service.SysMonForegroundService
import com.sysmon.monitor.ui.screens.MonitorScreen
import com.sysmon.monitor.ui.theme.SysMonTheme
import com.sysmon.monitor.viewmodel.MonitorViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    /**
     * 屏幕控制广播接收器：
     *  - ACTION_SCREEN_OFF → 取消 FLAG_KEEP_SCREEN_ON，退到后台让屏幕正常超时熄灭
     *  - ACTION_SCREEN_ON  → 强制点亮屏幕并将 Activity 带回前台
     */
    private val screenControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SysMonForegroundService.ACTION_SCREEN_OFF -> applyScreenOff()
                SysMonForegroundService.ACTION_SCREEN_ON  -> applyScreenOn()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸式
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // 支持在锁屏上显示 Activity 并点亮屏幕（API 27+ 用方法，旧版用 Flag）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 注册屏幕控制广播（仅接收本应用内广播）
        registerScreenReceiver()

        // 监听连接状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.wsState.collect { state ->
                    when (state) {
                        is WsState.Connected -> {
                            // 横屏 + 常亮
                            requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            // 启动前台服务
                            val fgIntent = SysMonForegroundService.startIntent(this@MainActivity)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(fgIntent)
                            } else {
                                startService(fgIntent)
                            }
                        }
                        else -> {
                            // 断开/重连中：恢复自由旋转
                            // FLAG_KEEP_SCREEN_ON 的清除交由 applyScreenOff() 在全部失败后执行
                            requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        }
                    }
                }
            }
        }

        // 首次启动时请求忽略电池优化
        requestIgnoreBatteryOptimizations()

        setContent {
            SysMonTheme {
                MonitorScreen(vm = vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        vm.retryAutoConnect()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenControlReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 屏幕控制 ──────────────────────────────────────────────────────────────

    /**
     * 所有 WS 链接均失败 → 取消常亮标志，退到后台让屏幕正常超时熄灭。
     * 进程和 Service 的 PARTIAL_WAKE_LOCK 依然持有，CPU 不会休眠。
     */
    private fun applyScreenOff() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        moveTaskToBack(true)
    }

    /**
     * 任意 WS 链接重连成功 → 点亮屏幕并将界面带回前台。
     * 使用 PowerManager.FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 强制唤醒屏幕。
     * FULL_WAKE_LOCK 虽已废弃，但在 Android 15 上对于前台应用依然有效；
     * API 27+ 同时借助 setTurnScreenOn(true) + requestDismissKeyguard() 解锁。
     */
    @Suppress("DEPRECATION")
    private fun applyScreenOn() {
        // 1. 恢复常亮标志
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 用 FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 唤醒屏幕（短暂持有 2s）
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SysMon::ScreenWakeUp"
        )
        wl.acquire(2_000L)  // 2s 后自动释放，FLAG_KEEP_SCREEN_ON 接管

        // 3. API 27+ 请求解除锁屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }

        // 4. 将 Activity 带回前台
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    // ── 广播注册 ──────────────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(SysMonForegroundService.ACTION_SCREEN_OFF)
            addAction(SysMonForegroundService.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenControlReceiver, filter)
        }
    }

    // ── 系统栏隐藏 ────────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── 电池优化 ──────────────────────────────────────────────────────────────

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // 部分厂商 ROM 不支持此 Intent，静默忽略
        }
    }
}
