#!/usr/bin/env python3
"""
Generates animation pack files (new animations only, no overlap with the bundled defaults):

    app/src/main/assets/animations/pack_aod.json
    app/src/main/assets/animations/pack_volume.json
    app/src/main/assets/animations/pack_charging.json
    app/src/main/assets/animations/pack_notification.json
    (+ copies in examples/packs/)

    python3 tools/gen_pack.py

Frame legend: 13 rows x 13 chars. 0-9 a-f = intensity (nibble*17), '.' = off, '#' = full.
Identical frames are stored once and shared by name.
"""
import glob
import json
import math
import os
import random

N = 13
C = 6.0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "animations")
EXAMPLES = os.path.join(ROOT, "examples", "packs")
TAU = 2 * math.pi


# ----------------------------------------------------------------- drawing
def G():
    return [[0.0] * N for _ in range(N)]


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def put(g, x, y, v=255.0):
    xi, yi = int(round(x)), int(round(y))
    if 0 <= xi < N and 0 <= yi < N and v > g[yi][xi]:
        g[yi][xi] = min(255.0, v)


def dot(g, x, y, v=255.0, r=1.0):
    for yy in range(N):
        for xx in range(N):
            val = max(0.0, 1 - math.hypot(xx - x, yy - y) / r) * v
            if val > g[yy][xx]:
                g[yy][xx] = min(255.0, val)


def line(g, x0, y0, x1, y1, v=255.0, fade=None):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 3) + 1
    for i in range(steps + 1):
        t = i / steps
        vv = v if fade is None else v * (fade[0] + (fade[1] - fade[0]) * t)
        put(g, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, vv)


def ring(g, r, v=255.0, w=0.95, cx=C, cy=C, sy=1.0):
    for yy in range(N):
        for xx in range(N):
            d = math.hypot(xx - cx, (yy - cy) * sy)
            val = max(0.0, 1 - abs(d - r) / w) * v
            if val > g[yy][xx]:
                g[yy][xx] = min(255.0, val)


def disc(g, r, v=255.0, cx=C, cy=C):
    for yy in range(N):
        for xx in range(N):
            val = clamp(r - math.hypot(xx - cx, yy - cy) + 0.5) * v
            if val > g[yy][xx]:
                g[yy][xx] = min(255.0, val)


def rect(g, x0, y0, x1, y1, v=255.0, fill=False):
    for yy in range(int(y0), int(y1) + 1):
        for xx in range(int(x0), int(x1) + 1):
            edge = xx in (int(x0), int(x1)) or yy in (int(y0), int(y1))
            if fill or edge:
                put(g, xx, yy, v)


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


def poly(g, pts, v=255.0):
    for yy in range(N):
        for xx in range(N):
            hit = 0
            for sy in (-0.33, 0, 0.33):
                for sx in (-0.33, 0, 0.33):
                    if inside(pts, xx + sx, yy + sy):
                        hit += 1
            val = hit / 9.0 * v
            if val > g[yy][xx]:
                g[yy][xx] = min(255.0, val)


def sc(pts, s, cx=C, cy=C):
    return [(cx + (x - cx) * s, cy + (y - cy) * s) for x, y in pts]


def combine(*gs):
    out = G()
    for g in gs:
        for y in range(N):
            for x in range(N):
                out[y][x] = max(out[y][x], g[y][x])
    return out


def mul(g, k):
    return [[min(255.0, v * k) for v in r] for r in g]


def shift(g, dx, dy):
    out = G()
    for y in range(N):
        for x in range(N):
            nx, ny = x + dx, y + dy
            if 0 <= nx < N and 0 <= ny < N:
                out[ny][nx] = g[y][x]
    return out


def in_disc(x, y):
    return math.hypot(x - C, y - C) <= N / 2 - 0.15


def mask(g):
    return [[g[y][x] if in_disc(x, y) else 0.0 for x in range(N)] for y in range(N)]


def nib(v):
    return "0123456789abcdef"[max(0, min(15, int(round(v / 17.0))))]


def rows(g):
    return ["".join(nib(v) for v in r) for r in g]


def pp(frames):
    """ping-pong: forward then backward without repeating the ends"""
    return frames + frames[-2:0:-1]


def sweep(angle, tail=2.0, r_in=0.0, r_out=6.3):
    g = G()
    for y in range(N):
        for x in range(N):
            dx, dy = x - C, y - C
            d = math.hypot(dx, dy)
            if r_in <= d <= r_out:
                delta = angle - math.atan2(dy, dx)
                delta -= TAU * math.floor(delta / TAU)
                if delta < tail:
                    g[y][x] = (1 - delta / tail) * 255
    return g


def heart_fn(scale, band=None):
    g = G()
    for y in range(N):
        for x in range(N):
            u = (x - C) / (5.0 * scale)
            v = (C - y + 0.6) / (5.0 * scale)
            f = (u * u + v * v - 1) ** 3 - u * u * v ** 3
            if band is None:
                if f <= 0:
                    g[y][x] = 255.0
            elif -band <= f <= 0.02:
                g[y][x] = 255.0
    return g


BOLT = [(7.6, 0.4), (3.2, 7.0), (6.0, 7.0), (4.4, 12.4), (9.8, 5.0), (7.0, 5.0)]


def face(eye_l=True, eye_r=True, smile=1):
    g = G()
    if eye_l:
        put(g, 4, 4); put(g, 4, 5)
    else:
        put(g, 3, 5); put(g, 4, 5); put(g, 5, 5)
    if eye_r:
        put(g, 8, 4); put(g, 8, 5)
    else:
        put(g, 7, 5); put(g, 8, 5); put(g, 9, 5)
    pts = [(3, 8), (4, 9), (5, 9), (6, 9), (7, 9), (8, 9), (9, 8)] if smile else [(4, 9), (5, 9), (6, 9), (7, 9), (8, 9)]
    for x, y in pts:
        put(g, x, y, 220)
    return g


