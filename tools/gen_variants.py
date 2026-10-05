#!/usr/bin/env python3
"""
Gives every VOLUME and CHARGING animation its OWN "max" / "full" / "fast" variant.

Before: dozens of looks pointed at the same few shared variants (volume_pulse_max, charging_full_frames,
charging_flow_fast ...), so at 100% / full / fast charging they all looked identical.
After: each look gets <name>__max (volume at 100%), <name>__full and <name>__fast (charging). Each keeps the
look's own shape and gets a different effect, so they stay recognisable but all differ.

  procedural looks -> wrapped in the new "boost" generator (ProceduralGenerators.kt) + an effect
  frame looks      -> new frames: the fullest frame with a comet circling the rim (full), the frames with a
                      rim comet and double speed (fast)

Run from the project root:   python3 tools/gen_variants.py
Safe to run again: variants that are already unique and different from their base are left alone.
Rewrites app/src/main/assets/animations/*.json
"""
import json, math, pathlib, sys

ROOT = pathlib.Path("app/src/main/assets/animations")
N = 13
ROLES = {"volume": ["max"], "charging": ["fast", "full"]}

# effect settings, cycled per look so neighbouring looks differ
FX = {
    "max": [
        {"effect": "burst+sparkle", "effectI": 170, "effectPeriod": 1.4},
        {"effect": "burst+ripple", "effectI": 150, "effectPeriod": 1.1},
        {"effect": "burst+sweep", "effectI": 190, "effectPeriod": 1.3},
        {"effect": "burst+orbit", "effectI": 170, "effectPeriod": 1.6},
        {"effect": "burst+flash", "effectI": 160, "effectPeriod": 0.9},
        {"effect": "burst+sparkle+orbit", "effectI": 170, "effectPeriod": 1.5},
        {"effect": "burst+negative", "effectI": 170, "effectPeriod": 1.8},
        {"effect": "ripple+sweep", "effectI": 150, "effectPeriod": 1.2},
    ],
    "full": [
        {"effect": "ripple+sparkle", "effectI": 140, "effectPeriod": 1.8, "timeScale": 0.5},
        {"effect": "sweep+sparkle", "effectI": 170, "effectPeriod": 1.6, "timeScale": 0.6},
        {"effect": "orbit+flash", "effectI": 170, "effectPeriod": 2.0, "timeScale": 0.5},
        {"effect": "burst+ripple", "effectI": 150, "effectPeriod": 2.2, "timeScale": 0.6},
        {"effect": "negative+sparkle", "effectI": 170, "effectPeriod": 2.4, "timeScale": 0.5},
        {"effect": "burst+sweep+orbit", "effectI": 170, "effectPeriod": 2.0, "timeScale": 0.6},
        {"effect": "flash+sparkle", "effectI": 150, "effectPeriod": 1.2, "timeScale": 0.5},
        {"effect": "orbit+sparkle", "effectI": 170, "effectPeriod": 1.7, "timeScale": 0.6},
    ],
    "fast": [
        {"effect": "orbit", "effectI": 170, "effectPeriod": 0.9, "timeScale": 2.0},
        {"effect": "sweep", "effectI": 170, "effectPeriod": 0.8, "timeScale": 1.8},
        {"effect": "ripple", "effectI": 150, "effectPeriod": 0.7, "timeScale": 2.2},
        {"effect": "orbit+sweep", "effectI": 170, "effectPeriod": 1.0, "timeScale": 2.0},
        {"effect": "flash", "effectI": 150, "effectPeriod": 0.5, "timeScale": 2.0},
    ],
}


def rim(n=N):
    """outline pixels ordered clockwise from 12 o'clock"""
    c = (n - 1) / 2
    pts = []
    for y in range(n):
        for x in range(n):
            d = math.hypot(x - c, y - c)
            if n / 2 - 1.15 < d <= n / 2 - 0.15:
                pts.append(((math.atan2(y - c, x - c) + math.pi / 2) % (2 * math.pi), x, y))
    pts.sort()
    return [(x, y) for _, x, y in pts]


RING = rim()


def rows_of(root, name):
    d = root.get("frames", {}).get(name)
    if d is None:
        return None
    rows = d["rows"] if isinstance(d, dict) else d
    return [str(r) for r in rows]


def overlay(rows, head, step_dir=1, vals=(15, 12, 9, 6, 4)):
    g = [[int(ch, 16) for ch in r] for r in rows]
    for t, v in enumerate(vals):
        x, y = RING[(head - t * step_dir) % len(RING)]
        if y < len(g) and x < len(g[y]):
            g[y][x] = max(g[y][x], v)
    return ["".join("%x" % v for v in r) for r in g]


def step_name(step):
    return step if isinstance(step, str) else step.get("frame")


