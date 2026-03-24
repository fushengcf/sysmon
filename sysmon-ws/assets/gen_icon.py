#!/usr/bin/env python3
"""
用 macOS AppKit 渲染真实 🔥 emoji 图标（透明背景）
输出 iconset 目录，供 iconutil 转换为 .icns
"""
import os
import sys

try:
    from AppKit import (
        NSBitmapImageRep, NSGraphicsContext, NSImage,
        NSAttributedString, NSFont, NSColor,
        NSMakeRect, NSMakeSize,
        NSFontAttributeName,
        NSBitmapImageFileTypePNG,
        NSImageCompressionFactor,
        NSDeviceRGBColorSpace,
    )
    import AppKit
except ImportError:
    os.system(f"{sys.executable} -m pip install pyobjc-framework-Cocoa -q")
    from AppKit import (
        NSBitmapImageRep, NSGraphicsContext, NSImage,
        NSAttributedString, NSFont, NSColor,
        NSMakeRect, NSMakeSize,
        NSFontAttributeName,
        NSBitmapImageFileTypePNG,
        NSImageCompressionFactor,
        NSDeviceRGBColorSpace,
    )
    import AppKit

ICONSET = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.iconset")
os.makedirs(ICONSET, exist_ok=True)

SIZES = [16, 32, 64, 128, 256, 512, 1024]
EMOJI = "🔥"


def render_emoji_png(size: int, path: str):
    """用 AppKit 在 size×size 画布上渲染 emoji（透明背景），保存为 PNG"""

    # 关键：使用 NSDeviceRGBColorSpace + premultiplied alpha
    # NSAlphaFirstBitmapFormat = 1, NSPremultipliedAlphaBitmapFormat = 2
    rep = NSBitmapImageRep.alloc().initWithBitmapDataPlanes_pixelsWide_pixelsHigh_bitsPerSample_samplesPerPixel_hasAlpha_isPlanar_colorSpaceName_bitmapFormat_bytesPerRow_bitsPerPixel_(
        None,                   # planes (auto-allocate)
        size,                   # pixelsWide
        size,                   # pixelsHigh
        8,                      # bitsPerSample
        4,                      # samplesPerPixel (RGBA)
        True,                   # hasAlpha
        False,                  # isPlanar
        NSDeviceRGBColorSpace,  # colorSpaceName
        0,                      # bitmapFormat (0 = non-premultiplied, alpha last)
        0,                      # bytesPerRow (auto)
        0,                      # bitsPerPixel (auto)
    )
    rep.setSize_(NSMakeSize(size, size))

    ctx = NSGraphicsContext.graphicsContextWithBitmapImageRep_(rep)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.setCurrentContext_(ctx)

    # 完全透明背景（alpha = 0）
    NSColor.clearColor().set()
    AppKit.NSRectFill(NSMakeRect(0, 0, size, size))

    # 字体大小：让 emoji 充满画布（约 88%）
    font_size = size * 0.88
    font = NSFont.fontWithName_size_("AppleColorEmoji", font_size)
    if font is None:
        font = NSFont.systemFontOfSize_(font_size)

    attrs = {NSFontAttributeName: font}
    astr = NSAttributedString.alloc().initWithString_attributes_(EMOJI, attrs)

    # 居中绘制
    text_size = astr.size()
    x = (size - text_size.width) / 2.0
    y = (size - text_size.height) / 2.0
    astr.drawAtPoint_((x, y))

    NSGraphicsContext.restoreGraphicsState()

    # 导出 PNG（保留 alpha 通道）
    props = {NSImageCompressionFactor: 1.0}
    png_data = rep.representationUsingType_properties_(NSBitmapImageFileTypePNG, props)
    png_data.writeToFile_atomically_(path, True)


print(f"Rendering '{EMOJI}' emoji icons (transparent bg) → {ICONSET}/")

for size in SIZES:
    out = os.path.join(ICONSET, f"icon_{size}x{size}.png")
    render_emoji_png(size, out)
    print(f"  ✓ icon_{size}x{size}.png")

    if size <= 512:
        out2x = os.path.join(ICONSET, f"icon_{size}x{size}@2x.png")
        render_emoji_png(size * 2, out2x)
        print(f"  ✓ icon_{size}x{size}@2x.png")

print("Done.")