# ----------------------------------------------------------------- pack
class Pack:
    def __init__(self):
        self.frames = {}
        self.index = {}
        self.anims = {}

    def fr(self, g):
        key = tuple(rows(g))
        if key not in self.index:
            name = "f%03d" % len(self.index)
            self.index[key] = name
            self.frames[name] = list(key)
        return self.index[key]

    def frames_anim(self, name, label, role, grids, fps=12, brightness=60, loop=True,
                    durations=None, variants=None):
        entries = []
        for i, g in enumerate(grids):
            n = self.fr(g)
            d = durations[i] if durations and i < len(durations) else None
            entries.append({"frame": n, "duration": d} if d else n)
        a = {"label": label, "role": role, "loop": loop, "fps": fps, "brightness": brightness, "frames": entries}
        if variants:
            a["variants"] = variants
        assert name not in self.anims, name
        self.anims[name] = a

    def proc(self, name, label, role, gen, params, fps=20, brightness=60, loop=True, duration=None,
             variants=None):
        a = {"label": label, "role": role, "type": "procedural", "generator": gen, "loop": loop,
             "fps": fps, "brightness": brightness, "params": params}
        if duration:
            a["durationMs"] = duration
        if variants:
            a["variants"] = variants
        assert name not in self.anims, name
        self.anims[name] = a

    def write(self, paths):
        for p in paths:
            os.makedirs(os.path.dirname(p), exist_ok=True)
            lines = ['{', '  "schema": 1,', '  "frames": {']
            items = list(self.frames.items())
            for i, (k, v) in enumerate(items):
                lines.append('    %s: %s%s' % (json.dumps(k), json.dumps(v), ',' if i < len(items) - 1 else ''))
            lines.append('  },')
            lines.append('  "animations": ' + json.dumps(self.anims, indent=2).replace("\n", "\n  "))
            lines.append('}')
            with open(p, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")


# ======================================================================= AOD
aod = Pack()
R = "aod"

# --- geometric pulses
fs = []
for s in range(0, 7):
    g = G()
    if s == 0:
        put(g, 6, 6)
    else:
        rect(g, 6 - s, 6 - s, 6 + s, 6 + s, 255 * (1 - s * 0.1))
    fs.append(g)
fs.append(G())
aod.frames_anim("aod_square_pulse", "Square pulse", R, fs, fps=11)


def diamond(r, v=255.0):
    g = G()
    for y in range(N):
        for x in range(N):
            d = abs(x - C) + abs(y - C)
            g[y][x] = max(0.0, 1 - abs(d - r) / 0.9) * v
    return g


aod.frames_anim("aod_diamond_pulse", "Diamond pulse", R,
                [diamond(r, 255 * (1 - r * 0.07)) for r in (0, 1, 2, 3, 4, 5, 6, 7)] + [G()], fps=11)

fs = []
for s in range(1, 7):
    g = G()
    line(g, 6 - s, 6, 6 + s, 6, 255 * (1 - s * 0.08))
    line(g, 6, 6 - s, 6, 6 + s, 255 * (1 - s * 0.08))
    fs.append(g)
aod.frames_anim("aod_plus_pulse", "Plus pulse", R, fs + [G()], fps=11)

fs = []
for s in range(1, 7):
    g = G()
    line(g, 6 - s, 6 - s, 6 + s, 6 + s, 255 * (1 - s * 0.08))
    line(g, 6 - s, 6 + s, 6 + s, 6 - s, 255 * (1 - s * 0.08))
    fs.append(g)
aod.frames_anim("aod_x_pulse", "X pulse", R, fs + [G()], fps=11)

fs = []
for r in (1, 2, 3, 4, 5, 6, 5, 4, 3, 2):
    g = G()
    disc(g, r, 120 + r * 20)
    fs.append(g)
aod.frames_anim("aod_disc_breathe", "Solid disc breathe", R, fs, fps=10)

fs = []
for r in (6.2, 5.3, 4.4, 3.5, 2.6, 1.7, 0.9):
    g = G()
    ring(g, r, 255 * (0.4 + r / 10))
    fs.append(g)
fs.append(G())
aod.frames_anim("aod_ring_collapse", "Collapsing ring", R, fs, fps=12)

fs = []
for k in range(14):
    g = G()
    for off in (0.0, 3.2):
        r = (k * 0.5 + off) % 6.4
        ring(g, r, 255 * (1 - r / 7))
    fs.append(g)
aod.frames_anim("aod_double_ring", "Double ring", R, fs, fps=14)

# --- sweeps and spinners
aod.frames_anim("aod_radar", "Radar sweep", R,
                [combine(sweep(k * TAU / 16, 1.7), mul(ring_g := (lambda g: (ring(g, 3, 40), ring(g, 6, 40), g)[2])(G()), 1))
                 for k in range(16)], fps=14)
aod.frames_anim("aod_dual_spinner", "Dual spinner", R,
                [combine(sweep(k * TAU / 12, 1.5, 2.5, 5.8), sweep(k * TAU / 12 + math.pi, 1.5, 2.5, 5.8))
                 for k in range(12)], fps=14)
aod.frames_anim("aod_tri_spinner", "Triple spinner", R,
                [combine(*[sweep(k * TAU / 12 + i * TAU / 3, 1.0, 2.5, 5.8) for i in range(3)])
                 for k in range(12)], fps=14)

fs = []
for k in range(12):
    g = G()
    a = k * math.pi / 12
    line(g, 6 - 5.6 * math.cos(a), 6 - 5.6 * math.sin(a), 6 + 5.6 * math.cos(a), 6 + 5.6 * math.sin(a))
    dot(g, 6, 6, 255, 1.3)
    fs.append(g)
aod.frames_anim("aod_cross_rotate", "Rotating line", R, fs, fps=14)

fs = []
for x in pp(list(range(1, 12))):
    g = G()
    dot(g, x, 6, 255, 1.5)
    fs.append(g)
aod.frames_anim("aod_bounce_dot", "Ping-pong dot", R, fs, fps=18)

fs = []
for k in range(12):
    g = G()
    y = 10.5 - 9.5 * abs(math.sin(math.pi * k / 12))
    dot(g, 6, y, 255, 1.7)
    dot(g, 6, 12, 90 * (1 - (10.5 - y) / 10), 2.4)
    fs.append(g)
aod.frames_anim("aod_bounce_ball", "Bouncing ball", R, fs, fps=16)

pts = [(C + 5 * math.cos(TAU * i / 24), C + 5 * math.sin(TAU * i / 24)) for i in range(24)]
fs = []
for k in range(24):
    g = G()
    for j in range(7):
        x, y = pts[(k - j) % 24]
        dot(g, x, y, 255 * (1 - j / 7), 1.0)
    fs.append(g)
aod.frames_anim("aod_snake_ring", "Snake", R, fs, fps=18)

fs = []
for k in range(12):
    g = G()
    ph = k * TAU / 12
    for y in range(N):
        a = math.sin(y * 0.75 + ph)
        dot(g, C + 3.5 * a, y, 140 + 115 * math.cos(y * 0.75 + ph), 1.1)
        dot(g, C - 3.5 * a, y, 140 - 115 * math.cos(y * 0.75 + ph), 1.1)
    fs.append(mask(g))
aod.frames_anim("aod_dna", "DNA helix", R, fs, fps=12)

rnd = random.Random(3)
sites = []
while len(sites) < 18:
    x, y = rnd.randint(0, 12), rnd.randint(0, 12)
    if in_disc(x, y):
        sites.append((x, y, rnd.random(), rnd.choice([1, 1, 2])))
fs = []
for k in range(16):
    g = G()
    t = k / 16
    for x, y, ph, sp in sites:
        v = max(0.0, math.sin(TAU * (t * sp + ph))) ** 4 * 255
        put(g, x, y, v)
    fs.append(g)
aod.frames_anim("aod_twinkle", "Twinkle", R, fs, fps=10)

rnd = random.Random(11)
flakes = [(rnd.uniform(0, 12), rnd.uniform(0, 14), rnd.uniform(0.6, 1.0), rnd.uniform(0, TAU)) for _ in range(9)]
fs = []
for k in range(16):
    g = G()
    for x0, y0, sp, ph in flakes:
        y = (y0 + k * sp * 0.9) % 15 - 1
        x = x0 + 0.7 * math.sin(k * 0.4 + ph)
        dot(g, x, y, 255, 0.95)
    fs.append(g)
aod.frames_anim("aod_snow", "Snowfall", R, fs, fps=9)

fs = []
for k in range(5):
    g = G()
    dot(g, 6, 12 - k * 1.6, 255, 1.0)
    fs.append(g)
for p in range(9):
    g = G()
    for i in range(12):
        a = i * TAU / 12
        r = p * 0.7
        dot(g, 6 + r * math.cos(a), 5 + r * math.sin(a), 255 * (1 - p / 9), 0.95)
    fs.append(g)
fs += [G(), G()]
aod.frames_anim("aod_fireworks", "Fireworks", R, fs, fps=12)


def bolt_g(v=255.0, s=1.0):
    g = G()
    poly(g, sc(BOLT, s, 6.4, 6.4), v)
    return g


aod.frames_anim("aod_lightning_flicker", "Lightning flicker", R,
                [mul(bolt_g(), k) for k in (0, 0, 0, 1.0, 0.2, 0.8, 0, 0, 0.5, 0, 0, 0)], fps=10,
                durations=[600, 700, 500, 70, 60, 90, 400, 700, 80, 500, 600, 700])

rnd = random.Random(5)
spd = [1, 2, 1, 3, 2, 1, 2]
phs = [rnd.random() for _ in range(7)]
fs = []
fs_m = []
for k in range(16):
    g = G()
    gm = G()
    for i in range(7):
        h = 1 + 9 * (0.5 + 0.5 * math.sin(TAU * (k / 16 * spd[i] + phs[i])))
        hi = int(round(h))
        for y in range(N - hi, N):
            put(g, i * 2, y)
        half = int(round(h / 2))
        for y in range(6 - half, 6 + half + 1):
            put(gm, i * 2, y)
    fs.append(g)
    fs_m.append(gm)
aod.frames_anim("aod_equalizer", "Equalizer", R, fs, fps=12)
aod.frames_anim("aod_equalizer_mirror", "Equalizer (mirror)", R, fs_m, fps=12)

fs = []
for k in range(13):
    g = G()
    tmax = k / 12 * 4 * math.pi
    th = 0.0
    while th <= tmax:
        r = 0.4 + th / (4 * math.pi) * 5.8
        dot(g, 6 + r * math.cos(th), 6 + r * math.sin(th), 120 + 135 * (th / (4 * math.pi)), 0.9)
        th += 0.2
    fs.append(g)
fs += [mul(fs[-1], 0.6), mul(fs[-1], 0.3), G()]
aod.frames_anim("aod_spiral", "Spiral", R, fs, fps=12)

fs = []
for a_v in (255, 170, 85, 0, 85, 170):
    g = G()
    for y in range(N):
        for x in range(N):
            g[y][x] = a_v if (x + y) % 2 == 0 else 255 - a_v
    fs.append(mask(g))
aod.frames_anim("aod_checker", "Checker flip", R, fs, fps=7)

fs = []
for k in range(4):
    g = G()
    for y in range(N):
        for x in range(N):
            g[y][x] = max(0.0, 1 - ((x + y + k) % 4) / 1.6) * 255
    fs.append(mask(g))
aod.frames_anim("aod_diag_stripes", "Diagonal stripes", R, fs, fps=6)

fs = []
for k in range(6):
    g = G()
    for y in range(N):
        for x in range(N):
            g[y][x] = max(0.0, 1 - ((y + k) % 6) / 1.4) * 255
    fs.append(mask(g))
aod.frames_anim("aod_scroll_bars", "Scrolling bars", R, fs, fps=8)

fs = []
for k in range(N + 4):
    g = G()
    cc = k - 2
    for y in range(N):
        for x in range(N):
            g[y][x] = max(0.0, 1 - abs(x - cc) / 2.2) * 255
    fs.append(mask(g))
aod.frames_anim("aod_wipe", "Vertical wipe", R, fs, fps=16)


def eye(dx=0, closed=False):
    g = G()
    if closed:
        line(g, 1, 6, 11, 6, 220)
        return g
    for y in range(N):
        for x in range(N):
            e = ((x - 6) / 5.6) ** 2 + ((y - 6) / 3.0) ** 2
            g[y][x] = max(0.0, 1 - abs(e - 1) / 0.35) * 220
    disc(g, 1.4, 255, 6 + dx, 6)
    return g


aod.frames_anim("aod_eye", "Looking eye", R,
                [eye(0), eye(-2), eye(0), eye(2), eye(0), eye(0, True), eye(0)], fps=10,
                durations=[1300, 500, 300, 500, 1000, 140, 900])

aod.frames_anim("aod_wink", "Wink", R,
                [face(), face(eye_r=False), face(), face(eye_l=False, eye_r=False)], fps=10,
                durations=[2000, 350, 1400, 140])

fs = []
for k in range(6):
    g = G()
    for i in range(3):
        v = 70 + 185 * (0.5 + 0.5 * math.cos(TAU * (k / 6 - i / 3)))
        dot(g, 3 + i * 3, 6, v, 1.4)
    fs.append(g)
aod.frames_anim("aod_loading_dots", "Loading dots", R, fs, fps=8)

order = [(0, 0), (1, 0), (2, 0), (2, 1), (2, 2), (1, 2), (0, 2), (0, 1)]
fs = []
for k in range(8):
    g = G()
    for j in range(4):
        cx, cy = order[(k - j) % 8]
        dot(g, 3 + cx * 3, 3 + cy * 3, 255 * (1 - j / 4), 1.3)
    dot(g, 6, 6, 60, 1.3)
    fs.append(g)
aod.frames_anim("aod_grid_chase", "Grid chase", R, fs, fps=10)

fs = []
for k in range(18):
    g = G()
    for i in range(3):
        a = k * TAU / 18 + i * TAU / 3
        dot(g, 6 + 4.5 * math.cos(a), 6 + 4.5 * math.sin(a), 255, 1.35)
    fs.append(g)
aod.frames_anim("aod_trio_orbit", "Trio orbit", R, fs, fps=16)

fs = []
for k in range(20):
    g = G()
    th = 0.75 * math.sin(TAU * k / 20)
    bx, by = 6 + 10.5 * math.sin(th), 10.5 * math.cos(th)
    line(g, 6, 0, bx, by - 1.2, 110)
    disc(g, 1.5, 255, bx, by)
    fs.append(g)
aod.frames_anim("aod_pendulum", "Pendulum", R, fs, fps=16)

fs = []
for k in range(6):
    g = G()
    disc(g, 2.2, 255)
    off = k * (math.pi / 4) / 6
    for i in range(8):
        a = off + i * math.pi / 4
        line(g, 6 + 3.6 * math.cos(a), 6 + 3.6 * math.sin(a), 6 + 5.8 * math.cos(a), 6 + 5.8 * math.sin(a), 200)
    fs.append(g)
aod.frames_anim("aod_sun", "Sun", R, fs, fps=8)


def moon(star_phase):
    g = G()
    for y in range(N):
        for x in range(N):
            c1 = clamp(3.6 - math.hypot(x - 5, y - 6) + 0.5)
            c2 = clamp(3.1 - math.hypot(x - 6.6, y - 5.2) + 0.5)
            g[y][x] = c1 * (1 - c2) * 255
    for i, (sx, sy) in enumerate(((10, 2), (2, 10), (10, 9), (3, 2))):
        put(g, sx, sy, 60 + 195 * max(0.0, math.sin(TAU * (star_phase + i * 0.27))) ** 2)
    return g


aod.frames_anim("aod_moon_stars", "Moon & stars", R, [moon(k / 8) for k in range(8)], fps=8)

fs = []
for k in range(7):
    g = G()
    dot(g, 6, 0.5 + k * 1.4, 255, 1.1)
    fs.append(g)
for k in range(6):
    g = G()
    ring(g, 0.6 + k * 1.0, 255 * (1 - k / 6), 0.9, 6, 10, 2.0)
    fs.append(g)
fs += [G(), G()]
aod.frames_anim("aod_raindrop", "Raindrop", R, fs, fps=11)

fs = []
for k in range(10):
    g = G()
    ph = k * TAU / 10
    for y in range(N):
        for x in range(N):
            cx = C + 3.2 * math.sin(y * 0.55 + ph)
            g[y][x] = max(0.0, 1 - abs(x - cx) / 1.3) * 255
    fs.append(g)
aod.frames_anim("aod_wave_vertical", "Vertical wave", R, fs, fps=12)

pat = [0] * 26
for i, v in {4: -1, 5: -1, 8: 1, 9: -5, 10: 3, 11: 0, 15: -1, 16: -2, 17: -1}.items():
    pat[i] = v
fs = []
for k in range(13):
    g = G()
    sh = k * 2
    for x in range(N - 1):
        y0 = 6 + pat[(x + sh) % 26]
        y1 = 6 + pat[(x + 1 + sh) % 26]
        line(g, x, y0, x + 1, y1, 40 + 215 * x / 12)
    fs.append(g)
aod.frames_anim("aod_ecg", "ECG trace", R, fs, fps=14)

fs = []
for k in range(12):
    p = k / 11
    g = G()
    for a, b in (((2, 0), (6, 6)), ((10, 0), (6, 6)), ((2, 12), (6, 6)), ((10, 12), (6, 6))):
        line(g, a[0], a[1], b[0], b[1], 90)
    line(g, 2, 0, 10, 0, 90)
    line(g, 2, 12, 10, 12, 90)
    top_rows = int(round((1 - p) * 5))
    for i in range(top_rows):
        y = 5 - i
        hw = 4 * (1 - y / 6) - 0.7
        for x in range(int(round(6 - hw)), int(round(6 + hw)) + 1):
            put(g, x, y, 255)
    bot_rows = int(round(p * 5))
    for i in range(bot_rows):
        y = 11 - i
        hw = 4 * (1 - (12 - y) / 6) - 0.7
        for x in range(int(round(6 - hw)), int(round(6 + hw)) + 1):
            put(g, x, y, 255)
    if 0 < p < 1:
        put(g, 6, 6, 220)
    fs.append(g)
aod.frames_anim("aod_hourglass", "Hourglass", R, fs, fps=3)

aod.frames_anim("aod_heart_outline", "Heart outline", R,
                [heart_fn(s, 0.4) for s in (0.7, 0.8, 0.9, 1.0, 0.9, 0.8)], fps=9)

STAR = []
for i in range(10):
    r = 5.8 if i % 2 == 0 else 2.4
    a = -math.pi / 2 + i * math.pi / 5
    STAR.append((6 + r * math.cos(a), 6.5 + r * math.sin(a)))
aod.frames_anim("aod_star", "Star pulse", R,
                [(lambda s: (lambda g: (poly(g, sc(STAR, s, 6, 6.5), 120 + 135 * s), g)[1])(G()))(s)
                 for s in (0.5, 0.7, 0.9, 1.0, 0.9, 0.7)], fps=9)

TRI = [(6, 1), (11, 10), (1, 10)]
aod.frames_anim("aod_triangle", "Triangle pulse", R,
                [(lambda s: (lambda g: (poly(g, sc(TRI, s, 6, 7), 120 + 135 * s), g)[1])(G()))(s)
                 for s in (0.5, 0.7, 0.9, 1.0, 0.9, 0.7)], fps=9)

fs = []
for k in range(16):
    g = G()
    a = k * TAU / 16
    line(g, 6, 6, 6 + 5.5 * math.cos(a), 6 + 5.5 * math.sin(a), 255)
    line(g, 6, 6, 6 - 2.5 * math.cos(a), 6 - 2.5 * math.sin(a), 90)
    dot(g, 6, 6, 255, 1.1)
    fs.append(g)
aod.frames_anim("aod_compass", "Compass needle", R, fs, fps=10)

fs = []
for k in range(12):
    g = G()
    for i in range(12):
        a = i * TAU / 12
        put(g, 6 + 5.8 * math.cos(a), 6 + 5.8 * math.sin(a), 70)
    for j, v in enumerate((255, 130, 60)):
        a = (k - j) * TAU / 12
        line(g, 6, 6, 6 + 4.8 * math.cos(a), 6 + 4.8 * math.sin(a), v)
    fs.append(g)
aod.frames_anim("aod_clock_sweep", "Clock sweep", R, fs, fps=6)

aod.frames_anim("aod_soft_blink", "Soft blink", R,
                [(lambda v: (lambda g: (disc(g, 1.6, v), g)[1])(G()))(v) for v in (30, 80, 140, 200, 255, 200, 140, 80)],
                fps=8)

fs = []
for m in pp([1, 2, 3]):
    g = G()
    for sx, sy, dx, dy in ((m, m, 1, 1), (12 - m, m, -1, 1), (m, 12 - m, 1, -1), (12 - m, 12 - m, -1, -1)):
        line(g, sx, sy, sx + 3 * dx, sy, 255)
        line(g, sx, sy, sx, sy + 3 * dy, 255)
    fs.append(g)
aod.frames_anim("aod_viewfinder", "Viewfinder", R, fs, fps=6)

# --- procedural AOD variants (same generators, different params)
for name, label, p in [
    ("aod_breath_tight", "Breath · tight", {"period": 3.0, "minRadius": 0.8, "maxRadius": 3.5, "thickness": 0.9}),
    ("aod_breath_wide", "Breath · wide", {"period": 6.0, "minRadius": 1.0, "maxRadius": 6.0, "thickness": 1.1}),
    ("aod_breath_thick", "Breath · thick ring", {"period": 4.5, "minRadius": 1.5, "maxRadius": 5.5, "thickness": 2.0}),
    ("aod_breath_hollow", "Breath · hollow", {"period": 4.5, "minRadius": 1.0, "maxRadius": 5.5, "thickness": 1.0, "core": False}),
    ("aod_breath_rhythm", "Breath · rhythm", {"period": 1.6, "minRadius": 1.0, "maxRadius": 5.0, "thickness": 1.0}),
    ("aod_breath_dim", "Breath · ghost", {"period": 5.0, "minRadius": 1.0, "maxRadius": 5.5, "thickness": 1.0, "minIntensity": 0.05}),
    ("aod_breath_bright", "Breath · steady glow", {"period": 5.0, "minRadius": 1.0, "maxRadius": 5.5, "thickness": 1.2, "minIntensity": 0.6}),
    ("aod_breath_quick", "Breath · quick pulse", {"period": 1.0, "minRadius": 0.8, "maxRadius": 4.0, "thickness": 1.0}),
]:
    aod.proc(name, label, R, "breathing_ring", p, fps=15, brightness=60)

for name, label, p in [
    ("aod_orbit_r2_short", "Orbit · tiny short", {"period": 1.6, "radius": 2.0, "tail": 4, "ring": 0.15}),
    ("aod_orbit_r2_long", "Orbit · tiny long", {"period": 1.6, "radius": 2.0, "tail": 14, "ring": 0.15}),
    ("aod_orbit_mid_short", "Orbit · mid short", {"period": 2.5, "radius": 3.6, "tail": 6, "ring": 0.1}),
    ("aod_orbit_mid_long", "Orbit · mid long", {"period": 2.5, "radius": 3.6, "tail": 16, "ring": 0.1}),
    ("aod_orbit_big_short", "Orbit · big short", {"period": 3.5, "radius": 5.6, "tail": 5, "ring": 0.06}),
    ("aod_orbit_big_long", "Orbit · big long", {"period": 3.5, "radius": 5.6, "tail": 20, "ring": 0.06}),
    ("aod_orbit_slow", "Orbit · lazy", {"period": 7.0, "radius": 4.5, "tail": 9, "ring": 0.18}),
    ("aod_orbit_comet", "Orbit · comet", {"period": 1.0, "radius": 4.2, "tail": 28, "ring": 0.0}),
]:
    aod.proc(name, label, R, "orbit", p, fps=22, brightness=60)

for name, label, p in [
    ("aod_ripple_slow", "Ripple · slow", {"rings": 1, "maxRadius": 6.5, "thickness": 1.1, "period": 4.5}),
    ("aod_ripple_double", "Ripple · double", {"rings": 2, "maxRadius": 6.5, "thickness": 1.0, "period": 3.0}),
    ("aod_ripple_triple", "Ripple · triple", {"rings": 3, "maxRadius": 6.5, "thickness": 0.9, "period": 3.6}),
    ("aod_ripple_tight", "Ripple · tight", {"rings": 2, "maxRadius": 3.5, "thickness": 0.9, "period": 1.6}),
]:
    aod.proc(name, label, R, "ripple", p, fps=20, brightness=55)

# ===================================================================== VOLUME
vol = Pack()
R = "volume"
DISC_MAX = {"max": "volume_pulse_max"}
BAR_MAX = {"max": "volume_bar_max"}
for name, label, p in [
    ("vol_liquid_calm", "Liquid · calm", {"smoothMs": 360, "waveAmp": 0.5, "waveSpeed": 0.4, "outline": 26}),
    ("vol_liquid_wavy", "Liquid · wavy", {"smoothMs": 260, "waveAmp": 1.6, "waveSpeed": 0.9, "outline": 26}),
    ("vol_liquid_fast", "Liquid · fast", {"smoothMs": 110, "waveAmp": 0.7, "waveSpeed": 1.8, "outline": 26}),
    ("vol_tank_flat", "Tank · flat", {"smoothMs": 200, "waveAmp": 0.0, "outline": 30}),
    ("vol_tide_slow", "Slow tide", {"smoothMs": 700, "waveAmp": 1.0, "waveSpeed": 0.3, "outline": 26}),
    ("vol_choppy_sea", "Choppy sea", {"smoothMs": 220, "waveAmp": 2.2, "waveSpeed": 1.5, "outline": 26}),
    ("vol_glow_ring", "Glow ring", {"smoothMs": 240, "waveAmp": 0.6, "waveSpeed": 0.8, "outline": 90}),
    ("vol_bright_ring", "Bright ring", {"smoothMs": 240, "waveAmp": 0.0, "outline": 150}),
    ("vol_no_outline", "No outline", {"smoothMs": 240, "waveAmp": 0.8, "waveSpeed": 0.8, "outline": 0}),
    ("vol_spring", "Spring", {"smoothMs": 140, "waveAmp": 1.2, "waveSpeed": 2.4, "outline": 26}),
]:
    q = dict(p)
    q["shape"] = "disc"
    vol.proc(name, label, R, "level_fill", q, fps=30, brightness=100, variants=DISC_MAX)

for name, label, th, sm, ol in [
    ("vol_bar_hairline", "Bar · hairline", 1, 160, 40),
    ("vol_bar_slim", "Bar · slim", 2, 180, 36),
    ("vol_bar_medium", "Bar · medium", 4, 200, 30),
    ("vol_bar_wide", "Bar · wide", 7, 200, 30),
    ("vol_bar_band", "Bar · band", 8, 220, 30),
    ("vol_bar_block", "Bar · block", 11, 220, 24),
    ("vol_bar_full", "Bar · full height", 13, 240, 24),
    ("vol_bar_glass", "Bar · glass", 5, 200, 100),
    ("vol_bar_snappy", "Bar · snappy", 5, 70, 30),
    ("vol_bar_lazy", "Bar · lazy", 5, 600, 30),
]:
    vol.proc(name, label, R, "level_fill",
             {"shape": "bar", "smoothMs": sm, "outline": ol, "barThickness": th},
             fps=30, brightness=100, variants=BAR_MAX)

# ================================================================== CHARGING
chg = Pack()
R = "charging"
FULL = "charging_full_frames"
FLOW_VAR = {"fast": "charging_flow_fast", "full": "charging_full"}
for name, label, fs_, bf, sm, ol in [
    ("chg_flow_lazy", "Flow · lazy", 0.3, 0.5, 900, 24),
    ("chg_flow_river", "Flow · river", 0.6, 0.9, 700, 26),
    ("chg_flow_stream", "Flow · stream", 1.0, 1.2, 600, 26),
    ("chg_flow_rapids", "Flow · rapids", 1.8, 1.6, 450, 30),
    ("chg_flow_turbo", "Flow · turbo", 2.8, 2.0, 350, 30),
    ("chg_flow_stripes", "Flow · wide stripes", 0.9, 0.35, 600, 26),
    ("chg_flow_fine", "Flow · fine grain", 1.2, 2.8, 600, 26),
    ("chg_flow_ember", "Flow · ember", 0.5, 1.4, 1200, 18),
    ("chg_flow_aurora", "Flow · aurora", 0.7, 0.7, 1000, 40),
    ("chg_flow_pulse", "Flow · pulse", 2.2, 0.25, 500, 26),
    ("chg_flow_glass", "Flow · glass", 0.8, 1.0, 600, 90),
    ("chg_flow_dark", "Flow · dark edge", 0.9, 1.1, 600, 0),
]:
    chg.proc(name, label, R, "charge_flow",
             {"flowSpeed": fs_, "bandFreq": bf, "smoothMs": sm, "outline": ol},
             fps=22, brightness=80, variants=FLOW_VAR)


def add_chg(name, label, grids, fps, durations=None, brightness=80):
    chg.frames_anim(name, label, R, grids, fps=fps, brightness=brightness, durations=durations,
                    variants={"fast": name + "_fast", "full": FULL})
    chg.frames_anim(name + "_fast", label + " (fast)", R, grids, fps=fps * 2, brightness=min(100, brightness + 10),
                    durations=[max(10, int(d / 2)) for d in durations] if durations else None,
                    variants={"full": FULL})


def battery(fill_rows):
    g = G()
    rect(g, 4, 3, 8, 12, 255)
    rect(g, 5, 1, 7, 2, 255, fill=True)
    for i in range(fill_rows):
        y = 11 - i
        for x in (5, 6, 7):
            put(g, x, y, 255)
    return g


add_chg("chg_battery_fill", "Battery fill", [battery(i) for i in range(0, 9)] + [battery(8), battery(8)], 5)
add_chg("chg_battery_blink", "Battery blink", [battery(8), mul(battery(8), 0.25)], 3)
add_chg("chg_bolt_pulse", "Bolt pulse", [mul(bolt_g(), k) for k in (0.2, 0.5, 0.8, 1.0, 0.8, 0.5)], 8)
add_chg("chg_bolt_flicker", "Bolt flicker", [mul(bolt_g(), k) for k in (1.0, 0.3, 1.0, 0.6, 0.15, 0.9)], 9,
        durations=[260, 70, 120, 90, 150, 300])

fs = []
for k in range(17):
    g = G()
    prog = k / 16 * TAU
    for y in range(N):
        for x in range(N):
            dx, dy = x - C, y - C
            if abs(math.hypot(dx, dy) - 5.0) < 0.95:
                ang = math.atan2(dy, dx) + math.pi / 2
                ang -= TAU * math.floor(ang / TAU)
                if ang <= prog:
                    g[y][x] = 255
    fs.append(g)
fs += [fs[-1], fs[-1], G()]
add_chg("chg_ring_load", "Progress ring", fs, 10)

rnd = random.Random(9)
fs = []
for k in range(8):
    g = G()
    poly(g, sc(BOLT, 1.0, 6.4, 6.4), 140 + 115 * (k % 4) / 3)
    for _ in range(3):
        a = rnd.uniform(0, TAU)
        r = rnd.uniform(4.6, 6.0)
        put(g, 6 + r * math.cos(a), 6 + r * math.sin(a), 255)
    fs.append(g)
add_chg("chg_zap_sparks", "Zap & sparks", fs, 12)


def droplet(level):
    g = G()
    shape = G()
    poly(shape, [(6, 0.5), (2.8, 6.5), (9.2, 6.5)], 255)
    disc(shape, 3.5, 255, 6, 8.2)
    for y in range(N):
        for x in range(N):
            edge = shape[y][x]
            if edge > 0:
                fill = (12 - y) / 12 <= level
                g[y][x] = edge if fill else min(edge, 60)
    return g


add_chg("chg_droplet", "Droplet fill", [droplet(i / 9) for i in range(10)] + [droplet(1.0)], 7)

fs = []
for k in range(18):
    g = G()
    poly(g, sc(BOLT, 0.55, 6.4, 6.4), 255)
    a = k * TAU / 18
    dot(g, 6 + 5.2 * math.cos(a), 6 + 5.2 * math.sin(a), 255, 1.2)
    fs.append(g)
add_chg("chg_orbit_bolt", "Bolt & orbit", fs, 14)

fs = []
for i in range(N + 1):
    g = G()
    for x in range(i):
        line(g, x, 3, x, 9, 120 + 135 * x / 12)
    fs.append(g)
fs += [fs[-1], fs[-1]]
add_chg("chg_level_ticks", "Level ticks", fs, 10)

fs = []
for k in range(15):
    g = G()
    lvl = k / 14
    for y in range(N):
        for x in range(N):
            surf = N - lvl * N + 1.4 * math.sin(x * 0.8 + k * 0.9)
            d = (y + 0.5) - surf
            g[y][x] = clamp(d + 0.5) * 200 + (55 if -1 < d < 1 else 0)
    fs.append(mask(g))
add_chg("chg_wave_rise", "Rising wave", fs, 10)

rnd = random.Random(21)
ff = [(rnd.uniform(1, 11), rnd.uniform(0, 14), rnd.uniform(0.6, 1.0), rnd.uniform(0, TAU)) for _ in range(8)]
fs = []
for k in range(16):
    g = G()
    for x0, y0, sp, ph in ff:
        y = 13 - ((y0 + k * sp) % 15)
        dot(g, x0 + 0.8 * math.sin(k * 0.5 + ph), y, 255, 1.0)
    fs.append(g)
add_chg("chg_fireflies", "Rising sparks", fs, 10)


def chevrons(y0):
    g = G()
    for base in (y0, y0 + 6):
        for k in range(4):
            for x in (6 - k, 6 + k):
                for t in (0, 1):
                    put(g, x, base + k + t, 255)
    return g


add_chg("chg_chevrons_thick", "Thick chevrons", [chevrons(12 - i * 2) for i in range(7)], 7)

fs = []
for r in (6.2, 5.2, 4.2, 3.2, 2.2, 1.2):
    g = G()
    ring(g, r, 255)
    if r < 2.5:
        disc(g, 1.4, 255)
    fs.append(g)
add_chg("chg_ring_inward", "Ring inward", fs, 9)

# ============================================================== NOTIFICATION
ntf = Pack()
R = "notification"


def ntf_frames(name, label, grids, fps=12, durations=None, brightness=100):
    ntf.frames_anim(name, label, R, grids, fps=fps, brightness=brightness, loop=False, durations=durations)


# check mark drawn progressively
check_pts = [(2, 7), (5, 10), (11, 3)]
fs = []
for k in range(9):
    g = G()
    p = k / 8 * 2
    segs = [(check_pts[0], check_pts[1]), (check_pts[1], check_pts[2])]
    for i, (a, b) in enumerate(segs):
        t = clamp(p - i)
        if t > 0:
            line(g, a[0], a[1], a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            line(g, a[0], a[1] + 1, a[0] + (b[0] - a[0]) * t, a[1] + 1 + (b[1] - a[1]) * t)
    fs.append(g)
fs += [fs[-1], fs[-1]]
ntf_frames("ntf_check", "Check mark", fs, fps=14)


def excl(v=255.0):
    g = G()
    for y in range(1, 8):
        put(g, 6, y, v)
        put(g, 5, y, v * 0.6)
        put(g, 7, y, v * 0.6)
    put(g, 6, 10, v)
    put(g, 6, 11, v)
    return g


ntf_frames("ntf_exclaim", "Exclamation", [excl(), G(), excl(), G(), excl()], fps=6,
           durations=[300, 150, 300, 150, 600])


def envelope(v=255.0):
    g = G()
    rect(g, 1, 3, 11, 9, v)
    line(g, 1, 3, 6, 7, v)
    line(g, 11, 3, 6, 7, v)
    return g


ntf_frames("ntf_envelope", "Envelope", [mul(envelope(), k) for k in (0.3, 0.6, 1.0, 0.5, 1.0, 0.5, 1.0)], fps=9)


def bell():
    g = G()
    disc(g, 3.6, 255, 6, 4.8)
    for y in range(6, N):
        for x in range(N):
            g[y][x] = 0.0
    poly(g, [(2.4, 5), (9.6, 5), (11, 9), (1, 9)], 255)
    dot(g, 6, 10.8, 255, 1.1)
    put(g, 6, 0.8)
    return g


b0 = bell()
ntf_frames("ntf_bell", "Bell ring", [shift(b0, dx, 0) for dx in (0, -1, 0, 1, 0, -1, 0, 1, 0)], fps=14)

rnd = random.Random(2)
fs = []
for k in range(10):
    g = G()
    for i in range(5):
        h = rnd.randint(2, 11)
        for y in range(N - h, N):
            put(g, 1 + i * 2.5, y)
    fs.append(g)
ntf_frames("ntf_bars_jump", "Jumping bars", fs, fps=12)

fs = []
for k in range(12):
    g = G()
    for i in range(3):
        y = 7 - 3.5 * max(0.0, math.sin(TAU * (k / 12 - i / 6)))
        dot(g, 3 + i * 3, y, 255, 1.4)
    fs.append(g)
ntf_frames("ntf_dots_bounce", "Bouncing dots", fs, fps=14)

fs = []
for k in range(6):
    g = G()
    if k % 2 == 0:
        ring(g, 5.2, 255, 1.1)
    fs.append(g)
ntf_frames("ntf_ring_blink", "Ring blink", fs, fps=7)

fs = []
for s in (0, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6):
    g = G()
    if s == 0:
        put(g, 6, 6)
    else:
        rect(g, 6 - s, 6 - s, 6 + s, 6 + s, 255 * (1 - s * 0.1))
    fs.append(g)
ntf_frames("ntf_square_burst", "Square burst", fs + [G()], fps=16)


def xmark(v=255.0):
    g = G()
    line(g, 2, 2, 10, 10, v)
    line(g, 2, 10, 10, 2, v)
    line(g, 3, 2, 11, 10, v * 0.5)
    line(g, 1, 2, 9, 10, v * 0.5)
    return g


ntf_frames("ntf_x_flash", "X flash", [xmark(), G(), xmark(), G(), xmark()], fps=6,
           durations=[250, 120, 250, 120, 500])

fs = []
for k in range(12):
    g = G()
    tmax = k / 11 * 3 * math.pi
    th = 0.0
    while th <= tmax:
        r = 0.4 + th / (3 * math.pi) * 5.8
        dot(g, 6 + r * math.cos(th), 6 + r * math.sin(th), 255, 0.9)
        th += 0.2
    fs.append(g)
ntf_frames("ntf_spiral_burst", "Spiral burst", fs + [fs[-1]], fps=18)

ntf_frames("ntf_star_burst", "Star burst", [
    (lambda s: (lambda g: (poly(g, sc(STAR, s, 6, 6.5), 255), g)[1])(G()))(s)
    for s in (0.3, 0.6, 0.9, 1.1, 0.9, 1.1, 0.9)], fps=12)

fs = []
zz = [(1, 3), (4, 9), (7, 3), (10, 9), (12, 5)]
for k in range(10):
    g = G()
    t = k / 9 * (len(zz) - 1)
    for i in range(len(zz) - 1):
        f = clamp(t - i)
        if f > 0:
            a, b = zz[i], zz[i + 1]
            line(g, a[0], a[1], a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
    fs.append(g)
ntf_frames("ntf_zigzag", "Zig-zag", fs + [fs[-1]], fps=16)


def full_disc(v=255.0):
    g = G()
    disc(g, 6.4, v)
    return g


ntf_frames("ntf_double_flash", "Double flash", [full_disc(), G(), full_disc(), G()], fps=8,
           durations=[120, 100, 160, 200])

fs = []
for lit in (5, 5, 4, 3, 2, 1, 0, 5):
    g = G()
    for i in range(5):
        if i < lit:
            dot(g, 2 + i * 2, 6, 255, 1.2)
    fs.append(g)
ntf_frames("ntf_countdown", "Countdown dots", fs, fps=5)

fs = []
for k in range(8):
    g = G()
    for base in (k * 2 - 4, k * 2 - 10):
        for i in range(4):
            for y in (6 - i, 6 + i):
                put(g, base + (3 - i), y)
    fs.append(g)
ntf_frames("ntf_arrow_sweep", "Arrow sweep", fs, fps=14)

ntf_frames("ntf_smile", "Smile", [G(), mul(face(), 0.4), face(), face(), mul(face(), 0.5), G()], fps=7,
           durations=[100, 150, 700, 700, 150, 100])

for name, label, rings, per, dur in [
    ("ntf_ripple_single", "Ripple · single", 1, 0.7, 1400),
    ("ntf_ripple_quick", "Ripple · quick", 2, 0.45, 1200),
    ("ntf_ripple_quad", "Ripple · quad", 4, 0.9, 2400),
    ("ntf_ripple_slow", "Ripple · slow", 2, 1.2, 2600),
    ("ntf_ripple_pulse", "Ripple · tight pulse", 3, 0.5, 1600),
    ("ntf_ripple_wide", "Ripple · wide", 3, 1.1, 2800),
]:
    ntf.proc(name, label, R, "ripple",
             {"rings": rings, "maxRadius": 6.5, "thickness": 1.0, "period": per},
             fps=30, brightness=100, loop=False, duration=dur)

# ----------------------------------------------------------------- write + validate
packs = {
    "pack_aod.json": aod,
    "pack_volume.json": vol,
    "pack_charging.json": chg,
    "pack_notification.json": ntf,
}
existing = set()
for f in glob.glob(os.path.join(ASSETS, "*.json")):
    if os.path.basename(f) in packs:
        continue
    existing |= set(json.load(open(f))["animations"])

all_names = set()
total = 0
for fname, pk in packs.items():
    pk.write([os.path.join(ASSETS, fname), os.path.join(EXAMPLES, fname)])
    for n, a in pk.anims.items():
        assert n not in existing, "collides with bundled animation: " + n
        assert n not in all_names, "duplicate: " + n
        all_names.add(n)
        for fr in a.get("frames", []):
            ref = fr if isinstance(fr, str) else fr["frame"]
            assert ref in pk.frames, (n, ref)
    for k, r in pk.frames.items():
        assert len(r) == N and all(len(x) == N for x in r), k
    total += len(pk.anims)
    print("%-24s %3d animations, %4d unique frames" % (fname, len(pk.anims), len(pk.frames)))

# every variant target must exist somewhere
known = existing | all_names
for fname, pk in packs.items():
    for n, a in pk.anims.items():
        for v in a.get("variants", {}).values():
            assert v in known, (n, v)
print("TOTAL new animations:", total)
