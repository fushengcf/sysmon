package com.sysmon.monitor.ui.screens
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val savedRemarks   by vm.savedRemarks.collectAsStateWithLifecycle()
    val autoConnecting by vm.autoConnecting.collectAsStateWithLifecycle()
    val connectedUrl   by vm.connectedUrl.collectAsStateWithLifecycle()
    val cookie         by vm.cookie.collectAsStateWithLifecycle()

    LaunchedEffect(wsState) {
        if (wsState is WsState.Connected) vm.saveCurrentUrl()
    }

    val currentPage = if (wsState is WsState.Connected) Page.CHART else Page.CONNECT

    val rs = rememberResponsiveSize()

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
                    wsUrl              = wsUrl,
                    wsState            = wsState,
                    savedUrls          = savedUrls,
                    savedRemarks       = savedRemarks,
                    autoConnecting     = autoConnecting,
                    cookie             = cookie,
                    onUrlChange        = vm::updateUrl,
                    onConnect          = vm::connect,
                    onDisconnect       = vm::disconnect,
                    onCancelConnect    = vm::cancelAutoConnect,
                    onConnectTo        = vm::connectTo,
                    onRemoveUrl        = vm::removeUrl,
                    onSaveRemark       = vm::saveRemark,
                    onSaveCookie       = vm::saveCookie,
                )
                Page.CHART -> ChartPage(
                    wsState      = wsState,
                    metrics      = metrics,
                    cpuHistory   = cpuHistory,
                    memHistory   = memHistory,
                    netRxHistory = netRxHistory,
                    netTxHistory = netTxHistory,
                    connectedUrl = connectedUrl,
                    savedUrls    = savedUrls,
                    savedRemarks = savedRemarks,
                    onDisconnect = vm::disconnect,
                    onSwipePrev  = vm::switchToPrevUrl,
                    onSwipeNext  = vm::switchToNextUrl,
                    rs           = rs,
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
    savedRemarks: List<String>,
    autoConnecting: Boolean,
    cookie: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelConnect: () -> Unit,
    onConnectTo: (String) -> Unit,
    onRemoveUrl: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
    onSaveCookie: (String) -> Unit,
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
                cookie = cookie,
                onUrlChange = onUrlChange, onConnect = onConnect, onDisconnect = onDisconnect,
                onCancelConnect = onCancelConnect, onSaveCookie = onSaveCookie,
            )
            if (savedUrls.isNotEmpty()) {
                SavedUrlsCard(
                    urls         = savedUrls,
                    remarks      = savedRemarks,
                    currentUrl   = wsUrl,
                    wsState      = wsState,
                    onConnectTo  = onConnectTo,
                    onRemove     = onRemoveUrl,
                    onSaveRemark = onSaveRemark,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConnectPageHeader(wsState: WsState, autoConnecting: Boolean) {
    val rs = rememberResponsiveSize()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "SYSMON",
            color = Color.White,
            fontSize = rs.titleFontSize(),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (6 * rs.scaleFactor).sp
        )
        Spacer(Modifier.height(rs.itemSpacing()))
        val (dotColor, statusText) = when {
            autoConnecting                -> WarnOrange to "AUTO CONNECTING"
            wsState is WsState.Connecting -> WarnOrange to "CONNECTING"
            wsState is WsState.Error      -> DangerRed  to "CONNECTION FAILED"
            else                          -> TextMuted  to "OFFLINE"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing())
        ) {
            Box(modifier = Modifier.size(rs.dotSize()).background(dotColor, CircleShape))
            Text(text = statusText, color = dotColor, fontSize = rs.smallFontSize(),
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun ConnectionCard(
    wsUrl: String, wsState: WsState, autoConnecting: Boolean,
    cookie: String,
    onUrlChange: (String) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
    onCancelConnect: () -> Unit, onSaveCookie: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isConnected  = wsState is WsState.Connected
    val isBusy       = wsState is WsState.Connecting || autoConnecting
    val shape        = RoundedCornerShape(20.dp)
    val rs = rememberResponsiveSize()

    // cookie 输入框本地状态（与持久化值保持同步，失焦时自动保存）
    var cookieInput by remember(cookie) { mutableStateOf(cookie) }
    // 控制 cookie 区域是否展开
    var cookieExpanded by remember { mutableStateOf(cookie.isNotEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(colors = listOf(BgCardAlt, BgCard)))
            .border(1.dp, Brush.verticalGradient(
                colors = listOf(NeonBlue.copy(alpha = 0.3f), NeonBlue.copy(alpha = 0.08f))
            ), shape)
            .padding(rs.cardPadding()),
        verticalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 10.dp))
    ) {
        CardLabel(label = "CONNECTION", color = NeonBlue)

        OutlinedTextField(
            value = wsUrl, onValueChange = onUrlChange,
            enabled = !isConnected && !isBusy,
            placeholder = { Text("ws://192.168.x.x:9001", color = TextMuted, fontSize = rs.labelFontSize(),
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
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = rs.labelFontSize(base = 12f)),
            leadingIcon = { Icon(Icons.Default.Wifi, null,
                tint = if (isConnected) CpuGreen else TextMuted, modifier = Modifier.size(rs.iconSize(base = 18.dp))) }
        )

        // ── Cookie 区域 ─────────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            // 展开/折叠 触发行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { cookieExpanded = !cookieExpanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 6.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Cookie,
                        contentDescription = "Cookie",
                        tint = if (cookie.isNotEmpty()) NeonBlue else TextMuted,
                        modifier = Modifier.size(rs.iconSize(base = 14.dp))
                    )
                    Text(
                        text = if (cookie.isNotEmpty()) "COOKIE  (已设置)" else "COOKIE  (可选)",
                        color = if (cookie.isNotEmpty()) NeonBlue else TextMuted,
                        fontSize = rs.smallFontSize(),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                Icon(
                    imageVector = if (cookieExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(rs.iconSize(base = 16.dp))
                )
            }

            // 展开内容：输入框 + 保存/清除按钮
            AnimatedVisibility(visible = cookieExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = rs.itemSpacing(base = 6.dp)),
                    verticalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 6.dp))
                ) {
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        placeholder = {
                            Text(
                                "key1=value1; key2=value2",
                                color = TextMuted,
                                fontSize = rs.labelFontSize(base = 11f),
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            onSaveCookie(cookieInput)
                            focusManager.clearFocus()
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonBlue,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary,
                            cursorColor          = NeonBlue,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = rs.labelFontSize(base = 11f)
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.VpnKey, null,
                                tint = TextMuted,
                                modifier = Modifier.size(rs.iconSize(base = 16.dp))
                            )
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 8.dp))
                    ) {
                        // 保存按钮
                        Button(
                            onClick = {
                                onSaveCookie(cookieInput)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f).height(rs.buttonHeight(base = 36.dp)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = rs.itemSpacing(), vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonBlueFade,
                                contentColor   = NeonBlue,
                            ),
                            border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(rs.iconSize(base = 14.dp)))
                            Spacer(Modifier.width(4.dp))
                            Text("SAVE", fontFamily = FontFamily.Monospace, fontSize = rs.smallFontSize())
                        }
                        // 清除按钮
                        Button(
                            onClick = {
                                cookieInput = ""
                                onSaveCookie("")
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f).height(rs.buttonHeight(base = 36.dp)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = rs.itemSpacing(), vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed.copy(alpha = 0.12f),
                                contentColor   = DangerRed,
                            ),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(rs.iconSize(base = 14.dp)))
                            Spacer(Modifier.width(4.dp))
                            Text("CLEAR", fontFamily = FontFamily.Monospace, fontSize = rs.smallFontSize())
                        }
                    }
                }
            }
        }
        // ── Cookie 区域 END ─────────────────────────────────────────────────

        if (wsState is WsState.Error) {
            Text("⚠ ${wsState.message}", color = DangerRed, fontSize = rs.smallFontSize(), fontFamily = FontFamily.Monospace)
        }

        // 连接中：连接按钮（禁用）+ 取消按钮；其他状态：单个按钮
        if (isBusy) {
            Row(
                modifier = Modifier.fillMaxWidth().height(rs.buttonHeight()),
                horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing())
            ) {
                // 连接中状态显示（禁用）
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = rs.itemSpacing(base = 8.dp), vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = BorderColor,
                        disabledContentColor   = TextMuted
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(rs.dotSize(base = 14.dp)), color = WarnOrange, strokeWidth = 2.dp)
                    Spacer(Modifier.width(rs.itemSpacing(base = 6.dp)))
                    Text(
                        if (autoConnecting) "AUTO CONNECTING..." else "CONNECTING...",
                        fontFamily = FontFamily.Monospace, fontSize = rs.smallFontSize(),
                        maxLines = 1
                    )
                }
                // 取消按钮
                Button(
                    onClick = onCancelConnect,
                    modifier = Modifier.width((88 * rs.scaleFactor).dp).fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = rs.itemSpacing(base = 8.dp), vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed.copy(alpha = 0.15f),
                        contentColor   = DangerRed
                    ),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(rs.dotSize(base = 14.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("CANCEL", fontFamily = FontFamily.Monospace, fontSize = rs.smallFontSize())
                }
            }
        } else {
            Button(
                onClick = { if (isConnected) onDisconnect() else onConnect() },
                modifier = Modifier.fillMaxWidth().height(rs.buttonHeight()),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) DangerRed.copy(alpha = 0.15f) else NeonBlueFade,
                    contentColor   = if (isConnected) DangerRed else NeonBlue,
                ),
                border = BorderStroke(1.dp,
                    if (isConnected) DangerRed.copy(alpha = 0.5f) else NeonBlue.copy(alpha = 0.5f))
            ) {
                if (isConnected) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(rs.iconSize()))
                    Spacer(Modifier.width(rs.itemSpacing(base = 6.dp)))
                    Text("DISCONNECT", fontFamily = FontFamily.Monospace, fontSize = rs.bodyFontSize(base = 12f))
                } else {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(rs.iconSize()))
                    Spacer(Modifier.width(rs.itemSpacing(base = 6.dp)))
                    Text("CONNECT", fontFamily = FontFamily.Monospace, fontSize = rs.bodyFontSize(base = 12f))
                }
            }
        }
    }
}

