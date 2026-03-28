import SwiftUI

// ══════════════════════════════════════════════════════════════════════════════
// ConnectView  —  WebSocket 连接管理界面（SwiftUI，iOS 26）
// ══════════════════════════════════════════════════════════════════════════════

struct ConnectView: View {

    @Environment(MonitorViewModel.self) private var vm
    @FocusState private var urlFocused: Bool

    var body: some View {
        @Bindable var vm = vm

        ScrollView(showsIndicators: false) {
            VStack(spacing: 14) {
                Spacer().frame(height: 20)

                // ── 标题 ────────────────────────────────────────────────────
                HeaderSection()

                // ── 连接卡片 ────────────────────────────────────────────────
                ConnectionCard(urlFocused: $urlFocused)

                // ── 已保存的 URL ─────────────────────────────────────────────
                if !vm.savedUrls.isEmpty {
                    SavedUrlsCard()
                }

                Spacer().frame(height: 20)
            }
            .padding(.horizontal, 16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(C.bgDeep.ignoresSafeArea())
    }
}

// ── 标题区 ───────────────────────────────────────────────────────────────────

private struct HeaderSection: View {
    @Environment(MonitorViewModel.self) private var vm

    var body: some View {
        VStack(spacing: 8) {
            Text("SysMon")
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .foregroundStyle(C.textPrimary)
                .tracking(4)

            Text("系统监控 · iOS 26")
                .font(.system(size: 12, weight: .regular, design: .monospaced))
                .foregroundStyle(C.textMuted)

            // 状态指示器
            HStack(spacing: 6) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 7, height: 7)
                    .shadow(color: statusColor.opacity(0.8), radius: 4)

                Text(statusText)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(statusColor)
                    .tracking(1.5)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, 4)
    }

    private var statusColor: Color {
        switch vm.wsState {
        case .connected:    return C.liveGreen
        case .connecting:   return C.warnOrange
        case .error:        return C.dangerRed
        case .disconnected: return C.textMuted
        }
    }

    private var statusText: String {
        if vm.autoConnecting { return "AUTO CONNECTING" }
        switch vm.wsState {
        case .connected:    return "CONNECTED"
        case .connecting:   return "CONNECTING"
        case .error:        return "ERROR"
        case .disconnected: return "OFFLINE"
        }
    }
}

// ── 连接输入卡片 ──────────────────────────────────────────────────────────────

private struct ConnectionCard: View {
    @Environment(MonitorViewModel.self) private var vm
    var urlFocused: FocusState<Bool>.Binding

    private var isConnected:  Bool { vm.wsState == .connected }
    private var isConnecting: Bool { vm.wsState == .connecting }
    private var isBusy:       Bool { isConnecting || vm.autoConnecting }

    var body: some View {
        @Bindable var vm = vm

        GlassCard(accentColor: C.neonBlue) {
            VStack(alignment: .leading, spacing: 12) {
                CardLabel(text: "CONNECTION", color: C.neonBlue)

                // URL 输入框
                HStack(spacing: 8) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 14))
                        .foregroundStyle(isConnected ? C.cpuGreen : C.textMuted)
                        .frame(width: 20)

                    TextField("ws://192.168.x.x:9001", text: $vm.wsUrl)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(C.textPrimary)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .focused(urlFocused)
                        .disabled(isConnected || isBusy)
                        .tint(C.neonBlue)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(C.bgCardAlt)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(C.neonBlue.opacity(isConnected ? 0.2 : 0.4), lineWidth: 1)
                )

                // 错误信息
                if case .error(let msg) = vm.wsState {
                    Text("⚠ \(msg)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(C.dangerRed)
                }

                // 连接 / 断开按钮
                Button {
                    urlFocused.wrappedValue = false
                    if isConnected {
                        vm.disconnect()
                    } else {
                        vm.connect()
                    }
                } label: {
                    HStack(spacing: 8) {
                        if isBusy {
                            ProgressView()
                                .tint(C.warnOrange)
                                .scaleEffect(0.8)
                            Text(vm.autoConnecting ? "AUTO CONNECTING..." : "CONNECTING...")
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                .foregroundStyle(C.warnOrange)
                        } else if isConnected {
                            Image(systemName: "link.badge.minus")
                            Text("DISCONNECT")
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        } else {
                            Image(systemName: "link")
                            Text("CONNECT")
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(isConnected ? C.dangerRed.opacity(0.2) : C.neonBlueFade)
                .foregroundStyle(isConnected ? C.dangerRed : C.neonBlue)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(
                            (isConnected ? C.dangerRed : C.neonBlue).opacity(0.5),
                            lineWidth: 1
                        )
                )
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .disabled(isBusy)
            }
        }
    }
}

// ── 已保存 URL 卡片 ────────────────────────────────────────────────────────────

private struct SavedUrlsCard: View {
    @Environment(MonitorViewModel.self) private var vm
    @State private var editingUrl: String? = nil
    @State private var editingRemark: String = ""

