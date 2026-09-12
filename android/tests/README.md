# Android input probes

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
its current position before repeating the test. The probe's contact pressure
field is only a property of the injected Android event; the backend does not
read or forward pressure in this milestone.

Observe `Aseprite` logcat messages and capture screenshots to validate actual UI
responses. Injection acceptance alone does not prove widget hit testing. Source
`stylus` via `adb shell input stylus tap` is also an injection, not a physical pen.

Portable raster/queue tests use the standalone CMake project at
`laf/os/android/tests`. See `android/README.md` for its build and CTest commands.
