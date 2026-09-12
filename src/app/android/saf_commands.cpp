#include "app/android/saf_bridge.h"
#include "app/commands/command.h"
#include "app/commands/commands.h"
#include "app/commands/params.h"
#include "app/context.h"
#include "app/doc.h"
#include "app/file/file.h"
#include "app/job.h"
#include "app/ui/status_bar.h"
#include "app/ui_context.h"
#include "base/fs.h"
#include "app/site.h"
#include "doc/sprite.h"
#include "ui/alert.h"
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <unistd.h>

namespace app {
namespace {
void result(int status, const std::string& path, const std::string& message)
{
  // Called only on the GUI thread, after validating the NativeActivity generation.
  if (status == 2) {
    ui::Alert::show("Android storage<<" + message + "||OK");
    return;
  }
  if (status == 0 && !path.empty()) {
    Params params{{"filename", path}, {"sequence", "no"}};
    UIContext::instance()->executeCommand(Commands::instance()->byId(CommandId::OpenFile()), params);
  }
  StatusBar::instance()->setStatusText(6000, message);
}

class EncodeJob final : public Job, public IFileOpProgress {
  FileOp& operation;
  void onJob() override
  {
    try { operation.operate(this); }
    catch (const std::exception& e) { operation.setError("%s", e.what()); }
    operation.done();
  }
  void ackFileOpProgress(double value) override
  {
    jobProgress(value);
    if (isCanceled()) operation.stop();
  }
public:
  explicit EncodeJob(FileOp& operation) : Job("Preparing external copy", true), operation(operation) {}
  void run()
  {
    startJob();
    if (isCanceled()) operation.stop();
    waitJob();
  }
};

class SafCommand final : public Command {
  int mode; // 0 import, 1 ASE copy, 2 current-frame PNG copy
  bool onEnabled(Context* context) override
  {
    return android::safAvailable() && context->isUIAvailable() &&
           (mode == 0 || context->activeDocument());
  }
  void onExecute(Context* context) override
  {
    if (mode == 0) {
      if (!android::requestSaf(true, "", "", "*/*", result))
        result(2, "", "Android document picker is unavailable");
      return;
    }
    auto* doc = context->activeDocument();
    const auto extension = mode == 2 ? "png" : "aseprite";
    auto title = base::replace_extension(base::get_file_name(doc->filename()), extension);
    std::string directory = base::join_path(base::get_user_docs_folder(), ".saf-export-XXXXXX");
    if (!mkdtemp(directory.data()))
      throw std::runtime_error("Cannot create private export directory");
    const auto path = base::join_path(directory, title);
    bool handedOff = false;
    try {
      doc::FramesSequence frames;
      if (mode == 2) frames.insert(context->activeSite().frame());
      FileOpROI roi(doc, doc->sprite()->bounds(), "", "", frames, false);
      std::unique_ptr<FileOp> operation(FileOp::createSaveDocumentOperation(context, roi, path, "", false));
      if (operation) {
        if (!operation->hasError()) {
          EncodeJob job(*operation);
          job.run();
        }
        if (operation->hasError()) result(2, "", operation->error());
        else if (!operation->isStop()) {
          // Export a snapshot, never mark the editable document saved and never
          // replace its private filename or put staging paths into Recent Files.
          handedOff = android::requestSaf(false, path, title,
                        mode == 2 ? "image/png" : "application/octet-stream", result);
          if (!handedOff) result(2, "", "Android document picker is unavailable");
        }
      }
    } catch (...) {
      unlink(path.c_str()); rmdir(directory.c_str());
      throw;
    }
    if (!handedOff) { unlink(path.c_str()); rmdir(directory.c_str()); }
  }
public:
  SafCommand(const char* id, int mode) : Command(id), mode(mode) {}
};
}
Command* CommandFactory::createAndroidOpenExternalCommand()
{ return new SafCommand(CommandId::AndroidOpenExternal(), 0); }
Command* CommandFactory::createAndroidExportAsepriteCommand()
{ return new SafCommand(CommandId::AndroidExportAseprite(), 1); }
Command* CommandFactory::createAndroidExportPngCommand()
{ return new SafCommand(CommandId::AndroidExportPng(), 2); }
}