def fullest(root, steps):
    best, score = None, -1
    for s in steps:
        rows = rows_of(root, step_name(s))
        if not rows:
            continue
        sc = sum(int(ch, 16) for r in rows for ch in r)
        if sc > score:
            best, score = rows, sc
    return best


def proc_variant(name, base, kind, idx):
    params = dict(base.get("params") or {})
    params["base"] = base["generator"]
    params.update(dict(FX[kind][idx % len(FX[kind])]))
    if kind == "max":
        params["fixed"] = 1.0
    if kind == "full":
        params.setdefault("shimmer", True)
    return {
        "label": f'{base.get("label", name)} ({kind})',
        "type": "procedural", "generator": "boost", "loop": True,
        "fps": int(min(30, max(base.get("fps", 15), 24))),
        "brightness": min(100, int(base.get("brightness", 80)) + 10),
        "params": params,
    }


def frames_full(root, name, base, idx):
    best = fullest(root, base["frames"])
    if best is None:
        return None
    step, names = 4, []
    for k in range(math.ceil(len(RING) / step)):
        fn = f"vx_{name}_full_{k}"
        root.setdefault("frames", {})[fn] = overlay(best, k * step, 1 if idx % 2 == 0 else -1)
        names.append(fn)
    return {"label": f'{base.get("label", name)} (full)', "loop": True, "fps": 14,
            "brightness": min(100, int(base.get("brightness", 80)) + 15), "frames": names}


def frames_fast(root, name, base):
    out = []
    for i, s in enumerate(base["frames"]):
        rows = rows_of(root, step_name(s))
        if not rows:
            return None
        fn = f"vx_{name}_fast_{i}"
        root.setdefault("frames", {})[fn] = overlay(rows, (i * 3) % len(RING))
        if isinstance(s, dict):
            e = dict(s)
            e["frame"] = fn
            if "duration" in e:
                e["duration"] = max(30, int(e["duration"] * 0.5))
            out.append(e)
        else:
            out.append(fn)
    return {"label": f'{base.get("label", name)} (fast)', "loop": True,
            "fps": int(min(24, max(8, base.get("fps", 8) * 2))),
            "brightness": min(100, int(base.get("brightness", 80)) + 10), "frames": out}


def main():
    files = {p: json.loads(p.read_text(encoding="utf-8")) for p in sorted(ROOT.glob("*.json"))}
    if not files:
        sys.exit(f"no JSON found in {ROOT.resolve()} - run this from the project root")
    anims = {k: (p, a) for p, r in files.items() for k, a in r.get("animations", {}).items()}
    refs = {}
    for k, (_, a) in anims.items():
        for vn in (a.get("variants") or {}).values():
            refs.setdefault(vn, []).append(k)
    targets = set(refs)
    counters = {"max": 0, "full": 0, "fast": 0}
    made = {"max": 0, "full": 0, "fast": 0}
    skipped = []

    for p, root in files.items():
        new_anims = {}
        for name, a in list(root.get("animations", {}).items()):
            role = a.get("role")
            if role not in ROLES or name in targets:
                continue
            variants = dict(a.get("variants") or {})
            for kind in ROLES[role]:
                cur = variants.get(kind)
                cur_anim = anims[cur][1] if cur in anims else None
                # replace a variant when it is missing, shared by several looks, or just the same frames played faster
                replace = (
                    cur_anim is None
                    or len(refs.get(cur, [])) > 1
                    or ("frames" in a and cur_anim.get("frames") == a.get("frames"))
                )
                if not replace:
                    continue
                idx = counters[kind]
                counters[kind] += 1
                if "generator" in a or a.get("type") == "procedural":
                    v = proc_variant(name, a, kind, idx)
                elif "frames" in a:
                    try:
                        v = frames_full(root, name, a, idx) if kind == "full" else frames_fast(root, name, a)
                    except (ValueError, KeyError):
                        v = None
                else:
                    v = None
                if v is None:
                    skipped.append(f"{name} ({kind})")
                    continue
                vname = f"{name}__{kind}"
                new_anims[vname] = v
                variants[kind] = vname
                made[kind] += 1
            if variants:
                a["variants"] = variants
        root["animations"].update(new_anims)

    # variant looks are not picked directly: take them out of the pickers (they still work as variants)
    hidden = 0
    for t in targets:
        if t in anims and anims[t][1].get("role") in ROLES:
            anims[t][1].pop("role", None)
            hidden += 1

    for p, root in files.items():
        p.write_text(json.dumps(root, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"created: {made}   hidden old variants: {hidden}")
    if skipped:
        print("skipped (could not build a variant):", ", ".join(skipped))


if __name__ == "__main__":
    main()