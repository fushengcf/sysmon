package com.sysmon.monitor.ui.screens.cam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView
import com.sysmon.monitor.service.StreamForegroundService
import com.sysmon.monitor.ui.theme.*
import com.sysmon.monitor.viewmodel.CameraViewModel
import com.sysmon.monitor.viewmodel.StreamState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "CameraScreen"

private fun hasPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

/** Android 13+ 需要 POST_NOTIFICATIONS 运行时权限，否则前台服务通知无法显示 */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}

@Composable
fun CamCameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit = {}
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasPermissions(context)) }
    var hasRequested by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        permissionsGranted = allGranted
        if (!allGranted) {
            isPermanentlyDenied = results.entries
                .filter { !it.value }
                .none { (perm, _) ->
                    (context as? androidx.activity.ComponentActivity)
                        ?.shouldShowRequestPermissionRationale(perm) == true
                }
        }
    }

    // 通知权限 launcher（Android 13+，前台服务必需）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* 不管用户同不同意，都继续，只是通知可能不显示 */ }

    LaunchedEffect(Unit) {
        if (!permissionsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasRequested = true
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // 摄像头权限拿到后，再请求通知权限
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && needsNotificationPermission(context)) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (hasRequested && !permissionsGranted) {
        CyberPermissionDenied(isPermanentlyDenied) {
            hasRequested = true
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
        return
    }
    if (!permissionsGranted) return

    val lensFacing by viewModel.lensFacing.collectAsState()
    val streamState by viewModel.streamState.collectAsState()
    val audioEnabled by viewModel.audioEnabled.collectAsState()
    val streamUrl by viewModel.streamUrl.collectAsState()
    val errorMsg by viewModel.errorMsg.collectAsState()
    val stats by viewModel.stats.collectAsState()
    var showStats by remember { mutableStateOf(false) }

    LaunchedEffect(streamState) {
        when (streamState) {
            StreamState.STREAMING -> { showStats = true; startFg(context, streamUrl) }
            StreamState.IDLE, StreamState.DISCONNECTED -> {
                try { context.stopService(Intent(context, StreamForegroundService::class.java)) } catch (_: Exception) {}
            }
            else -> {}
        }
    }

    // 页面完全可见标志：进入动画结束后为 true，开始退出时立即置 false
    // 用于控制 CamScanOverlay 仅在页面完全可见时渲染，避免过渡期 Canvas 不断 invalidate
    var isFullyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 等待进入动画结束（进入 CAM_CAMERA 用 150ms 淡入，这里稍微多留 20ms 余量）
        kotlinx.coroutines.delay(170)
        isFullyVisible = true
    }
    DisposableEffect(Unit) {
        onDispose { isFullyVisible = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ---- camera preview ----
        // Surface 生命周期完全委托给 ViewModel，推流不因 Surface 销毁而中断
        AndroidView(
            factory = { ctx ->
                val glView = OpenGlView(ctx)
                glView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d(TAG, "surfaceCreated → delegate to ViewModel")
                        viewModel.onSurfaceCreated(glView)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        Log.d(TAG, "surfaceDestroyed → delegate to ViewModel")
                        viewModel.onSurfaceDestroyed()
                    }
                })
                glView
            },
            modifier = Modifier.fillMaxSize()
        )

        // scan overlay：仅在页面完全可见且推流中时渲染
        // 过渡动画期间（进入/退出）不渲染，避免 InfiniteTransition Canvas 持续驱动重绘，
        // 加重底层 OpenGlView Surface 销毁/重建期间的 GPU 竞争
        if (isFullyVisible && streamState == StreamState.STREAMING) { CamScanOverlay(Modifier.fillMaxSize()) }

        // ---- top bar ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeonCircleBtn(onBack, Icons.Default.ArrowBack, "返回", TextSecondary)
                CyberBadge(streamState)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonCircleBtn(onOpenPlayer, Icons.Default.OndemandVideo, "播放器", NeonBlue)
                NeonCircleBtn(onOpenSettings, Icons.Default.Settings, "设置", MemPurple)
            }
        }

        // ---- stats overlay ----
        AnimatedVisibility(
            visible = showStats && streamState == StreamState.STREAMING,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)
        ) { CyberStats(stats) }

        // ---- error toast ----
        AnimatedVisibility(
            visible = errorMsg.isNotBlank(), enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).padding(24.dp)
        ) {
            Box(
                Modifier
                    .background(Brush.horizontalGradient(listOf(DangerRed.copy(0.9f), DangerRed.copy(0.7f))), RoundedCornerShape(12.dp))
                    .border(1.dp, DangerRed, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(errorMsg, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ---- bottom controls ----
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f), Color.Black.copy(0.92f))))
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // stream url preview
            val noUrl = streamUrl.isBlank() || streamUrl == CameraViewModel.DEFAULT_URL
            Row(
                Modifier.fillMaxWidth()
                    .background(BgSlate.copy(0.6f), RoundedCornerShape(8.dp))
                    .border(0.5.dp, Brush.horizontalGradient(
                        if (noUrl) listOf(WarnOrange.copy(0.5f), WarnOrange.copy(0.2f))
                        else listOf(NeonBlue.copy(0.4f), MemPurple.copy(0.2f))
                    ), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (noUrl) Icons.Default.LinkOff else Icons.Default.Link, null,
                    tint = if (noUrl) WarnOrange else NeonBlue.copy(0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (noUrl) "未配置推流地址，请点击右上角设置" else streamUrl,
                    color = if (noUrl) WarnOrange else TextPrimary.copy(0.7f),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    modifier = Modifier.weight(1f), letterSpacing = 0.5.sp
                )
            }

            // control buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CtrlBtn(
                    if (audioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    if (audioEnabled) "MIC ON" else "MUTED",
                    if (audioEnabled) NeonBlue else TextSecondary, audioEnabled
                ) { viewModel.toggleAudio() }

                MainRecBtn(streamState, { viewModel.startStream() }, { viewModel.stopStream() })

                CtrlBtn(
                    Icons.Default.FlipCameraAndroid,
                    if (lensFacing == CameraHelper.Facing.BACK) "REAR" else "FRONT",
                    MemPurple, true
                ) { viewModel.toggleCamera() }
            }

            if (streamState == StreamState.STREAMING) {
                TextButton(onClick = { showStats = !showStats }) {
                    Text(
                        if (showStats) "[ HIDE STATS ]" else "[ SHOW STATS ]",
                        color = NeonBlue.copy(0.8f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
                    )
                }
                // ── 实时时间戳 ──
                LiveTimestamp()
            }
        }
    }
}

