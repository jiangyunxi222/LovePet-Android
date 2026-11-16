package io.bandorilove.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.provider.Settings;
import android.view.*;
import android.widget.FrameLayout;

import org.json.JSONObject;

public final class PetService extends Service {
    private static final String STOP = "io.bandorilove.pet.STOP_PET";
    private static volatile PetService active;
    private PetWeb player;
    private WindowManager window;
    private WindowManager.LayoutParams frame;
    private Rect screen;
    private FrameLayout container;
    private SharedPreferences saved;

    static boolean showing() { return active != null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || STOP.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (container != null) return START_NOT_STICKY;
        startForeground(42, notification());
        try {
            showOverlay();
        } catch (RuntimeException error) {
            android.util.Log.e("BandoriLove", "Could not show Android pet", error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("pet", "BandoriLovePet", NotificationManager.IMPORTANCE_LOW));
        PendingIntent panel = PendingIntent.getActivity(this, 1, panelIntent(), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PetService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "pet").setContentTitle("BandoriLovePet")
                .setContentText("拖动角色移动，双指缩放")
                .setSmallIcon(R.drawable.pet_icon).setContentIntent(panel).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "退出桌宠", stop).build()).build();
    }

    private Intent panelIntent() {
        return new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private void showOverlay() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context overlay = createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        window = overlay.getSystemService(WindowManager.class);
        saved = getSharedPreferences("overlay", MODE_PRIVATE);
        int width = saved.getInt("width", dp(190));
        frame = new WindowManager.LayoutParams(width, Math.round(width * 1.45f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        frame.gravity = Gravity.TOP | Gravity.LEFT;
        screen = window.getCurrentWindowMetrics().getBounds();
        frame.x = saved.getInt("x", screen.width() - frame.width);
        frame.y = saved.getInt("y", screen.height() - frame.height - dp(80));
        place(frame.x, frame.y, width);
        player = new PetWeb(overlay, true, () -> {}, this::stopSelf, () -> startActivity(panelIntent()), this::stopSelf);
        container = new DragFrame(overlay);
        container.addView(player.view(), new FrameLayout.LayoutParams(-1, -1));
        window.addView(container, frame);
        active = this;
        player.load();
        MainActivity.petChanged();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void place(int x, int y, int width) {
        int height = Math.round(width * 1.45f);
        int margin = dp(42);
        x = Math.max(margin - width, Math.min(screen.width() - margin, x));
        y = Math.max(margin - height, Math.min(screen.height() - margin, y));
        if (frame.x == x && frame.y == y && frame.width == width && frame.height == height) return;
        frame.x = x;
        frame.y = y;
        frame.width = width;
        frame.height = height;
        if (container != null && container.isAttachedToWindow()) window.updateViewLayout(container, frame);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (frame != null) {
            screen = window.getCurrentWindowMetrics().getBounds();
            place(frame.x, frame.y, frame.width);
        }
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        if (container != null && container.isAttachedToWindow()) window.removeViewImmediate(container);
        if (player != null) {
            container.removeView(player.view());
            player.destroy();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        MainActivity.petChanged();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static void receive(JSONObject command) {
        PetService current = active;
        if (current != null) current.player.receive(command, true);
    }

    static void refresh() {
        PetService current = active;
        if (current != null) current.player.refresh();
    }

    private final class DragFrame extends FrameLayout {
        private final int slop;
        private final Runnable drawFrame = this::applyFrame;
        private float startX;
        private float startY;
        private int windowX;
        private int windowY;
        private boolean dragging;
        private boolean pinching;
        private boolean captionGesture;
        private int firstFinger;
        private int secondFinger;
        private float initialSpan;
        private int initialWidth;
        private float anchorX;
        private float anchorY;
        private boolean framePending;
        private int nextX, nextY, nextWidth;

        DragFrame(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private void queueFrame(int x, int y, int width) {
            nextX = x;
            nextY = y;
            nextWidth = width;
            if (!framePending) {
                framePending = true;
                postOnAnimation(drawFrame);
            }
        }

        private void applyFrame() {
            if (!framePending) return;
            framePending = false;
            place(nextX, nextY, nextWidth);
        }

        private void beginPinch(MotionEvent event) {
            removeCallbacks(drawFrame);
            applyFrame();
            firstFinger = event.getPointerId(0);
            secondFinger = event.getPointerId(event.getActionIndex());
            float x = event.getRawX(0), y = event.getRawY(0);
            float otherX = event.getRawX(event.getActionIndex()), otherY = event.getRawY(event.getActionIndex());
            initialSpan = Math.max(1, (float) Math.hypot(x - otherX, y - otherY));
            initialWidth = frame.width;
            anchorX = ((x + otherX) / 2 - frame.x) / frame.width;
            anchorY = ((y + otherY) / 2 - frame.y) / frame.height;
        }

        private void movePinch(MotionEvent event) {
            int first = event.findPointerIndex(firstFinger);
            int second = event.findPointerIndex(secondFinger);
            if (first < 0 || second < 0) return;
            float x = event.getRawX(first), y = event.getRawY(first);
            float otherX = event.getRawX(second), otherY = event.getRawY(second);
            float span = (float) Math.hypot(x - otherX, y - otherY);
            int width = Math.max(dp(96), Math.min(dp(380), Math.round(initialWidth * span / initialSpan)));
            int height = Math.round(width * 1.45f);
            queueFrame(Math.round((x + otherX) / 2 - anchorX * width),
                    Math.round((y + otherY) / 2 - anchorY * height), width);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                captionGesture = player.hitsCaption(event.getX(), event.getY());
                dragging = pinching = false;
                startX = event.getRawX();
                startY = event.getRawY();
                windowX = frame.x;
                windowY = frame.y;
            }
            if (captionGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) captionGesture = false;
                return super.dispatchTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && !pinching) {
                if (!dragging) cancelChild(event);
                pinching = true;
                beginPinch(event);
                player.pinching(true);
            }
            if (pinching && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_UP)) {
                movePinch(event);
            } else if (!pinching && action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    cancelChild(event);
                    dragging = true;
                }
                if (dragging) queueFrame(windowX + Math.round(dx), windowY + Math.round(dy), frame.width);
            }
            if (!dragging && !pinching) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(drawFrame);
                applyFrame();
                if (pinching) player.pinching(false);
                saved.edit().putInt("x", frame.x).putInt("y", frame.y).putInt("width", frame.width).apply();
            }
            return true;
        }

        private void cancelChild(MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
    }
}
