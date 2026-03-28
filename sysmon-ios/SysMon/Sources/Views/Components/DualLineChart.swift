import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// DualLineChart  —  双折线面积图（网速 RX + TX，SwiftUI Canvas，iOS 26）
// ══════════════════════════════════════════════════════════════════════════════

struct DualLineChart: View {

    var rxData: [Double]
    var txData: [Double]
    var rxColor: Color = C.netAmber
    var txColor: Color = C.netPink

    var body: some View {
        Canvas { ctx, size in
            let allData = rxData + txData
            guard !allData.isEmpty else { return }

            let maxVal  = allData.max() ?? 1
            let len     = max(rxData.count, txData.count)
            guard len >= 2 else { return }

            func draw(_ data: [Double], color: Color) {
                guard data.count >= 2 else { return }
                let pts = data.enumerated().map { i, v -> CGPoint in
                    CGPoint(
                        x: CGFloat(i) / CGFloat(len - 1) * size.width,
                        y: size.height - CGFloat(v / maxVal) * size.height * 0.85 - size.height * 0.05
                    )
                }

                // 折线（贝塞尔曲线平滑）
                var linePath = Path()
                linePath.move(to: pts[0])
                for i in 1..<pts.count {
                    let prev = pts[i - 1]
                    let cur  = pts[i]
                    let cpx  = (prev.x + cur.x) / 2
                    linePath.addCurve(to: cur,
                                      control1: CGPoint(x: cpx, y: prev.y),
                                      control2: CGPoint(x: cpx, y: cur.y))
                }

                // 填充区域
                var fillPath = linePath
                fillPath.addLine(to: CGPoint(x: size.width, y: size.height))
                fillPath.addLine(to: CGPoint(x: 0, y: size.height))
                fillPath.closeSubpath()

                ctx.fill(
                    fillPath,
                    with: .linearGradient(
                        Gradient(stops: [
                            .init(color: color.opacity(0.40), location: 0),
                            .init(color: color.opacity(0.02), location: 1)
                        ]),
                        startPoint: CGPoint(x: 0, y: 0),
                        endPoint: CGPoint(x: 0, y: size.height)
                    )
                )
                ctx.stroke(linePath,
                           with: .color(color),
                           style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
            }

            draw(rxData, color: rxColor)
            draw(txData, color: txColor)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// SingleLineChart  —  单折线面积图（CPU / 内存历史）
// ══════════════════════════════════════════════════════════════════════════════

struct SingleLineChart: View {

    var data:  [Float]
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            guard data.count >= 2 else { return }
            let maxVal = Double(data.max() ?? 1)
            let pts    = data.enumerated().map { i, v -> CGPoint in
                CGPoint(
                    x: CGFloat(i) / CGFloat(data.count - 1) * size.width,
                    y: size.height - CGFloat(Double(v) / maxVal) * size.height * 0.85 - size.height * 0.05
                )
            }

            var linePath = Path()
            linePath.move(to: pts[0])
            for i in 1..<pts.count {
                let prev = pts[i - 1]
                let cur  = pts[i]
                let cpx  = (prev.x + cur.x) / 2
                linePath.addCurve(to: cur,
                                  control1: CGPoint(x: cpx, y: prev.y),
                                  control2: CGPoint(x: cpx, y: cur.y))
            }

            var fillPath = linePath
            fillPath.addLine(to: CGPoint(x: size.width, y: size.height))
            fillPath.addLine(to: CGPoint(x: 0, y: size.height))
            fillPath.closeSubpath()

            ctx.fill(
                fillPath,
                with: .linearGradient(
                    Gradient(stops: [
                        .init(color: color.opacity(0.35), location: 0),
                        .init(color: color.opacity(0.02), location: 1)
                    ]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: 0, y: size.height)
                )
            )
            ctx.stroke(linePath,
                       with: .color(color),
                       style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        }
    }
}
