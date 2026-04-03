package com.sysmon.monitor.widget

import android.graphics.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/**
 * 用 Android 原生 Canvas 绘制和 APP 内 DualLineChart 完全一致的双折线图 Bitmap。
 * 颜色：RX 蓝紫 #7B7FEB，TX 橙 #FF9C3E
 */
object NetSpeedBitmapDrawer {

    /**
     * @param widthPx   Bitmap 宽度（px）
     * @param heightPx  Bitmap 高度（px）
     * @param rxHistory RX 历史数据（KB/s），最新值在末尾
     * @param txHistory TX 历史数据（KB/s），最新值在末尾
     */
    fun draw(widthPx: Int, heightPx: Int, rxHistory: List<Float>, txHistory: List<Float>): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawChart(canvas, widthPx.toFloat(), heightPx.toFloat(), rxHistory, txHistory)
        return bmp
    }

    private fun drawChart(
        canvas: Canvas, w: Float, h: Float,
        rxHistory: List<Float>, txHistory: List<Float>
    ) {
        if (rxHistory.size < 2 && txHistory.size < 2) return

        val allValues = (rxHistory + txHistory).map { it.toDouble() }
        val rawMax    = allValues.maxOrNull()?.toFloat()?.coerceAtLeast(10f) ?: 10f

        // 好看的刻度间隔
        val niceStep  = niceTickStep(rawMax.toDouble(), targetTicks = 4)
        val niceMax   = (ceil(rawMax / niceStep) * niceStep).toFloat().coerceAtLeast(niceStep.toFloat())
        val tickCount = (niceMax / niceStep).toInt().coerceIn(2, 6)

        // 左侧预留 Y 轴标签宽度
        val labelW  = 40f
        val padTop  = 10f
        val padBot  = 4f
        val chartX  = labelW
        val chartW  = w - labelW
        val drawH   = h - padTop - padBot

        // Y 轴标签画笔
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.argb((0.65f * 255).toInt(), 0x8A, 0x8F, 0xA8)
            textSize  = 18f    // 约 9sp @2x
            textAlign = Paint.Align.RIGHT
            typeface  = Typeface.MONOSPACE
        }

        // 点虚线网格画笔
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect  = DashPathEffect(floatArrayOf(4f, 8f), 0f)
        }

        // 绘制网格线 + Y 轴标签
        for (i in 0..tickCount) {
            val ratio     = i.toFloat() / tickCount
            val y         = padTop + drawH * (1f - ratio)
            val tickValue = niceStep * i

            gridPaint.color = Color.argb(
                if (i == 0) (0.40f * 255).toInt() else (0.22f * 255).toInt(),
                0x8A, 0x8F, 0xA8
            )
            canvas.drawLine(chartX, y, w, y, gridPaint)

            val labelText = formatTickLabel(tickValue)
            val fm = labelPaint.fontMetrics
            canvas.drawText(labelText, labelW - 4f, y - (fm.ascent + fm.descent) / 2f, labelPaint)
        }

        // 绘制单条折线 + 填充
        fun drawSeries(data: List<Float>, lineColorInt: Int) {
            if (data.size < 2) return
            val n      = data.size
            val stepX  = chartW / (n - 1).toFloat()
            fun xAt(i: Int) = chartX + i * stepX
            fun yAt(v: Float) = (padTop + drawH * (1f - (v / niceMax).coerceIn(0f, 1f)))

            // 构建贝塞尔曲线路径
            val linePath = Path()
            val fillPath = Path()
            linePath.moveTo(xAt(0), yAt(data[0]))
            fillPath.moveTo(xAt(0), h)
            fillPath.lineTo(xAt(0), yAt(data[0]))

            for (i in 1 until n) {
                val px  = xAt(i - 1); val py = yAt(data[i - 1])
                val cx  = xAt(i);     val cy = yAt(data[i])
                val cpX = (px + cx) / 2f
                linePath.cubicTo(cpX, py, cpX, cy, cx, cy)
                fillPath.cubicTo(cpX, py, cpX, cy, cx, cy)
            }
            fillPath.lineTo(xAt(n - 1), h)
            fillPath.close()

            // 渐变填充
            val r = Color.red(lineColorInt)
            val g = Color.green(lineColorInt)
            val b = Color.blue(lineColorInt)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style  = Paint.Style.FILL
                shader = LinearGradient(
                    0f, padTop, 0f, h,
                    intArrayOf(
                        Color.argb((0.28f * 255).toInt(), r, g, b),
                        Color.argb((0.02f * 255).toInt(), r, g, b)
                    ),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(fillPath, fillPaint)

            // 发光宽线
            canvas.drawPath(linePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style       = Paint.Style.STROKE
                strokeWidth = 14f
                strokeCap   = Paint.Cap.ROUND
                strokeJoin  = Paint.Join.ROUND
                color       = Color.argb((0.18f * 255).toInt(), r, g, b)
            })

            // 主线
            canvas.drawPath(linePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style       = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap   = Paint.Cap.ROUND
                strokeJoin  = Paint.Join.ROUND
                color       = lineColorInt
            })
        }

        // TX 橙先画，RX 蓝紫后画（与 APP 层叠顺序一致）
        drawSeries(txHistory, Color.rgb(0xFF, 0x9C, 0x3E))   // 橙
        drawSeries(rxHistory, Color.rgb(0x7B, 0x7F, 0xEB))   // 蓝紫
    }

    // ── 工具函数 ──────────────────────────────────────────────────────────────

    private fun niceTickStep(rawMax: Double, targetTicks: Int = 4): Double {
        if (rawMax <= 0) return 1.0
        val roughStep  = rawMax / targetTicks
        val mag        = Math.pow(10.0, floor(log10(roughStep)).toDouble())
        val normalized = roughStep / mag
        val niceNorm   = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else              -> 10.0
        }
        return niceNorm * mag
    }

    private fun formatTickLabel(kbps: Double): String =
        if (kbps >= 1024.0) String.format("%.0fM", kbps / 1024.0)
        else String.format("%.0fK", kbps)
}
