import Foundation

// ══════════════════════════════════════════════════════════════════════════════
// WsState  —  WebSocket 连接状态枚举
// ══════════════════════════════════════════════════════════════════════════════

enum WsState: Equatable {
    case disconnected
    case connecting
    case connected
    case error(String)

    var isConnected: Bool { self == .connected }

    var errorMessage: String? {
        if case .error(let msg) = self { return msg }
        return nil
    }
}
