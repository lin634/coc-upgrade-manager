#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成应用图标:绿色圆角方块 + 白色时钟,输出各密度 mipmap PNG(纯 stdlib)"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def png_write(path, w, h, rows):
    raw = b""
    for row in rows:
        raw += b"\x00" + bytes(v for p in row for v in p)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)


def make_icon(size):
    cx = cy = (size - 1) / 2.0
    half = (size - 1) / 2.0
    radius = size * 0.22
    ring = size * 0.32
    thick = size * 0.06
    hand = size * 0.035
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            dx = max(abs(x - cx) - (half - radius), 0)
            dy = max(abs(y - cy) - (half - radius), 0)
            if dx * dx + dy * dy > radius * radius:
                row.append((0, 0, 0, 0))
                continue
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if ring - thick <= d <= ring:
                col = (255, 255, 255)
            elif abs(x - cx) <= hand and cy - ring * 0.7 <= y <= cy:
                col = (255, 255, 255)  # 时针(向上)
            elif abs(y - cy) <= hand and cx <= x <= cx + ring * 0.7:
                col = (255, 255, 255)  # 分针(向右)
            else:
                # 顶部到底部轻微渐变的绿色
                t = y / max(size - 1, 1)
                col = (int(56 - 10 * t), int(142 - 20 * t), int(60 - 15 * t))
            row.append((col[0], col[1], col[2], 255))
        rows.append(row)
    return rows


def main():
    for dpi, size in SIZES.items():
        d = os.path.join(ROOT, "res", "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        png_write(os.path.join(d, "ic_launcher.png"), size, size, make_icon(size))
        print("wrote mipmap-%s (%dx%d)" % (dpi, size, size))


if __name__ == "__main__":
    main()
