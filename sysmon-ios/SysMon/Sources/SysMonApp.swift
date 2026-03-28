import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// SysMonApp  —  应用入口（SwiftUI @main，iOS 26+）
// ══════════════════════════════════════════════════════════════════════════════

@main
struct SysMonApp: App {

    @State private var viewModel = MonitorViewModel()

    var body: some Scene {
        WindowGroup {
            ContentRootView()
                .environment(viewModel)
                .preferredColorScheme(.dark)
        }
    }
}

// ── 根视图：根据连接状态切换 Connect / Chart ────────────────────────────────────

struct ContentRootView: View {
    @Environment(MonitorViewModel.self) private var vm

    var body: some View {
        ZStack {
            SysMonColors.bgDeep.ignoresSafeArea()

            if vm.wsState == .connected {
                ChartView()
                    .transition(.asymmetric(
                        insertion:  .move(edge: .trailing).combined(with: .opacity),
                        removal:    .move(edge: .leading).combined(with: .opacity)
                    ))
            } else {
                ConnectView()
                    .transition(.asymmetric(
                        insertion:  .move(edge: .leading).combined(with: .opacity),
                        removal:    .move(edge: .trailing).combined(with: .opacity)
                    ))
            }
        }
        .animation(.easeInOut(duration: 0.4), value: vm.wsState == .connected)
    }
}
