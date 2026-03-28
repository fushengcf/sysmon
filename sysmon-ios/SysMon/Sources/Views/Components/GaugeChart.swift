import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// GaugeChart  —  半圆仪表盘（SwiftUI Canvas，iOS 26）
// ══════════════════════════════════════════════════════════════════════════════

struct GaugeChart: View {

    var value:          Float   // 0 ~ 100
    var color:          Color
    var gradientEnd:    Color
    var trackOpacity:   Double = 0.08

    // 圆弧从 -215° 到 35°，共 250°
    private let startAngle = Angle.degrees(-215)
    private let endAngle   = Angle.degrees(35)
    private var totalAngle: Double { endAngle.degrees - startAngle.degrees } // 250

    private var progressAngle: Angle {
        let clamped = Double(min(100, max(0, value)))
        return Angle.degrees(startAngle.degrees + totalAngle * clamped / 100)
    }

    var body: some View {
        GeometryReader { geo in
            let size   = min(geo.size.width, geo.size.height)
            let r      = size * 0.42
            let cx     = geo.size.width  / 2
            let cy     = geo.size.height / 2
            let trackW = size * 0.038
            let arcW   = size * 0.065

            ZStack {
                // 轨道弧
                Path { path in
                    path.addArc(center: CGPoint(x: cx, y: cy),
                                radius: r,
                                startAngle: startAngle,
                                endAngle: endAngle,
                                clockwise: false)
                }
                .stroke(Color.white.opacity(trackOpacity),
                        style: StrokeStyle(lineWidth: trackW, lineCap: .round))

                // 进度弧（渐变）
                if value > 0 {
                    Path { path in
                        path.addArc(center: CGPoint(x: cx, y: cy),
                                    radius: r,
                                    startAngle: startAngle,
                                    endAngle: progressAngle,
                                    clockwise: false)
                    }
                    .stroke(
                        AngularGradient(
                            colors: [color, gradientEnd],
                            center: .center,
                            startAngle: startAngle,
                            endAngle: progressAngle
                        ),
                        style: StrokeStyle(lineWidth: arcW, lineCap: .round)
                    )
                    // 辉光效果
                    .shadow(color: color.opacity(0.5), radius: 6)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.easeOut(duration: 0.3), value: value)
    }
}
