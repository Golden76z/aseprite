# Android ARM64 build milestones

This contains the build infrastructure and minimal LAF platform skeleton from
[ANDROID_ARM64_AUDIT.md](../ANDROID_ARM64_AUDIT.md). Android now has an internal
event queue, a CommonSystem base and one logical Skia window. Its raster surface
is presented through ANativeWindow. The real Aseprite startup and Home UI now
run on the tablet. Single-contact touch, basic pen/eraser, mouse and hardware-key
translation now use the existing LAF event queue. System filesystem integration
remains unimplemented.

Detailed reports (French):
[jalon 1](../ANDROID_ARM64_JALON_1_COMPTE_RENDU.md),
[jalon 2](../ANDROID_ARM64_JALON_2_COMPTE_RENDU.md),
[jalon 3](../ANDROID_ARM64_JALON_3_COMPTE_RENDU.md),
[jalon 4](../ANDROID_ARM64_JALON_4_COMPTE_RENDU.md),
[jalon 5](../ANDROID_ARM64_JALON_5_COMPTE_RENDU.md),
[jalon 6](../ANDROID_ARM64_JALON_6_COMPTE_RENDU.md),
[jalon 7](../ANDROID_ARM64_JALON_7_COMPTE_RENDU.md).
Device validation: [XPPen MDP1221](../ANDROID_ARM64_JALON_4_VALIDATION_TABLETTE.md).

