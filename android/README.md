# Android ARM64 build milestone

This implements only the build infrastructure portion of
[ANDROID_ARM64_AUDIT.md](../ANDROID_ARM64_AUDIT.md). It does not implement an
Android window, event loop, input, filesystem, or presentation backend.

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
through `GEN_EXE`, configures Android CMake, then attempts to compile `aseprite`.
It does not build an APK. The current expected outcome is the backend compile
failure documented below.

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
with `SK_SUPPORT_GPU=0`. The existing process-local `clip_none.cpp` is selected
instead of XCB; no system clipboard integration was added. Native desktop
dialog sources and X11 OS sources are excluded only for Android. The common
Skia system/window sources remain in the build, exposing the missing backend
rather than substituting a fake window implementation.

## Verified build status — 12 September 2026

Validated against Aseprite `375989a61`, LAF `ec6f2a5`, and clip `964847c`, with
the working-tree changes in this milestone.

Successful checks:

- Gradle wrapper and Android plugin configuration.
- Native Linux `gen` compilation and execution against `data/pref.xml`.
- Android CMake configuration and generation for ARM64/API 26.
- CMake code model identifies `aseprite` as `SHARED_LIBRARY`, artifact
  `lib/libaseprite.so` relative to the native build directory.
- Android `generate_files` target: 67 generation steps completed using host
  `gen`, including preferences, widgets, strings, theme, and command IDs.
- Android `laf-base` target builds, along with dependency archives including
  clip, FreeType, archive, GIF, zlib, cmark, JSON and XML libraries.
- The NativeActivity entry stub compiles to an AArch64 object.
- Android manifest processing succeeds.

The full native build **fails during compilation**, before linking. No
`libaseprite.so` or APK has been produced. The last full native build log is
`android/build/android-build.log` in the validated workspace.

### Encountered errors fixed in this milestone

1. Host cfg could not find `SimpleIni.h`: supplied its existing include path to
   the isolated host-tools project.
2. Android configuration required XCB, then X11: selected the existing clip
   stub and separated Android from desktop platform source/library selection.
3. Existing ARM64 Skia/JPEG archives were not found outside the NDK sysroot:
   made explicit Android dependency paths bypass sysroot re-rooting. Also
   corrected Skia's `PATH` arguments to CMake's `PATHS` keyword.
4. Android inherited desktop GL/fontconfig settings: selected the raster-only
   Android Skia configuration matching the dependency build.
5. `laf/base/memory.cpp` called `aligned_alloc`, unavailable at API 26: added an
   Android-only `posix_memalign` implementation, keeping desktop code intact.
6. Raster-only compilation of `SkiaSurface::getBitmap()` lacked the
   `SkSurface_Raster` definition: moved its required include outside the GPU
   conditional. Desktop builds already included this header with GPU enabled.
7. WebP was enabled but its archive was not found because of sysroot re-rooting:
   corrected the Android archive search rather than disabling the feature.

### First remaining errors actually observed

Parallel compilation reports these missing platform types; their order can
vary between runs:

```text
laf/os/common/event_queue.cpp:22
  error: unknown type name 'EventQueueImpl'

laf/os/skia/skia_window.h:37
  error: expected class name
  class SkiaWindow : public SkiaWindowPlatform

laf/os/skia/skia_system.h:41
  error: unknown class name 'SkiaSystemBase'
```

These are the unimplemented Android event-queue, window, and system backend
selection points. Compilation stops here for this milestone. No fixes for
unreached downstream blockers are included, and no full desktop build or
Android runtime test is claimed.

## Changed files

Created:

```text
android/.gitignore
android/README.md
android/settings.gradle.kts
android/build.gradle.kts
android/app/build.gradle.kts
android/app/src/main/AndroidManifest.xml
android/gradlew
android/gradlew.bat
android/gradle/wrapper/gradle-wrapper.jar
android/gradle/wrapper/gradle-wrapper.properties
android/host-tools/CMakeLists.txt
src/main/android_main.cpp
```

Modified:

```text
CMakeLists.txt
cmake/FindJpegTurbo.cmake
src/CMakeLists.txt
laf/base/CMakeLists.txt
laf/base/platform.h
laf/base/memory.cpp
laf/cmake/FindSkia.cmake
laf/clip/CMakeLists.txt
laf/dlgs/CMakeLists.txt
laf/os/CMakeLists.txt
laf/os/skia/skia_surface.cpp
```

The LAF changes are inside a Git submodule; the clip change is inside its nested
submodule. No commits or submodule pointer updates were made. The existing
`ANDROID_ARM64_AUDIT.md` was left unchanged.
