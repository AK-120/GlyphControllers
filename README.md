# Glyph Controller — custom AOD / volume / charging for the Nothing Phone (4a) Pro Glyph Matrix

Drives the **physical 13×13 Glyph Matrix** through Nothing's official Glyph Matrix SDK (`glyph-matrix-sdk-2.0.aar`).
Nothing is drawn on the phone screen; the in-app preview is only a mirror for debugging.

> **Status: written and reviewed, but not compiled or run on a device.** The build sandbox had no Android SDK
> and no Phone (4a) Pro. Expect to fix small compile errors on first sync, and treat every "unverified" item
> below as something to confirm on your phone.

---

## 1. What the platform actually allows (researched, not assumed)

Sources: Nothing's official `GlyphMatrix-Developer-Kit` README and a `javap` dump of `glyph-matrix-sdk-2.0.aar`.

### Confirmed (public, documented)
| Capability | API |
|---|---|
| Device id / size | `Glyph.DEVICE_25111p`, 13×13, `Common.getDeviceMatrixLength()` |
| Connect | `GlyphMatrixManager.getInstance(ctx)`, `init(callback)`, `register(deviceId)`, `unInit()` |
| Draw from an app | `setAppMatrixFrame(...)` — needs system build **20250801 or later** |
| Stop drawing | `closeAppMatrix()` |
| Frame content | `GlyphMatrixFrame.Builder` (3 layers) + `GlyphMatrixObject.Builder` (bitmap, position, scale, `setBrightness(0..255)`) |
| Always-on toy | Service with action `com.nothing.glyph.TOY` + meta-data `com.nothing.glyph.toy.aod_support=1`; system sends `EVENT_AOD` about once a minute |
| Permission | `com.nothing.ketchum.permission.ENABLE` |

### Hard limits you cannot code around
- **Glyph Toys outrank apps.** If the user presses the Glyph button / the toy carousel is active, it overrides your content.
- **Phone (4a) Pro supports only AOD toys** (no Glyph Touch).
- **No global matrix-brightness API.** Brightness exists only per `GlyphMatrixObject` (0–255). This project computes
  `255 × global × animation × frame × state` and sets it on the object. Screen brightness is never touched.
- **No public "volume changed" callback**, and **no public "fast charging" flag** in Android (see §4).
- A GitHub issue against the official example reports the matrix going dark after ~3–4 minutes on Phone (3)
  until the Glyph button is pressed, with no keep-alive API. **Unverified on the 4a Pro**, but plan for it.
  The SDK binary has an undocumented `setGlyphMatrixTimeout(boolean)`; its meaning is unknown, so it is exposed
  as an opt-in debug dropdown (default: never called).

### Unverified — test on your phone
1. Whether `setAppMatrixFrame` content is visible **with the screen off / phone face-down** and for how long.
2. Whether the always-on toy path keeps a 12 fps animation running or the system only honours the 1-minute `EVENT_AOD` cadence.
3. Whether re-sending a static frame (the "Keep-alive" setting) resets the system's timeout.

### Not possible for a normal third-party app
Reading the Glyph button as an app, overriding the toy carousel, forcing the matrix on indefinitely, and changing
the matrix's hardware brightness outside the per-object value.

---

## 2. Architecture

```
GlyphApp ─ GlyphRuntime (process singleton: settings, library, status flows, owners)
              │
   owners:  GlyphService (foreground, specialUse)   GlyphToyService (AOD toy)
              │                                       │
              └──────────────► GlyphController ◄──────┘     events:
                                   │   priority → picks state     VolumeMonitor   (AudioManager + volume broadcast)
                                   ▼                              ChargingMonitor (ACTION_BATTERY_CHANGED, sticky)
                           GlyphAnimationEngine                   ScreenMonitor   (SCREEN_ON/OFF)
                           frames | procedural                    GlyphNotificationListener (optional)
                                   ▼
                            GlyphRenderer ─► GlyphLink ─► GlyphMatrixManager (shared, ref-counted)
```
- **State is derived, not scripted.** Charging lasts while plugged; volume/notification are timed overlays.
  `resolve()` picks the highest-priority active state, so "volume interrupts charging, then charging resumes"
  and "timeout → AOD" need no special code. Default priority is Volume › Charging › Notification
  (otherwise volume feedback could never show while charging); switch to Charging-first in the UI.