The port is on the `android-port` branch of
[Golden76z/aseprite](https://github.com/Golden76z/aseprite/tree/android-port).
Clone with `--recurse-submodules` to obtain the matching LAF and clip forks.

The `aseprite` CMake target is a shared library whose output is
`libaseprite.so`. Its NativeActivity entry logs library loading, creation, start
and destruction, then returns to Android's main looper without finishing the
activity. Once ANativeWindow is available, an owned UI thread extracts the APK
resources to app-private storage and calls the existing `app_main()` (no duplicate
App initialization). Android destruction requests normal app exit and joins that
thread. The milestone 5 test pattern has been removed.

## Toolchain

The versions used for validation are pinned in this project:

| Tool | Version |
|---|---|
| JDK | 17 |
| Gradle wrapper | 9.1.0, with distribution SHA-256 verification |
| Android Gradle plugin | 9.0.1 |
| Compile/target SDK | 36 |
| Minimum Android API | 26 |
| Android NDK | 28.2.13676358 |
| SDK CMake | 3.22.1, including Ninja |
| Android ABI | `arm64-v8a` only |
| Skia | `m124-08a5439a6b` (`08a5439a6be726021c1c1905d23ce298a3edc5e4`) |

The AGP/Gradle/JDK pairing follows the
[AGP 9.0 compatibility table](https://developer.android.com/build/releases/agp-9-0-0-release-notes).

Provide the SDK through the usual `ANDROID_HOME` environment variable or an
untracked `android/local.properties` containing `sdk.dir=/absolute/path/to/sdk`.
The Linux host also needs a native C/C++ compiler, Git, and Python 3.

## Skia prerequisite

Supply a matching **Android ARM64, raster-only, debug** build of the pinned
Skia source, including its dependency archives. Desktop Skia binaries are not
valid inputs. Skia is an external prerequisite, not downloaded during Gradle
configuration.

The default locations, relative to the repository root, are:

```text
.deps/skia
.deps/skia/out/android-arm64
```

The following commands reproduce the Skia build performed for this milestone.
Run them from the repository root; adjust `aseprite_sdk` if necessary. The
checkout command is for a new dependency directory.

```bash
aseprite_sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
git clone --depth 1 --branch m124-08a5439a6b \
  https://github.com/aseprite/skia.git .deps/skia
(
  cd .deps/skia
  python3 tools/git-sync-deps
  bin/gn gen out/android-arm64 --args="\
    target_os=\"android\" target_cpu=\"arm64\" \
    ndk=\"$aseprite_sdk/ndk/28.2.13676358\" ndk_api=26 \
    is_debug=true is_official_build=false is_trivial_abi=false \
    skia_enable_tools=false skia_enable_gpu=false skia_use_gl=false \
    skia_use_system_expat=false skia_use_system_icu=false \
    skia_use_system_libjpeg_turbo=false skia_use_system_libpng=false \
    skia_use_system_libwebp=false skia_use_system_zlib=false \
    skia_use_freetype=true skia_use_harfbuzz=true \
    skia_pdf_subset_harfbuzz=true skia_use_system_freetype2=false \
    skia_use_system_harfbuzz=false"
  "$aseprite_sdk/cmake/3.22.1/bin/ninja" -C out/android-arm64 -j 8 skia modules
)
```

Skia and its build products are ignored by Git. The tested Skia archive contains
PNG objects, so the existing Aseprite PNG linkage through Skia was retained.
WebP remains enabled and resolves to the ARM64 archive in this directory.

## Build commands

From the repository root, with the prerequisites above:

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

This produces the signed debug APK at
`android/app/build/outputs/apk/debug/app-debug.apk`, containing
`lib/arm64-v8a/libaseprite.so` and `lib/arm64-v8a/libc++_shared.so`.

To build only the native library:

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4
```

For Skia at other locations, use absolute paths:

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  -Paseprite.skiaDir=/absolute/path/to/skia \
  -Paseprite.skiaLibraryDir=/absolute/path/to/skia/out/android-arm64 \
  --console=plain --max-workers=4
```

The native-build task configures the Linux host tools, builds `gen`, imports it
through `GEN_EXE`, configures Android CMake, then compiles and links `aseprite`.
The verified output is `libaseprite.so`. This task does not build an APK.

Useful separate tasks:

```bash
android/gradlew -p android :app:buildHostGen --console=plain
android/gradlew -p android ':app:configureCMakeDebug[arm64-v8a]' --console=plain
android/gradlew -p android :app:processDebugMainManifest --console=plain
```

`android/host-tools/CMakeLists.txt` configures only the dependencies of `gen`:
LAF base, cfg/SimpleIni, and tinyxml2. It rejects cross-compilation. The output
is `android/build/host-tools/bin/gen`, an x86-64 Linux executable on the tested
machine. Native CMake configuration rejects a missing or non-runnable
`GEN_EXE`; Android does not build its own `gen` target from source.

## Feature configuration

The Gradle project passes these CMake options explicitly:

```text
LAF_BACKEND=skia
LAF_WITH_CLIP=ON
LAF_WITH_EXAMPLES=OFF
ENABLE_TESTS=OFF
ENABLE_BENCHMARKS=OFF
ENABLE_NEWS=OFF
ENABLE_UPDATER=OFF
ENABLE_DRM=OFF
ENABLE_SCRIPTING=OFF
ENABLE_WEBSOCKET=OFF
ENABLE_STEAM=OFF
ENABLE_SENTRY=OFF
ENABLE_DESKTOP_INTEGRATION=OFF
ENABLE_QT_THUMBNAILER=OFF
ENABLE_I18N_STRINGS=OFF
ENABLE_TRIAL_MODE=OFF
```

Android defines `LAF_ANDROID`, not `LAF_LINUX`, and uses `SK_BUILD_FOR_ANDROID`
with `SK_SUPPORT_GPU=0` and `SK_ENABLE_SKSL=1`. The latter matches the runtime
effects already present in the raster Skia archive; it does not enable GPU
rendering. The existing process-local `clip_none.cpp` is selected
instead of XCB; no system clipboard integration was added. Native desktop
dialog sources and X11 OS sources are excluded only for Android. The common
Skia system/window sources use the Android skeleton introduced in milestone 2.

## Verified build status — milestone 7, 12 September 2026

The full native build and debug APK succeed. The APK contains the ARM64 Aseprite
and C++ runtime libraries, 190 runtime resource files and their extraction index.
The original build feature flags and single-window policy are preserved.

On the XPPen MDP1221 (Android 14/API 34), installation succeeds and `app_main()`
loads the theme, fonts, widgets, strings and palettes. Screenshots confirm the
real Home UI, menus opened through Android input, and the New Sprite dialog.

Android defaults to window scale 2 through the existing `screen_scale` preference,
with a one-time migration from the scale-1 bootstrap. Later user choices are
preserved. The logical Skia surface is 1080x720; integer nearest-neighbor expansion
fills the physical 2160x1440 RGBA8888 buffer, respecting both row strides and channel
order. Theme/UI scale remains 1. GPU and MultipleWindows stay off.

NativeActivity attaches AInputQueue to Android's main looper. Its callbacks enqueue
LAF pointer/key events and wake the existing GUI queue; they never call widgets.
Physical input coordinates are divided by the current window scale before UI
hit testing. One pointer ID is tracked; secondary contacts are ignored. Cancel,
focus loss and input-queue destruction release pressed state. The LAF stylus type
is named `Pen`; pressure, tilt, barrel buttons and pen hover are not implemented.
Keyboard text uses Android's hardware KeyCharacterMap, without IME composition.

The fullscreen window flag uncovers the menu bar. Android navigation and XP-Pen
controls still overlay parts of the bottom/left edges. There is no density-based
layout or per-widget adaptation. Automated device injections confirm touch hit
positions, cancellation, pen/eraser identification, mouse buttons and basic keys;
physical finger/pen ergonomics still require user validation. Reproducible input
probes are documented in [tests/README.md](tests/README.md).

Presentation retains the native-window lifetime mutex through lock/copy/post.
Redraw remains event-driven. Home/return, nested-menu task removal, and Exit/relaunch
in the same process were tested. Exit now finishes the NativeActivity; a new run
resets the previous UI closing state before `app_main()`. At idle, CPU ticks did
not increase over the final 12-second sample. See the milestone 7 report for exact
evidence and limits.

### Install and check native startup manually

The initial APK build had no connected device; the subsequent tablet validation
succeeded. To repeat it, run these commands from the repository root after
connecting an authorized ARM64 device (Android API 26+).
If multiple devices are listed, add `-s SERIAL` to the adb commands.

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" devices -l
"$aseprite_adb" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" shell am force-stop org.aseprite.android
"$aseprite_adb" shell am start -W -n org.aseprite.android/android.app.NativeActivity
sleep 5
"$aseprite_adb" shell pidof org.aseprite.android
"$aseprite_adb" shell dumpsys activity activities > android/build/jalon7-activity.txt
"$aseprite_adb" logcat -d -v threadtime -s Aseprite:I AndroidRuntime:E libc:F \
  > android/build/jalon7-logcat.txt
cat android/build/jalon7-logcat.txt
```

Check the current launch's timestamps in logcat and that the resumed activity
in `jalon7-activity.txt` belongs to `org.aseprite.android`. A surviving process
alone does not establish that its activity stayed open.

Startup messages (also observed during the tablet validation; SDK was `34`):

```text
Native library loaded: libaseprite.so
ANativeActivity_onCreate entered
Platform=Android ABI=arm64-v8a backend=skia GPU=0 SDK=<device API>
Android activity started
Runtime resources extracted: 190 files
Entering Aseprite app_main
First raster frame presented
```

Normal activity destruction logs `Aseprite app_main returned: 0`, then
`Android activity destroyed; UI thread joined`. Force-stopping
or killing the process does not guarantee an `onDestroy` callback. Milestone 5
also logs native-window creation/destruction, raster dimensions, the first locked
buffer and `First raster frame presented` on successful copy/post.

Capture the UI after the launch animation has finished:

```bash
sleep 5
"$aseprite_adb" exec-out screencap -p > android/build/jalon7-screen-scaled.png
```

The image should show the Aseprite menu bar and Home tab at window scale 2.
A physical tap at `(18, 12)` opens File on the tested tablet. Runtime logs can
also be read with:

```bash
"$aseprite_adb" shell run-as org.aseprite.android cat files/user/Aseprite.log
```

### Build just the Android LAF target

After Gradle configuration, the native build directory in this session is
`android/app/.cxx/Debug/3x1d695f/arm64-v8a`. Its hash may differ elsewhere.

```bash
/home/golden/Android/Sdk/cmake/3.22.1/bin/cmake \
  --build android/app/.cxx/Debug/3x1d695f/arm64-v8a \
  --target laf-os --parallel 4
```

### Run the queue and raster contract tests on the host

This standalone project exercises queue waits/wakeups and integer raster copying
with RGBA/BGRA, scales 1/2/4 and independent row padding on Linux. It does not enable
tests in the cross-compiled application.

```bash
aseprite_sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
"$aseprite_sdk/cmake/3.22.1/bin/cmake" \
  -S laf/os/android/tests -B android/build/laf-android-tests -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$aseprite_sdk/cmake/3.22.1/bin/ninja" \
  -DCMAKE_BUILD_TYPE=Release
"$aseprite_sdk/cmake/3.22.1/bin/cmake" \
  --build android/build/laf-android-tests --parallel 4
"$aseprite_sdk/cmake/3.22.1/bin/ctest" \
  --test-dir android/build/laf-android-tests --output-on-failure
```

See the milestone reports for exact file lists, implementation limits, build
iterations and commit references. Skia, build outputs and logs remain untracked.
