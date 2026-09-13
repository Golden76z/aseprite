package org.aseprite.android;

import android.app.Activity;
import android.view.View;
import java.lang.ref.WeakReference;

/** Temporary profiling switch. This class is not packaged in Release builds. */
public final class GestureProfileExperiment {
    private static WeakReference<View> changedView = new WeakReference<>(null);
    private static int originalFlags;

    public static void apply(Activity activity, boolean immersive) {
        View decor = activity.getWindow().getDecorView();
        if (immersive) {
            if (changedView.get() != decor) {
                originalFlags = decor.getSystemUiVisibility();
                changedView = new WeakReference<>(decor);
            }
            decor.setSystemUiVisibility(originalFlags
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        } else if (changedView.get() == decor) {
            decor.setSystemUiVisibility(originalFlags);
            changedView.clear();
        }
    }
}
