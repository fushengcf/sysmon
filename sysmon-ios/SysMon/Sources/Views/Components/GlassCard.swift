import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// GlassCard  —  磨砂玻璃风格卡片容器（SwiftUI，iOS 26）
// ══════════════════════════════════════════════════════════════════════════════

struct GlassCard<Content: View>: View {

    var accentColor: Color = C.neonBlue
    var cornerRadius: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(C.bgCard)
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(
                            LinearGradient(
                                colors: [accentColor.opacity(0.35), accentColor.opacity(0.06)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 1
                        )
                )

            content()
                .padding(12)
        }
    }
}

// ── 卡片标签（LABEL 风格）────────────────────────────────────────────────────

struct CardLabel: View {
    let text: String
    var color: Color = C.neonBlue

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundStyle(color)
            .tracking(2)
    }
}
