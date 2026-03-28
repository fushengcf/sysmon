import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// ChartView  —  实时监控仪表盘（SwiftUI，iOS 26）
// 支持左右滑动切换已保存的连接
// ══════════════════════════════════════════════════════════════════════════════

struct ChartView: View {

    @Environment(MonitorViewModel.self) private var vm

    var body: some View {
        VStack(spacing: 0) {
            // ── 顶部状态栏 ────────────────────────────────────────────────
            TopBar()

            // ── 主内容滚动区 ────────────────────────────────────────────
            ScrollView(showsIndicators: false) {
                VStack(spacing: 10) {
                    // 网络流量图（全宽）
                    NetworkCard()

                    // CPU + 内存横排
                    HStack(spacing: 10) {
                        CpuCard()
                        MemCard()
                    }

                    // 多核 CPU（有多核时显示）
                    if (vm.metrics?.cpuPerCore.count ?? 0) > 1 {
                        CoresCard()
                    }

                    Spacer().frame(height: 8)
                }
                .padding(.horizontal, 12)
                .padding(.top, 10)
            }
        }
        .background(C.bgDeep.ignoresSafeArea())
        // 左右滑动切换连接
        .gesture(
            DragGesture(minimumDistance: 50, coordinateSpace: .local)
                .onEnded { value in
                    guard vm.savedUrls.count >= 2 else { return }
                    if value.translation.width < -50 {
                        vm.switchToNextUrl()
                    } else if value.translation.width > 50 {
                        vm.switchToPrevUrl()
                    }
                }
        )
    }
}

// ── 顶部状态栏 ────────────────────────────────────────────────────────────────

private struct TopBar: View {
    @Environment(MonitorViewModel.self) private var vm

    private var connectedRemark: String {
        let idx = vm.savedUrls.firstIndex(of: vm.connectedUrl) ?? -1
        guard idx >= 0, idx < vm.savedRemarks.count else { return "" }
        return vm.savedRemarks[idx]
    }

    var body: some View {
        HStack(spacing: 8) {
            // SYSMON 标题
            Text("SYSMON")
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(C.textPrimary)
                .tracking(3)

            Spacer()

            // 多连接时的滑动提示
            if vm.savedUrls.count > 1 {
                Text("◀ 滑动切换 ▶")
                    .font(.system(size: 8, design: .monospaced))
                    .foregroundStyle(C.textMuted.opacity(0.5))
            }

            // LIVE 徽章
            HStack(spacing: 4) {
                Circle()
                    .fill(C.liveGreen)
                    .frame(width: 5, height: 5)
                    .shadow(color: C.liveGreen.opacity(0.8), radius: 3)

                Text(connectedRemark.isEmpty ? "LIVE" : "\(connectedRemark)-LIVE")
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                    .foregroundStyle(C.liveGreen)
                    .lineLimit(1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(C.liveGreenBg)
            .clipShape(Capsule())

            // 断开按钮
            Button {
                vm.disconnect()
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: "link.badge.minus")
                        .font(.system(size: 9))
                    Text("DISC")
                        .font(.system(size: 9, weight: .medium, design: .monospaced))
                }
                .foregroundStyle(C.dangerRed.opacity(0.85))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color(red: 0.102, green: 0.122, blue: 0.180))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .strokeBorder(C.dangerRed.opacity(0.35), lineWidth: 1)
                )
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(C.bgCard.opacity(0.9))
        .overlay(
            Rectangle()
                .fill(C.borderColor)
                .frame(height: 0.5),
            alignment: .bottom
        )
    }
}

// ── 网络流量卡片 ───────────────────────────────────────────────────────────────

private struct NetworkCard: View {
    @Environment(MonitorViewModel.self) private var vm

    private var rxKbps: Double { vm.metrics?.netRxKbps ?? 0 }
    private var txKbps: Double { vm.metrics?.netTxKbps ?? 0 }

    var body: some View {
        GlassCard(accentColor: C.netAmber) {
            VStack(alignment: .leading, spacing: 8) {
                // 标题行
                HStack {
                    CardLabel(text: "NETWORK", color: C.netAmber)
                    Spacer()
                    HStack(spacing: 12) {
                        Label("RX", systemImage: "arrow.down")
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(C.netAmber)
                        Label("TX", systemImage: "arrow.up")
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(C.netPink)
                    }
                }

                // 折线图
                DualLineChart(rxData: vm.netRxHistory, txData: vm.netTxHistory)
                    .frame(height: 80)

                // 数值行
                HStack {
                    SpeedValueRow(arrow: "↓", value: rxKbps, color: C.netAmber)
                    Spacer()
                    SpeedValueRow(arrow: "↑", value: txKbps, color: C.netPink)
                    Spacer()
                }
            }
        }
    }
}

