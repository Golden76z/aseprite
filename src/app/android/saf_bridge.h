#ifndef APP_ANDROID_SAF_BRIDGE_H_INCLUDED
#define APP_ANDROID_SAF_BRIDGE_H_INCLUDED
#pragma once
#include <android/native_activity.h>
#include <functional>
#include <string>
namespace app::android {
void attachSaf(ANativeActivity* activity);
void detachSaf(ANativeActivity* activity);
bool safAvailable();
// Result always dispatched through the LAF GUI callback queue. No URI crosses JNI.
bool requestSaf(bool importing, const std::string& path, const std::string& title,
                const std::string& mime,
                std::function<void(int, const std::string&, const std::string&)> result);
}
#endif
