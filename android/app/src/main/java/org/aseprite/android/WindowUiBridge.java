package org.aseprite.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/** Activity-lifetime window policy; independent of IME editing generations. */
public final class WindowUiBridge {
    private static WeakReference<WindowUiBridge> current = new WeakReference<>(null);
    private final Activity activity;
    private final View decor;
    private final long epoch;
    private boolean disposed;
    private boolean imeVisible;
    private int[] lastContent;
    private String lastDiagnostic;
    private int diagnostics;
    private ViewTreeObserver.OnGlobalLayoutListener legacyLayout;
    private static native void viewport(long epoch, int left, int top, int right, int bottom);

    private WindowUiBridge(Activity activity, long epoch) {
        this.activity = activity;
        this.epoch = epoch;
        decor = activity.getWindow().getDecorView();
        Window window = activity.getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(attrs);
        } else if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
        decor.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            // Layout is edge-to-edge. NativeActivity's SurfaceView and the 1px
            // IME target must not independently apply these insets a second time.
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        if (Build.VERSION.SDK_INT < 30) {
            legacyLayout = () -> { if (!disposed) applyInsets(decor.getRootWindowInsets()); };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(legacyLayout);
        }
        decor.post(() -> { if (!disposed) refresh(); });
    }

    public static void install(Activity activity, long epoch) {
        WindowUiBridge old = current.get();
        if (old != null) old.dispose();
        current = new WeakReference<>(new WindowUiBridge(activity, epoch));
    }
    public static void focus(Activity activity) {
        WindowUiBridge bridge = current.get();
        if (bridge != null && bridge.activity == activity && !bridge.disposed) bridge.refresh();
    }
    public static void detach(Activity activity) {
        WindowUiBridge bridge = current.get();
        if (bridge != null && bridge.activity == activity) { bridge.dispose(); current.clear(); }
    }
    private void dispose() {
        disposed = true;
        decor.setOnApplyWindowInsetsListener(null);
        if (legacyLayout != null && decor.getViewTreeObserver().isAlive())
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(legacyLayout);
    }
    private void refresh() {
        if (disposed || activity.isFinishing() || activity.isDestroyed()) return;
        hideBars();
        decor.requestApplyInsets();
    }
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
    private static int[] values(android.graphics.Insets i) { return new int[]{i.left,i.top,i.right,i.bottom}; }
    private static int[] union(int[] a, int[] b) {
        return new int[]{Math.max(a[0],b[0]),Math.max(a[1],b[1]),Math.max(a[2],b[2]),Math.max(a[3],b[3])};
    }
    private void applyInsets(WindowInsets insets) {
        if (disposed || insets == null) return;
        int[] bars, stable, gestures = new int[4], mandatory = new int[4], tappable = new int[4], cutout = new int[4], ime = new int[4];
        boolean keyboard;
        if (Build.VERSION.SDK_INT >= 30) {
            bars = values(insets.getInsets(WindowInsets.Type.systemBars()));
            stable = values(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()));
            gestures = values(insets.getInsets(WindowInsets.Type.systemGestures()));
            mandatory = values(insets.getInsets(WindowInsets.Type.mandatorySystemGestures()));
            tappable = values(insets.getInsets(WindowInsets.Type.tappableElement()));
            cutout = values(insets.getInsets(WindowInsets.Type.displayCutout()));
            ime = values(insets.getInsets(WindowInsets.Type.ime()));
            keyboard = insets.isVisible(WindowInsets.Type.ime());
        } else {
            bars = new int[]{insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()};
            stable = new int[]{insets.getStableInsetLeft(),insets.getStableInsetTop(),
                insets.getStableInsetRight(),insets.getStableInsetBottom()};
            Rect visible = new Rect(); decor.getWindowVisibleDisplayFrame(visible);
            int[] location = new int[2]; decor.getLocationOnScreen(location);
            int bottom = Math.max(bars[3], location[1] + decor.getHeight() - visible.bottom);
            keyboard = bottom > stable[3];
            if (keyboard) ime[3] = bottom;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout c = insets.getDisplayCutout();
                cutout = new int[]{c.getSafeInsetLeft(),c.getSafeInsetTop(),c.getSafeInsetRight(),c.getSafeInsetBottom()};
            }
            if (Build.VERSION.SDK_INT >= 29) {
                gestures = values(insets.getSystemGestureInsets());
                mandatory = values(insets.getMandatorySystemGestureInsets());
                tappable = values(insets.getTappableElementInsets());
            }
        }
        // Hidden stable bars are diagnostic, not permanent padding. Protect
        // actual occlusion. Gesture/tappable sources can retain the hidden bars
        // geometry (observed on API 34); they must not reserve invisible bars
        // forever. Visible bars already protect their tap targets. Edge swipes
        // keep Android priority, including when transient bars overlay the UI.
        int[] content = union(union(bars, cutout), ime);
        if (!Arrays.equals(content,lastContent)) {
            lastContent = content;
            viewport(epoch,content[0],content[1],content[2],content[3]);
        }
        if (BuildConfig.DEBUG && diagnostics < 96) {
            String state = "bars="+Arrays.toString(bars)+" stable="+Arrays.toString(stable)
                +" gestures="+Arrays.toString(gestures)+" mandatory="+Arrays.toString(mandatory)
                +" tappable="+Arrays.toString(tappable)+" cutout="+Arrays.toString(cutout)+" ime="+Arrays.toString(ime)
                +" content="+Arrays.toString(content)+" imeVisible="+keyboard;
            if (!state.equals(lastDiagnostic)) { Log.i("AsepriteInsets",state); lastDiagnostic=state; ++diagnostics; }
        }
        boolean closed = imeVisible && !keyboard;
        imeVisible = keyboard;
        // Let Android time out naturally revealed transient bars. Only lifecycle
        // focus and an actual IME-close transition reassert immersive policy.
        if (closed) decor.post(() -> { if (!disposed && decor.hasWindowFocus()) hideBars(); });
    }
}