    private var isConnected:  Bool { vm.wsState == .connected }
    private var isConnecting: Bool { vm.wsState == .connecting }
    private var isBusy:       Bool { isConnecting || vm.autoConnecting }

    var body: some View {
        GlassCard(accentColor: C.memPurple) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    CardLabel(text: "SAVED  \(vm.savedUrls.count)/10", color: C.memPurple)
                    Spacer()
                    Text("点击连接  长按编辑备注")
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(C.textMuted)
                }

                VStack(spacing: 4) {
                    ForEach(vm.savedUrls, id: \.self) { url in
                        SavedUrlRow(
                            url: url,
                            remark: vm.getRemark(for: url),
                            isActive: url == vm.connectedUrl && isConnected,
                            editingUrl: $editingUrl,
                            editingRemark: $editingRemark,
                            onConnect: {
                                guard !isConnected && !isBusy else { return }
                                vm.connectTo(url)
                            },
                            onDelete: { vm.removeUrl(url) },
                            onSaveRemark: { vm.saveRemark($0, for: url) }
                        )
                    }
                }
            }
        }
    }
}

// ── 单条已保存 URL 行 ──────────────────────────────────────────────────────────

private struct SavedUrlRow: View {
    let url:     String
    let remark:  String
    let isActive: Bool
    @Binding var editingUrl:    String?
    @Binding var editingRemark: String

    var onConnect:     () -> Void
    var onDelete:      () -> Void
    var onSaveRemark:  (String) -> Void

    @FocusState private var remarkFocused: Bool
    private var isEditing: Bool { editingUrl == url }

    var body: some View {
        VStack(spacing: 0) {
            // 主行
            HStack(spacing: 8) {
                Circle()
                    .fill(isActive ? C.liveGreen : C.textMuted)
                    .frame(width: 6, height: 6)

                VStack(alignment: .leading, spacing: 2) {
                    if !remark.isEmpty {
                        Text(remark)
                            .font(.system(size: 10, weight: .semibold, design: .monospaced))
                            .foregroundStyle(C.memPurple)
                            .lineLimit(1)
                    }
                    Text(url)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(isActive ? C.liveGreen : C.textSecondary)
                        .lineLimit(1)
                }

                Spacer()

                // 编辑备注按钮
                Button {
                    editingUrl    = url
                    editingRemark = remark
                    remarkFocused = true
                } label: {
                    Image(systemName: "pencil")
                        .font(.system(size: 11))
                        .foregroundStyle(C.textMuted.opacity(0.6))
                }

                // 删除按钮（非当前连接才显示）
                if !isActive {
                    Button(role: .destructive) {
                        onDelete()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 11))
                            .foregroundStyle(C.textMuted)
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(isActive ? C.memPurple.opacity(0.12) : C.bgSlate.opacity(0.4))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .contentShape(RoundedRectangle(cornerRadius: 8))
            .onTapGesture { onConnect() }
            .onLongPressGesture {
                editingUrl    = url
                editingRemark = remark
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    remarkFocused = true
                }
            }

            // 内联备注编辑行
            if isEditing {
                HStack(spacing: 6) {
                    TextField("输入备注（如：家里Mac）", text: $editingRemark)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(C.textPrimary)
                        .tint(C.memPurple)
                        .focused($remarkFocused)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(C.bgCardAlt)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(C.memPurple.opacity(0.6), lineWidth: 1)
                        )
                        .onSubmit {
                            onSaveRemark(editingRemark)
                            editingUrl = nil
                        }

                    Button {
                        onSaveRemark(editingRemark)
                        editingUrl = nil
                    } label: {
                        Image(systemName: "checkmark")
                            .foregroundStyle(C.memPurple)
                            .frame(width: 32, height: 32)
                    }

                    Button {
                        editingUrl = nil
                    } label: {
                        Image(systemName: "xmark")
                            .foregroundStyle(C.textMuted)
                            .frame(width: 32, height: 32)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.top, 4)
            }
        }
    }
}
