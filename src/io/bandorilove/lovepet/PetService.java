package io.bandorilove.lovepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.FrameLayout;

import org.json.JSONObject;

public final class PetService extends Service {
    private static final String NOTIFICATION_CHANNEL = "pet-runtime";
    private static final String STOP = "io.bandorilove.lovepet.STOP_PET";
    private static final String HOME_ONLY = "homeOnly";
    private static final int OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    private static volatile PetService active;
    private PetWeb player;
    private WindowManager window;
    private DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshDisplay = this::refreshDisplayBounds;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) scheduleDisplayRefresh();
        }
    };
    private WindowManager.LayoutParams frame;
    private Rect screen;
    private DragFrame container;
    private final Rect crop = new Rect();
    private final Rect windowBounds = new Rect();
    private final Rect previousContent = new Rect();
    private final Rect controls = new Rect();
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
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        return new Notification.Builder(this, NOTIFICATION_CHANNEL).setContentTitle("BandoriLovePet")
                .setSmallIcon(R.drawable.pet_icon).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET).build();
    }

    private Intent panelIntent() {
        return new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private void showOverlay() {
        displays = getSystemService(DisplayManager.class);
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        Context overlay = createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        window = overlay.getSystemService(WindowManager.class);
        saved = getSharedPreferences("overlay", MODE_PRIVATE);
        readScreenBounds();
        frame = overlayFrame();
        int width = saved.getInt("width", dp(190));
        if (!saved.getBoolean("fixedAspect", false) && width > shortSide()) {
            // An old landscape placement used display-width units.
            float ratio = shortSide() / (float) longSide();
            width = Math.round(width * ratio * ratio);
        }
        width = limitWidth(width);
        player = new PetWeb(overlay, true, () -> {}, this::stopSelf, () -> startActivity(panelIntent()),
                ignored -> {}, ignored -> {}, ignored -> {}, this::stopSelf);
        place(saved.getInt("x", screen.right - width),
                saved.getInt("y", screen.bottom - heightForWidth(width) - dp(60)), width);
        container = new DragFrame(overlay);
        // Keep the WebView's pixel density and the controls fixed as the model scales.
        container.addView(player.view(), new FrameLayout.LayoutParams(shortSide(), longSide()));
        container.setVisibility(View.INVISIBLE);
        container.addOnLayoutChangeListener((v, l, t, r, b, previousL, previousT, previousR, previousB) -> pushTouchRegion());
        player.onTouchShapeChanged(this::updateWindowCrop);
        window.addView(container, frame);
        displays.registerDisplayListener(displayListener, main);
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
        if (Build.VERSION.SDK_INT >= 34) layout.setCanPlayMoveAnimation(false);
        return layout;
    }

    private void readScreenBounds() {
        // A cropped floating window can still report metrics from its previous orientation.
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        WindowMetrics metrics = createDisplayContext(display).getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect bounds = metrics.getBounds();
        displayWidth = bounds.width();
        displayHeight = bounds.height();
        screen = new Rect(insets.left, insets.top, displayWidth - insets.right, displayHeight - insets.bottom);
    }

    private int limitWidth(int width) {
        return Math.min(shortSide(), Math.max(dp(96), width));
    }

    private int heightForWidth(int width) {
        // Rotating the display moves the model without changing its viewport aspect or size.
        return Math.round(width * (longSide() / (float) shortSide()));
    }

    private int shortSide() { return Math.min(displayWidth, displayHeight); }
    private int longSide() { return Math.max(displayWidth, displayHeight); }

    private void updateWindowCrop() {
        if (container == null || !container.isAttachedToWindow()) return;
        if (container.moving()) return;
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
        Region controlRegion = new Region();
        shape.addControls(controlRegion, player.view().getWidth(), player.view().getHeight());
        controls.set(controlRegion.getBounds());
        touchRegion.translate(canvasX, canvasY);
        controlRegion.translate(canvasX, canvasY);
        touchRegion.op(controlRegion, Region.Op.UNION);
        updateWindowFrame();
        pushTouchRegion();
    }

    private void updateWindowFrame() {
        if (cropArea.isEmpty()) crop.set(canvasX, canvasY, canvasX + 1, canvasY + 1);
        else crop.set(canvasX + Math.round(cropArea.left * canvasWidth),
                canvasY + Math.round(cropArea.top * canvasHeight),
                canvasX + Math.round(cropArea.right * canvasWidth),
                canvasY + Math.round(cropArea.bottom * canvasHeight));
        if (!controls.isEmpty()) crop.union(controls.left + canvasX, controls.top + canvasY,
                controls.right + canvasX, controls.bottom + canvasY);
        // Keep the clipped window on the physical display when the model extends past an edge.
        if (!crop.intersect(0, 0, displayWidth, displayHeight)) crop.set(0, 0, 1, 1);
        boolean retainWindow = Build.VERSION.SDK_INT >= 34 && container.pinching;
        if (retainWindow ? !windowBounds.contains(crop) : !windowBounds.equals(crop)) {
            windowBounds.set(crop);
            if (retainWindow) {
                // Keep the preceding frame visible while WebView presents the updated layer.
                windowBounds.union(previousContent);
                int margin = dp(40);
                windowBounds.inset(-margin, -margin);
                windowBounds.intersect(0, 0, displayWidth, displayHeight);
            }
            frame.x = windowBounds.left;
            frame.y = windowBounds.top;
            frame.width = windowBounds.width();
            frame.height = windowBounds.height();
            window.updateViewLayout(container, frame);
        }
        player.view().setTranslationX(canvasX - frame.x);
        player.view().setTranslationY(canvasY - frame.y);
        previousContent.set(crop);
    }

    private void pushTouchRegion() {
        if (Build.VERSION.SDK_INT < 33 || container.moving()) return;
        AttachedSurfaceControl surface = container.getRootSurfaceControl();
        if (surface != null) {
            Region local = new Region(touchRegion);
            local.op(windowBounds, Region.Op.INTERSECT);
            local.translate(-frame.x, -frame.y);
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
        if (!show && container.moving()) container.cancelGesture();
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
        boolean landscape = displayWidth > displayHeight;
        int left = landscape ? 0 : screen.left;
        int right = landscape ? displayWidth : screen.right;
        int marginX = Math.min(dp(24), (right - left) / 2);
        int marginY = Math.min(dp(24), screen.height() / 2);
        x = Math.max(left + marginX - anchorX, Math.min(right - marginX - anchorX, x));
        y = Math.max(screen.top + marginY - anchorY, Math.min(screen.bottom - marginY - anchorY, y));
        boolean resized = canvasWidth != width || canvasHeight != height;
        if (!resized && canvasX == x && canvasY == y) return;
        canvasX = x;
        canvasY = y;
        canvasWidth = width;
        canvasHeight = height;
        if (resized) player.desktopScale(canvasWidth / (float) shortSide());
        if (container != null) {
            if (container.moving()) updateWindowFrame();
            else updateWindowCrop();
        }
    }

    private void scheduleDisplayRefresh() {
        if (container == null) return;
        main.removeCallbacks(refreshDisplay);
        main.post(refreshDisplay);
        // Some devices publish the new metrics after the configuration callback.
        main.postDelayed(refreshDisplay, 120);
    }

    private void refreshDisplayBounds() {
        if (container == null || !container.isAttachedToWindow()) return;
        Rect previous = screen;
        int previousWidth = displayWidth, previousHeight = displayHeight;
        readScreenBounds();
        if (previousWidth == displayWidth && previousHeight == displayHeight) {
            if (!screen.equals(previous)) {
                place(canvasX, canvasY, canvasWidth);
                updateWindowCrop();
            }
            return;
        }
        container.cancelGesture();
        windowBounds.setEmpty();
        previousContent.setEmpty();
        if (Math.min(previousWidth, previousHeight) != shortSide() || Math.max(previousWidth, previousHeight) != longSide()) {
            player.view().setLayoutParams(new FrameLayout.LayoutParams(shortSide(), longSide()));
        }
        PointF grip = player.touchShape().grip();
        float gx = grip == null ? .5f : grip.x, gy = grip == null ? .54f : grip.y;
        float relativeX = (canvasX + gx * canvasWidth - previous.left) / Math.max(1, previous.width());
        float relativeY = (canvasY + gy * canvasHeight - previous.top) / Math.max(1, previous.height());
        int width = limitWidth(canvasWidth);
        int x = Math.round(screen.left + relativeX * screen.width() - gx * width);
        int y = Math.round(screen.top + relativeY * screen.height() - gy * heightForWidth(width));
        place(x, y, width);
        player.desktopScale(canvasWidth / (float) shortSide());
        updateWindowCrop();
        savePlacement();
    }

    private void savePlacement() {
        saved.edit().putInt("x", canvasX).putInt("y", canvasY).putInt("width", canvasWidth)
                .putBoolean("fixedAspect", true).apply();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        scheduleDisplayRefresh();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacks(refreshDisplay);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
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
        private float startX;
        private float startY;
        private int windowX;
        private int windowY;
        private boolean dragging;
        private boolean pinching;
        private boolean controlsGesture;
        private boolean ignoreUntilDown;
        private int firstFinger;
        private int secondFinger;
        private float initialSpan;
        private int initialWidth;
        private float anchorX;
        private float anchorY;

        DragFrame(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private boolean moving() { return dragging || pinching; }

        private void beginPinch(MotionEvent event) {
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
            // Android already batches MOVE events to the display frame.
            place(Math.round((x + otherX) / 2 - anchorX * width),
                    Math.round((y + otherY) / 2 - anchorY * height), width);
        }

        private void cancelGesture() {
            if (moving()) player.desktopGesture(false, false);
            dragging = pinching = controlsGesture = false;
            ignoreUntilDown = true;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) ignoreUntilDown = false;
            else if (ignoreUntilDown && action != MotionEvent.ACTION_CANCEL) return true;
            if (action == MotionEvent.ACTION_DOWN) {
                controlsGesture = player.hitsControls(event.getRawX() - canvasX, event.getRawY() - canvasY);
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
                player.desktopGesture(true, true);
            }
            if (pinching && action == MotionEvent.ACTION_MOVE) {
                movePinch(event);
            } else if (!pinching && action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    cancelChild(event);
                    dragging = true;
                    player.desktopGesture(true, false);
                }
                if (dragging) place(windowX + Math.round(dx), windowY + Math.round(dy), canvasWidth);
            }
            if (!dragging && !pinching) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = pinching = false;
                player.desktopGesture(false, false);
                player.afterVisualState(PetService.this::updateWindowCrop);
                savePlacement();
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
