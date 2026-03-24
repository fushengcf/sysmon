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
// 1. 弧形仪表盘（GaugeChart）
//    对齐设计稿：渐变弧 + 端点亮点（大圆 + 白色内圆）+ 发光
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

    Canvas(modifier = modifier) {
        if (rxData.size < 2 && txData.size < 2) return@Canvas

        val allValues = (rxData + txData)
        val rawMax = allValues.maxOrNull()?.toFloat()?.coerceAtLeast(10f) ?: 10f

        // ── 计算"好看"的刻度间隔（niceStep）────────────────────────────────
        val niceStep = niceTickStep(rawMax.toDouble(), targetTicks = 4)
        val niceMax  = (ceil(rawMax / niceStep) * niceStep).toFloat().coerceAtLeast(niceStep.toFloat())
        val tickCount = (niceMax / niceStep).toInt().coerceIn(2, 6)

        // Y 轴标签宽度预留（右侧）
        val labelWidthPx = 46f
        val w = size.width - labelWidthPx
        val h = size.height
        val padTop    = 8f
        val padBottom = 4f
        val drawH = h - padTop - padBottom

        // ── 网格线 + Y 轴刻度标签 ────────────────────────────────────────────
        for (i in 0..tickCount) {
            val ratio = i.toFloat() / tickCount
            val y = padTop + drawH * (1f - ratio)
            val tickValue = niceStep * i   // KB/s 值

            // 虚线网格
            drawLine(
                color = BgSlate.copy(alpha = if (i == 0) 0.5f else 0.35f),
                start = Offset(0f, y),
                end   = Offset(w, y),
                strokeWidth = if (i == 0) 1f else 0.7f,
                pathEffect  = if (i == 0) null
                              else PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )

            // 刻度标签（右侧）
            if (i > 0) {
                val labelText = formatTickLabel(tickValue)
                val measured  = textMeasurer.measure(
                    text  = labelText,
                    style = TextStyle(
                        fontSize   = 8.sp,
                        color      = TextSecondary.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = w + 3f,
                        y = y - measured.size.height / 2f
                    )
                )
            }
        }

        // ── 绘制面积图 ────────────────────────────────────────────────────────
        fun drawArea(data: List<Double>, lineColor: Color, fillStartColor: Color) {
            if (data.size < 2) return
            val n = data.size
            val stepX = w / (n - 1).toFloat()
            fun xAt(i: Int) = i * stepX
            fun yAt(v: Double) = padTop + drawH * (1.0 - (v / niceMax).coerceIn(0.0, 1.0)).toFloat()

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

            drawPath(
                path  = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillStartColor.copy(alpha = 0.35f), fillStartColor.copy(alpha = 0f)),
                    startY = padTop, endY = h
                )
            )
            drawPath(linePath, lineColor.copy(alpha = 0.25f),
                style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(linePath, lineColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        drawArea(txData, NetOrange, NetOrange)
        drawArea(rxData, NetPink,   NetPink)
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
    coreValues: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (coreValues.isEmpty()) return@Canvas

        val n = coreValues.size
        val totalH = size.height
        val totalW = size.width
        val rowH = totalH / n
        val barH = (rowH - 5f).coerceAtLeast(6f)
        val cornerR = barH / 2f

        coreValues.forEachIndexed { i, v ->
            val top = i * rowH + (rowH - barH) / 2f
            val fillW = (totalW * (v / 100f).coerceIn(0f, 1f))

            // 轨道（slate-800/80）
            drawRoundRect(
                color = BgSlate.copy(alpha = 0.8f),
                topLeft = Offset(0f, top),
                size = Size(totalW, barH),
                cornerRadius = CornerRadius(cornerR)
            )

            // 背景网格纹理（设计稿：bg-[linear-gradient(90deg,...)] bg-[length:8px]）
            val gridStep = 8f
            var gx = gridStep
            while (gx < totalW) {
                drawLine(
                    color = Color(0xFF334155).copy(alpha = 0.2f),
                    start = Offset(gx, top),
                    end = Offset(gx, top + barH),
                    strokeWidth = 0.8f
                )
                gx += gridStep
            }

            if (fillW < cornerR * 2) return@forEachIndexed

            // 颜色策略（对齐设计稿 getBarColor）
            val (startColor, endColor) = when {
                i == 0 -> CoreCyan    to Color(0xFF06B6D4)   // cyan
                i == 1 -> CoreEmerald to Color(0xFF10B981)   // emerald
                v > 50f -> CoreAmber  to CoreOrange           // 高负载
                v > 20f -> CoreBlue   to Color(0xFF6366F1)   // 中等
                else    -> CoreSlate  to Color(0xFF475569)   // 低
            }

            val glowColor = when {
                i == 0 -> CoreCyan.copy(alpha = 0.5f)
                i == 1 -> CoreEmerald.copy(alpha = 0.5f)
                v > 50f -> CoreAmber.copy(alpha = 0.4f)
                v > 20f -> CoreBlue.copy(alpha = 0.4f)
                else    -> Color.Transparent
            }

            // 发光（设计稿：boxShadow）
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

            // 高亮线（设计稿：inset-y-1 bg-white/20）
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
