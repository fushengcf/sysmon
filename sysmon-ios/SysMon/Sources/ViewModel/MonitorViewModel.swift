import Foundation
import Observation

// ══════════════════════════════════════════════════════════════════════════════
// MonitorViewModel  —  使用 Swift 5.9 @Observable 宏（iOS 17+）
// 主线程绑定：所有属性直接驱动 SwiftUI 视图更新
// ══════════════════════════════════════════════════════════════════════════════

@Observable
@MainActor
final class MonitorViewModel {

    // ── 连接状态 ────────────────────────────────────────────────────────────
    var wsState:      WsState        = .disconnected
    var wsUrl:        String         = "ws://192.168.1.100:9001"
    var connectedUrl: String         = ""
    var autoConnecting: Bool         = false

    // ── 实时指标 ────────────────────────────────────────────────────────────
    var metrics: SystemMetrics?

    // ── 历史数据（60 个点）────────────────────────────────────────────────
    var cpuHistory:   [Float]  = []
    var memHistory:   [Float]  = []
    var netRxHistory: [Double] = []
    var netTxHistory: [Double] = []

    // ── 已保存的连接（存储属性，让 @Observable 能追踪变化）────────────────────
    var savedUrls:    [String] = []
    var savedRemarks: [String] = []

    // ── 内部成员 ─────────────────────────────────────────────────────────
    private let wsClient  = SysMonWebSocket()
    private let urlRepo   = UrlRepository.shared

    private var manuallyDisconnected = false
    private var autoConnectTask:  Task<Void, Never>?
    private var reconnectTask:    Task<Void, Never>?

    private static let maxHistory:         Int          = 60
    private static let autoConnectTimeout: Duration     = .seconds(5)
    private static let reconnectDelay:     Duration     = .seconds(8)

    // ── 初始化 ──────────────────────────────────────────────────────────────

    init() {
        // 从仓库加载初始数据
        savedUrls    = UrlRepository.shared.urls
        savedRemarks = UrlRepository.shared.remarks
        bindWebSocket()
        Task { await tryAutoConnect() }
    }

    // ── WebSocket 回调绑定 ──────────────────────────────────────────────────

    private func bindWebSocket() {
        wsClient.onConnected = { [weak self] in
            guard let self else { return }
            self.wsState      = .connected
            self.connectedUrl = self.wsUrl
            self.saveCurrentUrl()
            self.stopAutoConnect()
        }

        wsClient.onDisconnected = { [weak self] reason in
            guard let self else { return }
            let wasConnected = (self.wsState == .connected)
            self.wsState = .disconnected
            if wasConnected, !self.manuallyDisconnected {
                self.scheduleReconnect()
            }
        }

        wsClient.onError = { [weak self] message in
            guard let self else { return }
            self.wsState = .error(message)
            if !self.manuallyDisconnected {
                self.scheduleReconnect()
            }
        }

        wsClient.onMetrics = { [weak self] m in
            guard let self else { return }
            self.metrics = m
            self.appendHistory(m)
        }
    }

    // ── 自动连接（逐个尝试已保存的 URL）───────────────────────────────────────

    private func tryAutoConnect() async {
        let urls = urlRepo.urls
        guard !urls.isEmpty, wsState != .connected else { return }

        stopAutoConnect()
        autoConnecting = true

        autoConnectTask = Task { [weak self] in
            guard let self else { return }
            for url in urls {
                guard !Task.isCancelled else { break }
                if self.wsState == .connected { break }

                await MainActor.run {
                    self.wsUrl = url
                    self.wsClient.connect(urlString: url)
                }

                // 等待连接结果（最多 5 秒）
                let deadline = Date().addingTimeInterval(5)
                while Date() < deadline && !Task.isCancelled {
                    let currentState = self.wsState
                    if currentState == .connected { break }
                    if case .error = currentState { break }
                    if case .disconnected = currentState, currentState != .connecting { break }
                    try? await Task.sleep(for: .milliseconds(200))
                }

                if self.wsState == .connected { break }

                await MainActor.run { self.wsClient.disconnect() }
                try? await Task.sleep(for: .milliseconds(300))
            }

            await MainActor.run { [weak self] in
                self?.autoConnecting = false
            }
        }
        await autoConnectTask?.value
        autoConnecting = false
    }

    private func stopAutoConnect() {
        autoConnectTask?.cancel()
        autoConnectTask = nil
        autoConnecting  = false
    }

    private func scheduleReconnect() {
        guard !manuallyDisconnected, !urlRepo.urls.isEmpty else { return }
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: Self.reconnectDelay)
            guard !Task.isCancelled else { return }
            await self?.tryAutoConnect()
        }
    }

    // ── 公开操作 ─────────────────────────────────────────────────────────────

    func connect() {
        manuallyDisconnected = false
        stopAutoConnect()
        reconnectTask?.cancel()
        wsClient.connect(urlString: wsUrl)
    }

    func disconnect() {
        manuallyDisconnected = true
        stopAutoConnect()
        reconnectTask?.cancel()
        wsClient.disconnect()
        clearHistory()
    }

    func connectTo(_ url: String) {
        manuallyDisconnected = false
        stopAutoConnect()
        reconnectTask?.cancel()
        wsUrl = url
        wsClient.disconnect()
        clearHistory()
        wsClient.connect(urlString: url)
    }

    func switchToPrevUrl() {
        let urls = urlRepo.urls
        guard urls.count >= 2 else { return }
        let idx    = urls.firstIndex(of: connectedUrl) ?? 0
        let target = urls[idx <= 0 ? urls.count - 1 : idx - 1]
        connectTo(target)
    }

    func switchToNextUrl() {
        let urls = urlRepo.urls
        guard urls.count >= 2 else { return }
        let idx    = urls.firstIndex(of: connectedUrl) ?? -1
        let target = urls[(idx < 0 || idx >= urls.count - 1) ? 0 : idx + 1]
        connectTo(target)
    }

    func saveCurrentUrl() {
        let url = wsUrl.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty else { return }
        urlRepo.addUrl(url)
        syncSavedUrls()
    }

    func removeUrl(_ url: String) {
        urlRepo.removeUrl(url)
        syncSavedUrls()
    }

    func saveRemark(_ remark: String, for url: String) {
        urlRepo.saveRemark(remark, for: url)
        syncSavedUrls()
    }

    private func syncSavedUrls() {
        savedUrls    = urlRepo.urls
        savedRemarks = urlRepo.remarks
    }

    func getRemark(for url: String) -> String {
        urlRepo.getRemark(for: url)
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private func appendHistory(_ m: SystemMetrics) {
        func append<T>(_ arr: inout [T], _ val: T) {
            arr.append(val)
            if arr.count > Self.maxHistory { arr.removeFirst() }
        }
        append(&cpuHistory,   m.cpuUsagePercent)
        append(&memHistory,   m.memoryUsagePercent)
        append(&netRxHistory, m.netRxKbps)
        append(&netTxHistory, m.netTxKbps)
    }

    private func clearHistory() {
        metrics      = nil
        cpuHistory   = []
        memHistory   = []
        netRxHistory = []
        netTxHistory = []
    }
}
