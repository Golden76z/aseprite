# Android input probes

For bounded Debug timing captures, real pinch/pan recording, and reversible
immersive/scale comparisons, see [Gesture profiling](GESTURE_PROFILING.md).

These shell tests inject events through Android InputDispatcher and the app's
real AInputQueue. They do not replace physical finger/stylus validation. The Java
probe is a test utility for API 34, not an activity or an APK dependency. It uses
InputManagerGlobal through reflection because `adb shell input` cannot express
multiple pointer IDs, eraser tool type, or explicit mouse button state.

Build from the repository root (JDK 17, SDK platform/build-tools installed):

```bash
aseprite_sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
mkdir -p android/build/input-probe/classes android/build/input-probe/dex
javac --release 8 -cp "$aseprite_sdk/platforms/android-34/android.jar" \
  -d android/build/input-probe/classes android/tests/InputProbe.java
"$aseprite_sdk/build-tools/36.0.0/d8" \
  --lib "$aseprite_sdk/platforms/android-34/android.jar" \
  --output android/build/input-probe/dex android/build/input-probe/classes/InputProbe.class
"$aseprite_sdk/platform-tools/adb" -d push android/build/input-probe/dex/classes.dex \
  /data/local/tmp/aseprite-input-probe.dex
```

Start Aseprite and verify it is the foreground app before each probe. Coordinates
below are physical pixels on the tested 2160x1440 tablet at window scale 2.
The later density adjustment changes dialog positions: inspect the current
screenshot before using the original milestone-7 Cancel coordinates below.
File/Edit remain accessible at the menu coordinates shown here.

```bash
aseprite_adb="$aseprite_sdk/platform-tools/adb"
"$aseprite_adb" -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
# These taps should open File and Edit, respectively:
"$aseprite_adb" -d shell input touchscreen tap 18 12
"$aseprite_adb" -d shell input touchscreen tap 66 12
"$aseprite_adb" -d shell input keyboard keyevent KEYCODE_ESCAPE
# Inject two contacts, with the active ID later moved to array index 1:
"$aseprite_adb" -d shell CLASSPATH=/data/local/tmp/aseprite-input-probe.dex \
  app_process /system/bin InputProbe multi 18 12
```

Probe modes take two coordinates `x y`:

- `multi`: ID 7 down; ID 11 down; reordered move; secondary up; active up.
- `active-up`: same, but active ID 7 is lifted first. Remaining ID 11 must not
  become a new active contact; its later move/up must not produce another click.
- `gesture-in` / `gesture-out`: two fingers spread/pinch around physical `x y`,
  using 40 current samples and reordered IDs. The first contact is held 80 ms
  before the second; its provisional stroke must be rolled back. After the
  original finger lifts, the remaining finger moves 300 px without drawing.
- `gesture-pan`: translate the midpoint by (+240,+130) physical pixels at
  constant separation. Actual editor scroll changes, zoom stays constant.
- `gesture-cancel`: same pan ending in Android ACTION_CANCEL.
- `pen-fingers`: Pen plus one Finger slot, ten Pen moves then both releases.
  Finger motion must not navigate or interrupt the Pen. Uses a touchscreen
  source with explicit tool types; this is not a physical pressure test.
- `cancel`: down immediately followed by cancel, without a forced sleep.
- `eraser`: an eraser-type down/up. The LAF name must be `Eraser`.
- `left` / `mouse`: explicit primary / secondary mouse down/up.
- `wheel`: one downward mouse wheel step at the supplied position.

For cancellation, open New Sprite using Home's New File link, press Cancel
without releasing, then cancel the gesture. The dialog must stay open, and a
subsequent ordinary tap on Cancel must close it:

```bash
"$aseprite_adb" -d shell input touchscreen motionevent DOWN 1150 902
"$aseprite_adb" -d shell input touchscreen motionevent CANCEL 1150 902
# Capture/inspect before the ordinary tap.
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon7-cancel.png
"$aseprite_adb" -d shell input touchscreen tap 1150 902
```

These coordinates apply to the centered dialog on the validated tablet. Inspect
its current position before repeating the test. The probe uses constant injected pressure 1.0. Since milestone 9 the backend
forwards normalized Pen/Eraser pressure; Touch remains at the existing default.
These probes cannot validate physical pressure dynamics.

Observe `Aseprite` logcat messages and capture screenshots to validate actual UI
responses. Injection acceptance alone does not prove widget hit testing. Source
`stylus` via `adb shell input stylus tap` is also an injection, not a physical pen.

Portable raster/queue tests use the standalone CMake project at
`laf/os/android/tests`. See `android/README.md` for its build and CTest commands.

Milestone 13: open a copy of a saved document before navigation probes, compare
screenshots and editor zoom/scroll logs, then save and compare the document bytes
with the original. A provisional mark must not persist after a gesture. Keep
physical validation separate: real fingers must verify direction/feel and the
real pen must still draw with pressure. Android may cancel artificial three-tool
streams; do not suppress framework ACTION_CANCEL to make a probe pass.

### Fullscreen/insets regression (milestone 14)

Use the optimized `rasterProfile` APK. The host display-metrics and raster
contracts cover all four insets, offset content, fractional nearest-neighbor
mapping, clipping and IME close/restore. They do not replace device validation.

On hardware: open a private document, inspect the top menu and bottom timeline,
reveal bars with an edge swipe and let them hide, open Save As/Gboard, type text,
hide/refocus the keyboard, return from Home and from the SAF picker. Verify
physical pen pressure and alignment, single-finger drawing and pinch/pan.
`AsepriteInsets` logs distinct visible/stable/gesture/tappable/cutout/IME values
in Debug/profile variants, bounded to 96 state changes per activity.

The short performance regression reuses the milestone 13 trajectory and
profiler at unchanged density/scale; see
[results and capture paths](../../ANDROID_ARM64_JALON_14_COMPTE_RENDU.md).

### Bounded stylus discovery (milestone 15)

On Debug or `rasterProfile`, set `debug.aseprite.stylus` to a new token. The first
Pen/Eraser event starts a 30-second window, capped at 384 `AsepriteStylus` lines.
MOVE/HOVER_MOVE are sampled at 10 Hz; action/button changes are retained until
the cap. The token is polled at most once a second during stylus input. Clear
the property after collection. No output is produced in a normal Release build.

Test near-vertical and tilted directions, hover, light/firm contacts, each real
barrel button in hover/contact, and a physical eraser if present. Do not label
injected events as hardware observations. Decode the log with:

```sh
python3 android/tests/analyze_stylus_sample.py sample.log --token TOKEN --output summary.json
```

An empty log is rejected. Report observed ranges rather than hardware calibration
or a button capability inferred from dumpsys. UI scale regression compares
Default/Large with the same document and gesture trajectory; different logical
surfaces are intentional in this comparison.
