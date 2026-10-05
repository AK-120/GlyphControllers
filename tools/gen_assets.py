#!/usr/bin/env python3
"""
Generates the bundled animation JSON files and the vector icons.

    python3 tools/gen_assets.py

Frame format used by the app: N rows of N characters (N = 13 on Phone (4a) Pro).
Each character is one LED:
    '0'-'9','a'-'f'  hex nibble, intensity = nibble * 17   (0 .. 255)
    '.' or ' '       off
    '#'              full intensity
Edit the JSON by hand afterwards if you like - this script only exists so the
default frames are not typed manually.
"""
import json
import math
import os

N = 13
C = (N - 1) / 2.0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "animations")
RES = os.path.join(ROOT, "app", "src", "main", "res")
EXAMPLES = os.path.join(ROOT, "examples")


def nib(v):
    return "0123456789abcdef"[max(0, min(15, int(round(v / 17.0))))]


def to_rows(grid):
    return ["".join(nib(v) for v in row) for row in grid]


def blank():
    return [[0.0] * N for _ in range(N)]


def ring(radius, intensity=255.0, width=0.95):
    g = blank()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - C, y - C)
            g[y][x] = max(0.0, 1.0 - abs(d - radius) / width) * intensity
    return g


def dot(intensity=255.0):
    g = blank()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - C, y - C)
            g[y][x] = max(0.0, 1.0 - d / 1.4) * intensity
    return g


def add(a, b):
    return [[min(255.0, a[y][x] + b[y][x]) for x in range(N)] for y in range(N)]


def line(g, x0, y0, x1, y1, v=255.0):
    steps = max(abs(x1 - x0), abs(y1 - y0)) * 2
    for i in range(steps + 1):
        t = i / steps
        x = round(x0 + (x1 - x0) * t)
        y = round(y0 + (y1 - y0) * t)
        for dx in (0, 1):
            if 0 <= x + dx < N and 0 <= y < N:
                g[y][x + dx] = v


def bolt(intensity=255.0):
    g = blank()
    line(g, 8, 1, 4, 7, intensity)
    line(g, 4, 7, 7, 7, intensity)
    line(g, 7, 7, 5, 12, intensity)
    return g


def scale(g, k):
    return [[v * k for v in row] for row in g]


frames_ripple = {}
radii = [0.0, 1.2, 2.4, 3.6, 4.8, 6.0]
for i, r in enumerate(radii):
    fade = 1.0 - i * 0.14
    base = dot(255.0 * fade) if i == 0 else ring(r, 255.0 * fade)
    frames_ripple["ripple_%d" % i] = to_rows(base)
frames_ripple["rest"] = to_rows(dot(70.0))

aod_entries = [
    {"frame": "ripple_0", "duration": 140},
    {"frame": "ripple_1", "duration": 110},
    {"frame": "ripple_2", "duration": 110},
    {"frame": "ripple_3", "duration": 110},
    {"frame": "ripple_4", "duration": 120},
    {"frame": "ripple_5", "duration": 140, "brightness": 70},
    {"frame": "rest", "duration": 900, "brightness": 80},
]

aod = {
    "schema": 1,
    "frames": frames_ripple,
    "animations": {
        "aod_breathing": {
            "label": "Breathing ring",
            "role": "aod",
            "type": "procedural",
            "generator": "breathing_ring",
            "loop": True,
            "fps": 15,
            "brightness": 60,
            "params": {
                "period": 4.5,
                "minRadius": 1.0,
                "maxRadius": 5.2,
                "thickness": 1.0,
                "minIntensity": 0.25,
                "core": True,
            },
        },
        "aod_orbit": {
            "label": "Orbit",
            "role": "aod",
            "type": "procedural",
            "generator": "orbit",
            "loop": True,
            "fps": 20,
            "brightness": 60,
            "params": {"period": 3.0, "radius": 4.8, "tail": 7, "ring": 0.08},
        },
        "aod_ripple_frames": {
            "label": "Ripple (JSON frames)",
            "role": "aod",
            "loop": True,
            "loopCount": 0,
            "fps": 12,
            "brightness": 60,
            "frames": aod_entries,
        },
    },
}

bolt_frames = {}
for i, k in enumerate([0.25, 0.5, 0.75, 1.0, 0.75, 0.5]):
    bolt_frames["bolt_%d" % i] = to_rows(scale(bolt(), k))
bolt_frames["full_a"] = to_rows(add(ring(5.6, 200.0), dot(120.0)))
bolt_frames["full_b"] = to_rows(add(ring(5.6, 255.0), dot(255.0)))

