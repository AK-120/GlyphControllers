#!/usr/bin/env python3
"""
Generates the dot-matrix logos:

  drawable/ic_launcher_fg_{ring,g,bolt}.xml   adaptive-icon foregrounds (white dots + one red accent dot)
  mipmap-anydpi/ic_launcher_{ring,g,bolt}.xml adaptive icons (with a monochrome layer for themed icons)
  drawable/ic_notif_{ring,g,bolt}.xml         status-bar / picker icons (single colour, white)

    python3 tools/gen_icons.py
"""
import math
import os

N = 13
C = 6.0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def ring_pixels():
    dots, accent = set(), set()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - C, y - C)
            if 4.6 <= d <= 6.2 or d < 0.5:
                dots.add((x, y))
    accent.add((6, 6))
    # a gap in the ring at the top-right, like an orbit that has not closed yet
    for p in ((9, 2), (10, 3)):
        dots.discard(p)
    return dots, accent


G_BITMAP = [
    "..###..",
    ".#...#.",
    "#.....#",
    "#......",
    "#..####",
    "#.....#",
    "#.....#",
    ".#...#.",
    "..###..",
]


def g_pixels():
    dots, accent = set(), set()
    ox, oy = 3, 2
    for r, line in enumerate(G_BITMAP):
        for c, ch in enumerate(line):
            if ch == "#":
                dots.add((ox + c, oy + r))
    accent.add((ox + 6, oy + 4))
    return dots, accent


BOLT = [(7.6, 0.4), (3.2, 7.0), (6.0, 7.0), (4.4, 12.4), (9.8, 5.0), (7.0, 5.0)]


def inside(pts, x, y):
    c = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi:
            c = not c
        j = i
    return c


def bolt_pixels():
    dots, accent = set(), set()
    for y in range(N):
        for x in range(N):
            if inside(BOLT, x, y):
                dots.add((x, y))
    top = min(dots, key=lambda p: (p[1], -p[0]))
    accent.add(top)
    return dots, accent


STYLES = {"ring": ring_pixels(), "g": g_pixels(), "bolt": bolt_pixels()}


def circle(cx, cy, r):
    return ("M%.2f,%.2f m-%.2f,0 a%.2f,%.2f 0 1,0 %.2f,0 a%.2f,%.2f 0 1,0 -%.2f,0"
            % (cx, cy, r, r, r, 2 * r, r, r, 2 * r))


def path_data(pixels, vp, pitch, radius):
    off = (vp - pitch * (N - 1)) / 2.0
    return " ".join(circle(off + x * pitch, off + y * pitch, radius) for x, y in sorted(pixels))


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def vector(vp, parts):
    body = "".join('    <path android:fillColor="%s" android:pathData="%s"/>\n' % (c, d) for c, d in parts)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="%ddp" android:height="%ddp"\n'
            '    android:viewportWidth="%d" android:viewportHeight="%d">\n%s</vector>\n') % (vp, vp, vp, vp, body)


for name, (dots, accent) in STYLES.items():
    plain = dots - accent
    write(os.path.join(RES, "drawable", "ic_launcher_fg_%s.xml" % name),
          vector(108, [("#FFFFFFFF", path_data(plain, 108, 4.0, 1.55)),
                       ("#FFD71921", path_data(accent, 108, 4.0, 1.75))]))
    write(os.path.join(RES, "drawable", "ic_notif_%s.xml" % name),
          vector(24, [("#FFFFFFFF", path_data(dots, 24, 1.62, 0.62))]))
    write(os.path.join(RES, "mipmap-anydpi", "ic_launcher_%s.xml" % name),
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@color/ic_launcher_background"/>\n'
          '    <foreground android:drawable="@drawable/ic_launcher_fg_%s"/>\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_fg_%s"/>\n'
          '</adaptive-icon>\n' % (name, name))
    print("%-5s %3d dots" % (name, len(dots)))

# The old single icon is replaced by the three styles above.
for old in (os.path.join(RES, "mipmap-anydpi", "ic_launcher.xml"),
            os.path.join(RES, "drawable", "ic_launcher_foreground.xml")):
    if os.path.exists(old):
        os.remove(old)

# ASCII preview of each logo
for name, (dots, accent) in STYLES.items():
    print("--", name)
    for y in range(N):
        print("".join(("R " if (x, y) in accent else "# " if (x, y) in dots else ". ") for x in range(N)))
