#!/usr/bin/env python3
"""
Generates assets/animations/extras.json - extra animations for every role.

    python3 tools/gen_extras.py

Same frame format as gen_assets.py: 13 rows x 13 chars, 0-9 a-f = intensity, '.' = off.
"""
import json
import math
import os
import random

N = 13
C = (N - 1) / 2.0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = [
    os.path.join(ROOT, "app", "src", "main", "assets", "animations", "extras.json"),
    os.path.join(ROOT, "examples", "extras_example.json"),
]


def nib(v):
    return "0123456789abcdef"[max(0, min(15, int(round(v / 17.0))))]


def rows(g):
    return ["".join(nib(v) for v in r) for r in g]


def blank():
    return [[0.0] * N for _ in range(N)]


def disc_mask(x, y):
    return math.hypot(x - C, y - C) <= N / 2.0 - 0.15


# ---------------------------------------------------------------- frame sets
frames = {}


def heart(scale, intensity=255.0):
    g = blank()
    for y in range(N):
        for x in range(N):
            u = (x - C) / (5.0 * scale)
            v = (C - y + 0.6) / (5.0 * scale)
            val = (u * u + v * v - 1) ** 3 - u * u * v ** 3
            if val <= 0:
                g[y][x] = intensity
            elif val < 0.15:
                g[y][x] = intensity * 0.35
    return g


frames["heart_small"] = rows(heart(0.72, 190))
frames["heart_big"] = rows(heart(0.95, 255))
frames["heart_mid"] = rows(heart(0.82, 220))


def spinner(angle, tail=2.2, r_in=2.5, r_out=5.8):
    g = blank()
    for y in range(N):
        for x in range(N):
            dx, dy = x - C, y - C
            d = math.hypot(dx, dy)
            if r_in <= d <= r_out:
                delta = angle - math.atan2(dy, dx)
                delta -= 2 * math.pi * math.floor(delta / (2 * math.pi))
                if delta < tail:
                    g[y][x] = (1 - delta / tail) * 255
    return g


SPIN = 12
for i in range(SPIN):
    frames["spin_%d" % i] = rows(spinner(i * 2 * math.pi / SPIN))


def scan(pos):
    g = blank()
    for y in range(N):
        for x in range(N):
            if not disc_mask(x, y):
                continue
            d = abs(y - pos)
            g[y][x] = max(0.0, 1 - d / 1.8) * 255
    return g


SCAN_POS = list(range(0, N)) + list(range(N - 2, 0, -1))
for i, p in enumerate(SCAN_POS):
    frames["scan_%d" % i] = rows(scan(p))


def wave(phase):
    g = blank()
    for y in range(N):
        for x in range(N):
            cy = C + 3.2 * math.sin(x * 0.55 + phase)
            g[y][x] = max(0.0, 1 - abs(y - cy) / 1.3) * 255
    return g


WAVE = 10
for i in range(WAVE):
    frames["wave_%d" % i] = rows(wave(i * 2 * math.pi / WAVE))

rng = random.Random(7)
drops = [(rng.uniform(0, N + 6), rng.choice([0.7, 1.0, 1.3])) for _ in range(N)]
RAIN = 16
TAIL = 5


def rain(t):
    g = blank()
    for x in range(N):
        off, sp = drops[x]
        head = (off + t * sp) % (N + TAIL + 2)
        for k in range(TAIL):
            y = int(round(head - k))
            if 0 <= y < N:
                g[y][x] = max(g[y][x], (1 - k / TAIL) * 255)
    return g


for i in range(RAIN):
    frames["rain_%d" % i] = rows(rain(i))


def face(blink):
    g = blank()
    ey = 4
    for ex in (4, 8):
        g[ey][ex] = 255
        if not blink:
            g[ey + 1][ex] = 255
    for x, y in ((3, 8), (4, 9), (5, 9), (6, 9), (7, 9), (8, 9), (9, 8)):
        g[y][x] = 220
    return g


frames["face_open"] = rows(face(False))
frames["face_blink"] = rows(face(True))


def chevron(y0, intensity=255.0):
    g = blank()
    for k in range(4):
        for x in (int(C) - k, int(C) + k):
            y = y0 + k
            if 0 <= y < N and 0 <= x < N:
                g[y][x] = max(g[y][x], intensity)
    return g


def arrows(offset):
    g = blank()
    for base in (offset, offset + 6):
        c = chevron(base)
        for y in range(N):
            for x in range(N):
                g[y][x] = max(g[y][x], c[y][x])
    return g


