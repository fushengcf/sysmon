import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// SysMonColors  —  应用主题色（SwiftUI Color，iOS 26+）
// ══════════════════════════════════════════════════════════════════════════════

enum SysMonColors {
    // ── 背景 ────────────────────────────────────────────────────────────────
    static let bgDeep    = Color(red: 0.039, green: 0.055, blue: 0.102)  // #0A0E1A
    static let bgCard    = Color(red: 0.067, green: 0.094, blue: 0.153)  // #111827
    static let bgCardAlt = Color(red: 0.051, green: 0.078, blue: 0.141)  // #0D1424
    static let bgSlate   = Color(red: 0.102, green: 0.125, blue: 0.208)  // #1A2035

    // ── 霓虹蓝（连接 / 高亮）────────────────────────────────────────────────
    static let neonBlue     = Color(red: 0.290, green: 0.620, blue: 1.000)  // #4A9EFF
    static let neonBlueFade = Color(red: 0.290, green: 0.620, blue: 1.000).opacity(0.15)

    // ── CPU 绿 ───────────────────────────────────────────────────────────────
    static let cpuGreen     = Color(red: 0.000, green: 0.902, blue: 0.463)  // #00E676
    static let cpuGreenFade = Color(red: 0.000, green: 0.902, blue: 0.463).opacity(0.20)
    static let cpuCyan      = Color(red: 0.000, green: 0.737, blue: 0.831)  // #00BCD4

    // ── 内存紫 ───────────────────────────────────────────────────────────────
    static let memPurple     = Color(red: 0.733, green: 0.525, blue: 0.988)  // #BB86FC
    static let memPurpleFade = Color(red: 0.733, green: 0.525, blue: 0.988).opacity(0.20)
    static let memPink       = Color(red: 0.812, green: 0.400, blue: 0.475)  // #CF6679

    // ── 网络 ─────────────────────────────────────────────────────────────────
    static let netAmber = Color(red: 1.000, green: 0.702, blue: 0.000)  // #FFB300
    static let netPink  = Color(red: 1.000, green: 0.251, blue: 0.506)  // #FF4081

    // ── 多核 ─────────────────────────────────────────────────────────────────
    static let coreCyan  = Color(red: 0.000, green: 0.898, blue: 1.000)  // #00E5FF
    static let coreBlue  = Color(red: 0.267, green: 0.541, blue: 1.000)  // #448AFF
    static let coreAmber = Color(red: 1.000, green: 0.671, blue: 0.251)  // #FFAB40

    // ── 状态 ─────────────────────────────────────────────────────────────────
    static let liveGreen    = Color(red: 0.412, green: 0.941, blue: 0.682)  // #69F0AE
    static let liveGreenBg  = Color(red: 0.412, green: 0.941, blue: 0.682).opacity(0.12)
    static let warnOrange   = Color(red: 1.000, green: 0.596, blue: 0.000)  // #FF9800
    static let dangerRed    = Color(red: 1.000, green: 0.322, blue: 0.322)  // #FF5252

    // ── 文字 ─────────────────────────────────────────────────────────────────
    static let textPrimary   = Color.white
    static let textSecondary = Color(red: 0.604, green: 0.647, blue: 0.706)  // #9AA5B4
    static let textMuted     = Color(red: 0.329, green: 0.431, blue: 0.478)  // #546E7A

    // ── 边框 ─────────────────────────────────────────────────────────────────
    static let borderColor = Color.white.opacity(0.08)
}

// ── 便捷别名（保持简短的 C.xxx 风格可选）────────────────────────────────────────
typealias C = SysMonColors
