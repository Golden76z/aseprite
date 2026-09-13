# Bounded gesture profiling (jalon 13)

Profiling is compiled for Android Debug, or with the explicit CMake option
`ASEPRITE_ANDROID_GESTURE_PROFILE=ON`. The `rasterProfile` Android variant uses
this option with optimized native code and `NDEBUG`; ordinary Release excludes
the probes. Both configurations use the same recording hooks and bounds.
It does not change recognition, dispatch, redraw, or presentation behavior.

Build/install the Debug or rasterProfile APK. Arm a single two-finger gesture with a
new token (letters, digits, hyphens, underscores; maximum 60 characters):

```sh
adb -d shell setprop debug.aseprite.profile normal-physical-pinch-01
```

On the colored test document, make a continuous real pinch, then lift
both fingers. Use a separate token for a pan at nearly constant finger spacing.
Use 5–8 seconds for the slow Debug reference, or initially 2–3 seconds for the
optimized build so its higher frame count fits the same bounded record buffer.
The trace starts at receipt of the second contact. It ends after End/Cancel is
processed on the GUI thread and its redraw/presentation completes. It records
at most 15 seconds and 16384 records. A timeout is flushed at the next GUI cycle;
a motionless unfinished gesture may therefore defer the file, but cannot keep
recording forever. A token is consumed once per process. No new token means no
new recording. Clear the property when done.

```sh
adb -d exec-out run-as org.aseprite.android cat \
  files/jalon13-profile-normal-physical-pinch-01.csv > /tmp/pinch.csv
python3 android/tests/analyze_gesture_profile.py /tmp/pinch.csv
adb -d shell 'setprop debug.aseprite.profile ""'
```

`AsepriteProfile` emits only a completion/error log. Check `overflow=0` in that
message before using a trace. Records are buffered in memory, with no file/log
I/O during the measured gesture. The buffer mutex and monotonic-clock calls add
small overhead; older input diagnostics remain enabled in Debug only. The flush is
excluded from measured GUI/presentation spans. Do not run screenshots,
screenrecord, or file dumps during a measurement. Record both native and Skia
optimization levels; Debug timings do not predict optimized Release speed.

## Reversible comparisons

Save unsaved drawings and back up `files/user` before reinstalling/restarting.
Use the same document, initial 100% zoom, centered canvas, orientation and thermal
conditions. Repeat pinch and pan; do not equate injected input with physical
sensor behavior. Use ABBA order for normal/immersive comparisons when possible.

For an OS-supported immersive experiment, first save the exact value from
`settings get global policy_control`. Temporarily set
`immersive.full=org.aseprite.android`, inspect the actual screenshot and window
insets to verify that Android honored it, then restore the original setting
(`settings delete global policy_control` if originally absent). A setting that
is accepted but does not hide the navigation bar is not a valid immersive case.
On this tablet the OS policy setting was accepted but did not hide the bar.
The alternative provided here is a class under `src/debug/java`, shared by the
Debug and explicit rasterProfile variants only:
`GestureProfileExperiment`. Set `debug.aseprite.immersive=1` and send the app
Home/foreground so its native focus callback applies the experiment. Set it to
0 and repeat the focus transition to restore the original decor flags. No
rendering architecture or permanent fullscreen behavior changes.

For the optional true 1:1 presentation experiment, set
`debug.aseprite.scale1=1` **before process launch**. This profiling-only override uses
window scale 1 and mapping density 320, yielding a source raster the size of the
native buffer (subject to keyboard content bounds). It does not modify Android
wm density or Aseprite's saved scale preference. It changes UI size/rendering
workload, so it is not a copy-only microbenchmark. Restart after clearing the
property to restore density scaling. Preserve/compare user preferences and
restore any experiment-induced layout values before the final launch.

## Trace schema

CSV columns: `stage,t_ns,duration_ns,id,frame,tid,x0..x7`. Timestamps are
`CLOCK_MONOTONIC` nanoseconds, matching NDK MotionEvent timestamps. Durations are
wall time, including any blocking. `id` correlates a MotionEvent with navigation,
queue, callback and editor processing; `frame` identifies a GUI dispatch cycle,
not necessarily a single buffer post. Input thread records have frame zero.

