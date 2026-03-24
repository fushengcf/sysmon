package com.sysmon.monitor

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸式
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

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
                            // 启动前台服务，保持后台运行
                            startForegroundService(
                                SysMonForegroundService.startIntent(this@MainActivity)
                            )
                        }
                        else -> {
                            // 恢复自由旋转 + 取消常亮
                            requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }

        // 首次启动时请求忽略电池优化（静默引导，不强制）
        requestIgnoreBatteryOptimizations()

        setContent {
            SysMonTheme {
                MonitorScreen(vm = vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台时重新隐藏系统栏（防止从后台切回后状态栏重新出现）
        hideSystemBars()
        // 如果未连接则重新尝试自动连接列表
        vm.retryAutoConnect()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口重新获得焦点时（如弹窗关闭后）再次隐藏系统栏
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时不停止服务，让服务继续在后台保持连接
    }

    /**
     * 请求系统忽略本应用的电池优化。
     * Android 6+ 需要此权限才能在 Doze 模式下保持网络活跃。
     * 仅在尚未被豁免时弹出系统设置页面引导用户授权。
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return   // 已豁免，无需再次请求

        try {
            // 直接跳转到本应用的电池优化设置页（无需额外权限）
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // 部分厂商 ROM 不支持此 Intent，静默忽略
        }
    }
}