// ═══════════════════════════ UI Components ═══════════════════════════

/**
 * 推流扫描线 Overlay —— 轻量版
 * 去掉全屏网格线(每帧几十条 drawLine 极耗 GPU)，
 * 只保留一条扫描线 + 放慢动画(5s)降低重绘频率
 */
@Composable
private fun CamScanOverlay(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "scan")
    val y by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Reverse),
        label = "sy"
    )
    Canvas(modifier) {
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, NeonBlue.copy(0.15f), NeonBlue.copy(0.25f), NeonBlue.copy(0.15f), Color.Transparent)
            ),
            Offset(0f, size.height * y),
            Offset(size.width, size.height * y),
            2.dp.toPx()
        )
    }
}

@Composable
private fun CornerBrackets(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val l = 28.dp.toPx(); val w = 2.dp.toPx(); val p = 4.dp.toPx()
        drawLine(color, Offset(p, p), Offset(p + l, p), w)
        drawLine(color, Offset(p, p), Offset(p, p + l), w)
        drawLine(color, Offset(size.width - p, p), Offset(size.width - p - l, p), w)
        drawLine(color, Offset(size.width - p, p), Offset(size.width - p, p + l), w)
        drawLine(color, Offset(p, size.height - p), Offset(p + l, size.height - p), w)
        drawLine(color, Offset(p, size.height - p), Offset(p, size.height - p - l), w)
        drawLine(color, Offset(size.width - p, size.height - p), Offset(size.width - p - l, size.height - p), w)
        drawLine(color, Offset(size.width - p, size.height - p), Offset(size.width - p, size.height - p - l), w)
    }
}