| Stage | Additional values |
|---|---|
| motion | event time ns, history count, action, device ID, pointer count, oldest history time ns, first tool type, source |
| navigation | phase (0 Begin, 1 Update, 2 End, 3 Cancel), ratio × 1e6, midpoint x/y |
| queued / gui_dequeue / gui_callback | correlation ID; each timestamp identifies a different queue boundary |
| editor | phase, displayed zoom changed, only scroll changed, displayed zoom × 1e6, scroll x/y |
| presentation | physical width/height, logical raster width/height, window scale, native stride pixels, copy success, post return code |
| raster_copy | source width/height, destination width/height, pixels written, bytes written, BGRA conversion, success |

Other timed spans: `motion_work`, `input_coordinate_mapping`,
`native_mutex_wait` (distinguish input and GUI TIDs), `gui_cycle`,
`redraw_invalidation` (flushRedraw), `redraw_paint` (paint message dispatch),
`editor_paint`, `sprite_render` (Aseprite render engine), `skia_draw_image`
(actual raster SkCanvas drawImageRect), `skia_composite` (UI layers),
`window_lock`, `window_post`. Skia drawing elsewhere (e.g. text/rectangles) is
included in outer paint/composition spans; this is not a GPU timing probe.
Nested spans overlap; do not add them together.

The analyzer writes summaries, individual presentation records, GUI-frame
records and update-to-frame mappings. Frame records include dimensions,
zoom/pan classification, total copied bytes/pixels and presentation time.
It groups repeated presentations within a GUI cycle to avoid reporting duplicate
posts as distinct visual updates/FPS. Multiple navigation updates within a GUI
frame are reported as visually combined, not silently dropped. Queue/callback ID
differences expose missing dispatch; callbacks without editor acceptance are
reported separately. Historical samples are counted but not replayed by this
backend. Current sample cadence and delivery age describe batching plus input
thread scheduling/blocking, not isolated digitizer hardware latency.

## Optimized raster variant

`rasterProfile` inherits Release packaging, uses CMake `RelWithDebInfo` and
`ASEPRITE_ANDROID_GESTURE_PROFILE=ON`, and links the separate optimized Skia
archive at `.deps/skia/out/android-arm64-profile`. It is debuggable only to allow
ADB `run-as` collection; native code keeps `NDEBUG`, without `_DEBUG`/`DEBUGMODE`.
The recording hooks, clock, buffer size, tokens and flush boundaries are identical
to Debug. Ordinary Debug input/pressure diagnostic logs are disabled by `NDEBUG`.

Build optimized Skia first, at the pinned revision, using the exact GN arguments
recorded in `ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md`. Then run:

```sh
android/gradlew -p android :app:assembleRasterProfile --console=plain --max-workers=4
adb -d install -r android/app/build/outputs/apk/rasterProfile/app-rasterProfile.apk
```

Override its archive directory with `-Paseprite.profileSkiaLibraryDir=/path`.
Inspect `.cxx/RelWithDebInfo/*/arm64-v8a/compile_commands.json`: the measured
configuration uses `-O2 -g -DNDEBUG`, `SK_SUPPORT_GPU=0`, `SK_ENABLE_SKSL=1` and the
explicit profiling define. Retain the unstripped `libaseprite.so` and native debug
symbol metadata beside the APK; the packaged library can be stripped without
removing the probes. Skia uses `-O3`, line tables and unwind tables.

For comparisons, rerun the analyzer on both baseline and candidate traces; it
now reports `gesture_begin` and per-frame `editor_paint_ms`. For example:

```sh
python3 android/tests/compare_gesture_profiles.py \
  --baseline android/build/jalon13-profile-single-present-?-gesture-pan.csv \
  --candidate android/build/jalon13-profile-optimized-?-gesture-pan.csv \
  --output android/build/jalon13-optimized-pan-comparison.json
```

The comparator rejects changed framebuffer/logical dimensions or post counts.
Do not infer physical FPS from the injected probe's approximately 30 Hz input.
