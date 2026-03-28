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
import android.util.Log
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
     *  - ACTION_SCREEN_OFF → 退到后台让屏幕熄灭
     *  - ACTION_SCREEN_ON  → 强制点亮屏幕并将 Activity 带回前台
     */
    private val screenControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("SysMonMain", "收到广播: ${intent?.action}")
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

        // 监听连接状态：仅控制屏幕常亮和旋转方向，不再干预 Service 生命周期
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.wsState.collect { state ->
                    when (state) {
                        is WsState.Connected -> {
                            // 横屏 + 常亮
                            requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        else -> {
                            // 断开/重连中：恢复自由旋转
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

    override fun onPause() {
        super.onPause()
        Log.d("SysMonMain", "onPause()")
    }

    override fun onStop() {
        super.onStop()
        Log.d("SysMonMain", "onStop()")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(screenControlReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    // ── 屏幕控制 ──────────────────────────────────────────────────────────────

    /**
     * 断线 → 清除常亮标志，退到后台，屏幕立刻熄灭。
     * 先关闭 showWhenLocked / turnScreenOn（否则系统会阻止 moveTaskToBack），
     * 再退到后台，屏幕失去前台 Activity 的 FLAG_KEEP_SCREEN_ON 后立即熄灭。
     */
    private fun applyScreenOff() {
        Log.d("SysMonMain", "applyScreenOff() called", Exception("caller trace"))
        // 1. 清除常亮标志
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 关掉锁屏覆盖属性，否则系统拒绝将该 Activity 移到后台
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 3. 退到后台 → 屏幕立刻熄灭
        moveTaskToBack(true)
    }

    /**
     * 重连成功 → 恢复锁屏覆盖属性，点亮屏幕并将界面带回前台。
     */
    @Suppress("DEPRECATION")
    private fun applyScreenOn() {
        Log.d("SysMonMain", "applyScreenOn() called")
        // 1. 恢复锁屏覆盖属性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 2. 恢复常亮标志
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 3. 用 FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 唤醒屏幕（短暂持有 2s）
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SysMon::ScreenWakeUp"
        )
        wl.acquire(2_000L)

        // 4. API 27+ 请求解除锁屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }

        // 5. 将 Activity 带回前台（只用 REORDER_TO_FRONT，不创建新实例）
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    // ── 广播注册 ──────────────────────────────────────────────────────────────

    private var receiverRegistered = false

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SysMonForegroundService.ACTION_SCREEN_OFF)
            addAction(SysMonForegroundService.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenControlReceiver, filter)
        }
        receiverRegistered = true
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
