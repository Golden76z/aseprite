// NativeActivity owns lifecycle; Aseprite owns its UI thread and initialization.
#include "app/app_menus.h"
#include "app/android/saf_bridge.h"
#include "app/ui/file_selector.h"
#include "base/fs.h"
#include "base/platform.h"
#include "os/android/input.h"
#include "os/android/text_input.h"
#include "os/android/system.h"
#include "os/android/window.h"
#include "os/event.h"
#include "os/event_queue.h"
#include "os/window.h"
#include "ui/app_state.h"
#include "ui/manager.h"
#include "ui/message.h"

#include <android/asset_manager.h>
#include <android/configuration.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <android/native_window.h>
#include <android/window.h>

#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>

int app_main(int, char**);

namespace {
constexpr const char* kLogTag = "Aseprite";

std::string readAsset(AAssetManager* manager, const std::string& name)
{
  std::unique_ptr<AAsset, decltype(&AAsset_close)> asset(
    AAssetManager_open(manager, name.c_str(), AASSET_MODE_STREAMING),
    AAsset_close);
  if (!asset)
    throw std::runtime_error("Missing APK asset: " + name);
  std::string bytes(size_t(AAsset_getLength64(asset.get())), '\0');
  size_t offset = 0;
  while (offset < bytes.size()) {
    const int n = AAsset_read(asset.get(), bytes.data() + offset, bytes.size() - offset);
    if (n <= 0)
      throw std::runtime_error("Cannot read APK asset: " + name);
    offset += n;
  }
  return bytes;
}

void extractResources(ANativeActivity* activity)
{
  const std::string root = std::string(activity->internalDataPath) + "/runtime";
  const auto index = readAsset(activity->assetManager, "runtime-files.txt");
  size_t begin = 0;
  int count = 0;
  while (begin < index.size()) {
    const auto end = index.find('\n', begin);
    const auto name = index.substr(begin, end - begin);
    if (name.empty() || name.front() == '/' || name.find("..") != std::string::npos)
      throw std::runtime_error("Invalid runtime asset path");
    const auto destination = root + "/" + name;
    base::make_all_directories(base::get_file_path(destination));
    const auto data = readAsset(activity->assetManager, "runtime/" + name);
    std::ofstream output(destination, std::ios::binary | std::ios::trunc);
    output.write(data.data(), data.size());
    output.close();
    if (!output)
      throw std::runtime_error("Cannot extract " + name);
    ++count;
    if (end == std::string::npos)
      break;
    begin = end + 1;
  }
  // Existing user-folder override; assets remain separate from writable settings.
  const std::string user = std::string(activity->internalDataPath) + "/user";
  base::make_all_directories(user);
  // Ordinary document paths, separate from extracted assets and preferences.
  const std::string documents = std::string(activity->internalDataPath) + "/documents";
  base::make_all_directories(documents);
  if (setenv("LAF_ANDROID_DOCUMENTS_DIR", documents.c_str(), 1) != 0)
    throw std::runtime_error("Cannot configure Android documents directory");
#ifndef NDEBUG
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "Documents directory: %s", documents.c_str());
#endif
  // stderr otherwise goes to /dev/null in NativeActivity. Keep native assertions
  // even when Android removes the process before debuggerd writes a tombstone.
  if (!std::freopen((user + "/native-stderr.log").c_str(), "w", stderr))
    throw std::runtime_error("Cannot open native diagnostic log");
  setenv("ASEPRITE_USER_FOLDER", user.c_str(), 1);
  setenv("ASEPRITE_ANDROID_DATA_DIR", (root + "/data").c_str(), 1);
  setenv("ICU_DATA", root.c_str(), 1);
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "Runtime resources extracted: %d files", count);
}

struct Completion {
  std::mutex mutex;
  std::condition_variable changed;
  bool finished = false;
  size_t requested = 0;
  size_t completed = 0;
};

struct AndroidApp {
  explicit AndroidApp(JNIEnv* env) : input(env) {}
  os::InputAndroid input;
  std::thread thread;
  std::shared_ptr<Completion> completion = std::make_shared<Completion>();

