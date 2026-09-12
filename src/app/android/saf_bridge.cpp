#include "app/android/saf_bridge.h"
#include "os/event.h"
#include "os/event_queue.h"
#include <android/log.h>
#include <mutex>

extern "C" JNIEXPORT void JNICALL Java_org_aseprite_android_SafBridge_complete(
  JNIEnv*, jclass, jlong, jint, jbyteArray, jbyteArray);

namespace app::android {
namespace {
std::mutex mutex;
JavaVM* vm = nullptr;
jobject activityRef = nullptr;
jclass bridge = nullptr;
jmethodID launch = nullptr;
uint64_t generation = 0, serial = 0, pending = 0;
std::function<void(int, const std::string&, const std::string&)> callback;

jbyteArray bytes(JNIEnv* env, const std::string& value)
{
  auto array = env->NewByteArray(value.size());
  if (array) env->SetByteArrayRegion(array, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
  return array;
}
std::string string(JNIEnv* env, jbyteArray value)
{
  if (!value) return {};
  std::string result(env->GetArrayLength(value), '\0');
  env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
  return result;
}
}

void attachSaf(ANativeActivity* activity)
{
  std::lock_guard<std::mutex> lock(mutex);
  JNIEnv* env = activity->env;
  vm = activity->vm;
  ++generation;
  // NativeActivity is a framework class; use the application's class loader.
  auto cls = env->GetObjectClass(activity->clazz);
  auto getLoader = env->GetMethodID(cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
  auto loader = env->CallObjectMethod(activity->clazz, getLoader);
  auto loaderCls = env->GetObjectClass(loader);
  auto load = env->GetMethodID(loaderCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  auto name = env->NewStringUTF("org.aseprite.android.SafBridge");
  auto local = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
  if (!env->ExceptionCheck() && local) {
    // NativeActivity loads the library through the framework native loader,
    // not this helper's System.loadLibrary namespace. Bind the callback explicitly.
    JNINativeMethod methods[] = {{const_cast<char*>("complete"), const_cast<char*>("(JI[B[B)V"),
      reinterpret_cast<void*>(Java_org_aseprite_android_SafBridge_complete)}};
    if (env->RegisterNatives(local, methods, 1) != JNI_OK) {
      env->ExceptionClear();
      __android_log_write(ANDROID_LOG_ERROR, "Aseprite", "SAF callback registration failed");
      env->DeleteLocalRef(local);
      local = nullptr;
    }
  }
  if (!env->ExceptionCheck() && local) {
    bridge = static_cast<jclass>(env->NewGlobalRef(local));
    activityRef = env->NewGlobalRef(activity->clazz);
    launch = env->GetStaticMethodID(bridge, "launch", "(Landroid/app/Activity;JZ[B[BLjava/lang/String;)V");
  }
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    __android_log_write(ANDROID_LOG_ERROR, "Aseprite", "SAF bridge initialization failed");
    launch = nullptr;
  }
  env->DeleteLocalRef(local);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(loaderCls);
  env->DeleteLocalRef(loader);
  env->DeleteLocalRef(cls);
}

void detachSaf(ANativeActivity* activity)
{
  std::lock_guard<std::mutex> lock(mutex);
  ++generation;
  pending = 0;
  callback = {};
  if (activityRef) activity->env->DeleteGlobalRef(activityRef);
  if (bridge) activity->env->DeleteGlobalRef(bridge);
  activityRef = nullptr;
  bridge = nullptr;
  launch = nullptr;
}

bool safAvailable()
{
  std::lock_guard<std::mutex> lock(mutex);
  return activityRef && launch && !pending;
}

bool requestSaf(bool importing, const std::string& path, const std::string& title,
                const std::string& mime,
                std::function<void(int, const std::string&, const std::string&)> result)
{
  std::lock_guard<std::mutex> lock(mutex);
  if (!activityRef || !launch || pending) return false;
  JNIEnv* env = nullptr;
  bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
  if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
  pending = ++serial;
  callback = std::move(result);
  auto p = bytes(env, path), t = bytes(env, title);
  auto m = env->NewStringUTF(mime.c_str());
  env->CallStaticVoidMethod(bridge, launch, activityRef, jlong(pending), jboolean(importing), p, t, m);
  bool ok = !env->ExceptionCheck();
  if (!ok) { env->ExceptionClear(); pending = 0; callback = {}; }
  env->DeleteLocalRef(p); env->DeleteLocalRef(t); env->DeleteLocalRef(m);
  if (attached) vm->DetachCurrentThread();
  return ok;
}

void complete(JNIEnv* env, jlong ticket, jint status, jbyteArray path, jbyteArray message)
{
  std::lock_guard<std::mutex> lock(mutex);
  if (!activityRef || pending != uint64_t(ticket)) return;
  const auto epoch = generation;
  auto result = std::move(callback);
  pending = 0;
  os::Event event;
  event.setType(os::Event::Callback);
  event.setCallback([epoch, result, status, path = string(env, path), message = string(env, message)] {
    {
      std::lock_guard<std::mutex> lock(mutex);
      if (epoch != generation || !activityRef) return;
    }
    if (result) result(status, path, message);
  });
  os::queue_event(event);
}
}
extern "C" JNIEXPORT void JNICALL Java_org_aseprite_android_SafBridge_complete(
  JNIEnv* env, jclass, jlong ticket, jint status, jbyteArray path, jbyteArray message)
{
  app::android::complete(env, ticket, status, path, message);
}
