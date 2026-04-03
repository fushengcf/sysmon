package com.sysmon.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysmon.monitor.ui.theme.*
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ══════════════════════════════════════════════════════════════════════════════
// 1. 速度计式仪表盘（SpeedometerGauge）
//    · 渐变弧：蓝 → 橙 → 红（与参考图一致）
//    · 刻度：紧贴弧轨道内外的短刻度线
//    · 指针：细长三角形（宽底尖头），红色，带轻微发光
//    · 中心枢轴：浅灰大圆 + 红色小圆点（与参考图一致）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SpeedometerGauge(
    value: Float,
    color: Color,
    glowColor: Color,
    modifier: Modifier = Modifier,
    gradientEndColor: Color = color,
    strokeWidth: androidx.compose.ui.unit.Dp = 16.dp,
    trackWidth: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val animatedValue = remember { Animatable(0f) }
    LaunchedEffect(value) {
        animatedValue.animateTo(
            targetValue = value.coerceIn(0f, 100f),
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
    }

    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val canvasMin = min(size.width, size.height)
        // 弧轨道半径：留出刻度线和弧宽的空间
        val radius = canvasMin / 2f - sw - canvasMin * 0.04f
        val center = Offset(size.width / 2f, size.height / 2f)

        val startAngle = 135f
        val sweepTotal = 270f
        val sweepValue = sweepTotal * (animatedValue.value / 100f)

        // ── 刻度线（22 条，完全在弧轨道内侧，不超出弧外边）──────────────────
        val tickCount = 22
        // 弧外边 = radius + sw/2，刻度线起点稍微往内收，确保不超出弧
        // 刻度线紧贴弧内边，保持短小：长刻度 = sw*0.18，短刻度 = sw*0.10
        val tickEdge  = radius - sw * 0.55f          // 紧贴弧内边
        val majorLen  = sw * 0.18f
        val minorLen  = sw * 0.10f
        for (i in 0..tickCount) {
            val angle = startAngle + (i.toFloat() / tickCount) * sweepTotal
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = (i % 2 == 0)
            val outerR = tickEdge
            val innerR = tickEdge - if (isMajor) majorLen else minorLen
            drawLine(
                color = Color(0xFFB0B4C8).copy(alpha = if (isMajor) 0.65f else 0.35f),
                start = Offset(center.x + outerR * cos(rad).toFloat(), center.y + outerR * sin(rad).toFloat()),
                end   = Offset(center.x + innerR * cos(rad).toFloat(), center.y + innerR * sin(rad).toFloat()),
                strokeWidth = if (isMajor) 1.8f else 1.0f,
                cap = StrokeCap.Round
            )
        }

        // ── 底色轨道（浅灰背景弧，参考图右侧未到达部分）──────────────────────
        drawArc(
            color = Color(0xFF3A3F55).copy(alpha = 0.50f),
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )

        if (sweepValue > 0.5f) {
            // ── 渐变主弧：蓝 → 橙 → 红（参考图左下蓝，右上红）──────────────
            // sweepGradient 的 0°=3点钟方向，故用角度偏移对齐弧起点
            // 弧从 135° 开始，用 colorStops 的分布位置模拟颜色沿弧走向
            val gradientBrush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.000f to Color(0xFFEC407A),   // 红粉（360°=0° 处占位，防止接缝）
                    0.200f to Color(0xFFEC407A),   // 红粉（弧终点附近）
                    0.375f to Color(0xFF3D6DEB),   // 蓝（135° 对应 375/1000 ≈ 0.375）
                    0.625f to Color(0xFFFFA726),   // 橙（270°+135° = 405° → 归一化）
                    0.750f to Color(0xFFEF5350),   // 红
                    1.000f to Color(0xFFEC407A),   // 红粉回到起点
                ),
                center = center
            )
            drawArc(
                brush = gradientBrush,
                startAngle = startAngle,
                sweepAngle = sweepValue,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }

        // ── 三角形指针（细长、宽底尖头，参考图样式）─────────────────────────
        val needleAngleRad = Math.toRadians((startAngle + sweepValue).toDouble())
        val needleLen   = radius * 0.78f    // 针尖到中心的距离
        val needleTailLen = radius * 0.18f  // 针尾（反方向）的距离
        val needleBaseHalf = sw * 0.28f     // 底部宽度的一半（控制三角胖瘦）

        // 针尖坐标
        val tipX = center.x + needleLen * cos(needleAngleRad).toFloat()
        val tipY = center.y + needleLen * sin(needleAngleRad).toFloat()
        // 针尾坐标（反方向延伸一小段）
        val tailX = center.x - needleTailLen * cos(needleAngleRad).toFloat()
        val tailY = center.y - needleTailLen * sin(needleAngleRad).toFloat()

        // 底部两个角点（垂直于指针方向）
        val perpRad = needleAngleRad + Math.PI / 2.0
        val baseL = Offset(
            tailX + needleBaseHalf * cos(perpRad).toFloat(),
            tailY + needleBaseHalf * sin(perpRad).toFloat()
        )
        val baseR = Offset(
            tailX - needleBaseHalf * cos(perpRad).toFloat(),
            tailY - needleBaseHalf * sin(perpRad).toFloat()
        )

        // 指针发光阴影
        val needleShadowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseL.x, baseL.y)
            lineTo(baseR.x, baseR.y)
            close()
        }
        drawPath(
            path = needleShadowPath,
            color = Color(0xFFEF5350).copy(alpha = 0.25f),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
        // 指针本体（红色三角）
        val needlePath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseL.x, baseL.y)
            lineTo(baseR.x, baseR.y)
            close()
        }
        drawPath(
            path = needlePath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFEF5350), Color(0xFFEF5350).copy(alpha = 0.7f)),
                start = Offset(tipX, tipY),
                end   = Offset(tailX, tailY)
            ),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        // ── 中心枢轴圆（更小，突出指针）──────────────────────────────────────
        val hubRadius = needleBaseHalf * 1.6f
        // 外发光（更克制）
        drawCircle(color = Color(0xFFEF5350).copy(alpha = 0.12f), radius = hubRadius * 1.6f, center = center)
        // 浅灰主圆
        drawCircle(color = Color(0xFFD8DAE5), radius = hubRadius, center = center)
        // 红色内圆
        drawCircle(color = Color(0xFFEF5350), radius = hubRadius * 0.45f, center = center)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 1.1 弧形仪表盘（GaugeChart - 保留供 MEM 使用）
