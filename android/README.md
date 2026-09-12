# Android ARM64 build milestones

This contains the build infrastructure and minimal LAF platform skeleton from
[ANDROID_ARM64_AUDIT.md](../ANDROID_ARM64_AUDIT.md). Android now has an internal
event queue, a CommonSystem base and one logical Skia window. There is no native
window presentation, Android input translation or filesystem integration.

Detailed reports (French):
[jalon 1](../ANDROID_ARM64_JALON_1_COMPTE_RENDU.md),
[jalon 2](../ANDROID_ARM64_JALON_2_COMPTE_RENDU.md),
[jalon 3](../ANDROID_ARM64_JALON_3_COMPTE_RENDU.md).

The port is on the `android-port` branch of
[Golden76z/aseprite](https://github.com/Golden76z/aseprite/tree/android-port).
Clone with `--recurse-submodules` to obtain the matching LAF and clip forks.

The `aseprite` CMake target is a shared library whose output is
`libaseprite.so`. Its NativeActivity entry is a build-only stub: if linked and
launched, it logs that the backend is unavailable and finishes the activity.
It does not invoke the desktop application loop.

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

## Verified build status — milestone 3, 12 September 2026

- Gradle configures Android CMake successfully and builds the Linux host `gen`.
- `EventQueueImpl`, `SkiaWindowPlatform` and `SkiaSystemBase` resolve on Android.
- All four Android backend sources compile to ELF AArch64 objects, with
  `LAF_ANDROID`, no `LAF_LINUX`, API 26 and `SK_SUPPORT_GPU=0`.
- The native `laf-os` target succeeds and produces `lib/liblaf-os.a` in the
  native build directory.
- The host event-queue contract test passed in milestone 2; it was not rerun for
  these platform-selection changes.
- `getFullOSString()` now returns `Android` on Android, without Linux distribution
  fields or an invented OS version.
- The full `aseprite` target succeeds: exit code 0, `BUILD SUCCESSFUL in 1m 49s`.
- Linking produces the ELF64 AArch64 shared library at
  `android/app/.cxx/Debug/3x1d695f/arm64-v8a/lib/libaseprite.so`.
  Its exported symbols include `ANativeActivity_onCreate` and `app_main(int, char**)`.
- No compiler or linker errors remain in this build. Existing compiler warnings
  remain. No APK construction, device execution or functional editor is claimed.

The logical window stores geometry and requested state only. Its native handle
and screen are null. The common Skia raster surface has no presentation path.
Android advertises only window scale and color-space capabilities, and rejects
construction of a second live logical window.

The first rebuild after the user-agent fix exposed missing SkSL declarations in
`brush_preview.cpp`. The Android branch of `laf/cmake/FindSkia.cmake` now exposes
the SkSL support already present in the archive. No editor source or rendering
implementation was changed. See the milestone 3 report for the exact diagnostics
and the two build iterations.

### Build just the Android LAF target

After Gradle configuration, the native build directory in this session is
`android/app/.cxx/Debug/3x1d695f/arm64-v8a`. Its hash may differ elsewhere.

```bash
/home/golden/Android/Sdk/cmake/3.22.1/bin/cmake \
  --build android/app/.cxx/Debug/3x1d695f/arm64-v8a \
  --target laf-os --parallel 4
```

### Run the queue contract test on the host

This standalone project executes the portable Android queue implementation on
Linux; it does not enable tests in the cross-compiled application.

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
