import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// CoreBarChart  —  多核 CPU 条形图（SwiftUI，iOS 26）
// ══════════════════════════════════════════════════════════════════════════════

struct CoreBarChart: View {

    var coreValues: [Float]

    var body: some View {
        VStack(spacing: 4) {
            ForEach(Array(coreValues.enumerated()), id: \.offset) { i, v in
                HStack(spacing: 6) {
                    Text("C\(i)")
                        .font(.system(size: 9, weight: .regular, design: .monospaced))
                        .foregroundStyle(C.textSecondary)
                        .frame(width: 18, alignment: .leading)

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(C.bgSlate)
                                .frame(height: 10)

                            RoundedRectangle(cornerRadius: 4)
                                .fill(barColor(v))
                                .frame(
                                    width: max(2, geo.size.width * CGFloat(v / 100)),
                                    height: 10
                                )
                                .animation(.easeOut(duration: 0.25), value: v)
                        }
                    }
                    .frame(height: 10)

                    Text(String(format: "%2d%%", Int(v)))
                        .font(.system(size: 9, weight: .regular, design: .monospaced))
                        .foregroundStyle(barColor(v))
                        .frame(width: 30, alignment: .trailing)
                }
            }
        }
    }

    private func barColor(_ v: Float) -> Color {
        switch v {
        case 50...: return C.coreAmber
        case 20...: return C.coreBlue
        default:    return C.coreCyan
        }
    }
}
