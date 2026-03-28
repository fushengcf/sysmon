import Foundation

/// 持久化保存 WebSocket 链接列表（最多 10 个）及备注。
/// 纯 Foundation，兼容 iOS 12。
final class UrlRepository {

    static let shared = UrlRepository()

    static let maxUrls = 10
    private let keyUrls    = "sysmon_urls"
    private let keyRemarks = "sysmon_remarks"

    private(set) var urls: [String] = []
    private(set) var remarks: [String] = []

    // 数据变更回调（替代 @Published）
    var onChange: (() -> Void)?

    private init() {
        urls    = UserDefaults.standard.stringArray(forKey: keyUrls)    ?? []
        remarks = UserDefaults.standard.stringArray(forKey: keyRemarks) ?? []
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    func getRemark(for url: String) -> String {
        guard let idx = urls.firstIndex(of: url) else { return "" }
        return remarks.indices.contains(idx) ? remarks[idx] : ""
    }

    // ── 添加 ────────────────────────────────────────────────────────────────

    func addUrl(_ url: String) {
        var u = urls
        var r = remarks
        var existingRemark = ""
        if let idx = u.firstIndex(of: url) {
            existingRemark = r.indices.contains(idx) ? r[idx] : ""
            u.remove(at: idx)
            if r.indices.contains(idx) { r.remove(at: idx) }
        }
        u.insert(url, at: 0)
        r.insert(existingRemark, at: 0)
        if u.count > Self.maxUrls { u.removeLast(); r.removeLast() }
        persist(urls: u, remarks: r)
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    func removeUrl(_ url: String) {
        var u = urls
        var r = remarks
        if let idx = u.firstIndex(of: url) {
            u.remove(at: idx)
            if r.indices.contains(idx) { r.remove(at: idx) }
        }
        persist(urls: u, remarks: r)
    }

    // ── 备注 ────────────────────────────────────────────────────────────────

    func saveRemark(_ remark: String, for url: String) {
        guard let idx = urls.firstIndex(of: url) else { return }
        var r = remarks
        while r.count <= idx { r.append("") }
        r[idx] = remark.trimmingCharacters(in: .whitespaces)
        UserDefaults.standard.set(r, forKey: keyRemarks)
        remarks = r
        onChange?()
    }

    // ── 持久化 ──────────────────────────────────────────────────────────────

    private func persist(urls: [String], remarks: [String]) {
        UserDefaults.standard.set(urls,    forKey: keyUrls)
        UserDefaults.standard.set(remarks, forKey: keyRemarks)
        self.urls    = urls
        self.remarks = remarks
        onChange?()
    }
}
