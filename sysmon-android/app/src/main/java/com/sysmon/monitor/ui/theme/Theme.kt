package com.sysmon.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════════════════
// 配色系统（对齐 v0.dev 设计稿）
// ══════════════════════════════════════════════════════════════════════════════

// ── 背景层 ────────────────────────────────────────────────────────────────────
val BgDeep        = Color(0xFF0A0E14)   // 页面最深背景 #0a0e14
val BgCard        = Color(0xFF151C28)   // 卡片渐变终点 #151c28
val BgCardAlt     = Color(0xFF0D1117)   // 卡片渐变起点 #0d1117
val BgSlate       = Color(0xFF1E293B)   // 轨道/槽背景  slate-800
val BgSlate2      = Color(0xFF0F172A)   // 进度条背景   slate-900

// ── 边框 ──────────────────────────────────────────────────────────────────────
val BorderColor   = Color(0xFF1E2D45)   // 通用边框

// ── CPU：翠绿 → 青色（emerald → cyan）────────────────────────────────────────
val CpuGreen      = Color(0xFF10B981)   // emerald-500
val CpuGreenDim   = Color(0xFF059669)   // emerald-600
val CpuCyan       = Color(0xFF22D3EE)   // cyan-400
val CpuGreenFade  = Color(0x2010B981)   // emerald/20

// ── 内存：紫色 → 粉色（purple → pink）────────────────────────────────────────
val MemPurple     = Color(0xFFA855F7)   // purple-500
val MemPurpleDim  = Color(0xFF9333EA)   // purple-600
val MemPink       = Color(0xFFEC4899)   // pink-500
val MemPurpleFade = Color(0x20A855F7)   // purple/20

// ── 网络：琥珀 / 橙 / 粉（amber → orange → pink）────────────────────────────
val NetAmber      = Color(0xFFF59E0B)   // amber-400
val NetOrange     = Color(0xFFF97316)   // orange-500
val NetPink       = Color(0xFFEC4899)   // pink-500
val NetCyan       = Color(0xFF22D3EE)   // cyan-400
val NetAmberFade  = Color(0x20F59E0B)   // amber/20

// ── 多核条形图颜色 ────────────────────────────────────────────────────────────
val CoreCyan      = Color(0xFF22D3EE)   // C0
val CoreEmerald   = Color(0xFF34D399)   // C1
val CoreAmber     = Color(0xFFFBBF24)   // 高负载
val CoreOrange    = Color(0xFFF97316)   // 极高负载
val CoreBlue      = Color(0xFF60A5FA)   // 中等负载
val CoreSlate     = Color(0xFF64748B)   // 低负载

// ── GPU：蓝紫 → 品红（indigo → fuchsia）─────────────────────────────────────
val GpuIndigo     = Color(0xFF818CF8)   // indigo-400
val GpuFuchsia    = Color(0xFFE879F9)   // fuchsia-400
val GpuIndigoFade = Color(0x20818CF8)   // indigo/20

// ── 状态色 ────────────────────────────────────────────────────────────────────
val LiveGreen     = Color(0xFF34D399)   // emerald-400 LIVE 指示
val LiveGreenBg   = Color(0x2034D399)   // emerald/20
val DangerRed     = Color(0xFFFF453A)
val WarnOrange    = Color(0xFFF97316)

// ── 文字 ──────────────────────────────────────────────────────────────────────
val TextPrimary   = Color(0xFFE8F4FF)
val TextSecondary = Color(0xFF94A3B8)   // slate-400
val TextMuted     = Color(0xFF475569)   // slate-600

// ── 兼容旧代码的别名 ──────────────────────────────────────────────────────────
val NeonBlue      = Color(0xFF00D4FF)
val NeonBlueFade  = Color(0x3300D4FF)
val NeonCyan      = CpuGreen            // 旧 CPU 色 → 新翠绿
val NeonCyanFade  = CpuGreenFade
val NeonPurple    = MemPurple
val NeonPurpleFade= MemPurpleFade
val NeonOrange    = NetOrange
val NeonOrangeFade= Color(0x33F97316)
val NeonPink      = NetPink
val NeonPinkFade  = Color(0x33EC4899)

// ══════════════════════════════════════════════════════════════════════════════
// Material Theme
// ══════════════════════════════════════════════════════════════════════════════

private val DarkColorScheme = darkColorScheme(
    primary   = CpuGreen,
    secondary = MemPurple,
    tertiary  = NetAmber,
    background = BgDeep,
    surface    = BgCard,
    onPrimary  = BgDeep,
    onSecondary = BgDeep,
    onBackground = TextPrimary,
    onSurface    = TextPrimary,
)

@Composable
fun SysMonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