// ── CPU 仪表盘卡片 ─────────────────────────────────────────────────────────────

private struct CpuCard: View {
    @Environment(MonitorViewModel.self) private var vm

    private var value: Float { vm.metrics?.cpuUsagePercent ?? 0 }

    var body: some View {
        GlassCard(accentColor: C.cpuGreen) {
            VStack(spacing: 6) {
                HStack {
                    CardLabel(text: "CPU", color: C.cpuGreen)
                    Spacer()
                }

                ZStack {
                    GaugeChart(value: value, color: C.cpuGreen, gradientEnd: C.cpuCyan)
                        .frame(width: 110, height: 110)

                    VStack(spacing: 0) {
                        Text("\(Int(value))")
                            .font(.system(size: 28, weight: .bold, design: .monospaced))
                            .foregroundStyle(C.cpuGreen)
                        Text("%")
                            .font(.system(size: 12))
                            .foregroundStyle(C.textSecondary)
                    }
                }

                if let cores = vm.metrics?.cpuPerCore, !cores.isEmpty {
                    Text("\(cores.count) 核心")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(C.textMuted)
                }
            }
        }
    }
}

// ── 内存仪表盘卡片 ─────────────────────────────────────────────────────────────

private struct MemCard: View {
    @Environment(MonitorViewModel.self) private var vm

    private var value:   Float { vm.metrics?.memoryUsagePercent ?? 0 }
    private var usedMb:  Int64 { vm.metrics?.memoryUsedMb  ?? 0 }
    private var totalMb: Int64 { vm.metrics?.memoryTotalMb ?? 0 }

    var body: some View {
        GlassCard(accentColor: C.memPurple) {
            VStack(spacing: 6) {
                HStack {
                    CardLabel(text: "MEM", color: C.memPurple)
                    Spacer()
                }

                ZStack {
                    GaugeChart(value: value, color: C.memPurple, gradientEnd: C.memPink)
                        .frame(width: 110, height: 110)

                    VStack(spacing: 0) {
                        Text("\(Int(value))")
                            .font(.system(size: 28, weight: .bold, design: .monospaced))
                            .foregroundStyle(C.memPurple)
                        Text("%")
                            .font(.system(size: 12))
                            .foregroundStyle(C.textSecondary)
                    }
                }

                // 内存用量文字
                if totalMb > 0 {
                    Text("\(formatMb(usedMb)) / \(formatMb(totalMb))")
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(C.textSecondary)
                }

                // 进度条
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 3)
                            .fill(C.bgSlate)
                            .frame(height: 6)
                        RoundedRectangle(cornerRadius: 3)
                            .fill(LinearGradient(
                                colors: [C.memPurple, C.memPink],
                                startPoint: .leading, endPoint: .trailing
                            ))
                            .frame(
                                width: max(0, geo.size.width * CGFloat(value / 100)),
                                height: 6
                            )
                            .animation(.easeOut(duration: 0.3), value: value)
                    }
                }
                .frame(height: 6)
            }
        }
    }
}

// ── 多核卡片 ───────────────────────────────────────────────────────────────────

private struct CoresCard: View {
    @Environment(MonitorViewModel.self) private var vm

    private var cores: [Float] { vm.metrics?.cpuPerCore ?? [] }

    var body: some View {
        GlassCard(accentColor: C.coreCyan) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    CardLabel(text: "CORES", color: C.coreCyan)
                    Spacer()
                    Text("\(cores.count)c")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(C.coreCyan)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(C.coreCyan.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }

                CoreBarChart(coreValues: cores)
            }
        }
    }
}

// ── 网速数值子视图 ─────────────────────────────────────────────────────────────

private struct SpeedValueRow: View {
    let arrow: String
    let value: Double
    let color: Color

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)

            Text(arrow)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(C.textSecondary)

            Text(formatSpeedValue(value))
                .font(.system(size: 20, weight: .bold, design: .monospaced))
                .foregroundStyle(C.textPrimary)

            Text(formatSpeedUnit(value))
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(C.textSecondary)
        }
    }

    private func formatSpeedValue(_ kbps: Double) -> String {
        kbps >= 1024 ? String(format: "%.1f", kbps / 1024) : String(format: "%.1f", kbps)
    }

    private func formatSpeedUnit(_ kbps: Double) -> String {
        kbps >= 1024 ? "MB/s" : "KB/s"
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

private func formatMb(_ mb: Int64) -> String {
    mb >= 1024 ? String(format: "%.1fG", Double(mb) / 1024) : "\(mb)M"
}