ARR = 6
for i in range(ARR):
    frames["arrows_%d" % i] = rows(arrows(N - 1 - i))
    # offsets descend, so chevrons travel upward


def flash(radius, intensity=255.0):
    g = blank()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - C, y - C)
            g[y][x] = max(0.0, min(1.0, radius - d + 0.5)) * intensity
    return g


frames["flash_s"] = rows(flash(1.8, 255))
frames["flash_m"] = rows(flash(3.8, 255))
frames["flash_l"] = rows(flash(5.8, 200))
frames["flash_off"] = rows(blank())

# ---------------------------------------------------------------- animations
A = {}

# --- AOD
A["aod_heartbeat"] = {
    "label": "Heartbeat", "role": "aod", "loop": True, "fps": 10, "brightness": 60,
    "frames": [
        {"frame": "heart_small", "duration": 120},
        {"frame": "heart_big", "duration": 110},
        {"frame": "heart_mid", "duration": 100},
        {"frame": "heart_big", "duration": 110},
        {"frame": "heart_small", "duration": 900},
    ],
}
A["aod_spinner"] = {
    "label": "Spinner", "role": "aod", "loop": True, "fps": 14, "brightness": 60,
    "frames": ["spin_%d" % i for i in range(SPIN)],
}
A["aod_scanline"] = {
    "label": "Scan line", "role": "aod", "loop": True, "fps": 14, "brightness": 55,
    "frames": ["scan_%d" % i for i in range(len(SCAN_POS))],
}
A["aod_wave"] = {
    "label": "Sine wave", "role": "aod", "loop": True, "fps": 12, "brightness": 60,
    "frames": ["wave_%d" % i for i in range(WAVE)],
}
A["aod_rain"] = {
    "label": "Matrix rain", "role": "aod", "loop": True, "fps": 12, "brightness": 55,
    "frames": ["rain_%d" % i for i in range(RAIN)],
}
A["aod_face"] = {
    "label": "Blinking face", "role": "aod", "loop": True, "fps": 10, "brightness": 60,
    "frames": [
        {"frame": "face_open", "duration": 2200},
        {"frame": "face_blink", "duration": 130},
        {"frame": "face_open", "duration": 1600},
        {"frame": "face_blink", "duration": 130},
        {"frame": "face_open", "duration": 120},
        {"frame": "face_blink", "duration": 130},
    ],
}
A["aod_breathing_slow"] = {
    "label": "Breathing (slow)", "role": "aod", "type": "procedural", "generator": "breathing_ring",
    "loop": True, "fps": 15, "brightness": 50,
    "params": {"period": 8.0, "minRadius": 0.8, "maxRadius": 5.6, "thickness": 1.3, "minIntensity": 0.15, "core": True},
}
A["aod_orbit_fast"] = {
    "label": "Orbit (fast)", "role": "aod", "type": "procedural", "generator": "orbit",
    "loop": True, "fps": 24, "brightness": 65,
    "params": {"period": 1.2, "radius": 5.0, "tail": 12, "ring": 0.12},
}
A["aod_orbit_inner"] = {
    "label": "Orbit (inner)", "role": "aod", "type": "procedural", "generator": "orbit",
    "loop": True, "fps": 20, "brightness": 60,
    "params": {"period": 2.2, "radius": 2.6, "tail": 9, "ring": 0.2},
}
A["aod_ripple_loop"] = {
    "label": "Endless ripple", "role": "aod", "type": "procedural", "generator": "ripple",
    "loop": True, "fps": 20, "brightness": 55,
    "params": {"rings": 1, "maxRadius": 6.5, "thickness": 1.1, "period": 2.4},
}

