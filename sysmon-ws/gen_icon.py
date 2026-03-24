#!/usr/bin/env python3
"""
gen_icon.py — 生成 sysmon-ws Windows 托盘图标

输出：assets/icon.ico（多尺寸：16x16, 32x32, 48x48, 64x64, 256x256）

依赖：Pillow
  pip install Pillow

用法：
  python gen_icon.py              # 生成默认深蓝色图标
  python gen_icon.py --color "#1a2f5e"  # 自定义颜色
"""

import argparse
import os
import struct
import zlib
from pathlib import Path


def parse_hex_color(hex_str: str) -> tuple[int, int, int]:
    """解析 #RRGGBB 格式颜色"""
    hex_str = hex_str.lstrip("#")
    if len(hex_str) != 6:
        raise ValueError(f"Invalid color: #{hex_str}")
    r = int(hex_str[0:2], 16)
    g = int(hex_str[2:4], 16)
    b = int(hex_str[4:6], 16)
    return r, g, b


def draw_monitor_icon(size: int, bg: tuple, fg: tuple) -> list[list[tuple]]:
    """
    绘制一个简单的显示器图标（纯 Python，不依赖 Pillow）
    返回 size×size 的 RGBA 像素列表
    """
    pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]
    br, bg_c, bb = bg
    fr, fg_c, fb = fg

    # 圆角矩形背景
    margin = max(1, size // 8)
    radius = max(2, size // 6)

    for y in range(size):
        for x in range(size):
            # 判断是否在圆角矩形内
            in_rect = (margin <= x < size - margin) and (margin <= y < size - margin)
            if in_rect:
                # 四个角的圆角处理
                corners = [
                    (margin + radius, margin + radius),
                    (size - margin - radius - 1, margin + radius),
                    (margin + radius, size - margin - radius - 1),
                    (size - margin - radius - 1, size - margin - radius - 1),
                ]
                in_corner_zone = False
                for cx, cy in corners:
                    if abs(x - cx) > radius or abs(y - cy) > radius:
                        continue
                    dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                    if dist > radius:
                        in_corner_zone = True
                        break
                if not in_corner_zone:
                    pixels[y][x] = (br, bg_c, bb, 255)

    # 绘制简单的 "W" 字母（代表 WebSocket）
    # 在中心区域绘制白色像素
    cx = size // 2
    cy = size // 2
    char_h = max(4, size // 3)
    char_w = max(3, size // 3)

    # 简单的 "W" 形状
    for i in range(char_h):
        t = i / max(char_h - 1, 1)
        y = cy - char_h // 2 + i

        # W 的四条竖线位置
        x1 = cx - char_w // 2
        x2 = cx - char_w // 4
        x3 = cx + char_w // 4
        x4 = cx + char_w // 2

        # 上半部分：两条外侧竖线
        if t < 0.5:
            for x in [x1, x4]:
                if 0 <= x < size and 0 <= y < size:
                    pixels[y][x] = (fr, fg_c, fb, 255)
        # 下半部分：四条线（W 的底部）
        else:
            for x in [x1, x2, x3, x4]:
                if 0 <= x < size and 0 <= y < size:
                    pixels[y][x] = (fr, fg_c, fb, 255)

    return pixels


def pixels_to_png_bytes(pixels: list[list[tuple]], size: int) -> bytes:
    """将像素数组转换为 PNG 字节（纯 Python 实现）"""
    # PNG 签名
    signature = b'\x89PNG\r\n\x1a\n'

    def make_chunk(chunk_type: bytes, data: bytes) -> bytes:
        length = struct.pack('>I', len(data))
        crc = struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)
        return length + chunk_type + data + crc

    # IHDR chunk
    ihdr_data = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    # 修正：IHDR 应该是 8 位深度，RGBA 颜色类型 = 6
    ihdr_data = struct.pack('>II', size, size) + bytes([8, 6, 0, 0, 0])
    ihdr = make_chunk(b'IHDR', ihdr_data)

    # IDAT chunk（图像数据）
    raw_data = b''
    for row in pixels:
        raw_data += b'\x00'  # filter type: None
        for r, g, b, a in row:
            raw_data += bytes([r, g, b, a])

    compressed = zlib.compress(raw_data, 9)
    idat = make_chunk(b'IDAT', compressed)

    # IEND chunk
    iend = make_chunk(b'IEND', b'')

    return signature + ihdr + idat + iend


def generate_ico(sizes: list[int], bg_color: tuple, fg_color: tuple, output_path: str):
    """生成多尺寸 ICO 文件"""
    images = []
    for size in sizes:
        pixels = draw_monitor_icon(size, bg_color, fg_color)
        png_bytes = pixels_to_png_bytes(pixels, size)
        images.append((size, png_bytes))

    # ICO 文件格式
    # Header: 6 bytes
    # Directory entries: 16 bytes each
    # Image data

    num_images = len(images)
    header = struct.pack('<HHH', 0, 1, num_images)  # reserved, type=1(ICO), count

    # 计算每个图像的偏移量
    dir_offset = 6 + 16 * num_images
    offsets = []
    current_offset = dir_offset
    for _, png_bytes in images:
        offsets.append(current_offset)
        current_offset += len(png_bytes)

    # 目录条目
    directory = b''
    for i, (size, png_bytes) in enumerate(images):
        w = size if size < 256 else 0
        h = size if size < 256 else 0
        directory += struct.pack('<BBBBHHII',
            w, h,           # width, height (0 = 256)
            0,              # color count (0 = no palette)
            0,              # reserved
            1,              # color planes
            32,             # bits per pixel
            len(png_bytes), # size of image data
            offsets[i],     # offset of image data
        )

    # 写入文件
    os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
    with open(output_path, 'wb') as f:
        f.write(header)
        f.write(directory)
        for _, png_bytes in images:
            f.write(png_bytes)

    print(f"[ok] Generated {output_path} ({num_images} sizes: {sizes})")


def main():
    parser = argparse.ArgumentParser(description="Generate sysmon-ws tray icon")
    parser.add_argument(
        "--color", default="#1a2f5e",
        help="Background color in #RRGGBB format (default: #1a2f5e)"
    )
    parser.add_argument(
        "--fg", default="#ffffff",
        help="Foreground color in #RRGGBB format (default: #ffffff)"
    )
    parser.add_argument(
        "--output", default="assets/icon.ico",
        help="Output path (default: assets/icon.ico)"
    )
    args = parser.parse_args()

    bg = parse_hex_color(args.color)
    fg = parse_hex_color(args.fg)

    # 尝试使用 Pillow 生成更高质量的图标
    try:
        from PIL import Image, ImageDraw, ImageFont
        generate_ico_pillow(bg, fg, args.output)
    except ImportError:
        print("[warn] Pillow not found, using built-in generator (lower quality)")
        print("       Install Pillow for better icons: pip install Pillow")
        sizes = [16, 32, 48, 64, 256]
        generate_ico(sizes, bg, fg, args.output)


def generate_ico_pillow(bg: tuple, fg: tuple, output_path: str):
    """使用 Pillow 生成高质量图标"""
    from PIL import Image, ImageDraw

    sizes = [16, 32, 48, 64, 256]
    images = []

    for size in sizes:
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)

        # 圆角矩形背景
        margin = max(1, size // 8)
        radius = max(2, size // 5)
        draw.rounded_rectangle(
            [margin, margin, size - margin - 1, size - margin - 1],
            radius=radius,
            fill=(*bg, 255)
        )

        # 绘制 "W" 字母
        font_size = max(8, int(size * 0.55))
        cx = size // 2
        cy = size // 2

        # 简单绘制 W 形状（用线段）
        lw = max(1, size // 12)
        h = int(size * 0.45)
        w = int(size * 0.45)
        x0 = cx - w // 2
        x4 = cx + w // 2
        x1 = x0 + w // 4
        x2 = cx
        x3 = x4 - w // 4
        y_top = cy - h // 2
        y_bot = cy + h // 2
        y_mid = cy + h // 6

        # W 的五个点：左上、左下、中上、右下、右上
        pts = [(x0, y_top), (x1, y_bot), (x2, y_mid), (x3, y_bot), (x4, y_top)]
        draw.line(pts, fill=(*fg, 255), width=lw)

        images.append(img)

    # 保存为 ICO
    os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
    images[0].save(
        output_path,
        format='ICO',
        sizes=[(s, s) for s in sizes],
        append_images=images[1:]
    )
    print(f"[ok] Generated {output_path} with Pillow ({len(sizes)} sizes: {sizes})")


if __name__ == '__main__':
    main()
