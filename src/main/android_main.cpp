// Raster presentation milestone only: app_main() and the editor are not started.
#include "base/platform.h"
#include "os/android/system.h"
#include "os/skia/skia_surface.h"
#include "os/system.h"
#include "os/window.h"
#include "os/window_spec.h"

#include <android/log.h>
#include <android/native_activity.h>
#include <android/native_window.h>

#include <exception>

namespace {

constexpr const char* kLogTag = "Aseprite";

static_assert(base::Platform::os == base::Platform::OS::Android);
static_assert(base::Platform::arch == base::Platform::Arch::arm64);

struct RasterPreview {
  os::SystemRef system = os::System::make();
  os::WindowRef window;
  int lastWidth = 0;
  int lastHeight = 0;

  os::SystemAndroid& platform() { return *static_cast<os::SystemAndroid*>(system.get()); }

  void drawFrame()
  {
    auto* native = platform().nativeWindow();
    if (!native)
      return;
    const int width = ANativeWindow_getWidth(native);
    const int height = ANativeWindow_getHeight(native);
    if (width <= 0 || height <= 0)
      return;

    try {
      if (!window)
        window = system->makeWindow(os::WindowSpec(width, height, 1));
      else
        window->setFrame(gfx::Rect(0, 0, width, height));

      // The surface is the same one supplied to LAF clients/future Aseprite UI.
      // Scale is explicitly 1: one Skia pixel per actual native-window pixel.
      auto* surface = static_cast<os::SkiaSurface*>(window->surface());
      auto& canvas = surface->canvas();
      canvas.clear(SkColorSetRGB(16, 24, 32));
      SkPaint paint;
      paint.setAntiAlias(false);
      paint.setColor(SK_ColorWHITE);
      canvas.drawRect(SkRect::MakeXYWH(width / 8, height / 8, 3 * width / 4, 3 * height / 4),
                      paint);
      paint.setColor(SkColorSetRGB(0, 192, 224));
      canvas.drawRect(SkRect::MakeXYWH(width / 4, height / 4, width / 4, height / 4), paint);
      paint.setColor(SkColorSetRGB(240, 64, 32));
      canvas.drawRect(SkRect::MakeXYWH(width / 2, height / 2, width / 4, height / 4), paint);
      paint.setColor(SkColorSetRGB(255, 208, 0));
      paint.setStrokeWidth(8);
      canvas.drawLine(width / 8, 7 * height / 8, 7 * width / 8, height / 8, paint);
      window->swapBuffers();
    }
    catch (const std::exception& error) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Raster preview failed: %s", error.what());
    }
  }
};

void logNativeWindow(const char* event, ANativeWindow* window)
{
  __android_log_print(ANDROID_LOG_INFO,
                      kLogTag,
                      "Native window %s %dx%d format=%d",
                      event,
                      ANativeWindow_getWidth(window),
                      ANativeWindow_getHeight(window),
                      ANativeWindow_getFormat(window));
}

void onNativeWindowCreated(ANativeActivity* activity, ANativeWindow* window)
{
  logNativeWindow("created", window);
  auto* preview = static_cast<RasterPreview*>(activity->instance);
  if (preview && preview->platform().setNativeWindow(window)) {
    preview->lastWidth = ANativeWindow_getWidth(window);
    preview->lastHeight = ANativeWindow_getHeight(window);
    preview->drawFrame();
  }
}

void onNativeWindowResized(ANativeActivity* activity, ANativeWindow* window)
{
  auto* preview = static_cast<RasterPreview*>(activity->instance);
  if (!preview || preview->platform().nativeWindow() != window)
    return;
  const int width = ANativeWindow_getWidth(window);
  const int height = ANativeWindow_getHeight(window);
  if (width != preview->lastWidth || height != preview->lastHeight) {
    logNativeWindow("resized", window);
    preview->lastWidth = width;
    preview->lastHeight = height;
  }
  preview->drawFrame();
}

void onNativeWindowRedrawNeeded(ANativeActivity* activity, ANativeWindow* window)
{
  auto* preview = static_cast<RasterPreview*>(activity->instance);
  if (preview && preview->platform().nativeWindow() == window)
    preview->drawFrame(); // Complete synchronous presentation before returning to Android.
}

void onNativeWindowDestroyed(ANativeActivity* activity, ANativeWindow* window)
{
  logNativeWindow("destroyed", window);
  auto* preview = static_cast<RasterPreview*>(activity->instance);
  if (preview && preview->platform().nativeWindow() == window) {
    preview->platform().setNativeWindow(nullptr);
    __android_log_write(ANDROID_LOG_INFO, kLogTag, "Native window cleared and reference released");
  }
}

__attribute__((constructor)) void onLibraryLoaded()
{
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Native library loaded: libaseprite.so");
}

void onStart(ANativeActivity*)
{
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity started");
}

void onDestroy(ANativeActivity* activity)
{
  delete static_cast<RasterPreview*>(activity->instance);
  activity->instance = nullptr;
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity destroyed");
}

} // namespace

extern "C" JNIEXPORT void ANativeActivity_onCreate(ANativeActivity* activity, void*, size_t)
{
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "ANativeActivity_onCreate entered");
  __android_log_print(ANDROID_LOG_INFO,
                      kLogTag,
                      "Platform=Android ABI=arm64-v8a backend=skia GPU=%d SDK=%d",
                      SK_SUPPORT_GPU,
                      activity->sdkVersion);

  activity->callbacks->onStart = onStart;
  activity->callbacks->onDestroy = onDestroy;
  activity->callbacks->onNativeWindowCreated = onNativeWindowCreated;
  activity->callbacks->onNativeWindowResized = onNativeWindowResized;
  activity->callbacks->onNativeWindowRedrawNeeded = onNativeWindowRedrawNeeded;
  activity->callbacks->onNativeWindowDestroyed = onNativeWindowDestroyed;
  try {
    activity->instance = new RasterPreview;
  }
  catch (const std::exception& error) {
    __android_log_print(ANDROID_LOG_ERROR,
                        kLogTag,
                        "Raster initialization failed: %s",
                        error.what());
  }
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity created; editor not started");

  // Return to Android's main looper. No worker, polling loop or finish request
  // is needed to keep this activity alive and receive lifecycle callbacks.
}
