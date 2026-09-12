// Build milestone only. The Android application/window backend is not implemented yet.
#include <android/log.h>
#include <android/native_activity.h>

extern "C" JNIEXPORT void ANativeActivity_onCreate(ANativeActivity* activity, void*, size_t)
{
  __android_log_write(ANDROID_LOG_ERROR, "Aseprite", "Android backend is not implemented yet");
  ANativeActivity_finish(activity);
}
