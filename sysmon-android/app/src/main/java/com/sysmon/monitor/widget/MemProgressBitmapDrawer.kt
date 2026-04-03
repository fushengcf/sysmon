package com.sysmon.monitor.widget

import android.graphics.*

/**
 * 用 Android 原生 Canvas 绘制和 APP 内 MemProgressBar 完全一致的进度条 Bitmap。
 * 样式：圆角外框白色描边 + 左侧亮白填充块 + 内缩 padding 感。
 */
object MemProgressBitmapDrawer {

    /**
     * @param widthPx   Bitmap 宽度（px）
     * @param heightPx  Bitmap 高度（px），建议 44~56px（对应 22~28dp @2x）
     * @param percent   内存使用率 0~100f
     * @param usedMb    已用内存（MB）
     * @param totalMb   总内存（MB）
     */
    fun draw(widthPx: Int, heightPx: Int, percent: Float, usedMb: Long, totalMb: Long): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawProgress(canvas, widthPx.toFloat(), heightPx.toFloat(), percent.coerceIn(0f, 100f))
        return bmp
    }

    private fun drawProgress(canvas: Canvas, w: Float, h: Float, percent: Float) {
        val barH     = h
        val cornerR  = barH / 2f
        val fraction = percent / 100f

        // ── 轨道背景（半透明白色）──────────────────────────────────────────────
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(0x22, 0xFF, 0xFF, 0xFF)
        }
        canvas.drawRoundRect(0f, 0f, w, barH, cornerR, cornerR, trackPaint)

        // ── 外圈描边（白色渐变，左亮右暗）──────────────────────────────────────
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 2.5f
            shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    Color.argb((0.55f * 255).toInt(), 0xFF, 0xFF, 0xFF),
                    Color.argb((0.25f * 255).toInt(), 0xFF, 0xFF, 0xFF)
                ),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2,
            w - borderPaint.strokeWidth / 2, barH - borderPaint.strokeWidth / 2,
            cornerR, cornerR, borderPaint
        )

        // ── 填充块（内缩 3px，左侧亮白渐变）──────────────────────────────────
        if (fraction > 0f) {
            val inset  = 3f
            val fillW  = ((w - inset * 2) * fraction).coerceAtLeast(0f)
            val fillR  = (cornerR - inset).coerceAtLeast(2f)

            if (fillW > fillR * 2) {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style  = Paint.Style.FILL
                    shader = LinearGradient(
                        inset, 0f, inset + fillW, 0f,
                        intArrayOf(
                            Color.argb((0.95f * 255).toInt(), 0xFF, 0xFF, 0xFF),
                            Color.argb((0.60f * 255).toInt(), 0xFF, 0xFF, 0xFF)
                        ),
                        null, Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(inset, inset, inset + fillW, barH - inset, fillR, fillR, fillPaint)
            }
        }
    }
}