@Composable
private fun MainRecBtn(state: StreamState, onStart: () -> Unit, onStop: () -> Unit) {
    val streaming = state == StreamState.STREAMING
    val connecting = state == StreamState.CONNECTING
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
        if (connecting) {
            val inf = rememberInfiniteTransition(label = "con")
            val r by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "cr")
            Canvas(Modifier.size(64.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                rotate(r, c) {
                    drawArc(Brush.sweepGradient(listOf(NeonBlue, MemPurple, NeonBlue), c), -90f, 270f, false,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                }
                drawCircle(NeonBlue.copy(0.5f), 4.dp.toPx(), c)
            }
        } else {
            val sc by animateFloatAsState(if (streaming) 1.05f else 1f, label = "bs")
            if (streaming) {
                val inf = rememberInfiniteTransition(label = "glow")
                val ga by inf.animateFloat(0.2f, 0.6f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse), label = "ga")
                Box(Modifier.size(72.dp).border(2.dp, Brush.sweepGradient(listOf(DangerRed.copy(ga), MemPurple.copy(ga), DangerRed.copy(ga))), CircleShape))
            }
            FloatingActionButton(
                onClick = { if (streaming) onStop() else onStart() },
                modifier = Modifier.size(60.dp).scale(sc).then(
                    if (!streaming) Modifier.border(2.dp, Brush.sweepGradient(listOf(NeonBlue, MemPurple, NeonBlue)), CircleShape) else Modifier
                ),
                containerColor = when (state) {
                    StreamState.STREAMING -> Color(0xFFCC0000)
                    StreamState.ERROR -> DangerRed.copy(0.7f)
                    else -> Color.Black.copy(0.8f)
                },
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp)
            ) {
                Icon(
                    if (streaming) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    if (streaming) "停止推流" else "开始推流",
                    tint = if (streaming) Color.White else DangerRed,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun CtrlBtn(icon: ImageVector, label: String, color: Color, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
                .background(Color.Black.copy(0.5f), CircleShape)
                .border(1.dp, if (active) color.copy(0.6f) else TextSecondary.copy(0.3f), CircleShape)
        ) { Icon(icon, label, tint = color, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(label, color = color.copy(0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

@Composable
private fun CyberBadge(state: StreamState) {
    val (label, clr, pulse) = when (state) {
        StreamState.STREAMING    -> Triple("● REC", DangerRed, true)
        StreamState.CONNECTING   -> Triple("LINKING…", WarnOrange, true)
        StreamState.ERROR        -> Triple("ERROR", DangerRed, false)
        StreamState.DISCONNECTED -> Triple("OFFLINE", TextSecondary, false)
        StreamState.IDLE         -> Triple("STANDBY", NeonBlue, false)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(0.65f), RoundedCornerShape(20.dp))
            .border(1.dp, clr.copy(0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (pulse) {
            val inf = rememberInfiniteTransition(label = "bp")
            val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "ba")
            Box(Modifier.size(8.dp).background(clr.copy(a), CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = clr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    }
}

@Composable
private fun NeonCircleBtn(onClick: () -> Unit, icon: ImageVector, cd: String, borderColor: Color) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
            .background(Color.Black.copy(0.55f), CircleShape)
            .border(1.dp, borderColor.copy(0.5f), CircleShape)
    ) { Icon(icon, cd, tint = borderColor, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun CyberStats(stats: CameraViewModel.Stats) {
    fun fmt(s: Long): String { val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60; return if (h > 0) "%02d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss) }
    Row(
        Modifier.background(Color.Black.copy(0.7f), RoundedCornerShape(10.dp))
            .border(0.5.dp, Brush.horizontalGradient(listOf(NeonBlue.copy(0.5f), MemPurple.copy(0.3f))), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCell(Icons.Default.Speed, "BITRATE", "${stats.bitrate / 1000} kbps")
        StatCell(Icons.Default.Timer, "ELAPSED", fmt(stats.duration))
    }
}

@Composable
private fun StatCell(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = NeonBlue.copy(0.6f), modifier = Modifier.size(14.dp))
        Column {
            Text(label, color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        }
    }
}

// ═══════════════════════════ Permission denied ═══════════════════════════

@Composable
private fun CyberPermissionDenied(isPermanentlyDenied: Boolean, onRequest: () -> Unit) {
    val context = LocalContext.current
    val inf = rememberInfiniteTransition(label = "perm_anim")
    val pulse by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse), label = "pp")

    Box(Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
        // corner brackets
        CornerBrackets(Modifier.fillMaxSize().padding(20.dp), NeonBlue.copy(0.2f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Box(
                Modifier.size(80.dp)
                    .border(2.dp, NeonBlue.copy(pulse), CircleShape)
                    .background(BgSlate, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CameraAlt, null, tint = NeonBlue.copy(pulse), modifier = Modifier.size(40.dp))
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "ACCESS REQUIRED",
                color = NeonBlue,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )
            Text(
                if (isPermanentlyDenied) "权限已被永久拒绝\n请前往系统设置手动开启"
                else "需要摄像头和麦克风权限才能推流\n请授予相关权限",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(8.dp))

            if (isPermanentlyDenied) {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                ) {
                    Text("GO TO SETTINGS", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            } else {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                ) {
                    Text("GRANT ACCESS", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

// ═══════════════════════════ Foreground service ═══════════════════════════

/** 实时时间戳：每秒刷新，显示 yyyy-MM-dd HH:mm:ss */
@Composable
private fun LiveTimestamp() {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()) }
    var now by remember { mutableStateOf(formatter.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            now = formatter.format(Date())
            delay(1000L)
        }
    }

    Text(
        text = now,
        color = TextSecondary.copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.5.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun startFg(context: Context, url: String) {
    try {
        val intent = Intent(context, StreamForegroundService::class.java).apply {
            action = StreamForegroundService.ACTION_START
            putExtra("stream_url", url)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        Log.e(TAG, "startForegroundService error: ${e.message}", e)
    }
}
