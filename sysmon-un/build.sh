#!/usr/bin/env bash
# build.sh — sysmon-un 构建脚本（Ubuntu 25 桌面版）
#
# 首次使用：自动安装 Rust + 系统依赖
# 用法：chmod +x build.sh && ./build.sh
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
log()  { echo -e "${GREEN}[BUILD]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }

BINARY_NAME="sysmon-un"
OUT_DIR="dist"

# ── 安装系统依赖 ──────────────────────────────────────────────────────────────
install_deps() {
    log "安装系统依赖..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq \
        build-essential pkg-config \
        libgtk-3-dev libayatana-appindicator3-dev libdbus-1-dev \
        libssl-dev
    log "系统依赖安装完成"
}

# ── 安装 Rust ──────────────────────────────────────────────────────────────────
install_rust() {
    if command -v cargo &>/dev/null; then
        log "Rust 已安装: $(cargo --version)"
        return
    fi
    log "安装 Rust 工具链..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
    log "Rust 安装完成: $(cargo --version)"
}

# ── 构建 ──────────────────────────────────────────────────────────────────────
build() {
    log "编译 sysmon-un (release)..."
    cargo build --release
    mkdir -p "$OUT_DIR"
    cp "target/release/$BINARY_NAME" "$OUT_DIR/"
    cp client-demo.html "$OUT_DIR/"
    log "构建完成！"
    ls -lh "$OUT_DIR/"
}

# ── 打包 ──────────────────────────────────────────────────────────────────────
package() {
    local archive="sysmon-un-linux-x86_64.tar.gz"
    log "打包: $archive"
    tar czf "$archive" -C "$OUT_DIR" "$BINARY_NAME" client-demo.html
    log "产物: $(pwd)/$archive ($(du -h "$archive" | cut -f1))"
}

# ── 主流程 ─────────────────────────────────────────────────────────────────────
case "${1:-}" in
    --deps)   install_deps; exit 0 ;;
    --rust)   install_rust; exit 0 ;;
    --build)  build; exit 0 ;;
    --pack)   package; exit 0 ;;
esac

install_deps
install_rust
build
package

log "全部完成！文件："
echo "  dist/sysmon-un          — 可执行文件"
echo "  dist/client-demo.html   — 浏览器监控面板"
echo "  sysmon-un-linux-x86_64.tar.gz — 压缩包"
echo ""
log "运行: ./dist/sysmon-un        (托盘模式)"
log "运行: ./dist/sysmon-un --no-tray  (纯服务模式)"
log "浏览器打开 client-demo.html 连接 ws://localhost:9001 查看监控"