charging = {
    "schema": 1,
    "frames": bolt_frames,
    "animations": {
        "charging_flow": {
            "label": "Charging flow",
            "role": "charging",
            "type": "procedural",
            "generator": "charge_flow",
            "loop": True,
            "fps": 20,
            "brightness": 80,
            "params": {"flowSpeed": 0.9, "bandFreq": 0.9, "smoothMs": 600, "outline": 26},
            "variants": {"fast": "charging_flow_fast", "full": "charging_full"},
        },
        "charging_flow_fast": {
            "label": "Charging flow (fast)",
            "role": "charging",
            "type": "procedural",
            "generator": "charge_flow",
            "loop": True,
            "fps": 24,
            "brightness": 90,
            "params": {"flowSpeed": 2.2, "bandFreq": 1.3, "smoothMs": 400, "outline": 26},
        },
        "charging_full": {
            "label": "Fully charged",
            "role": "charging",
            "type": "procedural",
            "generator": "level_fill",
            "loop": True,
            "fps": 15,
            "brightness": 70,
            "params": {"shape": "disc", "fixed": 1.0, "waveAmp": 0.0, "shimmer": True, "outline": 26},
        },
        "charging_bolt": {
            "label": "Lightning bolt (JSON frames)",
            "role": "charging",
            "loop": True,
            "loopCount": 0,
            "fps": 8,
            "brightness": 80,
            "frames": ["bolt_0", "bolt_1", "bolt_2", "bolt_3", "bolt_4", "bolt_5"],
            "variants": {"fast": "charging_bolt_fast", "full": "charging_full_frames"},
        },
        "charging_bolt_fast": {
            "label": "Lightning bolt (fast)",
            "role": "charging",
            "loop": True,
            "fps": 16,
            "brightness": 90,
            "frames": ["bolt_0", "bolt_1", "bolt_2", "bolt_3", "bolt_4", "bolt_5"],
        },
        "charging_full_frames": {
            "label": "Fully charged (JSON frames)",
            "role": "charging",
            "loop": True,
            "brightness": 70,
            "frames": [
                {"frame": "full_a", "duration": 700},
                {"frame": "full_b", "duration": 300, "brightness": 90},
            ],
        },
    },
}

volume = {
    "schema": 1,
    "animations": {
        "volume_pulse": {
            "label": "Volume pulse",
            "role": "volume",
            "type": "procedural",
            "generator": "level_fill",
            "loop": True,
            "fps": 30,
            "brightness": 100,
            "params": {
                "shape": "disc",
                "smoothMs": 240,
                "waveAmp": 0.9,
                "waveSpeed": 0.8,
                "outline": 26,
                "shimmer": False,
            },
            "variants": {"max": "volume_pulse_max"},
        },
        "volume_pulse_max": {
            "label": "Volume pulse (100%)",
            "role": "volume",
            "type": "procedural",
            "generator": "level_fill",
            "loop": True,
            "fps": 30,
            "brightness": 100,
            "params": {
                "shape": "disc",
                "smoothMs": 240,
                "waveAmp": 0.2,
                "waveSpeed": 1.4,
                "outline": 26,
                "shimmer": True,
            },
        },
        "volume_bar": {
            "label": "Volume bar",
            "role": "volume",
            "type": "procedural",
            "generator": "level_fill",
            "loop": True,
            "fps": 30,
            "brightness": 100,
            "params": {"shape": "bar", "smoothMs": 200, "outline": 26, "barThickness": 5},
            "variants": {"max": "volume_bar_max"},
        },
        "volume_bar_max": {
            "label": "Volume bar (100%)",
            "role": "volume",
            "type": "procedural",
            "generator": "level_fill",
            "loop": True,
            "fps": 30,
            "brightness": 100,
            "params": {"shape": "bar", "smoothMs": 200, "outline": 26, "barThickness": 5, "shimmer": True},
        },
    },
}

notification = {
    "schema": 1,
    "animations": {
        "notification_ping": {
            "label": "Ping",
            "role": "notification",
            "type": "procedural",
            "generator": "ripple",
            "loop": False,
            "durationMs": 1600,
            "fps": 30,
            "brightness": 100,
            "params": {"rings": 2, "maxRadius": 6.5, "thickness": 1.0},
        }
    },
}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def validate(obj, name):
    for fname, rows in obj.get("frames", {}).items():
        assert len(rows) == N, (name, fname, len(rows))
        for r in rows:
            assert len(r) == N, (name, fname, r)
    refs = set(obj.get("frames", {}))
    for an, a in obj["animations"].items():
        for fr in a.get("frames", []):
            ref = fr if isinstance(fr, str) else fr.get("frame")
            if ref:
                assert ref in refs, (name, an, ref)


for fname, obj in (("aod.json", aod), ("charging.json", charging),
                   ("volume.json", volume), ("notification.json", notification)):
    validate(obj, fname)
    dump(os.path.join(ASSETS, fname), obj)

# Standalone, minimal examples the user can copy to the device and edit.
dump(os.path.join(EXAMPLES, "aod_example.json"), aod)
dump(os.path.join(EXAMPLES, "volume_example.json"), volume)
dump(os.path.join(EXAMPLES, "charging_example.json"), charging)


# ---------- vector icons ----------
def circle_path(cx, cy, r):
    return ("M%.2f,%.2f m-%.2f,0 a%.2f,%.2f 0 1,0 %.2f,0 a%.2f,%.2f 0 1,0 -%.2f,0"
            % (cx, cy, r, r, r, 2 * r, r, r, 2 * r))


def dots_path(size, pitch, radius, mask_r):
    parts = []
    off = (size - pitch * (N - 1)) / 2.0
    for y in range(N):
        for x in range(N):
            if math.hypot(x - C, y - C) <= mask_r:
                parts.append(circle_path(off + x * pitch, off + y * pitch, radius))
    return " ".join(parts)


def write_vector(path, vp, pitch, radius, bg):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    bg_xml = ('    <path android:fillColor="#FF000000" android:pathData="M0,0h%d v%d h-%d z"/>\n'
              % (vp, vp, vp)) if bg else ""
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="%ddp" android:height="%ddp"\n'
           '    android:viewportWidth="%d" android:viewportHeight="%d">\n%s'
           '    <path android:fillColor="#FFFFFFFF" android:pathData="%s"/>\n'
           '</vector>\n') % (vp, vp, vp, vp, bg_xml, dots_path(vp, pitch, radius, 6.2))
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)


write_vector(os.path.join(RES, "drawable", "img_toy_preview.xml"), 96, 6.4, 2.2, True)
# Launcher / notification icons are generated by tools/gen_icons.py

print("assets generated")