- Duplicate requests (same state + animation) only update inputs; they never restart the animation.
- One dedicated thread runs timing, rendering and controller state. Timing uses absolute deadlines and
  `delay()` — no busy loops. One reused `Bitmap`; identical frames are not re-sent.
- **Foreground service** is required: monitors are dynamically-registered receivers that die with the process.
  `BootReceiver` restarts it after reboot. If Android refuses a background restart, open the app once.
- CPU suspends with the screen off, which freezes timers. "Animate with screen off" holds a partial wake lock
  (battery cost); default off.

## 3. JSON animation format

Bundled: `app/src/main/assets/animations/*.json`. Yours: `<Android/data/com.example.glyphcontroller/files>/animations/*.json`
(or **Import JSON** in the app). Later files override earlier ones by animation name. Invalid files/animations/frames
are skipped and listed under **PROBLEMS** in the app — they never crash it.

```jsonc
{
  "schema": 1,
  "frames": {                       // optional reusable frames
    "dot": { "rows": ["0000000000000", "... 13 rows of 13 chars ..."] }
  },                                // char: 0-9 a-f = intensity*17, '.'/' ' = off, '#' = full
  "animations": {
    "my_aod": {
      "label": "My AOD", "role": "aod",       // role: aod | volume | charging | notification
      "loop": true, "loopCount": 0,           // loopCount 0 = forever
      "fps": 12, "brightness": 60,            // brightness 0-100
      "frames": [
        "dot",                                                  // by name
        { "frame": "dot", "duration": 100, "brightness": 70 },  // name + per-frame timing/brightness
        { "rows": ["...13 rows..."], "duration": 120 }          // inline
      ],
      "variants": { "fast": "other_animation_name" }            // charging: fast | full, volume: max
    },
    "my_volume": {
      "role": "volume", "type": "procedural", "generator": "level_fill",
      "fps": 30, "brightness": 100,
      "params": { "shape": "disc", "smoothMs": 240, "waveAmp": 0.9 },
      "variants": { "max": "volume_pulse_max" }
    }
  }
}
```
Generators (input-reactive, so they can show a percentage): `level_fill` (volume / fixed level), `charge_flow`
(battery level + flowing bands), `breathing_ring`, `orbit`, `ripple`. Parameters are in `ProceduralGenerators.kt`.
Examples: `examples/aod_example.json`, `volume_example.json`, `charging_example.json`.
Regenerate the defaults and icons with `python3 tools/gen_assets.py`, the extras with `tools/gen_extras.py`, and the 145-animation packs (`pack_*.json`) with `tools/gen_pack.py`.

## 4. Detection details
- **Volume:** `percent = getStreamVolume(MUSIC) / getStreamMaxVolume(MUSIC) × 100` (max is read, not assumed).
  Triggers: the system's `android.media.VOLUME_CHANGED_ACTION` broadcast (long-standing but **undocumented**) plus
  ContentObservers on `volume_music*` settings keys as fallback.
- **Charging:** sticky `ACTION_BATTERY_CHANGED` → plug type (USB/AC/wireless/dock), full, level. No polling.
- **Fast charging:** heuristic only — `BATTERY_PROPERTY_CURRENT_NOW` magnitude ≥ 2500 mA (off below 1800 mA).
  Tune `FAST_ON_MA/FAST_OFF_MA` in `ChargingMonitor.kt`.

