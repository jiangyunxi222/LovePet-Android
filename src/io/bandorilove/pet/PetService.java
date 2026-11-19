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
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.*;
import android.widget.FrameLayout;

import org.json.JSONObject;

public final class PetService extends Service {
    static final String NOTIFICATION_CHANNEL = "pet";
    private static final String STOP = "io.bandorilove.pet.STOP_PET";
    private static final String HOME_ONLY = "homeOnly";
    private static final int OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    private static volatile PetService active;
    private PetWeb player;
    private WindowManager window;
    private WindowManager.LayoutParams frame;
    private Rect screen;
    private FrameLayout container;
    private final Rect crop = new Rect();
    private final RectF cropArea = new RectF();
    private Region touchRegion = new Region();
    private int canvasX, canvasY, canvasWidth, canvasHeight;
    private int displayWidth, displayHeight;
    private SharedPreferences saved;
    private HomeVisibility homeVisibility;
    private boolean wantedVisible;
    private boolean settingsOpen;
    private volatile boolean visible;

    static boolean showing() { return active != null; }
    static boolean visible() {
        PetService current = active;
        return current != null && current.visible;
    }
    static boolean homeOnly(Context context) {
        String shared = DesktopRuntime.read(context, HOME_ONLY);
        if (shared != null) return Boolean.parseBoolean(shared);
        return context.getSharedPreferences("overlay", MODE_PRIVATE).getBoolean(HOME_ONLY, false);
    }
    static void setHomeOnly(Context context, boolean homeOnly) {
        DesktopRuntime.write(context, HOME_ONLY, Boolean.toString(homeOnly));
        DesktopRuntime.flag("placement", homeOnly);
        MainActivity.petChanged();
    }
    static void applyHomeOnly(boolean homeOnly) {
        PetService current = active;
        if (current != null) current.configureVisibility(homeOnly);
    }
    static void suspendForSettings(boolean open) {
        PetService current = active;
        if (current != null) {
            current.settingsOpen = open;
            current.updateVisibility();
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        DesktopRuntime.initialize(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Settings.canDrawOverlays(this)
                || new ModelStore(this).active() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (container != null) return START_STICKY;
        startForeground(42, notification());
        try {
            showOverlay();
        } catch (RuntimeException error) {
            android.util.Log.e("BandoriLove", "Could not show Android pet", error);
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL, "BandoriLovePet", NotificationManager.IMPORTANCE_LOW));
        PendingIntent panel = PendingIntent.getActivity(this, 1, panelIntent(), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PetService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, NOTIFICATION_CHANNEL).setContentTitle("BandoriLovePet")
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
        readScreenBounds();
        frame = overlayFrame();
        int width = limitWidth(saved.getInt("width", dp(190)));
        player = new PetWeb(overlay, true, () -> {}, this::stopSelf, () -> startActivity(panelIntent()),
                ignored -> {}, ignored -> {}, ignored -> {}, this::stopSelf);
        place(saved.getInt("x", screen.right - width),
                saved.getInt("y", screen.bottom - heightForWidth(width) - dp(60)), width);
        container = new DragFrame(overlay);
        container.addView(player.view(), new FrameLayout.LayoutParams(canvasWidth, canvasHeight));
        container.setVisibility(View.INVISIBLE);
        container.addOnLayoutChangeListener((v, l, t, r, b, previousL, previousT, previousR, previousB) -> pushTouchRegion());
        player.onTouchShapeChanged(this::updateWindowCrop);
        window.addView(container, frame);
        updateWindowCrop();
        active = this;
        configureVisibility(homeOnly(this));
        player.load();
        DesktopRuntime.reportPet();
    }

    private WindowManager.LayoutParams overlayFrame() {
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OVERLAY_FLAGS, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.setFitInsetsTypes(0);
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return layout;
    }

    private void readScreenBounds() {
        WindowMetrics metrics = window.getCurrentWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect bounds = metrics.getBounds();
        displayWidth = bounds.width();
        displayHeight = bounds.height();
        screen = new Rect(insets.left, insets.top, displayWidth - insets.right, displayHeight - insets.bottom);
    }

    private int limitWidth(int width) {
        return Math.min(displayWidth, Math.max(dp(96), width));
    }

    private int heightForWidth(int width) {
        return Math.round(width * (displayHeight / (float) displayWidth));
    }

    private void updateWindowCrop() {
        if (container == null || !container.isAttachedToWindow()) return;
        PetTouchShape shape = player.touchShape();
        touchRegion = shape.modelRegion(canvasWidth, canvasHeight, dp(2));
        Rect model = touchRegion.getBounds();
        if (!model.isEmpty()) {
            RectF visible = new RectF(model.left / (float) canvasWidth, model.top / (float) canvasHeight,
                    model.right / (float) canvasWidth, model.bottom / (float) canvasHeight);
            // Keep the opaque window steady as the silhouette changes between frames.
            // Expand only when a pose exceeds the space already reserved.
            if (!cropArea.contains(visible)) {
                visible.inset(-.045f, -.045f);
                visible.union(.15f, .06f, .85f, .94f);
                visible.intersect(0, 0, 1, 1);
                cropArea.union(visible);
                player.desktopCrop(cropArea);
            }
        }
        shape.addControls(touchRegion, canvasWidth, canvasHeight);
        if (cropArea.isEmpty()) crop.set(0, 0, 1, 1);
        else crop.set(Math.round(cropArea.left * canvasWidth), Math.round(cropArea.top * canvasHeight),
                Math.round(cropArea.right * canvasWidth), Math.round(cropArea.bottom * canvasHeight));
        touchRegion.op(crop, Region.Op.INTERSECT);
        player.view().setTranslationX(-crop.left);
        player.view().setTranslationY(-crop.top);
        int x = canvasX + crop.left, y = canvasY + crop.top;
        if (frame.x != x || frame.y != y || frame.width != crop.width() || frame.height != crop.height()) {
            frame.x = x;
            frame.y = y;
            frame.width = crop.width();
            frame.height = crop.height();
            window.updateViewLayout(container, frame);
        }
        pushTouchRegion();
    }

    private void pushTouchRegion() {
        if (Build.VERSION.SDK_INT < 33) return;
        AttachedSurfaceControl surface = container.getRootSurfaceControl();
        if (surface != null) {
            Region local = new Region(touchRegion);
            local.translate(-crop.left, -crop.top);
            surface.setTouchableRegion(local);
        }
    }

    private void configureVisibility(boolean homeOnly) {
        if (homeVisibility != null) {
            homeVisibility.close();
            homeVisibility = null;
        }
        setVisible(!homeOnly);
        if (homeOnly) homeVisibility = new HomeVisibility(this, this::setVisible);
    }

    private void setVisible(boolean show) {
        wantedVisible = show;
        updateVisibility();
    }

    private void updateVisibility() {
        boolean show = wantedVisible && !settingsOpen;
        if (visible == show) return;
        visible = show;
        container.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        updateWindowCrop();
        player.status();
        DesktopRuntime.reportPet();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void place(int x, int y, int width) {
        width = limitWidth(width);
        int height = heightForWidth(width);
        PointF grip = player.touchShape().grip();
        int anchorX = Math.round(width * (grip == null ? .5f : grip.x));
        int anchorY = Math.round(height * (grip == null ? .54f : grip.y));
        int marginX = Math.min(dp(24), screen.width() / 2);
        int marginY = Math.min(dp(24), screen.height() / 2);
        x = Math.max(screen.left + marginX - anchorX, Math.min(screen.right - marginX - anchorX, x));
        y = Math.max(screen.top + marginY - anchorY, Math.min(screen.bottom - marginY - anchorY, y));
        boolean resized = canvasWidth != width || canvasHeight != height;
        if (!resized && canvasX == x && canvasY == y) return;
        canvasX = x;
        canvasY = y;
        canvasWidth = width;
        canvasHeight = height;
        if (container != null) {
            if (resized) {
                android.view.ViewGroup.LayoutParams size = player.view().getLayoutParams();
                size.width = width;
                size.height = height;
                player.view().setLayoutParams(size);
            }
            updateWindowCrop();
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (frame != null) {
            readScreenBounds();
            place(canvasX, canvasY, canvasWidth);
        }
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        if (homeVisibility != null) homeVisibility.close();
        if (container != null && container.isAttachedToWindow()) window.removeViewImmediate(container);
        if (player != null) {
            if (container != null) container.removeView(player.view());
            player.destroy();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        DesktopRuntime.reportPet();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static void receive(JSONObject command, boolean sound) {
        PetService current = active;
        if (current != null) current.player.receive(command, sound && current.visible);
    }

    static void refresh() {
        PetService current = active;
        if (current != null) current.player.refresh();
    }

    static void updateCaptions(boolean enabled) {
        PetService current = active;
        if (current != null && current.player != null) current.player.updateCaptionCards(enabled);
    }

    static void updateTheme() {
        PetService current = active;
        if (current != null && current.player != null) current.player.updateTheme();
    }

    static void updateTouchMenu(boolean enabled, boolean thirdParty) {
        PetService current = active;
        if (current != null && current.player != null) current.player.updateTouchMenu(enabled, thirdParty);
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
        private boolean controlsGesture;
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
            initialWidth = canvasWidth;
            anchorX = ((x + otherX) / 2 - canvasX) / canvasWidth;
            anchorY = ((y + otherY) / 2 - canvasY) / canvasHeight;
        }

        private void movePinch(MotionEvent event) {
            int first = event.findPointerIndex(firstFinger);
            int second = event.findPointerIndex(secondFinger);
            if (first < 0 || second < 0) return;
            float x = event.getRawX(first), y = event.getRawY(first);
            float otherX = event.getRawX(second), otherY = event.getRawY(second);
            float span = (float) Math.hypot(x - otherX, y - otherY);
            int width = limitWidth(Math.round(initialWidth * span / initialSpan));
            int height = heightForWidth(width);
            queueFrame(Math.round((x + otherX) / 2 - anchorX * width),
                    Math.round((y + otherY) / 2 - anchorY * height), width);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                controlsGesture = player.hitsControls(event.getX() + crop.left, event.getY() + crop.top);
                dragging = pinching = false;
                startX = event.getRawX();
                startY = event.getRawY();
                windowX = canvasX;
                windowY = canvasY;
            }
            if (controlsGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) controlsGesture = false;
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
                if (dragging) queueFrame(windowX + Math.round(dx), windowY + Math.round(dy), canvasWidth);
            }
            if (!dragging && !pinching) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(drawFrame);
                applyFrame();
                if (pinching) player.pinching(false);
                saved.edit().putInt("x", canvasX).putInt("y", canvasY).putInt("width", canvasWidth).apply();
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
