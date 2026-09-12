package org.aseprite.android;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// No Activity subclass: this headless framework fragment receives results on
// behalf of the existing NativeActivity. URI handling stays entirely in Java.
@SuppressWarnings("deprecation")
public final class SafBridge extends Fragment {
    private static final int REQUEST = 41;
    private long ticket;
    private boolean importing, copying;
    private volatile boolean destroyed;
    private String localPath;
    private final CancellationSignal cancellation = new CancellationSignal();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static native void complete(long ticket, int status, byte[] path, byte[] message);
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void trace(String s) { if (BuildConfig.DEBUG) Log.i("Aseprite", "SAF " + s); }

    // Called by the GUI thread through JNI, then marshalled onto Android's looper.
    public static void launch(Activity activity, long ticket, boolean importing,
                              byte[] path, byte[] title, String mime) {
        final String local = new String(path, StandardCharsets.UTF_8);
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                cleanExport(importing, local);
                complete(ticket, 1, utf8(""), utf8("External operation cancelled"));
                return;
            }
            SafBridge bridge = new SafBridge();
            bridge.ticket = ticket;
            bridge.importing = importing;
            bridge.localPath = local;
            try {
                activity.getFragmentManager().beginTransaction().add(bridge, "aseprite-saf").commitNow();
                Intent intent = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (importing) {
                    // Providers frequently classify ASE files inconsistently. The
                    // existing native decoder validates the selected bytes.
                    intent.setType("*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                } else {
                    intent.setType(mime);
                    intent.putExtra(Intent.EXTRA_TITLE, new String(title, StandardCharsets.UTF_8));
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
                trace("picker launched " + intent.getAction());
                bridge.startActivityForResult(intent, REQUEST);
            } catch (RuntimeException e) {
                bridge.finish(2, "", "Cannot launch Android document picker (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // A restored fragment cannot safely restore a native command/document.
        // Its ticket remains zero; a late picker result is ignored, not replayed.
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST || ticket == 0 || destroyed) return;
        if (result == Activity.RESULT_CANCELED) {
            finish(1, "", "External operation cancelled");
            return;
        }
        Uri uri = data != null ? data.getData() : null;
        if (result != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            finish(2, "", "Android returned no usable content URI");
            return;
        }
        trace("result scheme=" + uri.getScheme() + " authority=" + uri.getAuthority());
        final ContentResolver resolver = getActivity().getApplicationContext().getContentResolver();
        final int flags = data.getFlags();
        final File documents = new File(getActivity().getFilesDir(), "documents");
        copying = true;
        worker.execute(() -> {
            File directory = null, target = null;
            try {
                if (importing) {
                    int grants = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && grants != 0) {
                        try {
                            resolver.takePersistableUriPermission(uri, grants);
                            trace("persisted permissions flags=" + grants);
                        } catch (SecurityException | UnsupportedOperationException e) {
                            trace("persisted permissions unavailable " + e.getClass().getSimpleName());
                        }
                    } else trace("persisted permissions not offered");
                }
                String name = displayName(resolver, uri);
                trace("display name=" + name.replaceAll("[\\p{Cntrl}]", "_"));
                long bytes;
                if (importing) {
                    // A unique directory preserves the name without overwriting
                    // an existing document with the same provider display name.
                    directory = new File(documents, "import-" + UUID.randomUUID());
                    if (!directory.mkdirs()) throw new IOException("Cannot create working directory");
                    String safe = name.replace('/', '_').replace('\\', '_').replace('\0', '_');
                    if (safe.isEmpty() || safe.equals(".") || safe.equals(".."))
                        safe = "image/png".equals(resolver.getType(uri)) ? "document.png" : "document.aseprite";
                    target = new File(directory, safe);
                    File partial = new File(directory, ".copy-partial");
                    try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r", cancellation)) {
                        if (fd == null) throw new IOException("No input descriptor");
                        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                             FileOutputStream out = new FileOutputStream(partial)) {
                            bytes = copy(in, out);
                            out.getFD().sync();
                        }
                    }
                    if (!partial.renameTo(target)) throw new IOException("Cannot commit working copy");
                    trace("import bytes=" + bytes + " private=" + target.getAbsolutePath());
                    finish(0, target.getAbsolutePath(), "Opened private copy: " + name);
                } else {
                    try (FileInputStream in = new FileInputStream(localPath);
                         ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "wt", cancellation)) {
                        if (fd == null) throw new IOException("No output descriptor");
                        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                            bytes = copy(in, out);
                            out.flush();
                        }
                    }
                    trace("export bytes=" + bytes + " private=" + localPath);
                    finish(0, "", "Exported external copy: " + name);
                }
            } catch (Exception e) {
                // Never log provider exception text: it may contain URI queries.
                if (directory != null) {
                    if (target != null) target.delete();
                    new File(directory, ".copy-partial").delete();
                    directory.delete();
                }
                finish(2, "", "Android file copy failed (" + e.getClass().getSimpleName() +
                       "). The private document is unchanged; an external file may be incomplete.");
            } finally {
                cleanExport(importing, localPath);
                worker.shutdown();
            }
        });
    }

    private long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        long bytes = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            out.write(buffer, 0, n);
            bytes += n;
        }
        return bytes;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException e) { trace("display name unavailable " + e.getClass().getSimpleName()); }
        return "";
    }

    private void finish(int status, String path, String message) {
        trace("completion status=" + status);
        if (!destroyed && ticket != 0) complete(ticket, status, utf8(path), utf8(message));
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(() -> {
            ticket = 0;
            if (!copying) cleanExport(importing, localPath);
            if (isAdded()) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        });
    }

    private static void cleanExport(boolean importing, String path) {
        if (!importing && path != null && !path.isEmpty()) {
            File file = new File(path);
            file.delete();
            file.getParentFile().delete();
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        cancellation.cancel();
        worker.shutdownNow();
        if (!copying) cleanExport(importing, localPath);
        if (ticket != 0) complete(ticket, 1, utf8(""), utf8("External operation cancelled by activity destruction"));
        ticket = 0;
        super.onDestroy();
    }
}