## 5. Build & install
1. Android Studio (Ladybug or newer), JDK 17.
2. **Copy `glyph-matrix-sdk-2.0.aar`** from
   <https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit> into `app/libs/`.
   (Not bundled: Nothing's licence forbids redistribution.)
3. Open this folder, let Gradle sync (Android Studio creates the Gradle wrapper; or run `gradle wrapper --gradle-version 8.9`).
4. Phone: enable Developer options + USB debugging; Nothing OS must be build 20250801+ (20250829+ for the "Open Glyph Toys manager" button).
5. Run on the Phone (4a) Pro, or `./gradlew installDebug`.
6. In the app: switch **Glyph Controller** on, allow notifications, use **TEST** buttons.
7. Optional always-on path: Settings › Glyph Interface › Flip to Glyph › Always-on Glyph Toy → choose "Glyph Controller AOD".

## 6. Debugging
```
adb logcat -s GlyphLink GlyphRenderer GlyphRuntime GlyphMatrixManager
adb shell dumpsys activity services com.example.glyphcontroller
adb shell dumpsys battery set ac 1 ; adb shell dumpsys battery set level 42 ; adb shell dumpsys battery reset
adb push my.json /sdcard/Android/data/com.example.glyphcontroller/files/animations/
```
- SDK says **UNSUPPORTED** → `Common.is25111p()` returned false; check you're on a 4a Pro and the system build.
- Link **CONNECTED** but nothing lights → see "Unverified" items; try the always-on toy, press the Glyph button to wake the matrix.
- Nothing's debug switch (documented for the older strip SDK, may not apply): `adb shell settings put global nt_glyph_interface_debug_enable 1`.
- Release builds keep the SDK via `proguard-rules.pro`.

## 7. Files
`GlyphController.kt` state manager · `GlyphAnimationEngine.kt` · `ProceduralGenerators.kt` · `JsonAnimationLoader.kt`
· `GlyphRenderer.kt` · `GlyphLink.kt` · `GlyphRuntime.kt` · `GlyphService.kt` · `GlyphToyService.kt`
· `VolumeMonitor.kt` · `ChargingMonitor.kt` · `ScreenMonitor.kt` · `SettingsRepository.kt` (DataStore) · `MainActivity.kt` (Compose UI)

## Home-screen widget, quick setup, logo & notification icon

**Widget** (`GlyphWidget.kt`, `res/layout/widget_*.xml`): long-press the home screen > Widgets > "Glyph AOD", or use *ICON & WIDGET > Add home-screen widget* in the app.
- 2x2 shows the 13x13 dot matrix; wider (about 4x2) adds the animation name, state and brightness. Size mapping uses `RemoteViews(Map<SizeF, RemoteViews>)` (API 31+).
- It mirrors exactly what is sent to the matrix (same pixels, same brightness scaling).
- **Limit:** a widget is a `RemoteViews` bitmap, not a live view. The launcher cannot run animation code, so the picture is refreshed about 2x per second (`INTERVAL_MS` in `GlyphWidget.kt`) and looks like a slow stepped preview. Nothing is pushed while the screen is off or when no widget is placed.
- With the controller switched off the widget shows an empty matrix and `OFF`.
- Tapping it opens `QuickMenuActivity`, a translucent bottom sheet: live matrix, master switch, Glyph brightness, AOD animation (prev / next / shuffle), AOD speed, logo picker, "Open full app". It shares state with the main screen through `GlyphRuntime`.

**Logo & notification icon** (`IconStyle.kt`): three dot-matrix logos (Ring, G, Bolt), generated by `tools/gen_icons.py`.
- Notification (status-bar) icon: taken from the setting and re-posted immediately by `GlyphService`.
- Launcher icon: three `<activity-alias>` entries in the manifest; choosing a logo enables one and disables the others with `DONT_KILL_APP`. Android and the launcher decide when the home screen refreshes, so it can lag a few seconds, and some launchers need a restart. Adaptive icons also include a monochrome layer for themed icons.

## Animation picker & themes

- **Glyph deck** (`GlyphPicker.kt`): the animation dropdowns for AOD / volume / charging / notification were replaced by a snapping row of live dot-matrix tiles. Every visible tile plays its animation (about 11 fps, drawn only for visible tiles); tap to select. Level-style animations are previewed at a fixed sample level (volume 65 %, charging 60 %). Thumbnails ignore brightness and are approximations of what the engine sends.
- **Settings page** (top-right button on the main screen): THEME, ICON & WIDGET, ADVANCED.
- **Themes** (`AppTheme.kt`): Nothing, Paper (light), Cyber, Amber, Mint, Violet, Mono. Stored in DataStore and also applied to the quick-setup sheet. Add one by adding an entry to the `AppTheme` enum.
