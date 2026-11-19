package io.bandorilove.lovepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.ComponentCallbacks;
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
import android.os.IBinder;
import android.os.Handler;
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
    private Context overlayContext;
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
    private final ComponentCallbacks overlayConfiguration = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration configuration) { scheduleDisplayRefresh(); }
        @Override public void onLowMemory() {}
    };
    private WindowManager.LayoutParams frame;
    private Rect screen;
    private FrameLayout container;
    private final Rect crop = new Rect();
    private final Rect laidOutCrop = new Rect();
    private final RectF cropArea = new RectF();
    private final RectF modelArea = new RectF();
    private Region touchRegion = new Region();
    private boolean touchRegionDirty = true;
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
        overlayContext = overlay;
        window = overlay.getSystemService(WindowManager.class);
        saved = getSharedPreferences("overlay", MODE_PRIVATE);
        readScreenBounds();
        frame = overlayFrame();
        int width = saved.getInt("width", dp(190));
        if (!saved.getBoolean("fixedAspect", false) && width > shortSide()) {
            // Convert legacy landscape widths, which represented a much wider canvas.
            float ratio = shortSide() / (float) longSide();
            width = Math.round(width * ratio * ratio);
        }
        width = limitWidth(width);
        player = new PetWeb(overlay, true, () -> {}, this::stopSelf, () -> startActivity(panelIntent()),
                ignored -> {}, ignored -> {}, ignored -> {}, this::stopSelf);
        place(saved.getInt("x", screen.right - width),
                saved.getInt("y", screen.bottom - heightForWidth(width) - dp(60)), width);
        container = new DragFrame(overlay);
        player.view().setPivotX(0);
        player.view().setPivotY(0);
        container.addView(player.view(), new FrameLayout.LayoutParams(canvasWidth, canvasHeight));
        container.setVisibility(View.INVISIBLE);
        container.getViewTreeObserver().addOnPreDrawListener(() -> {
            positionViewport();
            return true;
        });
        player.onTouchShapeChanged(this::updateWindowCrop);
        window.addView(container, frame);
        overlay.registerComponentCallbacks(overlayConfiguration);
        displays.registerDisplayListener(displayListener, main);
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            scheduleDisplayRefresh();
            return insets;
        });
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
        // A cropped overlay's context can still describe the previous orientation.
        // Query the display area afresh, independently of that overlay window.
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

    private void scheduleDisplayRefresh() {
        if (container == null) return;
        main.removeCallbacks(refreshDisplay);
        main.post(refreshDisplay);
        // Window contexts and display information need not update in the same callback.
        main.postDelayed(refreshDisplay, 120);
    }

    private void refreshDisplayBounds() {
        if (container == null || !container.isAttachedToWindow()) return;
        Rect previous = new Rect(screen);
        int oldDisplayWidth = displayWidth, oldDisplayHeight = displayHeight;
        readScreenBounds();
        if (previous.equals(screen) && oldDisplayWidth == displayWidth && oldDisplayHeight == displayHeight) return;
        int x = canvasX, y = canvasY, width = canvasWidth;
        if (oldDisplayWidth != displayWidth || oldDisplayHeight != displayHeight) {
            ((DragFrame) container).cancelGesture();
            PointF grip = player.touchShape().grip();
            float gx = grip == null ? .5f : grip.x, gy = grip == null ? .54f : grip.y;
            float relativeX = (canvasX + gx * canvasWidth - previous.left) / Math.max(1, previous.width());
            float relativeY = (canvasY + gy * canvasHeight - previous.top) / Math.max(1, previous.height());
            width = limitWidth(width);
            x = Math.round(screen.left + relativeX * screen.width() - gx * width);
            y = Math.round(screen.top + relativeY * screen.height() - gy * heightForWidth(width));
        }
        place(x, y, width);
        if (!((DragFrame) container).isMoving()) updateWindowCrop();
        savePlacement();
    }

    private void savePlacement() {
        saved.edit().putInt("x", canvasX).putInt("y", canvasY).putInt("width", canvasWidth)
                .putBoolean("fixedAspect", true).apply();
    }

    private int shortSide() { return Math.min(displayWidth, displayHeight); }
    private int longSide() { return Math.max(displayWidth, displayHeight); }

    private int limitWidth(int width) {
        return Math.min(shortSide(), Math.max(dp(96), width));
    }

    private int heightForWidth(int width) {
        return Math.round(width * (longSide() / (float) shortSide()));
    }

    private void updateWindowCrop() {
        if (container == null || !container.isAttachedToWindow()) return;
        if (((DragFrame) container).isMoving()) return;
        PetTouchShape shape = player.touchShape();
        touchRegion = shape.modelRegion(canvasWidth, canvasHeight, dp(2));
        Rect model = touchRegion.getBounds();
        if (!model.isEmpty()) {
            modelArea.set(model.left / (float) canvasWidth, model.top / (float) canvasHeight,
                    model.right / (float) canvasWidth, model.bottom / (float) canvasHeight);
            // Keep the opaque window steady as the silhouette changes between frames.
            // Expand only when a pose exceeds the space already reserved.
            if (!cropArea.contains(modelArea)) {
                RectF visible = new RectF(modelArea);
                visible.inset(-.045f, -.045f);
                visible.union(.15f, .06f, .85f, .94f);
                visible.intersect(0, 0, 1, 1);
                cropArea.union(visible);
                player.desktopCrop(cropArea);
            }
        }
        shape.addControls(touchRegion, canvasWidth, canvasHeight);
        touchRegionDirty = true;
        updateWindowFrame();
    }

    private void updateWindowFrame() {
        if (cropArea.isEmpty()) crop.set(0, 0, 1, 1);
        else crop.set(Math.round(cropArea.left * canvasWidth), Math.round(cropArea.top * canvasHeight),
                Math.round(cropArea.right * canvasWidth), Math.round(cropArea.bottom * canvasHeight));
        // Clip the window ourselves so WindowManager never shifts negative coordinates inward.
        if (!crop.intersect(-canvasX, -canvasY, displayWidth - canvasX, displayHeight - canvasY)) {
            int x = Math.max(0, Math.min(displayWidth - 1, canvasX)) - canvasX;
            int y = Math.max(0, Math.min(displayHeight - 1, canvasY)) - canvasY;
            crop.set(x, y, x + 1, y + 1);
        }
        layoutWindow(canvasX + crop.left, canvasY + crop.top, crop.width(), crop.height());
        // A translation or scale change also needs a draw when the window bounds match.
        container.invalidate();
    }

    private void layoutWindow(int x, int y, int width, int height) {
        if (frame.x != x || frame.y != y || frame.width != width || frame.height != height) {
            frame.x = x;
            frame.y = y;
            frame.width = width;
            frame.height = height;
            window.updateViewLayout(container, frame);
        }
    }

    private void pushTouchRegion() {
        if (Build.VERSION.SDK_INT < 33) return;
        AttachedSurfaceControl surface = container.getRootSurfaceControl();
        if (surface != null) {
            Region local;
            if (((DragFrame) container).pinching) {
                local = new Region(0, 0, container.getWidth(), container.getHeight());
            } else {
                local = new Region(touchRegion);
                local.op(laidOutCrop, Region.Op.INTERSECT);
                local.translate(-laidOutCrop.left, -laidOutCrop.top);
            }
            surface.setTouchableRegion(local);
            touchRegionDirty = false;
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
        int anchorX = Math.round(width * (modelArea.isEmpty() ? .5f : modelArea.centerX()));
        int anchorY = Math.round(height * (grip == null ? .54f : grip.y));
        int marginX = Math.min(dp(4), displayWidth / 2);
        int marginY = Math.min(dp(24), screen.height() / 2);
        // Horizontal hiding uses the physical edges, symmetric even with an inset on one side.
        x = Math.max(marginX - anchorX, Math.min(displayWidth - marginX - anchorX, x));
        y = Math.max(screen.top + marginY - anchorY, Math.min(screen.bottom - marginY - anchorY, y));
        boolean resized = canvasWidth != width || canvasHeight != height;
        if (!resized && canvasX == x && canvasY == y) return;
        canvasX = x;
        canvasY = y;
        canvasWidth = width;
        canvasHeight = height;
        if (container != null) {
            if (resized && !((DragFrame) container).pinching) syncViewport();
            if (((DragFrame) container).isMoving()) updateWindowFrame();
            else updateWindowCrop();
        }
    }

    private void positionViewport() {
        View view = player.view();
        if (view.getWidth() == 0 || view.getHeight() == 0) return;
        boolean offsetChanged = !laidOutCrop.equals(crop);
        laidOutCrop.set(crop);
        // Apply the crop and scale at draw time, using the WebView size that was actually laid out.
        // On pinch release the old WebView stays scaled until its new layout is ready.
        view.setTranslationX(-crop.left);
        view.setTranslationY(-crop.top);
        view.setScaleX(canvasWidth / (float) view.getWidth());
        view.setScaleY(canvasHeight / (float) view.getHeight());
        if (offsetChanged) touchRegionDirty = true;
        if (touchRegionDirty) pushTouchRegion();
    }

    private void syncViewport() {
        View view = player.view();
        android.view.ViewGroup.LayoutParams size = view.getLayoutParams();
        if (size.width != canvasWidth || size.height != canvasHeight) {
            size.width = canvasWidth;
            size.height = canvasHeight;
            view.setLayoutParams(size);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        scheduleDisplayRefresh();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (overlayContext != null) overlayContext.unregisterComponentCallbacks(overlayConfiguration);
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
        private boolean ignoreGesture;
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

        private boolean isMoving() { return dragging || pinching; }

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

        private void resetGesture() {
            removeCallbacks(drawFrame);
            applyFrame();
            boolean wasPinching = pinching;
            dragging = pinching = controlsGesture = false;
            if (wasPinching) {
                syncViewport();
                player.pinching(false);
            }
        }

        private void cancelGesture() {
            removeCallbacks(drawFrame);
            framePending = false;
            resetGesture();
            ignoreGesture = true;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) ignoreGesture = false;
            else if (ignoreGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoreGesture = false;
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                controlsGesture = player.touchShape().hitsControls((event.getX() + laidOutCrop.left) / canvasWidth,
                        (event.getY() + laidOutCrop.top) / canvasHeight);
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
            if (pinching && action == MotionEvent.ACTION_MOVE) {
                movePinch(event);
            } else if (!pinching && action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;
                if (!dragging) {
                    float distance = (float) Math.hypot(dx, dy);
                    if (distance > slop) {
                        cancelChild(event);
                        dragging = true;
                        // Keep the avatar still within touch slop without jumping by that distance.
                        startX += dx * slop / distance;
                        startY += dy * slop / distance;
                        dx = event.getRawX() - startX;
                        dy = event.getRawY() - startY;
                    }
                }
                if (dragging) queueFrame(windowX + Math.round(dx), windowY + Math.round(dy), canvasWidth);
            }
            if (!dragging && !pinching) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetGesture();
                updateWindowCrop();
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
