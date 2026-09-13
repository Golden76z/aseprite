package org.aseprite.android;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.util.Log;
import java.lang.ref.WeakReference;

// Focus target only. NativeActivity's surface continues to draw all Aseprite UI.
public final class ImeBridge extends View {
    private static WeakReference<ImeBridge> current = new WeakReference<>(null);
    private long generation;
    private boolean editing;
    private Connection connection;
    private final InputMethodManager imm;
    private static native void receive(long generation, String text, int key);
    private static void trace(String s) { if (BuildConfig.DEBUG) Log.i("Aseprite", "IME " + s); }

    private ImeBridge(Activity activity) {
        super(activity);
        imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setWillNotDraw(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

    }

    public static void update(Activity activity, long generation, boolean editing) {
        activity.runOnUiThread(() -> {
            ImeBridge view = current.get();
            if (view == null || view.getContext() != activity) {
                if (!editing || activity.isDestroyed() || activity.isFinishing()) return;
                view = new ImeBridge(activity);
                activity.addContentView(view, new ViewGroup.LayoutParams(1, 1));
                current = new WeakReference<>(view);
            }
            if (view.editing && editing && view.generation == generation) {
                if (view.hasWindowFocus()) view.imm.showSoftInput(view, 0);
                return;
            }
            view.generation = generation;
            view.editing = editing;
            if (view.connection != null) view.connection.valid = false;
            trace(editing ? "text input activated; show request" : "text input deactivated; hide request");
            if (editing) {
                view.setFocusableInTouchMode(true);
                view.setFocusable(true);
                view.requestFocus();
                view.imm.restartInput(view);
                final ImeBridge target = view;
                view.post(() -> { if (target.editing) target.imm.showSoftInput(target, 0); });
            } else {
                final ImeBridge target = view;
                view.post(() -> {
                    if (target.editing) return; // Another Entry acquired focus.
                    target.imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
                    target.setFocusable(false);
                    target.clearFocus();
                    target.imm.restartInput(target);
                    ViewGroup content = activity.findViewById(android.R.id.content);
                    if (content != null && content.getChildCount() > 0)
                        content.getChildAt(0).requestFocus();
                });

            }
        });
    }

    @Override public boolean onCheckIsTextEditor() { return editing; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (!editing) return null;
        // LAF supplies activation/caret geometry, not surrounding text/selection.
        // Do not advertise an editable shadow copy or an invented cursor index.
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                          EditorInfo.IME_FLAG_NO_FULLSCREEN;
        info.initialSelStart = info.initialSelEnd = -1;
        connection = new Connection(this, generation);
        return connection;
    }
    @Override protected void onDetachedFromWindow() {
        editing = false;
        if (connection != null) connection.valid = false;
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        super.onDetachedFromWindow();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && editing) post(() -> { if (editing) imm.showSoftInput(this, 0); });
    }

    private final class Connection extends BaseInputConnection {
        private final long epoch;
        private boolean valid = true;
        // Only an uncommitted composition, never a second copy of the widget.
        private String composing = "";
        Connection(View view, long epoch) { super(view, true); this.epoch = epoch; }
        private boolean live() { return valid && editing && epoch == generation; }
        private void commit(String text) {
            if (!text.isEmpty()) {
                trace("committed UTF-16 length=" + text.length());
                receive(epoch, text, 0);
            }
        }
        @Override public boolean commitText(CharSequence text, int cursor) {
            if (!live()) return false;
            composing = "";
            commit(text.toString());
            return true;
        }
        @Override public boolean setComposingText(CharSequence text, int cursor) {
            if (!live()) return false;
            trace("composition " + (composing.isEmpty() ? "start" : "update") + " length=" + text.length());
            composing = text.toString();
            return true;
        }
        @Override public boolean finishComposingText() {
            if (!live()) return false;
            if (!composing.isEmpty()) {
                commit(composing); composing = ""; trace("composition end");
            }
            return true;
        }
        @Override public boolean setComposingRegion(int start, int end) { return false; }
        @Override public boolean setSelection(int start, int end) { return false; }
        @Override public CharSequence getTextBeforeCursor(int n, int flags) { return null; }
        @Override public CharSequence getTextAfterCursor(int n, int flags) { return null; }
        @Override public CharSequence getSelectedText(int flags) { return null; }
        @Override public SurroundingText getSurroundingText(int before, int after, int flags) { return null; }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest r, int flags) { return null; }
        @Override public boolean deleteSurroundingText(int before, int after) {
            if (!live() || before < 0 || after < 0 || before > 1 || after > 1) return false;
            // With no surrounding-text contract, only single delete operations
            // can be mapped faithfully to the widget's existing grapheme edits.
            if (!composing.isEmpty() && before == 1) {
                composing = composing.substring(0, composing.offsetByCodePoints(composing.length(), -1));
                return true;
            }
            if (before == 1) receive(epoch, "", KeyEvent.KEYCODE_DEL);
            if (after == 1) receive(epoch, "", KeyEvent.KEYCODE_FORWARD_DEL);
            return true;
        }
        @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            return deleteSurroundingText(before, after);
        }
        @Override public boolean performEditorAction(int action) {
            if (!live()) return false;
            finishComposingText();
            receive(epoch, "", KeyEvent.KEYCODE_ENTER);
            return true;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            if (!live()) return false;
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL ||
                key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    finishComposingText(); receive(epoch, "", key);
                }
                return true;
            }
            // Some IMEs emit a character KeyEvent instead of commitText. Consume
            // it here once; hardware events still use NativeActivity's queue.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getUnicodeChar() > 0) {
                finishComposingText(); commit(new String(Character.toChars(event.getUnicodeChar())));
                return true;
            }
            return event.getAction() == KeyEvent.ACTION_UP;
        }
        @Override public void closeConnection() {
            // Do not replay composition into a different field after focus loss.
            valid = false; composing = "";
        }
    }
}
