# Dotwave

**Custom Glyph Matrix animations for Nothing phones: always-on display, volume, charging, notifications and now-playing music.**

Dotwave drives the **physical 13×13 Glyph Matrix** through Nothing's official Glyph Matrix SDK.
Nothing is drawn on the phone screen; the in-app preview is only a live mirror of the matrix.

> ### 🤖 This app is vibe-coded
> Dotwave was built almost entirely by describing what I wanted to an AI coding assistant (Claude) and iterating on
> the result, not by hand-writing every line. It works on my phone, but it is hobby software: expect rough edges,
> read the code before trusting it, and please open an issue if something breaks.
> Not affiliated with or endorsed by Nothing Technology Limited.

**Developed for:** Nothing Phone (4a) Pro (13×13 matrix, Nothing OS build 20250801 or newer).
**Not tested on other models.** The matrix size is read at runtime, but the SDK link is device-specific.

---

## Features

### Animations for every situation
| Situation | What Dotwave does |
|---|---|
| **AOD (idle)** | Plays an animation of your choice whenever nothing else is happening (optionally only with the screen off). |
| **Volume** | Shows the media volume level on the matrix when you press the volume keys; special look at 100 %. |
| **Charging** | Plays while a charger is connected: separate looks for normal, fast and fully-charged, plus "Battery %" looks that show your real battery level as digits. |
| **Notification flash** *(optional)* | A short animation when a notification arrives (never reads notification content). |
| **Music** *(optional)* | While a song is playing, the AOD scrolls **Title - Artist** once, then shows a pattern (Disc / Bars / Wave / Pulse) for N seconds, then repeats. Pattern-only mode available. |

State is *derived*, not scripted: volume and notification are timed overlays, charging lasts while plugged in, and the
highest-priority active state wins. So "volume interrupts charging, then charging resumes" just works. Choose the
priority order (Volume › Charging › Notification is the default).

### The Glyph deck
- **200+ built-in glyphs** (AOD, volume, charging, notification), each shown as a live dot-matrix tile you can tap to select.
- Big live preview, family filter chips, previous / next / shuffle, and a level scrubber for volume and battery looks.
- Per-state **speed**, **brightness**, **duration** and a **global glyph brightness** (phone screen brightness is never touched).
- **Volume bars**: the same bar pattern as the music mode, with bar height following your volume.

