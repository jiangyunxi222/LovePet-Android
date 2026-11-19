package io.bandorilove.lovepet;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;

final class WallpaperTilt implements SensorEventListener {
    static final String SETTING = "wallpaper-tilt";
    private static final float MAX_TILT = .26f;

    interface Shift { void move(float x, float y); }

    private final SensorManager sensors;
    private final Sensor orientation;
    private final Display display;
    private final Shift shift;
    private final float[] pose = new float[9];
    private final float[] anchor = new float[9];
    private boolean enabled;
    private int rotation = -1;
    private float x, y, lastX, lastY;

    WallpaperTilt(Context context, Shift shift) {
        sensors = context.getSystemService(SensorManager.class);
        Sensor game = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        orientation = game != null ? game : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        this.shift = shift;
    }

    boolean available() { return orientation != null; }

    boolean setEnabled(boolean wanted) {
        if (enabled == wanted) return enabled;
        if (wanted) {
            enabled = available() && sensors.registerListener(this, orientation, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensors.unregisterListener(this);
            enabled = false;
        }
        rotation = -1;
        x = y = lastX = lastY = 0;
        return enabled;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!enabled) return;
        SensorManager.getRotationMatrixFromVector(pose, event.values);
        int screenRotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        if (rotation != screenRotation) {
            rotation = screenRotation;
            System.arraycopy(pose, 0, anchor, 0, pose.length);
            x = y = lastX = lastY = 0;
            shift.move(0, 0);
            return;
        }
        // Project the current screen normal onto the original screen axes.
        float horizontal = anchor[0] * pose[2] + anchor[3] * pose[5] + anchor[6] * pose[8];
        float vertical = anchor[1] * pose[2] + anchor[4] * pose[5] + anchor[7] * pose[8];
        if (rotation == Surface.ROTATION_90) {
            float original = horizontal;
            horizontal = vertical;
            vertical = -original;
        } else if (rotation == Surface.ROTATION_180) {
            horizontal = -horizontal;
            vertical = -vertical;
        } else if (rotation == Surface.ROTATION_270) {
            float original = horizontal;
            horizontal = -vertical;
            vertical = original;
        }
        x += .22f * (normalized(-horizontal) - x);
        y += .22f * (normalized(vertical) - y);
        if (Math.abs(x - lastX) < .004f && Math.abs(y - lastY) < .004f) return;
        lastX = x;
        lastY = y;
        shift.move(x, y);
    }

    private static float normalized(float value) { return Math.max(-1f, Math.min(1f, value / MAX_TILT)); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
