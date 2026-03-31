package com.sysmon.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysmon.monitor.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════════
// 通用卡片容器（对齐设计稿：rounded-2xl + 渐变背景 + 彩色边框 + 角落光晕）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = NeonBlue,
    glowAlignment: GlowAlignment = GlowAlignment.TopRight,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rs = rememberResponsiveSize()
    val shape = RoundedCornerShape((20 * rs.scaleFactor).dp)

    Box(modifier = modifier) {
        // 角落光晕（模拟设计稿的 blur-3xl 效果）
        GlowOrb(color = accentColor, alignment = glowAlignment)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BgCardAlt, BgCard),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.35f),
                            accentColor.copy(alpha = 0.08f)
                        )
                    ),
                    shape = shape
                )
                .padding(rs.cardPadding()),
            content = content
        )
    }
}

enum class GlowAlignment { TopLeft, TopRight, BottomLeft, BottomRight }

@Composable
private fun GlowOrb(color: Color, alignment: GlowAlignment) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .align(
                    when (alignment) {
                        GlowAlignment.TopLeft     -> Alignment.TopStart
                        GlowAlignment.TopRight    -> Alignment.TopEnd
                        GlowAlignment.BottomLeft  -> Alignment.BottomStart
                        GlowAlignment.BottomRight -> Alignment.BottomEnd
                    }
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.12f), Color.Transparent),
                        radius = 120f
                    )
                )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 卡片标题行（色点 + 标签文字）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun CardLabel(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing())
    ) {
        // 发光色点
        Box(
            modifier = Modifier
                .size(rs.dotSize(base = 10.dp))
                .background(color, CircleShape)
                .drawBehind {
                    drawCircle(
                        color = color.copy(alpha = 0.5f),
                        radius = size.minDimension * 0.9f
                    )
                }
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = rs.bodyFontSize(),
            fontWeight = FontWeight.Bold,
            letterSpacing = (1.5 * rs.scaleFactor).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 大数字值（设计稿：text-5xl font-bold）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun BigValueText(
    value: String,
    unit: String,
    color: Color,
    valueFontSize: androidx.compose.ui.unit.TextUnit? = null,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    val fontSize = valueFontSize ?: rs.bigFontSize(base = 44f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            lineHeight = fontSize
        )
        Text(
            text = unit,
            color = TextSecondary,
            fontSize = rs.bodyFontSize(base = 16f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 网速行（设计稿：色点 + 箭头 + 大数字 + 单位）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun NetSpeedRow(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val rs = rememberResponsiveSize()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rs.itemSpacing(base = 10.dp))
    ) {
        Box(
            modifier = Modifier
                .size(rs.dotSize(base = 10.dp))
                .background(color, CircleShape)
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = rs.bodyFontSize(),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width((20 * rs.widthScale).dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = rs.bigFontSize(base = 22f),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = unit,
            color = TextSecondary,
            fontSize = rs.smallFontSize(base = 12f),
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 内存进度条（设计稿：h-3 rounded-full 渐变 + 发光）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun MemProgressBar(
    percent: Float,
    modifier: Modifier = Modifier,
    height: Dp? = null,
) {
    val rs = rememberResponsiveSize()
    val barHeight = height ?: (10 * rs.scaleFactor).dp
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(BgSlate)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                .clip(shape)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(MemPurpleDim, MemPink)
                    )
                )
                .drawBehind {
                    // 发光效果
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MemPurple.copy(alpha = 0f),
                                MemPurple.copy(alpha = 0.4f)
                            )
                        )
                    )
                }
        )
    }
}
