#!/usr/bin/env python3
"""Generate raster launcher icons for Portal Meta AI (stdlib zlib only).

Dark rounded tile + a glowing blue->purple gradient orb + a white infinity glyph.
Raster (not adaptive) because the Portal "aloha" launcher ignores adaptive icons.
Writes app/res/mipmap-*dpi/{ic_launcher,ic_launcher_round}.png.
"""
import os, struct, zlib, math

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "res")
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 3

BG_TOP = (0x10, 0x1a, 0x33)
BG_BOT = (0x05, 0x07, 0x0d)
CY = (0x7f, 0xd6, 0xff)
PU = (0x9b, 0x3c, 0xff)
WHITE = (0xff, 0xff, 0xff)


def lerp(a, b, t):
    return (int(a[0] + (b[0] - a[0]) * t),
            int(a[1] + (b[1] - a[1]) * t),
            int(a[2] + (b[2] - a[2]) * t))


def render(size, round_icon):
    S = size * SS
    r = S * 0.23
    cx = cy = S / 2.0
    rad = S / 2.0
    orbR = S * 0.34
    hx, hy = cx - orbR * 0.32, cy - orbR * 0.32      # highlight
    gd = orbR * 0.36                                  # infinity loop offset
    gr = orbR * 0.34                                  # loop radius
    gw = orbR * 0.13                                  # stroke half-width
    px = bytearray(4 * S * S)
    for y in range(S):
        yc = y + 0.5
        for x in range(S):
            xc = x + 0.5
            if round_icon:
                if (xc - cx) ** 2 + (yc - cy) ** 2 > rad * rad:
                    continue
            else:
                qx = min(max(xc, r), S - r)
                qy = min(max(yc, r), S - r)
                if (xc - qx) ** 2 + (yc - qy) ** 2 > r * r:
                    continue
            t = yc / S
            col = lerp(BG_TOP, BG_BOT, t)
            do = math.hypot(xc - cx, yc - cy)
            if do <= orbR:
                dh = min(1.0, math.hypot(xc - hx, yc - hy) / (orbR * 1.5))
                col = lerp(CY, PU, dh)
                dl = abs(math.hypot(xc - (cx - gd), yc - cy) - gr)
                dr = abs(math.hypot(xc - (cx + gd), yc - cy) - gr)
                if dl < gw or dr < gw:
                    col = WHITE
            o = 4 * (y * S + x)
            px[o] = col[0]; px[o+1] = col[1]; px[o+2] = col[2]; px[o+3] = 255
    return downsample(px, S, size)


def downsample(px, S, size):
    out = bytearray(4 * size * size)
    n = SS * SS
    for oy in range(size):
        for ox in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                for dx in range(SS):
                    o = 4 * ((oy * SS + dy) * S + (ox * SS + dx))
                    al = px[o+3]
                    r += px[o]*al; g += px[o+1]*al; b += px[o+2]*al; a += al
            oo = 4 * (oy * size + ox)
            if a:
                out[oo] = r//a; out[oo+1] = g//a; out[oo+2] = b//a
            out[oo+3] = a // n
    return out


def write_png(path, rgba, size):
    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data +
                struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))
    raw = bytearray()
    stride = 4 * size
    for y in range(size):
        raw.append(0); raw += rgba[y*stride:(y+1)*stride]
    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(bytes(raw), 9)) +
           chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    for d, size in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + d)
        os.makedirs(out, exist_ok=True)
        write_png(os.path.join(out, "ic_launcher.png"), render(size, False), size)
        write_png(os.path.join(out, "ic_launcher_round.png"), render(size, True), size)
        print("wrote", out, size)


if __name__ == "__main__":
    main()
