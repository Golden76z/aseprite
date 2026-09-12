// Native startup milestone only: the editor and rendering backend are not started.
#include "base/platform.h"

#include <android/log.h>
#include <android/native_activity.h>

namespace {

constexpr const char* kLogTag = "Aseprite";

static_assert(base::Platform::os == base::Platform::OS::Android);
static_assert(base::Platform::arch == base::Platform::Arch::arm64);

__attribute__((constructor)) void onLibraryLoaded()
{
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Native library loaded: libaseprite.so");
}

void onStart(ANativeActivity*)
{
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity started");
}

void onDestroy(ANativeActivity*)
{
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
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity created; editor not started");

  // Return to Android's main looper. No worker, polling loop or finish request
  // is needed to keep this activity alive and receive lifecycle callbacks.
}
