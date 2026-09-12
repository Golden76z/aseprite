// Device-only shell probe. This is not packaged in the application.
// Injects explicit pointer IDs/tool types through Android's real InputDispatcher.
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public final class InputProbe {
    private static Object manager;
    private static Method inject;
    private static long down;

    private static void send(int action, int tool, int source, int buttons,
                             int[] ids, float[] xs, float[] ys, float wheel) throws Exception {
        int[] tools = new int[ids.length];
        java.util.Arrays.fill(tools, tool);
        sendTools(action, tools, source, buttons, ids, xs, ys, wheel);
    }

    private static void sendTools(int action, int[] tools, int source, int buttons,
                                  int[] ids, float[] xs, float[] ys, float wheel) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; ++i) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = ids[i]; props[i].toolType = tools[i];
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i]; coords[i].y = ys[i];
            coords[i].pressure = 1; coords[i].size = 1;
            coords[i].setAxisValue(MotionEvent.AXIS_VSCROLL, wheel);
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                ids.length, props, coords, 0, buttons, 1, 1, 0, 0, source, 0);
        if (!(Boolean)inject.invoke(manager, event, 2))
            throw new IllegalStateException("Input injection rejected");
        event.recycle();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("InputProbe cancel|multi|active-up|eraser|mouse|left|wheel x y");
        Class<?> cls = Class.forName("android.hardware.input.InputManagerGlobal");
        manager = cls.getMethod("getInstance").invoke(null);
        inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        float x = Float.parseFloat(args[1]), y = Float.parseFloat(args[2]);
        int finger = MotionEvent.TOOL_TYPE_FINGER, touch = InputDevice.SOURCE_TOUCHSCREEN;
        int[] one = {7}; float[] oneX = {x}, oneY = {y};
        down = SystemClock.uptimeMillis();
        if (args[0].equals("pen-fingers")) {
            int pen = MotionEvent.TOOL_TYPE_STYLUS, source = InputDevice.SOURCE_TOUCHSCREEN;
            send(MotionEvent.ACTION_DOWN, pen, source, 0, one, oneX, oneY, 0);
            SystemClock.sleep(100);
            sendTools(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{pen,finger}, source, 0, new int[]{7,11}, new float[]{x,x+250}, new float[]{y,y+100}, 0);
            for (int i=1; i<=10; ++i) {
                sendTools(MotionEvent.ACTION_MOVE, new int[]{pen,finger}, source, 0,
                    new int[]{7,11}, new float[]{x+i*5,x+250-i*2}, new float[]{y+i*2,y+100}, 0);
                SystemClock.sleep(20);
            }
            sendTools(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{pen,finger}, source, 0, new int[]{7,11}, new float[]{x+50,x+230}, new float[]{y+20,y+100}, 0);
            send(MotionEvent.ACTION_UP, pen, source, 0, one, new float[]{x+50}, new float[]{y+20}, 0);
        } else if (args[0].startsWith("gesture-")) {
            String mode = args[0].substring(8);
            float radius = mode.equals("out") ? 340 : 140;
            send(MotionEvent.ACTION_DOWN, finger, touch, 0, one, new float[]{x-radius}, oneY, 0);
            SystemClock.sleep(80); // A real provisional stroke before second contact.
            send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                finger, touch, 0, new int[]{7,11}, new float[]{x-radius,x+radius}, new float[]{y,y}, 0);
            SystemClock.sleep(100);
            float cx=x, cy=y, r=radius;
            for (int i=1; i<=40; ++i) {
                float t=i/40f;
                if (mode.equals("in")) r=radius*(1+1.5f*t);
                if (mode.equals("out")) r=radius*(1-0.7f*t);
                if (mode.equals("pan") || mode.equals("cancel")) { cx=x+240*t; cy=y+130*t; }
                // Reordered indices throughout movement.
                send(MotionEvent.ACTION_MOVE, finger, touch, 0, new int[]{11,7},
                    new float[]{cx+r,cx-r}, new float[]{cy,cy}, 0);
                SystemClock.sleep(16);
            }
            if (mode.equals("cancel")) {
                send(MotionEvent.ACTION_CANCEL, finger, touch, 0, new int[]{11,7},
                    new float[]{cx+r,cx-r}, new float[]{cy,cy}, 0);
            } else {
                send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    finger, touch, 0, new int[]{11,7}, new float[]{cx+r,cx-r}, new float[]{cy,cy}, 0);
                // Remaining contact moves a long way: must never draw.
                send(MotionEvent.ACTION_MOVE, finger, touch, 0, new int[]{11}, new float[]{cx+300}, new float[]{cy+150}, 0);
                send(MotionEvent.ACTION_UP, finger, touch, 0, new int[]{11}, new float[]{cx+300}, new float[]{cy+150}, 0);
            }
        } else if (args[0].equals("cancel")) {
            // No artificial delay: exercise down/cancel while GUI may still be painting.
            send(MotionEvent.ACTION_DOWN, finger, touch, 0, one, oneX, oneY, 0);
            send(MotionEvent.ACTION_CANCEL, finger, touch, 0, one, oneX, oneY, 0);
        } else if (args[0].equals("multi") || args[0].equals("active-up")) {
            send(MotionEvent.ACTION_DOWN, finger, touch, 0, one, oneX, oneY, 0);
            SystemClock.sleep(100);
            send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    finger, touch, 0, new int[]{7,11}, new float[]{x,400}, new float[]{y,400}, 0);
            // Reorder the pointer indices. ID 7 remains the active finger at index 1.
            send(MotionEvent.ACTION_MOVE, finger, touch, 0,
                    new int[]{11,7}, new float[]{450,x+1}, new float[]{450,y+1}, 0);
            boolean activeUp = args[0].equals("active-up");
            send(MotionEvent.ACTION_POINTER_UP | ((activeUp ? 1 : 0) << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    finger, touch, 0, new int[]{11,7}, new float[]{450,x+1}, new float[]{450,y+1}, 0);
            if (activeUp) {
                send(MotionEvent.ACTION_MOVE, finger, touch, 0, new int[]{11}, new float[]{500}, new float[]{500}, 0);
                send(MotionEvent.ACTION_UP, finger, touch, 0, new int[]{11}, new float[]{500}, new float[]{500}, 0);
            } else {
                send(MotionEvent.ACTION_UP, finger, touch, 0, one, new float[]{x+1}, new float[]{y+1}, 0);
            }
        } else if (args[0].equals("eraser")) {
            send(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS, 0, one, oneX, oneY, 0);
            SystemClock.sleep(100);
            send(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS, 0, one, oneX, oneY, 0);
        } else if (args[0].equals("mouse") || args[0].equals("left")) {
            send(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE,
                    args[0].equals("left") ? MotionEvent.BUTTON_PRIMARY : MotionEvent.BUTTON_SECONDARY, one, oneX, oneY, 0);
            SystemClock.sleep(100);
            send(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE, 0, one, oneX, oneY, 0);
        } else if (args[0].equals("wheel")) {
            send(MotionEvent.ACTION_SCROLL, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE, 0, one, oneX, oneY, -1);
        } else {
            throw new IllegalArgumentException("Unknown probe");
        }
        System.out.println("Injected " + args[0] + " through Android InputDispatcher");
    }
}