// ── 已保存链接卡片（支持备注编辑）────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedUrlsCard(
    urls: List<String>,
    remarks: List<String>,
    currentUrl: String,
    wsState: WsState,
    onConnectTo: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
) {
    val isConnected = wsState is WsState.Connected
    val isBusy      = wsState is WsState.Connecting
    val shape       = RoundedCornerShape(20.dp)

    // 当前正在编辑备注的 url
    var editingUrl    by remember { mutableStateOf<String?>(null) }
    var editingText   by remember { mutableStateOf("") }
    val focusManager  = LocalFocusManager.current

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
            Text("点击连接  长按编辑备注", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }

        urls.forEachIndexed { idx, url ->
            val isActive = url == currentUrl && isConnected
            val remark   = remarks.getOrElse(idx) { "" }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) MemPurple.copy(alpha = 0.12f) else BgSlate.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = !isConnected && !isBusy,
                            onClick = { onConnectTo(url) },
                            onLongClick = {
                                editingUrl  = url
                                editingText = remark
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(
                        if (isActive) LiveGreen else TextMuted, CircleShape))

                    Column(modifier = Modifier.weight(1f)) {
                        // 备注行（有备注时显示）
                        if (remark.isNotEmpty()) {
                            Text(
                                text = remark,
                                color = MemPurple,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = url,
                            color = if (isActive) LiveGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 编辑备注图标
                    Icon(
                        Icons.Default.Edit, "编辑备注",
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp).clickable {
                            editingUrl  = url
                            editingText = remark
                        }
                    )

                    if (!isActive) {
                        Icon(Icons.Default.Close, "删除", tint = TextMuted,
                            modifier = Modifier.size(16.dp).clickable { onRemove(url) })
                    }
                }

                // 内联备注编辑框
                if (editingUrl == url) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgCard)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            placeholder = { Text("输入备注（如：家里Mac）", color = TextMuted, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                onSaveRemark(url, editingText)
                                editingUrl = null
                                focusManager.clearFocus()
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = MemPurple,
                                unfocusedBorderColor = BorderColor,
                                focusedTextColor     = TextPrimary,
                                unfocusedTextColor   = TextPrimary,
                                cursorColor          = MemPurple,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        )
                        // 确认
                        IconButton(
                            onClick = {
                                onSaveRemark(url, editingText)
                                editingUrl = null
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Check, "保存", tint = MemPurple,
                                modifier = Modifier.size(18.dp))
                        }
                        // 取消
                        IconButton(
                            onClick = { editingUrl = null; focusManager.clearFocus() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, "取消", tint = TextMuted,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 图表页（支持左右滑动切换链接）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChartPage(
    wsState: WsState,
    metrics: SystemMetrics?,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    netRxHistory: List<Double>,
    netTxHistory: List<Double>,
    connectedUrl: String,
    savedUrls: List<String>,
    savedRemarks: List<String>,
    onDisconnect: () -> Unit,
    onSwipePrev: () -> Unit,
    onSwipeNext: () -> Unit,
    rs: ResponsiveSize = ResponsiveSize(393f, 852f, 2f),
) {
    // 当前连接的备注
    val connectedIdx = savedUrls.indexOf(connectedUrl)
    val connectedRemark = if (connectedIdx >= 0) savedRemarks.getOrElse(connectedIdx) { "" } else ""

    // 水平拖拽检测：累计偏移超过阈值才触发切换，避免误触
    var dragAccum by remember { mutableStateOf(0f) }
    var swipeTriggered by remember { mutableStateOf(false) }
    val swipeThreshold = 80f

    val gpuValue = metrics?.gpuUsagePercent
    val coreList = metrics?.cpuPerCore.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(savedUrls.size) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccum = 0f
                        swipeTriggered = false
                    },
                    onDragEnd = {
                        dragAccum = 0f
                        swipeTriggered = false
                    },
                    onDragCancel = {
                        dragAccum = 0f
                        swipeTriggered = false
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (swipeTriggered) return@detectHorizontalDragGestures

                        dragAccum += dragAmount
                        when {
                            dragAccum > swipeThreshold -> {
                                swipeTriggered = true
                                onSwipePrev()
                            }
                            dragAccum < -swipeThreshold -> {
                                swipeTriggered = true
                                onSwipeNext()
                            }
                        }
                    }
                )
            }
            .padding(horizontal = rs.cardSpacing(base = 3.dp), vertical = rs.cardSpacing(base = 3.dp)),
        horizontalArrangement = Arrangement.spacedBy(rs.cardSpacing(base = 3.dp))
    ) {
        // 列1：网速图（含顶部 Header 行）
        NetworkCard(
            rxKbps          = metrics?.netRxKbps ?: 0.0,
            txKbps          = metrics?.netTxKbps ?: 0.0,
            rxHistory       = netRxHistory,
            txHistory       = netTxHistory,
            connectedRemark = connectedRemark,
            onDisconnect    = onDisconnect,
            modifier        = Modifier.weight(5f).fillMaxHeight()
        )

        // 列2：CPU（上）+ MEM（下）—— 始终不变
        Column(
            modifier = Modifier.weight(3f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(rs.cardSpacing(base = 3.dp))
        ) {
            CpuCard(
                value    = metrics?.cpuUsagePercent ?: 0f,
                history  = cpuHistory,
                fontSize = rs.bigFontSize(),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            MemCard(
                value    = metrics?.memoryUsagePercent ?: 0f,
                usedMb   = metrics?.memoryUsedMb ?: 0L,
                totalMb  = metrics?.memoryTotalMb ?: 0L,
                fontSize = rs.bigFontSize(),
                modifier = Modifier.fillMaxWidth().weight(0.38f)
            )
        }

        // 列3：有 gpu_usage_percent 时展示 GpuCard，否则展示多核（有数据时）
        if (gpuValue != null) {
            GpuCard(
                value    = gpuValue,
                fontSize = rs.bigFontSize(),
                modifier = Modifier.weight(2.5f).fillMaxHeight()
            )
        } else if (coreList.isNotEmpty()) {
            CoresCard(
                cores    = coreList,
                modifier = Modifier.weight(2.5f).fillMaxHeight()
            )
        }
    }
}

// ─── 网速卡片 ─────────────────────────────────────────────────────────────────

@Composable
private fun NetworkCard(
    rxKbps: Double, txKbps: Double,
    rxHistory: List<Double>, txHistory: List<Double>,
    connectedRemark: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    GlassCard(modifier = modifier, accentColor = NetAmber, glowAlignment = GlowAlignment.TopRight) {
        // ── 顶部 Header 行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing())
        ) {
            // NETWORK 标签
            CardLabel(label = "NET", color = NetAmber)

            // 备注-LIVE 胶囊（有备注时显示备注，否则只显示 LIVE）
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LiveGreenBg)
                    .padding(horizontal = rs.itemSpacing(base = 8.dp), vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(rs.dotSize(base = 5.dp)).background(LiveGreen, CircleShape))
                Text(
                    text = if (connectedRemark.isNotEmpty()) "$connectedRemark-LIVE" else "LIVE",
                    color = LiveGreen,
                    fontSize = rs.labelFontSize(base = 9f),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // DISC 按钮
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC1A1F2E))
                    .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable { onDisconnect() }
                    .padding(horizontal = rs.itemSpacing(base = 8.dp), vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.LinkOff, null,
                    tint = DangerRed.copy(alpha = 0.8f), modifier = Modifier.size(rs.labelFontSize(base = 11f).value.dp))
                Text("DISC", color = DangerRed.copy(alpha = 0.8f), fontSize = rs.labelFontSize(base = 9f),
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.weight(1f))

            // 图例（颜色与 DualLineChart 保持一致：RX 蓝紫，TX 橙）
            Row(horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 10.dp))) {
                LegendItem(color = Color(0xFF7B7FEB), label = "RX", arrow = "↓")
                LegendItem(color = Color(0xFFFF9C3E), label = "TX", arrow = "↑")
            }
        }

        Spacer(Modifier.height(rs.itemSpacing(base = 6.dp)))

        // ── 面积图
        DualLineChart(
            rxData   = rxHistory,
            txData   = txHistory,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Spacer(Modifier.height(rs.itemSpacing(base = 8.dp)))

        // ── 底部数值行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing())
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

@Composable
private fun CpuCard(
    value: Float, history: List<Float>,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    modifier: Modifier = Modifier,
) {
    // CPU 卡片：速度计仪表盘，内边距压到最小让表盘充满格子
    GlassCard(
        modifier = modifier,
        accentColor = CpuGreen,
        glowAlignment = GlowAlignment.TopLeft,
        contentPadding = 2.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 用 maxWidth 撑满卡片宽度，避免在矩形卡片中留大块空白
            val gaugeSize = maxWidth
            // 字体大小 = 表盘尺寸的 16%，自动跟随卡片尺寸缩放
            val dynamicFontSize = (gaugeSize.value * 0.16f).sp
            Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
                SpeedometerGauge(
                    value            = value,
                    color            = Color(0xFF3D6DEB),
                    glowColor        = Color(0x33EF5350),
                    gradientEndColor = Color(0xFFEC407A),
                    modifier         = Modifier.fillMaxSize()
                )
                // 数字放在表盘下半部分中心
                Text(
                    text = "${value.roundToInt()}",
                    color = TextPrimary,
                    fontSize = dynamicFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = dynamicFontSize,
                    modifier = Modifier.offset(y = gaugeSize * 0.22f)
                )
            }
        }
    }
}

