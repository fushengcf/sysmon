package com.sysmon.monitor.widget

import android.graphics.*
import kotlin.math.*

/**
 * 用 Android 原生 Canvas 绘制和 APP 内 SpeedometerGauge 完全一致的仪表盘 Bitmap。
 * Glance 不支持 Compose Canvas，故以此方式在 Widget 中复现。
 */
object SpeedometerBitmapDrawer {

    /**
     * @param sizePx  Bitmap 的边长（px），建议传入 widget 实际 dp 对应的 px 值
     * @param value   CPU 占用率 0~100f
     * @return        已绘制好的 Bitmap（ARGB_8888）
     */
    fun draw(sizePx: Int, value: Float): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawSpeedometer(canvas, sizePx.toFloat(), sizePx.toFloat(), value.coerceIn(0f, 100f))
        return bmp
    }

    private fun drawSpeedometer(canvas: Canvas, w: Float, h: Float, value: Float) {
        // ── 参数（与 Compose 版保持一致）────────────────────────────────────────
        val strokeWidthDp = 16f
        // 将 dp 比例换算：Compose 版用 16.dp，画布边长对应屏幕宽度
        // 这里用 sizePx * 0.085f 来近似 16dp/200dp ≈ 8% 的比例
        val sw = w * 0.085f

        val canvasMin = min(w, h)
        val radius    = canvasMin / 2f - sw - canvasMin * 0.04f
        val cx        = w / 2f
        val cy        = h / 2f

        val startAngle  = 135f
        val sweepTotal  = 270f
        val sweepValue  = sweepTotal * (value / 100f)

        // ── 刻度线（22 条，完全在弧内侧）──────────────────────────────────────
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style      = Paint.Style.STROKE
            strokeCap  = Paint.Cap.ROUND
        }
        val tickEdge  = radius - sw * 0.55f
        val majorLen  = sw * 0.18f
        val minorLen  = sw * 0.10f
        val tickCount = 22
        for (i in 0..tickCount) {
            val angle  = startAngle + (i.toFloat() / tickCount) * sweepTotal
            val rad    = Math.toRadians(angle.toDouble())
            val isMajor = (i % 2 == 0)
            val outerR = tickEdge
            val innerR = tickEdge - if (isMajor) majorLen else minorLen
            tickPaint.strokeWidth = if (isMajor) 1.8f else 1.0f
            tickPaint.color = if (isMajor)
                Color.argb((0.65f * 255).toInt(), 0xB0, 0xB4, 0xC8)
            else
                Color.argb((0.35f * 255).toInt(), 0xB0, 0xB4, 0xC8)

            canvas.drawLine(
                cx + outerR * cos(rad).toFloat(), cy + outerR * sin(rad).toFloat(),
                cx + innerR * cos(rad).toFloat(), cy + innerR * sin(rad).toFloat(),
                tickPaint
            )
        }

        // ── 底色轨道弧 ─────────────────────────────────────────────────────────
        val trackRect  = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = sw
            strokeCap   = Paint.Cap.ROUND
            color       = Color.argb((0.50f * 255).toInt(), 0x3A, 0x3F, 0x55)
        }
        canvas.drawArc(trackRect, startAngle, sweepTotal, false, trackPaint)

        // ── 渐变主弧 ───────────────────────────────────────────────────────────
        if (sweepValue > 0.5f) {
            // SweepGradient：0° = 3 点钟方向
            // 颜色节点与 Compose 版保持一致
            val sweepColors   = intArrayOf(
                Color.rgb(0xEC, 0x40, 0x7A),  // 0.000 红粉
                Color.rgb(0xEC, 0x40, 0x7A),  // 0.200 红粉
                Color.rgb(0x3D, 0x6D, 0xEB),  // 0.375 蓝
                Color.rgb(0xFF, 0xA7, 0x26),  // 0.625 橙
                Color.rgb(0xEF, 0x53, 0x50),  // 0.750 红
                Color.rgb(0xEC, 0x40, 0x7A),  // 1.000 红粉
            )
            val sweepPositions = floatArrayOf(0.000f, 0.200f, 0.375f, 0.625f, 0.750f, 1.000f)
            val sweepShader = SweepGradient(cx, cy, sweepColors, sweepPositions)

            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style       = Paint.Style.STROKE
                strokeWidth = sw
                strokeCap   = Paint.Cap.ROUND
                shader      = sweepShader
            }
            canvas.drawArc(trackRect, startAngle, sweepValue, false, arcPaint)
        }

        // ── 三角形指针 ─────────────────────────────────────────────────────────
        val needleAngleRad = Math.toRadians((startAngle + sweepValue).toDouble())
        val needleLen      = radius * 0.78f
        val needleTailLen  = radius * 0.18f
        val needleBaseHalf = sw * 0.28f

        val tipX  = cx + needleLen * cos(needleAngleRad).toFloat()
        val tipY  = cy + needleLen * sin(needleAngleRad).toFloat()
        val tailX = cx - needleTailLen * cos(needleAngleRad).toFloat()
        val tailY = cy - needleTailLen * sin(needleAngleRad).toFloat()

        val perpRad = needleAngleRad + Math.PI / 2.0
        val baseLx = tailX + needleBaseHalf * cos(perpRad).toFloat()
        val baseLy = tailY + needleBaseHalf * sin(perpRad).toFloat()
        val baseRx = tailX - needleBaseHalf * cos(perpRad).toFloat()
        val baseRy = tailY - needleBaseHalf * sin(perpRad).toFloat()

        val needlePath = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseLx, baseLy)
            lineTo(baseRx, baseRy)
            close()
        }

        // 发光阴影
        canvas.drawPath(needlePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((0.25f * 255).toInt(), 0xEF, 0x53, 0x50)
        })
        // 渐变主体
        val needleShader = LinearGradient(
            tipX, tipY, tailX, tailY,
            intArrayOf(Color.rgb(0xEF, 0x53, 0x50), Color.argb((0.7f * 255).toInt(), 0xEF, 0x53, 0x50)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(needlePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style  = Paint.Style.FILL
            shader = needleShader
        })

        // ── 中心枢轴 ───────────────────────────────────────────────────────────
        val hubRadius = needleBaseHalf * 1.6f

        // 外发光
        canvas.drawCircle(cx, cy, hubRadius * 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((0.12f * 255).toInt(), 0xEF, 0x53, 0x50)
        })
        // 浅灰主圆
        canvas.drawCircle(cx, cy, hubRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0xD8, 0xDA, 0xE5)
        })
        // 红色内圆
        canvas.drawCircle(cx, cy, hubRadius * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0xEF, 0x53, 0x50)
        })
    }
}