### Make it yours
- **Import your own animations** as JSON (frame-by-frame pictures or procedural generators), see [JSON format](#json-animation-format).
- **Delete imported glyphs one at a time**: long-press an imported tile, confirm in a dialog that matches the app theme.
  Volume and charging glyphs take their companion "max / fast / full" variants with them.
- **10 colour themes** (Nothing, Paper, Cyber, Amber, Mint, Violet, Mono, Sunset, Ocean, Terminal) and 3 dot-matrix **logos** (Ring, G, Bolt) that change the launcher and status-bar icon.
- **Home-screen widget** mirroring the matrix; tap it for a **quick-setup sheet** (master switch, brightness, AOD picker).

### Always on
- Foreground service keeps everything running with the app closed; restarts after reboot.
- Optional **Always-on Glyph Toy** so the system can run your AOD from *Settings › Glyph Interface*.
- Optional "animate with screen off" (holds a wake lock; costs battery) and a keep-alive resend for static pictures.

---

## Install

There is no Play Store build. You build it yourself:

1. Android Studio (Ladybug or newer), JDK 17.
2. **Copy `glyph-matrix-sdk-2.0.aar`** from
   <https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit> into `app/libs/`.
   (Not bundled: Nothing's licence forbids redistribution.)
3. Open this folder and let Gradle sync (or run `gradle wrapper --gradle-version 8.9`).
4. On the phone: enable Developer options + USB debugging; Nothing OS build 20250801+ (20250829+ for the "Open Glyph Toys manager" button).
5. Run from Android Studio, or `./gradlew installDebug`.
6. In the app: switch **Dotwave** on, allow notifications, and use the **TEST** buttons.
7. Optional always-on path: *Settings › Glyph Interface › Flip to Glyph › Always-on Glyph Toy* → choose **Dotwave AOD**.
8. Optional music / notification flash: *Setup › Grant notification access*.

> The Android package id is still `com.example.glyphcontroller` (kept so existing installs and settings survive the rename).

---

## Using it

- **HOME**: master switch, global brightness, priority, event rows with TEST buttons, and a PROBLEMS panel if a JSON file has errors.
- **AOD / VOLUME / CHARGE**: pick a glyph and tune speed, brightness, duration. Test buttons play the event on the real matrix.
- **AOD › MUSIC**: *Show playing music* on/off, pattern (Disc / Bars / Wave / Pulse), show song name on/off, how often the name scrolls (5-60 s, default 15).
- **SETUP**: theme, notification flash, logo, widget, animation files (import / reload), advanced options.
- **Settings are all in the app**: nothing needs editing in code.

### Music mode notes
Android only lets an app read the playing song through **Notification access**, so Dotwave needs that permission for
music (and notification flash). It reads only the active media session's title and artist, never notification content.
Titles in scripts the tiny pixel font does not cover (Hindi, Japanese, ...) show as a small ▪-style mark.

---

## What the platform allows

Sources: Nothing's official `GlyphMatrix-Developer-Kit` README and a `javap` dump of `glyph-matrix-sdk-2.0.aar`.

### Confirmed (public, documented)
| Capability | API |
|---|---|
| Device id / size | `Glyph.DEVICE_25111p`, 13×13, `Common.getDeviceMatrixLength()` |
| Connect | `GlyphMatrixManager.getInstance(ctx)`, `init(callback)`, `register(deviceId)`, `unInit()` |
| Draw from an app | `setAppMatrixFrame(...)`, needs system build **20250801 or later** |
| Stop drawing | `closeAppMatrix()` |
| Frame content | `GlyphMatrixFrame.Builder` (3 layers) + `GlyphMatrixObject.Builder` (bitmap, position, scale, `setBrightness(0..255)`) |
| Always-on toy | Service with action `com.nothing.glyph.TOY` + meta-data `com.nothing.glyph.toy.aod_support=1`; system sends `EVENT_AOD` about once a minute |
| Permission | `com.nothing.ketchum.permission.ENABLE` |

### Hard limits you cannot code around
- **Glyph Toys outrank apps.** If the Glyph button / toy carousel is active, it overrides your content.
- **Phone (4a) Pro supports only AOD toys** (no Glyph Touch).
- **No global matrix-brightness API.** Brightness exists only per `GlyphMatrixObject` (0-255). Dotwave computes
  `255 × global × animation × frame × state` and sets it on the object.
- **No public "volume changed" callback**, and **no public "fast charging" flag** in Android (see [Detection](#detection-details)).
- A GitHub issue against the official example reports the matrix going dark after ~3-4 minutes on Phone (3) until
  the Glyph button is pressed, with no keep-alive API. Unverified on the 4a Pro. The SDK binary has an undocumented
  `setGlyphMatrixTimeout(boolean)`; its meaning is unknown, so it is an opt-in debug dropdown (default: never called).

### Not possible for a normal third-party app
Reading the Glyph button, overriding the toy carousel, forcing the matrix on indefinitely, and changing the matrix's
hardware brightness outside the per-object value.

### Still worth checking on your own phone
1. Whether `setAppMatrixFrame` content stays visible with the screen off / phone face-down, and for how long.
2. Whether the always-on toy path keeps a fast animation running or the system only honours the 1-minute `EVENT_AOD` cadence.
3. Whether re-sending a static frame (the "Keep-alive" setting) resets the system's timeout.

---

## Architecture

```
GlyphApp ─ GlyphRuntime (process singleton: settings, library, status flows, owners)
              │
   owners:  GlyphService (foreground, specialUse)   GlyphToyService (AOD toy)
              │                                       │
              └──────────────► GlyphController ◄──────┘     events:
                                   │   priority → picks state     VolumeMonitor   (AudioManager + volume broadcast)
                                   ▼                              ChargingMonitor (ACTION_BATTERY_CHANGED, sticky)
                           GlyphAnimationEngine                   ScreenMonitor   (SCREEN_ON/OFF)
                           frames | procedural                    MusicMonitor    (active media sessions)
                                   ▼                              GlyphNotificationListener (optional)
                            GlyphRenderer ─► GlyphLink ─► GlyphMatrixManager (shared, ref-counted)
```
- Duplicate requests (same state + animation) only update inputs; they never restart the animation.
- One dedicated thread runs timing, rendering and controller state. Timing uses absolute deadlines and `delay()`, no busy loops. One reused `Bitmap`; identical frames are not re-sent.
- A **foreground service** is required: monitors are dynamically-registered receivers that die with the process. `BootReceiver` restarts it after reboot. If Android refuses a background restart, open the app once.
- CPU suspends with the screen off, which freezes timers. "Animate with screen off" holds a partial wake lock (battery cost); default off.

### Detection details
- **Volume:** `percent = getStreamVolume(MUSIC) / getStreamMaxVolume(MUSIC) × 100` (max is read, not assumed). Triggers: the system's `android.media.VOLUME_CHANGED_ACTION` broadcast (long-standing but **undocumented**) plus ContentObservers on `volume_music*` settings keys as fallback.
- **Charging:** sticky `ACTION_BATTERY_CHANGED` → plug type (USB/AC/wireless/dock), full, level. No polling.
- **Fast charging:** heuristic only: `BATTERY_PROPERTY_CURRENT_NOW` magnitude ≥ 2500 mA (off below 1800 mA). Tune `FAST_ON_MA/FAST_OFF_MA` in `ChargingMonitor.kt`.
- **Music:** `MediaSessionManager.getActiveSessions()` through the notification-listener component; the first session in `STATE_PLAYING` supplies title and artist.

---

## JSON animation format

Bundled: `app/src/main/assets/animations/*.json`. Yours: `<Android/data/com.example.glyphcontroller/files>/animations/*.json`
(or **Setup › Import JSON**). Later files override earlier ones by animation name. Invalid files/animations/frames are
skipped and listed under **PROBLEMS**, they never crash the app.

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
**Generators** (input-reactive, so they can show a percentage): `level_fill`, `charge_flow`, `breathing_ring`, `orbit`,
`ripple`, `ring_gauge`, `eq_bars`, `radial_fill`, `signal_steps`, `speaker`, `spiral_fill`, `charge_percent`, `boost`,
`volume_bars`, and `music_aod` (used internally by music mode). Parameters are documented in `ProceduralGenerators.kt`
and `MusicGlyph.kt`. Examples: `examples/*.json` and `examples/packs/`.

Regenerate defaults and icons with `python3 tools/gen_assets.py`, extras with `tools/gen_extras.py`, and the
animation packs (`pack_*.json`) with `tools/gen_pack.py`.

---

## Debugging
```
adb logcat -s GlyphLink GlyphRenderer GlyphRuntime GlyphMatrixManager
adb shell dumpsys activity services com.example.glyphcontroller
adb shell dumpsys battery set ac 1 ; adb shell dumpsys battery set level 42 ; adb shell dumpsys battery reset
adb push my.json /sdcard/Android/data/com.example.glyphcontroller/files/animations/
```
- SDK says **UNSUPPORTED** → `Common.is25111p()` returned false; check you are on a 4a Pro and the system build.
- Link **CONNECTED** but nothing lights → see "Still worth checking"; try the always-on toy, press the Glyph button to wake the matrix.
- Nothing's debug switch (documented for the older strip SDK, may not apply): `adb shell settings put global nt_glyph_interface_debug_enable 1`.
- Release builds keep the SDK via `proguard-rules.pro`.

## Source map
`GlyphController.kt` state manager · `GlyphAnimationEngine.kt` · `ProceduralGenerators.kt` · `MusicGlyph.kt` (pixel font, music patterns, volume bars)
· `JsonAnimationLoader.kt` · `GlyphRenderer.kt` · `GlyphLink.kt` · `GlyphRuntime.kt` · `GlyphService.kt` · `GlyphToyService.kt`
· `VolumeMonitor.kt` · `ChargingMonitor.kt` · `ScreenMonitor.kt` · `MusicMonitor.kt` · `SettingsRepository.kt` (DataStore)
· `MainActivity.kt` / `QuickMenuActivity.kt` (Compose UI) · `GlyphPicker.kt` (glyph deck, themes) · `GlyphWidget.kt` (home-screen widget) · `IconStyle.kt`

## Widget, quick setup & logo (details)
- **Widget**: 2×2 shows the 13×13 dot matrix; wider (about 4×2) adds animation name, state and brightness. It mirrors exactly what is sent to the matrix. A widget is a `RemoteViews` bitmap, not a live view, so it refreshes about twice per second and looks like a slow stepped preview. Nothing is pushed while the screen is off or no widget is placed.
- **Quick setup** (`QuickMenuActivity`): translucent bottom sheet sharing state with the main screen through `GlyphRuntime`.
- **Logo**: three `<activity-alias>` launcher entries; choosing a logo enables one and disables the others. The launcher decides when the home screen refreshes, so it can lag a few seconds.

## Contributing / feedback
Issues and pull requests are welcome, especially reports from other Nothing models. Since this is vibe-coded, "this looks wrong" is a perfectly good bug report.

## Licence and credits

**Dotwave's own code is released under the [MIT License](LICENSE)** (c) 2026 AK-120: use, copy, modify and share it freely,
keeping the copyright notice.

**The MIT licence covers this project only, not the Nothing SDK.** The Glyph Matrix SDK is *not* included in this
repository. You download `glyph-matrix-sdk-2.0.aar` yourself from Nothing's
[GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) and it stays under
Nothing's own licence, which (among other things) does not allow redistributing the SDK and requires written permission
from Nothing for commercial use. Read their `LICENSE.md` before you build or share an APK. Questions to Nothing: `GDKsupport@nothing.tech`.

### Third-party APIs, SDKs and libraries used
| Component | Used for | Licence |
|---|---|---|
| [Nothing Glyph Matrix SDK](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) (`GlyphMatrixManager`, `GlyphMatrixFrame`, ...) | Drawing on the physical Glyph Matrix, Glyph Toy API | Nothing's SDK licence (not redistributed here) |
| Android platform APIs (`MediaSessionManager`, `NotificationListenerService`, `AudioManager`, `BatteryManager`, foreground services, `AppWidgetManager`) | Music, notifications, volume, charging, widget | Android Open Source Project, Apache 2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) and Material 3 | UI | Apache 2.0 |
| AndroidX Core, Activity, Lifecycle, DataStore | App plumbing, settings storage | Apache 2.0 |
| [Kotlin](https://kotlinlang.org) and kotlinx.coroutines | Language, concurrency | Apache 2.0 |

"Nothing" and "Glyph" are trademarks of Nothing Technology Limited. Dotwave is an independent hobby project and is not
affiliated with, sponsored by or endorsed by Nothing.

Built by AK-120 with the help of an AI coding assistant ([Claude](https://claude.ai), by Anthropic). See the vibe-coded note at the top.

## Releasing
See [RELEASING.md](RELEASING.md).
