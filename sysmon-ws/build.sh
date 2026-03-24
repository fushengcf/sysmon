#!/usr/bin/env bash
# build.sh — 跨平台构建脚本（在 macOS/Linux 上运行）
#
# 用法：
#   ./build.sh              # 仅构建当前平台
#   ./build.sh --all        # 构建全部三个目标平台
#   ./build.sh --mac        # 仅构建 macOS (aarch64 + x86_64 universal)
#   ./build.sh --win        # 仅构建 Windows x86_64
#   ./build.sh --linux      # 仅构建 Linux x86_64

set -euo pipefail

BINARY_NAME="sysmon-ws"
OUT_DIR="dist"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[BUILD]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

mkdir -p "$OUT_DIR"

build_mac() {
    log "Building macOS (Apple Silicon arm64)..."
    rustup target add aarch64-apple-darwin 2>/dev/null || true
    cargo build --release --target aarch64-apple-darwin
    cp "target/aarch64-apple-darwin/release/$BINARY_NAME" "$OUT_DIR/${BINARY_NAME}-macos-arm64"

    log "Building macOS (Intel x86_64)..."
    rustup target add x86_64-apple-darwin 2>/dev/null || true
    cargo build --release --target x86_64-apple-darwin
    cp "target/x86_64-apple-darwin/release/$BINARY_NAME" "$OUT_DIR/${BINARY_NAME}-macos-x86_64"

    # 合并为 Universal Binary（需要 lipo，仅 macOS 可用）
    if command -v lipo &>/dev/null; then
        log "Creating macOS Universal Binary..."
        lipo -create -output "$OUT_DIR/${BINARY_NAME}-macos-universal" \
            "$OUT_DIR/${BINARY_NAME}-macos-arm64" \
            "$OUT_DIR/${BINARY_NAME}-macos-x86_64"
        log "Universal binary: $OUT_DIR/${BINARY_NAME}-macos-universal"
    fi
}

build_win() {
    log "Building Windows x86_64..."
    # 需要安装 mingw-w64 工具链
    # macOS: brew install mingw-w64
    # Ubuntu: sudo apt install gcc-mingw-w64-x86-64
    rustup target add x86_64-pc-windows-gnu 2>/dev/null || true

    if ! command -v x86_64-w64-mingw32-gcc &>/dev/null; then
        warn "mingw-w64 not found. Install it first:"
        warn "  macOS:  brew install mingw-w64"
        warn "  Ubuntu: sudo apt install gcc-mingw-w64-x86-64"
        return 1
    fi

    cargo build --release --target x86_64-pc-windows-gnu
    cp "target/x86_64-pc-windows-gnu/release/${BINARY_NAME}.exe" \
       "$OUT_DIR/${BINARY_NAME}-windows-x86_64.exe"
    log "Windows binary: $OUT_DIR/${BINARY_NAME}-windows-x86_64.exe"
}

build_linux() {
    log "Building Linux x86_64 (Ubuntu/Debian)..."
    rustup target add x86_64-unknown-linux-gnu 2>/dev/null || true

    # 如果在 macOS 上交叉编译 Linux，需要 cross 工具
    if [[ "$(uname)" == "Darwin" ]]; then
        if command -v cross &>/dev/null; then
            cross build --release --target x86_64-unknown-linux-gnu
        else
            warn "cross not found. Install it for macOS→Linux cross-compilation:"
            warn "  cargo install cross"
            warn "  (requires Docker)"
            return 1
        fi
    else
        cargo build --release --target x86_64-unknown-linux-gnu
    fi

    cp "target/x86_64-unknown-linux-gnu/release/$BINARY_NAME" \
       "$OUT_DIR/${BINARY_NAME}-linux-x86_64"
    log "Linux binary: $OUT_DIR/${BINARY_NAME}-linux-x86_64"
}

# 解析参数
BUILD_ALL=false
BUILD_MAC=false
BUILD_WIN=false
BUILD_LINUX=false

if [[ $# -eq 0 ]]; then
    # 无参数：构建当前平台
    case "$(uname)" in
        Darwin) BUILD_MAC=true ;;
        Linux)  BUILD_LINUX=true ;;
        *)      err "Unsupported platform: $(uname)" ;;
    esac
else
    for arg in "$@"; do
        case "$arg" in
            --all)   BUILD_ALL=true ;;
            --mac)   BUILD_MAC=true ;;
            --win)   BUILD_WIN=true ;;
            --linux) BUILD_LINUX=true ;;
            *) err "Unknown argument: $arg" ;;
        esac
    done
fi

if $BUILD_ALL; then
    BUILD_MAC=true
    BUILD_WIN=true
    BUILD_LINUX=true
fi

$BUILD_MAC   && build_mac   || true
$BUILD_WIN   && build_win   || true
$BUILD_LINUX && build_linux || true

log "Done! Artifacts in ./$OUT_DIR/"
ls -lh "$OUT_DIR/"
