import Foundation

// ══════════════════════════════════════════════════════════════════════════════
// SysMonWebSocket  —  WebSocket 客户端（URLSessionWebSocketTask，iOS 13+）
// iOS 26 版本：使用 async/await + actor 保证线程安全
// ══════════════════════════════════════════════════════════════════════════════

@MainActor
final class SysMonWebSocket: NSObject {

    // ── 回调闭包（在主线程上调用）──────────────────────────────────────────────
    var onConnected:    (() -> Void)?
    var onDisconnected: ((String) -> Void)?
    var onMetrics:      ((SystemMetrics) -> Void)?
    var onError:        ((String) -> Void)?

    private(set) var state: WsState = .disconnected

    private var wsTask:  URLSessionWebSocketTask?
    private var session: URLSession?
    private let decoder = JSONDecoder()
    private var receiveTask: Task<Void, Never>?

    // ── 连接 ────────────────────────────────────────────────────────────────

    func connect(urlString: String) {
        guard state != .connected && state != .connecting else { return }
        guard let url = URL(string: urlString) else {
            updateState(.error("无效的 URL"))
            return
        }

        disconnect()
        updateState(.connecting)

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)

        let task = session!.webSocketTask(with: url)
        wsTask = task
        task.resume()

        // 启动接收循环
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }
    }

    func disconnect() {
        receiveTask?.cancel()
        receiveTask = nil
        wsTask?.cancel(with: .normalClosure, reason: nil)
        wsTask = nil
        session?.invalidateAndCancel()
        session = nil
        if state != .disconnected {
            updateState(.disconnected)
        }
    }

    // ── 接收消息循环 ────────────────────────────────────────────────────────

    private func receiveLoop() async {
        guard let task = wsTask else { return }
        while !Task.isCancelled {
            do {
                let message = try await task.receive()
                switch message {
                case .string(let text):
                    handleText(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        handleText(text)
                    }
                @unknown default:
                    break
                }
            } catch {
                // 连接断开或取消
                if !Task.isCancelled {
                    await MainActor.run { [weak self] in
                        guard let self else { return }
                        if self.state != .disconnected {
                            self.updateState(.error(error.localizedDescription))
                            self.onError?(error.localizedDescription)
                            self.onDisconnected?(error.localizedDescription)
                        }
                    }
                }
                break
            }
        }
    }

    private func handleText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let metrics = try? decoder.decode(SystemMetrics.self, from: data)
        else { return }
        onMetrics?(metrics)
    }

    private func updateState(_ newState: WsState) {
        state = newState
    }
}

// ── URLSessionWebSocketDelegate ────────────────────────────────────────────────

extension SysMonWebSocket: URLSessionWebSocketDelegate {

    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.updateState(.connected)
            self.onConnected?()
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.state != .disconnected {
                self.updateState(.disconnected)
                self.onDisconnected?("")
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.state != .disconnected {
                let msg = error.localizedDescription
                self.updateState(.error(msg))
                self.onError?(msg)
                self.onDisconnected?(msg)
            }
        }
    }
}