// ─── GPU 卡片（竖向分段热力柱，有 gpu_usage_percent 字段时替换 CpuCard）────────
//
// 图表选型：竖向分段柱状图（SegmentedBar）
//   · 将 0~100% 分为 20 个小格（每格 5%），已达到的格子点亮，颜色随占用率渐变
//   · 与 CPU GaugeChart 视觉互补，同时占用相同的卡片尺寸区域
//   · 无历史记录（GPU 数据是实时快照），只显示当前值 + 大字百分比 + 标签

@Composable
private fun GpuCard(
    value: Float,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    val actualFontSize = fontSize ?: rs.bigFontSize()
    GlassCard(modifier = modifier, accentColor = GpuIndigo, glowAlignment = GlowAlignment.TopLeft) {
        Row(modifier = Modifier.fillMaxSize()) {
            VerticalLabel(label = "GPU", color = GpuIndigo)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = rs.itemSpacing(), horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 大字百分比
                Text(
                    text = "${value.roundToInt()}",
                    color = GpuIndigo,
                    fontSize = actualFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = actualFontSize
                )

                Spacer(Modifier.height(rs.itemSpacing()))

                // 竖向分段热力柱（20 格，每格 5%）
                GpuSegmentBar(
                    value = value,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

// 竖向分段热力柱：从下往上点亮，颜色从 GpuIndigo → GpuFuchsia 渐变
@Composable
private fun GpuSegmentBar(value: Float, modifier: Modifier = Modifier) {
    val animatedValue = remember { Animatable(0f) }
    LaunchedEffect(value) {
        animatedValue.animateTo(
            targetValue = value.coerceIn(0f, 100f),
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
        )
    }

    Canvas(modifier = modifier) {
        val totalSegments = 20
        val gapPx = 3f
        val totalGap = gapPx * (totalSegments - 1)
        val segH = ((size.height - totalGap) / totalSegments).coerceAtLeast(2f)
        val cornerR = segH / 2f
        val litCount = ((animatedValue.value / 100f) * totalSegments).toInt().coerceIn(0, totalSegments)

        for (i in 0 until totalSegments) {
            // 第 0 格在最底部，第 19 格在最顶部
            val segIdx = totalSegments - 1 - i   // 从顶到底绘制
            val top = i * (segH + gapPx)
            val lit = segIdx < litCount           // 从底部往上点亮

            val progress = if (totalSegments <= 1) 1f else segIdx.toFloat() / (totalSegments - 1)
            val litColor = lerp(GpuIndigo, GpuFuchsia, progress)

            drawRoundRect(
                color = if (lit) litColor else BgSlate.copy(alpha = 0.6f),
                topLeft = Offset(0f, top),
                size = Size(size.width, segH),
                cornerRadius = CornerRadius(cornerR)
            )

            // 亮起时加轻微发光
            if (lit) {
                drawRoundRect(
                    color = litColor.copy(alpha = 0.25f),
                    topLeft = Offset(-2f, top - 2f),
                    size = Size(size.width + 4f, segH + 4f),
                    cornerRadius = CornerRadius(cornerR + 2f)
                )
            }
        }
    }
}

// ─── 内存仪表卡片 ─────────────────────────────────────────────────────────────

@Composable
private fun MemCard(
    value: Float, usedMb: Long, totalMb: Long,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    // 去掉圆形图表和标题，只保留：xx/xx 文字（进度条上方）+ 新样式进度条
    GlassCard(
        modifier = modifier,
        accentColor = MemPurple,
        glowAlignment = GlowAlignment.TopRight,
        contentPadding = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            // xx/xx 文字放在进度条上方
            if (totalMb > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatMb(usedMb)} / ${formatMb(totalMb)}",
                        color = TextSecondary,
                        fontSize = rs.labelFontSize(base = 11f),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${value.roundToInt()}%",
                        color = MemPurple,
                        fontSize = rs.labelFontSize(base = 11f),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            MemProgressBar(percent = value, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─── 多核卡片 ─────────────────────────────────────────────────────────────────

@Composable
private fun CoresCard(cores: List<Float>, modifier: Modifier = Modifier) {
    val rs = rememberResponsiveSize()
    // 阈值：超过 14 核时切换为多列网格模式
    val useGridMode = cores.size > 14

    GlassCard(modifier = modifier, accentColor = CoreCyan, glowAlignment = GlowAlignment.TopRight) {
        // ── Header 行 ──────────────────────────────────────────────────────────
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
                Text(text = "${cores.size}c", color = CoreCyan, fontSize = rs.labelFontSize(),
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(rs.itemSpacing()))

        // 根据核心数动态计算进度条高度：核心越少越粗
        val barH: Dp = when {
            cores.size <= 4  -> (22 * rs.scaleFactor).dp
            cores.size <= 8  -> (16 * rs.scaleFactor).dp
            cores.size <= 14 -> (12 * rs.scaleFactor).dp
            else             -> (9  * rs.scaleFactor).dp
        }

        if (useGridMode) {
            // ── 超过 14 核：多列网格，每行 2 列────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 3.dp))
            ) {
                val chunked = cores.chunked(2)
                chunked.forEachIndexed { rowIdx, rowCores ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowCores.forEachIndexed { colIdx, v ->
                            val coreIdx = rowIdx * 2 + colIdx
                            Box(modifier = Modifier.weight(1f).height(barH)) {
                                CoreBarChart(
                                    value     = v,
                                    coreIndex = coreIdx,
                                    modifier  = Modifier.fillMaxSize()
                                )
                            }
                        }
                        if (rowCores.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            // ── ≤ 14 核：只保留进度条，铺满宽度 ─────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 3.dp))
            ) {
                cores.forEachIndexed { i, v ->
                    Box(modifier = Modifier.fillMaxWidth().height(barH)) {
                        CoreBarChart(
                            value     = v,
                            coreIndex = i,
                            modifier  = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

// ─── 竖排标题 ─────────────────────────────────────────────────────────────────

@Composable
private fun VerticalLabel(label: String, color: Color) {
    val rs = rememberResponsiveSize()
    Box(
        modifier = Modifier.fillMaxHeight().width((22 * rs.scaleFactor).dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight()
        ) {
            Box(modifier = Modifier.size(rs.dotSize()).background(color, CircleShape))
            Spacer(Modifier.height(rs.itemSpacing()))
            label.forEach { ch ->
                Text(text = ch.toString(), color = color, fontSize = rs.labelFontSize(),
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp, lineHeight = rs.bodyFontSize(base = 14f))
            }
        }
    }
}

// ─── 紧凑网速行 ───────────────────────────────────────────────────────────────

@Composable
private fun CompactSpeedRow(
    label: String, value: String, unit: String, color: Color,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(rs.dotSize(base = 7.dp)).background(color, CircleShape))
        Text(text = label, color = TextSecondary, fontSize = rs.labelFontSize(), fontFamily = FontFamily.Monospace)
        Text(text = value, color = Color.White, fontSize = rs.bigFontSize(base = 18f),
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(text = unit, color = TextSecondary, fontSize = rs.smallFontSize(), fontFamily = FontFamily.Monospace)
    }
}

// ─── 图例项 ───────────────────────────────────────────────────────────────────

@Composable
private fun LegendItem(color: Color, label: String, arrow: String) {
    val rs = rememberResponsiveSize()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(text = arrow, color = color, fontSize = rs.smallFontSize(), fontFamily = FontFamily.Monospace)
        Text(text = label, color = color, fontSize = rs.smallFontSize(), fontFamily = FontFamily.Monospace)
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