//      对齐设计稿：渐变弧 + 端点亮点（大圆 + 白色内圆）+ 发光
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun GaugeChart(
    value: Float,
    color: Color,
    glowColor: Color,
    modifier: Modifier = Modifier,
    gradientEndColor: Color = color,   // 渐变终点色（CPU: cyan, MEM: pink）
    strokeWidth: androidx.compose.ui.unit.Dp = 14.dp,
    trackWidth: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val animatedValue = remember { Animatable(0f) }
    LaunchedEffect(value) {
        animatedValue.animateTo(
            targetValue = value.coerceIn(0f, 100f),
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
        )
    }

    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val tw = trackWidth.toPx()
        val radius = (min(size.width, size.height) / 2f) - sw
        val center = Offset(size.width / 2f, size.height / 2f)

        // 仪表盘：从 -225° 开始，扫过 270°（与设计稿 rotate(-225) 一致）
        val startAngle = 135f
        val sweepTotal = 270f
        val sweepValue = sweepTotal * (animatedValue.value / 100f)

        // ── 轨道（slate-800 底色）────────────────────────────────────────────
        drawArc(
            color = BgSlate,
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = tw, cap = StrokeCap.Round)
        )

        if (sweepValue < 1f) return@Canvas

        // ── 发光层（多层半透明叠加）──────────────────────────────────────────
        for (i in 3 downTo 1) {
            drawArc(
                color = glowColor.copy(alpha = 0.07f * i),
                startAngle = startAngle,
                sweepAngle = sweepValue,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = sw + i * 8f, cap = StrokeCap.Round)
            )
        }

        // ── 渐变弧（主体）────────────────────────────────────────────────────
        // 用 sweepGradient 模拟设计稿的 linearGradient
        val gradient = Brush.sweepGradient(
            colorStops = arrayOf(
                0.0f to color.copy(alpha = 0.5f),
                0.75f to gradientEndColor,
            ),
            center = center
        )
        drawArc(
            brush = gradient,
            startAngle = startAngle,
            sweepAngle = sweepValue,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )

        // ── 端点亮点（设计稿：大圆 + 白色内圆）──────────────────────────────
        val endAngleRad = Math.toRadians((startAngle + sweepValue).toDouble())
        val dotX = center.x + radius * cos(endAngleRad).toFloat()
        val dotY = center.y + radius * sin(endAngleRad).toFloat()

        // 外发光圈
        drawCircle(
            color = gradientEndColor.copy(alpha = 0.6f),
            radius = sw * 0.75f,
            center = Offset(dotX, dotY)
        )
        // 主色圆
        drawCircle(
            color = gradientEndColor,
            radius = sw * 0.5f,
            center = Offset(dotX, dotY)
        )
        // 白色内圆
        drawCircle(
            color = Color.White,
            radius = sw * 0.22f,
            center = Offset(dotX, dotY)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 2. 迷你柱状历史图（MiniBarChart）
//    对齐设计稿 CPU/MEM 底部的 history bar chart
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun MiniBarChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        val n = data.size
        val gap = 2f
        val barW = (size.width - gap * (n - 1)) / n

        data.forEachIndexed { i, v ->
            val ratio = (v / maxValue).coerceIn(0f, 1f)
            val barH = (size.height * ratio).coerceAtLeast(2f)
            val left = i * (barW + gap)
            val top = size.height - barH
            val alpha = 0.3f + (i.toFloat() / n) * 0.7f   // 越新越亮

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.5f)),
                    startY = top,
                    endY = size.height
                ),
                topLeft = Offset(left, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 3. 双线面积图（DualLineChart）
//    带 Y 轴动态刻度标注：自动计算"好看"的刻度间隔，右侧显示 KB/s 或 MB/s
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun DualLineChart(
    rxData: List<Double>,
    txData: List<Double>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    // RX 颜色：蓝紫色；TX 颜色：橙色（对齐参考图）
    val rxColor  = Color(0xFF7B7FEB)   // 蓝紫
    val txColor  = Color(0xFFFF9C3E)   // 橙

    Canvas(modifier = modifier) {
        if (rxData.size < 2 && txData.size < 2) return@Canvas

        val allValues = (rxData + txData)
        val rawMax = allValues.maxOrNull()?.toFloat()?.coerceAtLeast(10f) ?: 10f

        // ── 计算"好看"的刻度间隔 ───────────────────────────────────────────
        val niceStep  = niceTickStep(rawMax.toDouble(), targetTicks = 4)
        val niceMax   = (ceil(rawMax / niceStep) * niceStep).toFloat().coerceAtLeast(niceStep.toFloat())
        val tickCount = (niceMax / niceStep).toInt().coerceIn(2, 6)

        // 左侧预留 Y 轴标签宽度
        val labelW  = 44f
        val padTop  = 12f
        val padBot  = 4f
        val chartX  = labelW          // 图表区起始 X
        val chartW  = size.width - labelW
        val drawH   = size.height - padTop - padBot

        // ── Y 轴刻度标签 + 点虚线网格（参考图：左侧标签，灰色点虚线）────────
        for (i in 0..tickCount) {
            val ratio      = i.toFloat() / tickCount
            val y          = padTop + drawH * (1f - ratio)
            val tickValue  = niceStep * i

            // 点虚线网格
            drawLine(
                color       = Color(0xFF8A8FA8).copy(alpha = if (i == 0) 0.40f else 0.25f),
                start       = Offset(chartX, y),
                end         = Offset(size.width, y),
                strokeWidth = 0.8f,
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
            )

            // Y 轴刻度标签（左侧，右对齐到 labelW - 4）
            val labelText = formatTickLabel(tickValue)
            val measured  = textMeasurer.measure(
                text  = labelText,
                style = TextStyle(
                    fontSize   = 8.sp,
                    color      = Color(0xFF8A8FA8),
                    fontFamily = FontFamily.Monospace
                )
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = labelW - measured.size.width - 4f,
                    y = y - measured.size.height / 2f
                )
            )
        }

        // ── 绘制平滑曲线 + 渐变填充区域 ──────────────────────────────────────
        fun buildPaths(data: List<Double>): Pair<Path, Path> {
            val n      = data.size
            val stepX  = chartW / (n - 1).toFloat()
            fun xAt(i: Int)   = chartX + i * stepX
            fun yAt(v: Double) = (padTop + drawH * (1.0 - (v / niceMax).coerceIn(0.0, 1.0))).toFloat()

            val linePath = Path()
            val fillPath = Path()

            linePath.moveTo(xAt(0), yAt(data[0]))
            fillPath.moveTo(xAt(0), size.height)
            fillPath.lineTo(xAt(0), yAt(data[0]))

            for (i in 1 until n) {
                val px = xAt(i - 1); val py = yAt(data[i - 1])
                val cx = xAt(i);     val cy = yAt(data[i])
                val cpX = (px + cx) / 2f
                linePath.cubicTo(cpX, py, cpX, cy, cx, cy)
                fillPath.cubicTo(cpX, py, cpX, cy, cx, cy)
            }
            fillPath.lineTo(xAt(n - 1), size.height)
            fillPath.close()
            return Pair(linePath, fillPath)
        }

        fun drawSeries(data: List<Double>, lineColor: Color) {
            if (data.size < 2) return
            val (linePath, fillPath) = buildPaths(data)

            // 渐变填充（参考图：颜色区域半透明，从线色到透明）
            drawPath(
                path  = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.28f),
                        lineColor.copy(alpha = 0.02f)
                    ),
                    startY = padTop,
                    endY   = size.height
                )
            )
            // 发光模糊描边（宽线低透明度，增加光晕感）
            drawPath(
                path  = linePath,
                color = lineColor.copy(alpha = 0.20f),
                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // 主线（较粗，清晰）
            drawPath(
                path  = linePath,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 先画 TX（橙），再画 RX（蓝紫），层叠关系与参考图一致
        drawSeries(txData, txColor)
        drawSeries(rxData, rxColor)
    }
}