  void start(ANativeActivity* activity)
  {
    if (thread.joinable())
      return;
    // UISystem binds main_gui_thread to its constructor thread. Manager::run
    // blocks on EventQueue; it must not occupy Android's callback/main looper.
    thread = std::thread([activity, done = completion] {
      try {
        extractResources(activity);
        char executable[] = "aseprite";
        char verbose[] = "--verbose";
        char* argv[] = { executable, verbose, nullptr };
        __android_log_write(ANDROID_LOG_INFO, kLogTag, "Entering Aseprite app_main");
        // Android may recreate this activity in the same process after Exit.
        // MainWindow::onResize skips layout while the previous run is kClosing.
        ui::set_app_state(ui::AppState::kNormal);
        app::FileSelector::resetNavigationHistory();
        const int result = app_main(2, argv);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Aseprite app_main returned: %d", result);
      }
      catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Aseprite startup failed: %s", error.what());
      }
      {
        std::lock_guard<std::mutex> lock(done->mutex);
        done->finished = true;
      }
      done->changed.notify_all();
      // File > Exit also ends NativeActivity. This NDK call posts to its main
      // looper; onDestroy still owns/join()s this thread.
      ANativeActivity_finish(activity);
    });
  }

  void redraw(bool wait)
  {
    auto done = completion;
    std::unique_lock<std::mutex> lock(done->mutex);
    if (!thread.joinable() || done->finished)
      return;
    const auto ticket = ++done->requested;
    os::Event event;
    event.setType(os::Event::Callback);
    event.setCallback([done, ticket] {
      const auto bounds = os::SystemAndroid::displayBounds();
      if (auto* window = os::WindowAndroid::instance()) {
        if (!bounds.isEmpty()) {
          window->setFrame(bounds);
          window->swapBuffers();
        }
      }
      {
        std::lock_guard<std::mutex> lock(done->mutex);
        done->completed = ticket;
      }
      done->changed.notify_all();
    });
    os::EventQueue::instance()->queueEvent(event);
    // Android requests synchronous redraw. Startup failure also releases this wait.
    if (wait)
      done->changed.wait(lock, [&] { return done->finished || done->completed >= ticket; });
  }

  ~AndroidApp()
  {
    input.detach();
    if (thread.joinable()) {
      os::Event event;
      event.setType(os::Event::Callback);
      event.setCallback([] {
        // Close nested menu boxes before the native close notification is
        // broadcast. Otherwise a detached child menu can receive it again.
        if (auto* menus = app::AppMenus::instance()) {
          if (auto* root = menus->getRootMenu()) {
            if (auto* box = dynamic_cast<ui::MenuBox*>(root->parent()))
              box->cancelMenuLoop();
          }
        }
        // cancelMenuLoop queues UI messages. Send CloseApp only after those messages
        // have executed, without dispatching widgets on Android's main thread.
        auto* manager = ui::Manager::getDefault();
        auto* afterMenus = new ui::CallbackMessage([] {
          os::Event close;
          close.setType(os::Event::CloseApp);
          os::queue_event(close);
        });
        afterMenus->setRecipient(manager);
        manager->enqueueMessage(afterMenus);
      });
      {
        std::lock_guard<std::mutex> lock(completion->mutex);
        if (!completion->finished)
          os::queue_event(event);
      }
      thread.join();
      // Input is detached and System teardown is complete. Do not leave late
      // callbacks/close events for a future activity in this process.
      os::EventQueue::instance()->clearEvents();
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
  if (os::SystemAndroid::setNativeWindow(window)) {
    auto* app = static_cast<AndroidApp*>(activity->instance);
    app->start(activity);
    app->redraw(false);
  }
}

void onNativeWindowResized(ANativeActivity* activity, ANativeWindow* window)
{
  logNativeWindow("resized", window);
  static_cast<AndroidApp*>(activity->instance)->redraw(false);
}

void onNativeWindowRedrawNeeded(ANativeActivity* activity, ANativeWindow*)
{
  static_cast<AndroidApp*>(activity->instance)->redraw(true);
}

void onNativeWindowDestroyed(ANativeActivity* activity, ANativeWindow* window)
{
  static_cast<AndroidApp*>(activity->instance)->input.cancel();
  logNativeWindow("destroyed", window);
  os::SystemAndroid::setNativeWindow(nullptr);
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Native window cleared and reference released");
}

void onInputQueueCreated(ANativeActivity* activity, AInputQueue* queue)
{
  static_cast<AndroidApp*>(activity->instance)->input.attach(queue);
}
void onInputQueueDestroyed(ANativeActivity* activity, AInputQueue*)
{
  static_cast<AndroidApp*>(activity->instance)->input.detach();
}
void updateDisplayDensity(ANativeActivity* activity)
{
  auto* configuration = AConfiguration_new();
  AConfiguration_fromAssetManager(configuration, activity->assetManager);
  const int density = AConfiguration_getDensity(configuration);
  AConfiguration_delete(configuration);
  os::SystemAndroid::setDisplayDensity(density);
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "Android display density=%d dpi", density);
}
void onConfigurationChanged(ANativeActivity* activity)
{
  updateDisplayDensity(activity);
  static_cast<AndroidApp*>(activity->instance)->redraw(false);
}

void onWindowFocusChanged(ANativeActivity* activity, int focused)
{
  if (!focused)
    static_cast<AndroidApp*>(activity->instance)->input.cancel();
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
  os::AndroidTextInput::detach(activity);
  app::android::detachSaf(activity);
  os::SystemAndroid::setNativeWindow(nullptr);
  delete static_cast<AndroidApp*>(activity->instance);
  activity->instance = nullptr;
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Android activity destroyed; UI thread joined");
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
  // The status bar previously covered the menu targets. Leave navigation and
  // vendor overlays to Android; no immersive-mode/lifecycle machinery here.
  ANativeActivity_setWindowFlags(activity, AWINDOW_FLAG_FULLSCREEN, 0);
  updateDisplayDensity(activity);
  activity->instance = new AndroidApp(activity->env);
  app::android::attachSaf(activity);
  os::AndroidTextInput::attach(activity);
  activity->callbacks->onConfigurationChanged = onConfigurationChanged;
  activity->callbacks->onInputQueueCreated = onInputQueueCreated;
  activity->callbacks->onInputQueueDestroyed = onInputQueueDestroyed;
  activity->callbacks->onWindowFocusChanged = onWindowFocusChanged;
  activity->callbacks->onStart = onStart;
  activity->callbacks->onDestroy = onDestroy;
  activity->callbacks->onNativeWindowCreated = onNativeWindowCreated;
  activity->callbacks->onNativeWindowResized = onNativeWindowResized;
  activity->callbacks->onNativeWindowRedrawNeeded = onNativeWindowRedrawNeeded;
  activity->callbacks->onNativeWindowDestroyed = onNativeWindowDestroyed;
}
