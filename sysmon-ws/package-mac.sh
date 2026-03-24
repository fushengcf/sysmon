#!/usr/bin/env bash
# package-mac.sh — 一键构建 macOS .app + .dmg 安装包
#
# 用法：
#   ./package-mac.sh              # 构建当前架构（arm64 或 x86_64）
#   ./package-mac.sh --universal  # 构建 Universal Binary（需要 Rosetta 环境）
#
# 产物：
#   dist/SysMon WS.app
#   dist/SysMon-WS-0.1.0.dmg

set -euo pipefail

# ── 配置 ──────────────────────────────────────────────────────────────────────
APP_NAME="SysMon WS"
BUNDLE_ID="com.sysmon-ws.app"
VERSION="0.1.0"
BINARY_NAME="sysmon-ws"
DIST_DIR="dist"
ASSETS_DIR="assets"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[PKG]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR]${NC}  $*"; exit 1; }

# ── 检查依赖 ──────────────────────────────────────────────────────────────────
command -v cargo    &>/dev/null || err "cargo not found. Install Rust: https://rustup.rs"
command -v iconutil &>/dev/null || err "iconutil not found (requires macOS)"
command -v hdiutil  &>/dev/null || err "hdiutil not found (requires macOS)"

# ── 解析参数 ──────────────────────────────────────────────────────────────────
UNIVERSAL=false
for arg in "$@"; do
    case "$arg" in
        --universal) UNIVERSAL=true ;;
        *) err "Unknown argument: $arg" ;;
    esac
done

# ── Step 1: 生成图标 ──────────────────────────────────────────────────────────
log "Generating app icon..."
if [[ ! -f "$ASSETS_DIR/AppIcon.icns" ]]; then
    python3 "$ASSETS_DIR/gen_icon.py"
    iconutil -c icns "$ASSETS_DIR/icon.iconset" -o "$ASSETS_DIR/AppIcon.icns"
    log "Icon created: $ASSETS_DIR/AppIcon.icns"
else
    log "Icon already exists, skipping."
fi

# ── Step 2: 编译 Rust 二进制 ──────────────────────────────────────────────────
log "Building Rust binary..."
if $UNIVERSAL; then
    log "  Building arm64..."
    rustup target add aarch64-apple-darwin 2>/dev/null || true
    cargo build --release --target aarch64-apple-darwin

    log "  Building x86_64..."
    rustup target add x86_64-apple-darwin 2>/dev/null || true
    cargo build --release --target x86_64-apple-darwin

    log "  Creating Universal Binary..."
    lipo -create -output "target/${BINARY_NAME}-universal" \
        "target/aarch64-apple-darwin/release/${BINARY_NAME}" \
        "target/x86_64-apple-darwin/release/${BINARY_NAME}"
    BINARY_PATH="target/${BINARY_NAME}-universal"
else
    cargo build --release
    BINARY_PATH="target/release/${BINARY_NAME}"
fi
log "Binary: $BINARY_PATH ($(du -sh "$BINARY_PATH" | cut -f1))"

# ── Step 3: 组装 .app bundle ──────────────────────────────────────────────────
APP_DIR="$DIST_DIR/${APP_NAME}.app"
CONTENTS="$APP_DIR/Contents"
MACOS="$CONTENTS/MacOS"
RESOURCES="$CONTENTS/Resources"

log "Assembling .app bundle..."
rm -rf "$APP_DIR"
mkdir -p "$MACOS" "$RESOURCES"

# 复制二进制
cp "$BINARY_PATH" "$MACOS/${BINARY_NAME}"
chmod +x "$MACOS/${BINARY_NAME}"

# 复制 Info.plist
cp "$ASSETS_DIR/Info.plist" "$CONTENTS/Info.plist"

# 复制图标
cp "$ASSETS_DIR/AppIcon.icns" "$RESOURCES/AppIcon.icns"

# 写入 PkgInfo（标准 macOS 格式）
echo -n "APPL????" > "$CONTENTS/PkgInfo"

log ".app bundle assembled: $APP_DIR"

# ── Step 4: 代码签名（临时自签名，方便本地运行）─────────────────────────────
log "Code signing (ad-hoc)..."
codesign --force --deep --sign - "$APP_DIR" 2>/dev/null && \
    log "Ad-hoc signature applied." || \
    warn "codesign failed (non-fatal, app may show Gatekeeper warning)"

# ── Step 5: 创建安装包（优先 .dmg，降级为 .zip）────────────────────────────
DMG_NAME="${BINARY_NAME}-${VERSION}.dmg"
ZIP_NAME="${BINARY_NAME}-${VERSION}-mac.zip"
DMG_PATH="$DIST_DIR/$DMG_NAME"
ZIP_PATH="$DIST_DIR/$ZIP_NAME"
STAGING_DIR="$DIST_DIR/dmg-staging"

log "Creating installer..."

# 尝试用 hdiutil 创建 DMG
DMG_OK=false
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"
cp -R "$APP_DIR" "$STAGING_DIR/"
ln -s /Applications "$STAGING_DIR/Applications"

if hdiutil create \
    -volname "${APP_NAME}" \
    -srcfolder "$STAGING_DIR" \
    -ov \
    -format UDZO \
    -imagekey zlib-level=9 \
    "$DMG_PATH" 2>/dev/null; then
    DMG_OK=true
fi
rm -rf "$STAGING_DIR"

if $DMG_OK; then
    log "✅ Done!"
    echo ""
    echo "  📦 .app bundle : $APP_DIR"
    echo "  💿 .dmg package: $DMG_PATH  ($(du -sh "$DMG_PATH" | cut -f1))"
    echo ""
    echo "  安装方式："
    echo "    双击 $DMG_NAME → 将 '${APP_NAME}.app' 拖入 Applications 文件夹"
else
    # 降级：打包为 .zip（macOS 标准分发格式，Gatekeeper 同样支持）
    warn "hdiutil unavailable, falling back to .zip packaging"
    cd "$DIST_DIR"
    zip -r --symlinks "$ZIP_NAME" "${APP_NAME}.app" 2>/dev/null
    cd - >/dev/null

    log "✅ Done!"
    echo ""
    echo "  📦 .app bundle : $APP_DIR"
    echo "  🗜  .zip package: $ZIP_PATH  ($(du -sh "$ZIP_PATH" | cut -f1))"
    echo ""
    echo "  安装方式："
    echo "    解压 $ZIP_NAME → 将 '${APP_NAME}.app' 拖入 /Applications 文件夹"
    echo ""
    echo "  如需生成 .dmg，在终端中运行："
    echo "    hdiutil create -volname '${APP_NAME}' -srcfolder '$APP_DIR' \\"
    echo "      -ov -format UDZO '$DMG_PATH'"
fi

echo ""
echo "  替换图标："
echo "    将新图标保存为 $ASSETS_DIR/AppIcon.icns，然后重新运行此脚本"