# --- Volume
A["volume_pulse_soft"] = {
    "label": "Volume liquid (soft)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "disc", "smoothMs": 420, "waveAmp": 1.4, "waveSpeed": 0.6, "outline": 30},
    "variants": {"max": "volume_pulse_max"},
}
A["volume_pulse_snappy"] = {
    "label": "Volume liquid (snappy)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "disc", "smoothMs": 90, "waveAmp": 0.4, "waveSpeed": 1.6, "outline": 20},
    "variants": {"max": "volume_pulse_max"},
}
A["volume_bar_thin"] = {
    "label": "Volume bar (thin)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "bar", "smoothMs": 180, "outline": 30, "barThickness": 3},
    "variants": {"max": "volume_bar_thin_max"},
}
A["volume_bar_thin_max"] = {
    "label": "Volume bar thin (100%)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "bar", "smoothMs": 180, "outline": 30, "barThickness": 3, "shimmer": True},
}
A["volume_bar_thick"] = {
    "label": "Volume bar (thick)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "bar", "smoothMs": 220, "outline": 30, "barThickness": 9},
    "variants": {"max": "volume_bar_thick_max"},
}
A["volume_bar_thick_max"] = {
    "label": "Volume bar thick (100%)", "role": "volume", "type": "procedural", "generator": "level_fill",
    "loop": True, "fps": 30, "brightness": 100,
    "params": {"shape": "bar", "smoothMs": 220, "outline": 30, "barThickness": 9, "shimmer": True},
}

# --- Charging
A["charging_flow_slow"] = {
    "label": "Charging flow (calm)", "role": "charging", "type": "procedural", "generator": "charge_flow",
    "loop": True, "fps": 20, "brightness": 70,
    "params": {"flowSpeed": 0.45, "bandFreq": 0.6, "smoothMs": 900, "outline": 24},
    "variants": {"fast": "charging_flow_fast", "full": "charging_full"},
}
A["charging_flow_neon"] = {
    "label": "Charging flow (neon)", "role": "charging", "type": "procedural", "generator": "charge_flow",
    "loop": True, "fps": 24, "brightness": 90,
    "params": {"flowSpeed": 1.4, "bandFreq": 1.8, "smoothMs": 500, "outline": 40},
    "variants": {"fast": "charging_flow_fast", "full": "charging_full"},
}
A["charging_arrows"] = {
    "label": "Rising arrows", "role": "charging", "loop": True, "fps": 7, "brightness": 80,
    "frames": ["arrows_%d" % i for i in range(ARR)],
    "variants": {"fast": "charging_arrows_fast", "full": "charging_full"},
}
A["charging_arrows_fast"] = {
    "label": "Rising arrows (fast)", "role": "charging", "loop": True, "fps": 14, "brightness": 90,
    "frames": ["arrows_%d" % i for i in range(ARR)],
}
A["charging_heartbeat"] = {
    "label": "Charging heartbeat", "role": "charging", "loop": True, "fps": 10, "brightness": 75,
    "frames": [
        {"frame": "heart_small", "duration": 160},
        {"frame": "heart_big", "duration": 140},
        {"frame": "heart_small", "duration": 700},
    ],
    "variants": {"fast": "charging_heartbeat_fast", "full": "charging_full"},
}
A["charging_heartbeat_fast"] = {
    "label": "Charging heartbeat (fast)", "role": "charging", "loop": True, "fps": 10, "brightness": 90,
    "frames": [
        {"frame": "heart_small", "duration": 80},
        {"frame": "heart_big", "duration": 70},
        {"frame": "heart_small", "duration": 250},
    ],
}

# --- Notification
A["notification_ping_triple"] = {
    "label": "Triple ping", "role": "notification", "type": "procedural", "generator": "ripple",
    "loop": False, "durationMs": 2200, "fps": 30, "brightness": 100,
    "params": {"rings": 3, "maxRadius": 6.5, "thickness": 1.0, "period": 0.9},
}
A["notification_flash"] = {
    "label": "Flash", "role": "notification", "loop": False, "fps": 14, "brightness": 100,
    "frames": ["flash_s", "flash_m", "flash_l", "flash_m", "flash_s", "flash_m", "flash_l", "flash_off"],
}
A["notification_heart"] = {
    "label": "Heart", "role": "notification", "loop": False, "fps": 8, "brightness": 100,
    "frames": [
        {"frame": "heart_small", "duration": 150},
        {"frame": "heart_big", "duration": 130},
        {"frame": "heart_small", "duration": 150},
        {"frame": "heart_big", "duration": 130},
        {"frame": "flash_off", "duration": 200},
    ],
}

doc = {"schema": 1, "frames": frames, "animations": A}

# ---------------------------------------------------------------- validation
for fname, r in frames.items():
    assert len(r) == N, fname
    for line in r:
        assert len(line) == N, (fname, line)
for name, a in A.items():
    for f in a.get("frames", []):
        ref = f if isinstance(f, str) else f["frame"]
        assert ref in frames, (name, ref)

for path in OUT:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")

print("extras: %d animations, %d frames" % (len(A), len(frames)))