/** 计算"好看"的刻度间隔：1/2/5 × 10^n 系列 */
private fun niceTickStep(rawMax: Double, targetTicks: Int = 4): Double {
    if (rawMax <= 0) return 1.0
    val roughStep = rawMax / targetTicks
    val mag = 10.0.pow(floor10(roughStep))
    val normalized = roughStep / mag
    val niceNorm = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else              -> 10.0
    }
    return niceNorm * mag
}

private fun floor10(v: Double): Int = kotlin.math.floor(log10(v)).toInt()

/** 将 KB/s 值格式化为刻度标签，自动切换 KB/MB */
private fun formatTickLabel(kbps: Double): String =
    if (kbps >= 1024.0) String.format("%.0fM", kbps / 1024.0)
    else String.format("%.0fK", kbps)

// ══════════════════════════════════════════════════════════════════════════════
// 4. 多核条形图（CoreBarChart）
//    对齐设计稿：彩色渐变条 + 背景网格纹理 + 高亮线 + 发光
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun CoreBarChart(
    value: Float,
    coreIndex: Int = 0,
    modifier: Modifier = Modifier,
) {
    // 平滑动画：600ms spring 缓动，避免跳变
    val animatedValue by androidx.compose.animation.core.animateFloatAsState(
        targetValue    = value.coerceIn(0f, 100f),
        animationSpec  = androidx.compose.animation.core.spring(
            dampingRatio   = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness      = androidx.compose.animation.core.Spring.StiffnessLow
        ),
        label = "coreBar$coreIndex"
    )

    Canvas(modifier = modifier) {
        val v      = animatedValue
        val totalH = size.height
        val totalW = size.width
        val barH    = totalH                    // 单条直接占满高度
        val top     = 0f
        val cornerR = (barH / 2f).coerceAtMost(totalW / 2f)   // 圆角 = 高度一半，但不超过宽度一半
        val fillW   = (totalW * (v / 100f).coerceIn(0f, 1f))

        // 轨道
        drawRoundRect(
            color = BgSlate.copy(alpha = 0.8f),
            topLeft = Offset(0f, top),
            size = Size(totalW, barH),
            cornerRadius = CornerRadius(cornerR)
        )

        // 背景网格纹理
        val gridStep = 8f
        var gx = gridStep
        while (gx < totalW) {
            drawLine(
                color = Color(0xFF334155).copy(alpha = 0.2f),
                start = Offset(gx, top),
                end   = Offset(gx, top + barH),
                strokeWidth = 0.8f
            )
            gx += gridStep
        }

        // 只要有内容就画（fillW >= 2px），不再用 cornerR*2 作 guard
        if (fillW < 2f) return@Canvas

        // 颜色策略：用真实 coreIndex 决定前两条的特殊色，其余按负载着色
        val (startColor, endColor) = when {
            coreIndex == 0 -> CoreCyan    to Color(0xFF06B6D4)
            coreIndex == 1 -> CoreEmerald to Color(0xFF10B981)
            v > 50f        -> CoreAmber   to CoreOrange
            v > 20f        -> CoreBlue    to Color(0xFF6366F1)
            else           -> CoreSlate   to Color(0xFF475569)
        }
        val glowColor = when {
            coreIndex == 0 -> CoreCyan.copy(alpha = 0.5f)
            coreIndex == 1 -> CoreEmerald.copy(alpha = 0.5f)
            v > 50f        -> CoreAmber.copy(alpha = 0.4f)
            v > 20f        -> CoreBlue.copy(alpha = 0.4f)
            else           -> Color.Transparent
        }

        // 发光
        if (glowColor != Color.Transparent) {
            drawRoundRect(
                color = glowColor,
                topLeft = Offset(0f, top - 2f),
                size = Size(fillW, barH + 4f),
                cornerRadius = CornerRadius(cornerR + 2f)
            )
        }

        // 渐变进度条
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(startColor, endColor),
                startX = 0f, endX = fillW
            ),
            topLeft = Offset(0f, top),
            size = Size(fillW, barH),
            cornerRadius = CornerRadius(cornerR)
        )

        // 高亮线
        if (v > 5f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(0f, top + barH * 0.15f),
                size = Size((fillW - cornerR).coerceAtLeast(0f), barH * 0.3f),
                cornerRadius = CornerRadius(barH * 0.15f)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 5. 折线面积图（LineAreaChart）—— 保留供其他地方使用
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun LineAreaChart(
    data: List<Float>,
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val padTop = 4f
        val padBottom = 2f
        val drawH = h - padTop - padBottom

        val n = data.size
        val stepX = w / (n - 1).toFloat()
        fun xAt(i: Int) = i * stepX
        fun yAt(v: Float) = padTop + drawH * (1f - (v / maxValue).coerceIn(0f, 1f))

        val linePath = Path()
        val fillPath = Path()
        linePath.moveTo(xAt(0), yAt(data[0]))
        fillPath.moveTo(xAt(0), h)
        fillPath.lineTo(xAt(0), yAt(data[0]))

        for (i in 1 until n) {
            val px = xAt(i - 1); val py = yAt(data[i - 1])
            val cx = xAt(i);     val cy = yAt(data[i])
            val cpX = (px + cx) / 2f
            linePath.cubicTo(cpX, py, cpX, cy, cx, cy)
            fillPath.cubicTo(cpX, py, cpX, cy, cx, cy)
        }
        fillPath.lineTo(xAt(n - 1), h)
        fillPath.close()

        drawPath(fillPath, brush = Brush.verticalGradient(
            colors = listOf(fillColor.copy(alpha = 0.4f), fillColor.copy(alpha = 0f)),
            startY = padTop, endY = h
        ))
        drawPath(linePath, lineColor.copy(alpha = 0.2f),
            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(linePath, lineColor,
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
