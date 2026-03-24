package com.sysmon.monitor.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysmon.monitor.data.model.SystemMetrics
import com.sysmon.monitor.data.websocket.WsState
import com.sysmon.monitor.ui.components.*
import com.sysmon.monitor.ui.theme.*
import com.sysmon.monitor.viewmodel.MonitorViewModel
import kotlin.math.roundToInt

// ─── 页面枚举 ─────────────────────────────────────────────────────────────────
private enum class Page { CONNECT, CHART }

// ══════════════════════════════════════════════════════════════════════════════
// 根 Screen
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val wsState        by vm.wsState.collectAsStateWithLifecycle()
    val metrics        by vm.metrics.collectAsStateWithLifecycle()
    val cpuHistory     by vm.cpuHistory.collectAsStateWithLifecycle()
    val memHistory     by vm.memHistory.collectAsStateWithLifecycle()
    val netRxHistory   by vm.netRxHistory.collectAsStateWithLifecycle()
    val netTxHistory   by vm.netTxHistory.collectAsStateWithLifecycle()
    val wsUrl          by vm.wsUrl.collectAsStateWithLifecycle()
    val savedUrls      by vm.savedUrls.collectAsStateWithLifecycle()
    val autoConnecting by vm.autoConnecting.collectAsStateWithLifecycle()

    LaunchedEffect(wsState) {
        if (wsState is WsState.Connected) vm.saveCurrentUrl()
    }

    val currentPage = if (wsState is WsState.Connected) Page.CHART else Page.CONNECT

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState == Page.CHART)
                    (slideInHorizontally(tween(400)) { it } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(400)) { -it } + fadeOut(tween(200)))
                else
                    (slideInHorizontally(tween(400)) { -it } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(400)) { it } + fadeOut(tween(200)))
            },
            modifier = Modifier.fillMaxSize(),
            label = "page_transition"
        ) { page ->
            when (page) {
                Page.CONNECT -> ConnectPage(
                    wsUrl          = wsUrl,
                    wsState        = wsState,
                    savedUrls      = savedUrls,
                    autoConnecting = autoConnecting,
                    onUrlChange    = vm::updateUrl,
                    onConnect      = vm::connect,
                    onDisconnect   = vm::disconnect,
                    onConnectTo    = vm::connectTo,
                    onRemoveUrl    = vm::removeUrl,
                )
                Page.CHART -> ChartPage(
                    wsState      = wsState,
                    metrics      = metrics,
                    cpuHistory   = cpuHistory,
                    memHistory   = memHistory,
                    netRxHistory = netRxHistory,
                    netTxHistory = netTxHistory,
                    onDisconnect = vm::disconnect,
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 连接页
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConnectPage(
    wsUrl: String,
    wsState: WsState,
    savedUrls: List<String>,
    autoConnecting: Boolean,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectTo: (String) -> Unit,
    onRemoveUrl: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            ConnectPageHeader(wsState = wsState, autoConnecting = autoConnecting)
            ConnectionCard(
                wsUrl = wsUrl, wsState = wsState, autoConnecting = autoConnecting,
                onUrlChange = onUrlChange, onConnect = onConnect, onDisconnect = onDisconnect,
            )
            if (savedUrls.isNotEmpty()) {
                SavedUrlsCard(
                    urls = savedUrls, currentUrl = wsUrl, wsState = wsState,
                    onConnectTo = onConnectTo, onRemove = onRemoveUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConnectPageHeader(wsState: WsState, autoConnecting: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "SYSMON",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 6.sp
        )
        Spacer(Modifier.height(6.dp))
        val (dotColor, statusText) = when {
            autoConnecting                -> WarnOrange to "AUTO CONNECTING"
            wsState is WsState.Connecting -> WarnOrange to "CONNECTING"
            wsState is WsState.Error      -> DangerRed  to "CONNECTION FAILED"
            else                          -> TextMuted  to "OFFLINE"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(text = statusText, color = dotColor, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun ConnectionCard(
    wsUrl: String, wsState: WsState, autoConnecting: Boolean,
    onUrlChange: (String) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isConnected  = wsState is WsState.Connected
    val isBusy       = wsState is WsState.Connecting || autoConnecting
    val shape        = RoundedCornerShape(20.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(colors = listOf(BgCardAlt, BgCard)))
            .border(1.dp, Brush.verticalGradient(
                colors = listOf(NeonBlue.copy(alpha = 0.3f), NeonBlue.copy(alpha = 0.08f))
            ), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CardLabel(label = "CONNECTION", color = NeonBlue)

        OutlinedTextField(
            value = wsUrl, onValueChange = onUrlChange,
            enabled = !isConnected && !isBusy,
            placeholder = { Text("ws://192.168.x.x:9001", color = TextMuted, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonBlue, unfocusedBorderColor = BorderColor,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                disabledTextColor = TextSecondary, disabledBorderColor = BorderColor, cursorColor = NeonBlue,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            leadingIcon = { Icon(Icons.Default.Wifi, null,
                tint = if (isConnected) CpuGreen else TextMuted, modifier = Modifier.size(18.dp)) }
        )

        if (wsState is WsState.Error) {
            Text("⚠ ${wsState.message}", color = DangerRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Button(
            onClick = { if (isConnected) onDisconnect() else onConnect() },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) DangerRed.copy(alpha = 0.15f) else NeonBlueFade,
                contentColor   = if (isConnected) DangerRed else NeonBlue,
                disabledContainerColor = BorderColor, disabledContentColor = TextMuted
            ),
            border = BorderStroke(1.dp,
                if (isConnected) DangerRed.copy(alpha = 0.5f) else NeonBlue.copy(alpha = 0.5f))
        ) {
            when {
                isBusy -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = WarnOrange, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (autoConnecting) "AUTO CONNECTING..." else "CONNECTING...",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                isConnected -> {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DISCONNECT", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                else -> {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CONNECT", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SavedUrlsCard(
    urls: List<String>, currentUrl: String, wsState: WsState,
    onConnectTo: (String) -> Unit, onRemove: (String) -> Unit,
) {
    val isConnected = wsState is WsState.Connected
    val isBusy      = wsState is WsState.Connecting
    val shape       = RoundedCornerShape(20.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(colors = listOf(BgCardAlt, BgCard)))
            .border(1.dp, Brush.verticalGradient(
                colors = listOf(MemPurple.copy(alpha = 0.3f), MemPurple.copy(alpha = 0.08f))
            ), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            CardLabel(label = "SAVED  ${urls.size}/10", color = MemPurple)
            Text("点击快速连接", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        urls.forEach { url ->
            val isActive = url == currentUrl && isConnected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) MemPurple.copy(alpha = 0.12f) else BgSlate.copy(alpha = 0.4f))
                    .clickable(enabled = !isConnected && !isBusy) { onConnectTo(url) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(6.dp).background(
                    if (isActive) LiveGreen else TextMuted, CircleShape))
                Text(url, color = if (isActive) LiveGreen else TextSecondary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!isActive) {
                    Icon(Icons.Default.Close, "删除", tint = TextMuted,
                        modifier = Modifier.size(16.dp).clickable { onRemove(url) })
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 图表页
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChartPage(
    wsState: WsState,
    metrics: SystemMetrics?,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    netRxHistory: List<Double>,
    netTxHistory: List<Double>,
    onDisconnect: () -> Unit,
) {
    // 整页无 padding，各卡片自带内边距
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 列1：网速图（含顶部 Header 行）
        NetworkCard(
            rxKbps       = metrics?.netRxKbps ?: 0.0,
            txKbps       = metrics?.netTxKbps ?: 0.0,
            rxHistory    = netRxHistory,
            txHistory    = netTxHistory,
            onDisconnect = onDisconnect,
            modifier     = Modifier.weight(5f).fillMaxHeight()
        )

        // 列2：CPU（上）+ MEM（下），各占一半
        Column(
            modifier = Modifier.weight(3f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CpuCard(
                value    = metrics?.cpuUsagePercent ?: 0f,
                history  = cpuHistory,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            MemCard(
                value    = metrics?.memoryUsagePercent ?: 0f,
                usedMb   = metrics?.memoryUsedMb ?: 0L,
                totalMb  = metrics?.memoryTotalMb ?: 0L,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }

        // 列3：多核（有多核时显示）
        if ((metrics?.cpuPerCore?.size ?: 0) > 1) {
            CoresCard(
                cores    = metrics?.cpuPerCore ?: emptyList(),
                modifier = Modifier.weight(2.5f).fillMaxHeight()
            )
        }
    }
}

// ─── 网速卡片 ─────────────────────────────────────────────────────────────────
// 布局：顶部 Header 行（NETWORK 标签 + LIVE + DISC + 图例）
//       中间 面积图（weight(1f) 占满剩余）
//       底部 数值行（固定高度）

@Composable
private fun NetworkCard(
    rxKbps: Double, txKbps: Double,
    rxHistory: List<Double>, txHistory: List<Double>,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, accentColor = NetAmber, glowAlignment = GlowAlignment.TopRight) {
        // ── 顶部 Header 行：标签 + LIVE + DISC + 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // NETWORK 标签
            CardLabel(label = "NETWORK", color = NetAmber)

            // LIVE 胶囊
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LiveGreenBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(5.dp).background(LiveGreen, CircleShape))
                Text("LIVE", color = LiveGreen, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }

            // DISC 按钮
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC1A1F2E))
                    .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable { onDisconnect() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.LinkOff, null,
                    tint = DangerRed.copy(alpha = 0.8f), modifier = Modifier.size(11.dp))
                Text("DISC", color = DangerRed.copy(alpha = 0.8f), fontSize = 9.sp,
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.weight(1f))

            // 图例
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendItem(color = NetAmber, label = "RX", arrow = "↓")
                LegendItem(color = NetPink,  label = "TX", arrow = "↑")
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 面积图：占满剩余高度（weight(1f)）
        DualLineChart(
            rxData   = rxHistory,
            txData   = txHistory,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Spacer(Modifier.height(8.dp))

        // ── 底部数值行：固定高度，不随图表变化
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactSpeedRow(
                label = "↓", value = formatSpeedValue(rxKbps),
                unit = formatSpeedUnit(rxKbps), color = NetAmber,
                modifier = Modifier.weight(1f)
            )
            CompactSpeedRow(
                label = "↑", value = formatSpeedValue(txKbps),
                unit = formatSpeedUnit(txKbps), color = NetPink,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ─── CPU 仪表卡片 ─────────────────────────────────────────────────────────────
// 布局：左侧竖排标题 | 右侧仪表盘占满

@Composable
private fun CpuCard(
    value: Float, history: List<Float>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, accentColor = CpuGreen, glowAlignment = GlowAlignment.TopLeft) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧：竖排标题（旋转 -90°）
            VerticalLabel(label = "CPU", color = CpuGreen)

            // 右侧：仪表盘占满，强制正方形
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val gaugeSize = minOf(maxWidth, maxHeight)
                Box(
                    modifier = Modifier.size(gaugeSize),
                    contentAlignment = Alignment.Center
                ) {
                    GaugeChart(
                        value            = value,
                        color            = CpuGreen,
                        glowColor        = CpuGreenFade,
                        gradientEndColor = CpuCyan,
                        modifier         = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${value.roundToInt()}",
                            color = CpuGreen,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 30.sp
                        )
                        Text(text = "%", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── 内存仪表卡片 ─────────────────────────────────────────────────────────────
// 布局：左侧竖排标题 | 右侧仪表盘 + 底部进度条

@Composable
private fun MemCard(
    value: Float, usedMb: Long, totalMb: Long,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, accentColor = MemPurple, glowAlignment = GlowAlignment.TopRight) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧：竖排标题
            VerticalLabel(label = "MEM", color = MemPurple)

            // 右侧：仪表盘 + 进度条
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 仪表盘占满剩余高度
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val gaugeSize = minOf(maxWidth, maxHeight)
                    Box(
                        modifier = Modifier.size(gaugeSize),
                        contentAlignment = Alignment.Center
                    ) {
                        GaugeChart(
                            value            = value,
                            color            = MemPurple,
                            glowColor        = MemPurpleFade,
                            gradientEndColor = MemPink,
                            modifier         = Modifier.fillMaxSize()
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${value.roundToInt()}",
                                color = MemPurple,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 30.sp
                            )
                            Text(text = "%", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // 已用/总量文字
                if (totalMb > 0) {
                    Text(
                        text = "${formatMb(usedMb)} / ${formatMb(totalMb)}",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // 进度条
                MemProgressBar(
                    percent  = value,
                    modifier = Modifier.fillMaxWidth(),
                    height   = 7.dp
                )
            }
        }
    }
}

// ─── 多核卡片 ─────────────────────────────────────────────────────────────────

@Composable
private fun CoresCard(
    cores: List<Float>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, accentColor = CoreCyan, glowAlignment = GlowAlignment.TopRight) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardLabel(label = "CORES", color = CoreCyan)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CoreCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${cores.size}c",
                    color = CoreCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            cores.forEachIndexed { i, v ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "C$i",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(18.dp)
                    )
                    Box(modifier = Modifier.weight(1f).height(18.dp)) {
                        CoreBarChart(coreValues = listOf(v), modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        text = "${v.roundToInt()}%",
                        color = when {
                            v > 50f -> CoreAmber
                            v > 20f -> CoreBlue
                            else    -> TextSecondary
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(26.dp)
                    )
                }
            }
        }
    }
}

// ─── 竖排标题（左侧旋转文字）─────────────────────────────────────────────────

@Composable
private fun VerticalLabel(label: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(22.dp),
        contentAlignment = Alignment.Center
    ) {
        // 用 Column 竖向逐字排列，避免 rotate 导致的布局问题
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight()
        ) {
            // 色点
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.height(6.dp))
            // 逐字竖排
            label.forEach { ch ->
                Text(
                    text = ch.toString(),
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ─── 紧凑网速行 ───────────────────────────────────────────────────────────────

@Composable
private fun CompactSpeedRow(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
        Text(text = label, color = TextSecondary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        Text(
            text = value,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = unit,
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─── 图例项 ───────────────────────────────────────────────────────────────────

@Composable
private fun LegendItem(color: Color, label: String, arrow: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(text = arrow, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(text = label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

private fun formatSpeedValue(kbps: Double): String =
    if (kbps >= 1024.0) String.format("%.1f", kbps / 1024.0)
    else String.format("%.1f", kbps)

private fun formatSpeedUnit(kbps: Double): String =
    if (kbps >= 1024.0) "MB/s" else "KB/s"

private fun formatMb(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
