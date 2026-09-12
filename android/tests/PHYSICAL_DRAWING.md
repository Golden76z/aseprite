# Physical drawing validation

Use a connected tablet and a human operator. `adb input` is allowed to prepare
an ordinary 256x256 RGBA document through Aseprite's UI, but must not generate
any validation stroke. Mark setup versus physical-test phases in logcat.
Never report injected events or device capability ranges as physical observations.

The debug APK emits bounded `Aseprite` diagnostics:

- `MotionSample`: native device/source/tool/action/button state, native position,
  adjusted LAF window position, logical UI position, raw Android pressure,
  tilt/orientation (discovery only), historical count and Android event timestamp.
  Down/up and three initial moves per contact; one hover observation per tool.
- `MotionSummary`: contact id, tool, termination reason, MOVE count, historical
  sample total/maximum batch, min/max contact pressure (DOWN/MOVE and their history,
  excluding the release), duration and maximum gap between delivered event times.
- `EditorPointer`: actual editor down/up UI and document coordinates, canvas bounds
  in UI coordinates, and capture state after editor state processing.

`tool=1` is Android finger; `2` stylus; `4` eraser. LAF's corresponding enum values
are Touch=3, Pen=4, Eraser=6. Diagnostics do not call `Event::setPressure()`, enqueue
history or change brush dynamics. Release builds omit these diagnostics.

## Finger phase

1. Choose the pencil and a visible color using the real finger.
2. Draw short separate strokes at the top-left, center and bottom-right of the canvas.
3. Draw a continuous stroke, including a fast stroke; approach/cross a canvas edge
   and lift the finger. Check that the next contact begins a separate stroke.
4. Tap a menu or toolbar control. Report alignment, gaps/jumps, perceived latency,
   any stuck state, and whether the current UI size is comfortable.
5. Capture `android/build/jalon8-finger.png` before the pen phase.

## Pen phase

1. Move the real pen above the surface without touching, then tap a tool/color.
2. Draw with the tip at the three canvas positions, slow and fast, with several
   quick separate strokes. Check releases near the edge and later UI taps.
3. For pressure discovery, make three strokes in this order: light, normal, firm
   (normal comfortable writing pressure, without forcing). Record their order
   explicitly; a numeric value alone cannot identify the operator's intent.
4. If the pen has a physical eraser end or a supported eraser mode, describe how
   it is activated and try it. If none exists, report that; do not simulate one.
5. Capture `android/build/jalon8-stylus.png` and report alignment, continuity,
   latency, releases and UI comfort. Test a natural interruption only if practical.

## Evidence collection

From the repository root:

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" -d logcat -v threadtime -T 1 -s Aseprite:I AndroidRuntime:E libc:F \
  > android/build/jalon8-logcat.txt
# Run captures separately while the logcat process is recording:
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon8-finger.png
"$aseprite_adb" -d shell dumpsys input > android/build/jalon8-input-devices.txt
"$aseprite_adb" -d shell dumpsys activity activities > android/build/jalon8-activity.txt
```

Device IDs are session-specific. Correlate `MotionSample device=...` with
`dumpsys input` and the operator's declared phase. The debug trace cannot itself
prove a human used a finger rather than an injector impersonating a device.
A screenshot proves rendered pixels, not perceived alignment under a physical tip
or motion-to-photon latency. Record those observations from the operator.

Historical samples are currently inspected for counts/pressure only. Their
presence alone is not justification to implement replay; require an observed
stroke-quality problem first. No interpolation, prediction, pressure curve,
barrel-button mapping or brush-pressure dynamics belongs to this validation.
