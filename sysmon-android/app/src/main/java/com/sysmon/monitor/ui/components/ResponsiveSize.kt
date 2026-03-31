package com.sysmon.monitor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 响应式尺寸工具
 * 
 * 根据屏幕实际尺寸（dp）和密度自动计算合适的字体大小、间距等。
 * 解决不同屏幕尺寸下文字过大/过小、布局溢出的问题。
 */
data class ResponsiveSize(
    /** 屏幕宽度 dp */
    val screenWidthDp: Float,
    /** 屏幕高度 dp */
    val screenHeightDp: Float,
    /** 密度比值 */
    val density: Float,
) {
    /** 屏幕对角线 dp，用于整体缩放 */
    private val screenDiagonalDp: Float = 
        kotlin.math.sqrt(screenWidthDp * screenWidthDp + screenHeightDp * screenHeightDp)

    /** 
     * 缩放因子
     * 基准：6.5" 手机（~393dp × 852dp，对角线 ~938dp）
     * 范围：0.7x ~ 1.4x
     */
    val scaleFactor: Float = (screenDiagonalDp / 938f).coerceIn(0.7f, 1.4f)

    /**
     * 宽度方向缩放因子（用于横向适配）
     * 基准宽度：393dp
     */
    val widthScale: Float = (screenWidthDp / 393f).coerceIn(0.7f, 1.8f)

    /**
     * 高度方向缩放因子（用于纵向适配）
     * 基准高度：852dp
     */
    val heightScale: Float = (screenHeightDp / 852f).coerceIn(0.7f, 1.5f)

    // ── 字体大小 ──────────────────────────────────────────────────────────────

    /** 大数字字体（仪表盘百分比） */
    fun bigFontSize(base: Float = 36f): TextUnit = (base * scaleFactor).sp

    /** 标签字体（CPU/MEM/NET 等标签） */
    fun labelFontSize(base: Float = 11f): TextUnit = (base * scaleFactor).sp

    /** 正文字体 */
    fun bodyFontSize(base: Float = 13f): TextUnit = (base * scaleFactor).sp

    /** 小字体（注释、图例） */
    fun smallFontSize(base: Float = 10f): TextUnit = (base * scaleFactor).sp

    /** 标题字体 */
    fun titleFontSize(base: Float = 28f): TextUnit = (base * scaleFactor).sp

    // ── 间距 ──────────────────────────────────────────────────────────────────

    /** 卡片内边距 */
    fun cardPadding(base: Dp = 16.dp): Dp = (base.value * scaleFactor).dp

    /** 卡片间距 */
    fun cardSpacing(base: Dp = 8.dp): Dp = (base.value * scaleFactor).dp

    /** 组件间距 */
    fun itemSpacing(base: Dp = 6.dp): Dp = (base.value * scaleFactor).dp

    // ── 元素尺寸 ──────────────────────────────────────────────────────────────

    /** 按钮高度 */
    fun buttonHeight(base: Dp = 44.dp): Dp = (base.value * scaleFactor).dp

    /** 圆形指示点大小 */
    fun dotSize(base: Dp = 6.dp): Dp = (base.value * scaleFactor).dp

    /** 图标大小 */
    fun iconSize(base: Dp = 16.dp): Dp = (base.value * scaleFactor).dp
}

/**
 * 创建并记住 ResponsiveSize 实例
 */
@Composable
fun rememberResponsiveSize(): ResponsiveSize {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, density.density) {
        ResponsiveSize(
            screenWidthDp = configuration.screenWidthDp.toFloat(),
            screenHeightDp = configuration.screenHeightDp.toFloat(),
            density = density.density,
        )
    }
}
