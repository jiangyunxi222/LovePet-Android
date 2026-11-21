package io.bandorilove.lovepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import org.json.JSONObject;

public final class PetService extends Service {
    private static final String NOTIFICATION_CHANNEL = "pet-runtime";
    private static final String STOP = "io.bandorilove.lovepet.STOP_PET";
    private static final String HOME_ONLY = "homeOnly";
    private static final long CROP_SETTLE_DELAY_MS = 500;
    private static final long SHOW_FADE_MS = 150;
    private static final long RENDERER_RECOVERY_INTERVAL_MS = 15_000;
    private static final int OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    private static volatile PetService active;
    private PetWeb player;
    private WindowManager window;
    private DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshDisplay = this::refreshDisplayBounds;
    private final Runnable settleWindowCrop = () -> {
        rotationCrop = false;
        this.container.keepPinchCrop = false;
        updateWindowCrop();
    };
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
    private float cropRotation;
    private int cropModelRevision;
    private Region touchRegion = new Region();
    private float silhouetteTop, silhouetteBottom = 1;
    private boolean hasSilhouette;
    private int canvasX, canvasY, canvasWidth, canvasHeight;
    private int displayWidth, displayHeight;
    private int topGrabInset, bottomGrabInset;
    private float desktopRotation;
    private boolean rotationCrop;
    private HomeVisibility homeVisibility;
    private boolean wantedVisible;
    private boolean settingsOpen;
    private volatile boolean visible;
    private long nextRendererRecoveryAt;
    private boolean rendererRecovering;
    private String stopEvent = "服务收到停止回调";

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
            stopEvent = intent != null && STOP.equals(intent.getAction()) ? "用户关闭桌宠" : "悬浮窗权限或当前模型不可用";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (container != null) return START_STICKY;
        startForeground(42, notification());
        try {
            showOverlay();
            RuntimeDiagnostics.petServiceEvent(this, "桌宠已启动");
        } catch (RuntimeException error) {
            android.util.Log.e("BandoriLove", "Could not show Android pet", error);
            stopEvent = "悬浮窗启动失败";
            stopSelf();
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (rootIntent != null && new ComponentName(this, MainActivity.class).equals(rootIntent.getComponent())) {
            RuntimeDiagnostics.processEvent(this, "收到最近任务移除回调");
            settingsOpen = false;
            if (homeVisibility != null) homeVisibility.panelTaskRemoved();
            updateVisibility();
        }
        super.onTaskRemoved(rootIntent);
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        return new Notification.Builder(this, NOTIFICATION_CHANNEL).setContentTitle(getString(R.string.app_name))
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
        readScreenBounds();
        frame = overlayFrame();
        player = createPlayer(overlay);
        resetPlacement();
        container = new DragFrame(overlay);
        attachPlayerView();
        container.setVisibility(View.INVISIBLE);
        container.addOnLayoutChangeListener((v, l, t, r, b, previousL, previousT, previousR, previousB) -> pushTouchRegion());
        window.addView(container, frame);
        displays.registerDisplayListener(displayListener, main);
        updateWindowCrop();
        active = this;
        configureVisibility(homeOnly(this));
        player.load();
        DesktopRuntime.reportPet();
    }

    private PetWeb createPlayer(Context context) {
        return new PetWeb(context, true, () -> {}, this::stopFromMenu, () -> startActivity(panelIntent()),
                ignored -> {}, ignored -> {}, ignored -> {}, this::recoverRenderer);
    }

    private void stopFromMenu() {
        stopEvent = "用户从桌宠菜单退出";
        stopSelf();
    }

    private void attachPlayerView() {
        // Keep the WebView's pixel density and the controls fixed as the model scales.
        container.addView(player.view(), new FrameLayout.LayoutParams(shortSide(), longSide()));
        player.onTouchShapeChanged(this::updateWindowCrop);
    }

    private void recoverRenderer() {
        RuntimeDiagnostics.processEvent(this, "桌宠渲染进程退出");
        long now = SystemClock.uptimeMillis();
        if (now < nextRendererRecoveryAt || container == null || !container.isAttachedToWindow()) {
            stopEvent = "渲染进程退出后无法恢复";
            stopSelf();
            return;
        }
        nextRendererRecoveryAt = now + RENDERER_RECOVERY_INTERVAL_MS;
        PetWeb failed = player;
        container.removeView(failed.view());
        failed.destroy();
        player = null;
        try {
            player = createPlayer(container.getContext());
            container.cancelGesture();
            player.desktopScale(canvasWidth / (float) shortSide());
            player.desktopRotation(desktopRotation);
            rendererRecovering = true;
            player.view().setAlpha(0f);
            attachPlayerView();
            updateWindowCrop();
            player.load();
        } catch (RuntimeException error) {
            android.util.Log.e("BandoriLovePet", "Could not recover the pet renderer", error);
            stopEvent = "重建桌宠渲染器失败";
            stopSelf();
        }
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
        WindowInsets windowInsets = metrics.getWindowInsets();
        Insets insets = windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Insets gestures = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
        topGrabInset = Math.max(insets.top, gestures.top);
        bottomGrabInset = Math.max(insets.bottom, gestures.bottom);
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
        if (player == null || container == null || !container.isAttachedToWindow()) return;
        if (container.moving()) return;
        PetTouchShape shape = player.touchShape();
        Rect model = new Rect();
        int visualPadding = dp(2);
        float renderedRotation = shape.modelRotation();
        int touchPadding = renderedRotation == 0 ? visualPadding : dp(10);
        touchRegion = shape.modelRegion(canvasWidth, canvasHeight, touchPadding, model);
        if (!model.isEmpty()) {
            updateVerticalLimits(model);
            model.inset(-visualPadding, -visualPadding);
            model.intersect(0, 0, canvasWidth, canvasHeight);
            updateModelCrop(model, renderedRotation, shape.modelRevision());
            if (rendererRecovering && visible) {
                rendererRecovering = false;
                PetWeb restored = player;
                restored.afterVisualState(() -> {
                    if (player == restored) fadeIn(restored.view());
                });
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

    private void updateModelCrop(Rect model, float renderedRotation, int modelRevision) {
        RectF visible = new RectF(model.left / (float) canvasWidth, model.top / (float) canvasHeight,
                model.right / (float) canvasWidth, model.bottom / (float) canvasHeight);
        boolean newPose = cropRotation != renderedRotation || cropModelRevision != modelRevision;
        if (!rendererRecovering && !newPose && cropArea.contains(visible)) return;
        int margin = dp(12);
        visible.inset(-margin / (float) canvasWidth, -margin / (float) canvasHeight);
        visible.intersect(0, 0, 1, 1);
        // Rebuild the frame for each new pose, while leaving room for small idle motions.
        if (newPose && !rendererRecovering) cropArea.set(visible);
        else cropArea.union(visible);
        cropRotation = renderedRotation;
        cropModelRevision = modelRevision;
        player.desktopCrop(cropArea);
    }

    private void updateVerticalLimits(Rect model) {
        silhouetteTop = model.top / (float) canvasHeight;
        silhouetteBottom = model.bottom / (float) canvasHeight;
        int y = hasSilhouette ? canvasY : clampVertical(canvasY, model.top, model.bottom);
        hasSilhouette = true;
        if (y + model.top < topGrabInset || y + model.bottom > displayHeight - bottomGrabInset) {
            // At a corner, keep a grab point in the part of the silhouette that is still on screen.
            Region visibleColumns = new Region(touchRegion);
            if (visibleColumns.op(screen.left - canvasX, 0, screen.right - canvasX, canvasHeight, Region.Op.INTERSECT)) {
                Rect reachable = visibleColumns.getBounds();
                int safety = Math.min(dp(8), reachable.height());
                if (y + reachable.bottom < topGrabInset + safety
                        || y + reachable.top > displayHeight - bottomGrabInset - safety) {
                    y = clampVertical(y, reachable.top, reachable.bottom);
                }
            }
        }
        canvasY = y;
    }

    private void updateWindowFrame() {
        if (rotationCrop) crop.set(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight);
        else if (cropArea.isEmpty()) crop.set(canvasX, canvasY, canvasX + 1, canvasY + 1);
        else crop.set(canvasX + Math.round(cropArea.left * canvasWidth),
                canvasY + Math.round(cropArea.top * canvasHeight),
                canvasX + Math.round(cropArea.right * canvasWidth),
                canvasY + Math.round(cropArea.bottom * canvasHeight));
        if (!controls.isEmpty()) crop.union(controls.left + canvasX, controls.top + canvasY,
                controls.right + canvasX, controls.bottom + canvasY);
        // Keep the clipped window on the physical display when the model extends past an edge.
        if (!crop.intersect(0, 0, displayWidth, displayHeight)) crop.set(0, 0, 1, 1);
        boolean retainWindow = Build.VERSION.SDK_INT >= 34 && container.keepPinchCrop;
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
        boolean captureFrame = rotationCrop || container.capturingModelTouches;
        if (Build.VERSION.SDK_INT < 33 || (container.moving() && !captureFrame)) return;
        AttachedSurfaceControl surface = container.getRootSurfaceControl();
        if (surface != null) {
            // Allow more fingers to join across the model once the first finger has hit its outline.
            Region local = captureFrame ? new Region(crop) : new Region(touchRegion);
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
        if (player == null || container == null) return;
        boolean show = wantedVisible && !settingsOpen;
        if (visible == show) return;
        visible = show;
        if (!show && (container.moving() || container.capturingModelTouches)) container.cancelGesture();
        container.animate().cancel();
        container.setAlpha(show && hasSilhouette && !rendererRecovering ? 0f : 1f);
        container.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show && container.getAlpha() == 0f) fadeIn(container);
        updateWindowCrop();
        player.status();
        DesktopRuntime.reportPet();
    }

    private void fadeIn(View view) {
        view.animate().alpha(1f).setDuration(SHOW_FADE_MS).setInterpolator(new DecelerateInterpolator()).start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clampVertical(int y, int modelTop, int modelBottom) {
        int modelHeight = modelBottom - modelTop;
        int visible = (modelHeight + 4) / 5;
        int handle = Math.min(modelHeight, dp(16));
        int top = Math.max(visible, topGrabInset + handle) - modelBottom;
        int bottom = displayHeight - Math.max(visible, bottomGrabInset + handle) - modelTop;
        return Math.max(top, Math.min(bottom, y));
    }

    private void place(int x, int y, int width) {
        width = limitWidth(width);
        int height = heightForWidth(width);
        PointF grip = player.touchShape().grip();
        int anchorX = Math.round(width * (grip == null ? .5f : grip.x));
        boolean landscape = displayWidth > displayHeight;
        int left = landscape ? 0 : screen.left;
        int right = landscape ? displayWidth : screen.right;
        int marginX = Math.min(dp(24), (right - left) / 2);
        x = Math.max(left + marginX - anchorX, Math.min(right - marginX - anchorX, x));
        y = clampVertical(y, Math.round(height * silhouetteTop), Math.round(height * silhouetteBottom));
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
        if (player == null || container == null || !container.isAttachedToWindow()) return;
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
    }

    private void setDesktopRotation(float angle) {
        desktopRotation = (float) Math.IEEEremainder(angle, 360);
        player.desktopRotation(desktopRotation);
    }

    private void holdRotationCrop() {
        main.removeCallbacks(settleWindowCrop);
        if (rotationCrop) return;
        rotationCrop = true;
        updateWindowFrame();
        pushTouchRegion();
    }

    private void resetPlacement() {
        if (container != null) holdRotationCrop();
        int width = limitWidth(dp(190));
        place(screen.right - width, screen.bottom - heightForWidth(width) - dp(60), width);
        setDesktopRotation(0);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        scheduleDisplayRefresh();
    }

    @Override public void onDestroy() {
        RuntimeDiagnostics.petServiceEvent(this, stopEvent);
        if (active == this) active = null;
        main.removeCallbacks(refreshDisplay);
        main.removeCallbacks(settleWindowCrop);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (homeVisibility != null) homeVisibility.close();
        if (container != null && container.isAttachedToWindow()) {
            container.animate().cancel();
            container.removeCallbacks(container.applyRotation);
            window.removeViewImmediate(container);
        }
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
        if (current != null && current.player != null) current.player.receive(command, sound && current.visible);
    }

    static void refresh() {
        PetService current = active;
        if (current != null && current.player != null) current.player.refresh();
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
        private boolean capturingModelTouches;
        private float startX;
        private float startY;
        private int windowX;
        private int windowY;
        private boolean dragging;
        private boolean pinching;
        private boolean keepPinchCrop;
        private boolean controlsGesture;
        private boolean ignoreUntilDown;
        private int firstFinger;
        private int secondFinger;
        private float initialSpan;
        private int initialWidth;
        private float anchorX;
        private float anchorY;
        private MultiFingerGesture twoFinger, threeFinger;
        private long lastTapAt;
        private int lastTapFingerCount;
        private float lastTapX, lastTapY;
        private float nextRotation;
        private boolean rotationPosted;
        private final Runnable applyRotation = () -> {
            rotationPosted = false;
            setDesktopRotation(nextRotation);
        };

        DragFrame(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private boolean moving() { return dragging || pinching || threeFinger != null; }

        private void postRotation(float angle) {
            nextRotation = angle;
            if (rotationPosted) return;
            rotationPosted = true;
            postOnAnimation(applyRotation);
        }

        private void flushRotation() {
            if (!rotationPosted) return;
            removeCallbacks(applyRotation);
            applyRotation.run();
        }

        private void beginPinch(MotionEvent event, int first, int second) {
            firstFinger = event.getPointerId(first);
            secondFinger = event.getPointerId(second);
            float x = event.getRawX(first), y = event.getRawY(first);
            float otherX = event.getRawX(second), otherY = event.getRawY(second);
            initialSpan = Math.max(1, (float) Math.hypot(x - otherX, y - otherY));
            initialWidth = canvasWidth;
            anchorX = ((x + otherX) / 2 - canvasX) / canvasWidth;
            anchorY = ((y + otherY) / 2 - canvasY) / canvasHeight;
            pinching = true;
            keepPinchCrop = Build.VERSION.SDK_INT >= 34;
            player.desktopGesture(true, true);
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
            if (twoFinger != null && Math.abs(canvasWidth - initialWidth) > dp(12)) twoFinger.moved = true;
        }

        private void cancelGesture() {
            flushRotation();
            if (moving()) player.desktopGesture(false, false);
            dragging = pinching = controlsGesture = false;
            twoFinger = threeFinger = null;
            capturingModelTouches = false;
            lastTapAt = 0;
            main.removeCallbacks(settleWindowCrop);
            rotationCrop = false;
            keepPinchCrop = false;
            ignoreUntilDown = true;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                main.removeCallbacks(settleWindowCrop);
                if (moving() || capturingModelTouches) {
                    cancelGesture();
                    player.afterVisualState(PetService.this::updateWindowCrop);
                }
                ignoreUntilDown = false;
                controlsGesture = player.hitsControls(event.getRawX() - canvasX, event.getRawY() - canvasY);
                dragging = pinching = false;
                twoFinger = null;
                startX = event.getRawX();
                startY = event.getRawY();
                windowX = canvasX;
                windowY = canvasY;
                if (desktopRotation != 0 && !controlsGesture) {
                    capturingModelTouches = true;
                    pushTouchRegion();
                }
            } else if (ignoreUntilDown && action != MotionEvent.ACTION_CANCEL) return true;
            boolean clearContactArea = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && capturingModelTouches;
            if (clearContactArea) capturingModelTouches = false;
            if (controlsGesture) {
                lastTapAt = 0;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    controlsGesture = false;
                    scheduleCropSettle();
                }
                return super.dispatchTouchEvent(event);
            }
            if (threeFinger != null) {
                handleThreeFinger(event);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 3) {
                threeFinger = new MultiFingerGesture(event);
                if (twoFinger != null) threeFinger.moved |= twoFinger.moved || twoFinger.canceled;
                twoFinger = null;
                dragging = pinching = false;
                player.desktopGesture(true, false);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && !pinching) {
                if (twoFinger == null) twoFinger = new MultiFingerGesture(event);
                else twoFinger.canceled = true;
                if (!dragging) cancelChild(event);
                beginPinch(event, 0, event.getActionIndex());
            }
            if (twoFinger != null && action != MotionEvent.ACTION_CANCEL) twoFinger.checkMovement(event);
            if (pinching && action == MotionEvent.ACTION_MOVE) {
                movePinch(event);
            } else if (pinching && action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() == 2) {
                int remaining = 1 - event.getActionIndex();
                startX = event.getRawX(remaining);
                startY = event.getRawY(remaining);
                windowX = canvasX;
                windowY = canvasY;
                dragging = true;
                pinching = false;
                player.desktopGesture(true, false);
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
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishResetTap(twoFinger, event);
                twoFinger = null;
                if (!dragging && !pinching) {
                    if (clearContactArea) pushTouchRegion();
                    scheduleCropSettle();
                    return super.dispatchTouchEvent(event);
                }
                dragging = pinching = false;
                player.desktopGesture(false, false);
                finishPlacement();
                return true;
            }
            return dragging || pinching || super.dispatchTouchEvent(event);
        }

        private void handleThreeFinger(MotionEvent event) {
            int action = event.getActionMasked();
            MultiFingerGesture chord = threeFinger;
            if (action == MotionEvent.ACTION_POINTER_DOWN) chord.canceled = true;
            if (!chord.canceled && action != MotionEvent.ACTION_CANCEL) chord.checkMovement(event);
            if (action == MotionEvent.ACTION_MOVE && !chord.released && !chord.canceled) chord.rotate(event);
            if (action == MotionEvent.ACTION_POINTER_UP) {
                if (chord.rotating && !chord.canceled && event.getPointerCount() == 3) {
                    int lifted = event.getActionIndex();
                    flushRotation();
                    threeFinger = null;
                    lastTapAt = 0;
                    // Rebase the pinch from the two fingers still on the rotated model.
                    beginPinch(event, lifted == 0 ? 1 : 0, lifted == 2 ? 1 : 2);
                    return;
                }
                chord.released = true;
            }
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
            flushRotation();
            finishResetTap(chord, event);
            threeFinger = null;
            player.desktopGesture(false, false);
            finishPlacement();
        }

        private void finishResetTap(MultiFingerGesture chord, MotionEvent event) {
            long now = event.getEventTime();
            if (chord == null || event.getActionMasked() != MotionEvent.ACTION_UP || !chord.isTap(now)) {
                lastTapAt = 0;
                return;
            }
            int fingerCount = chord.ids.length;
            if (lastTapAt != 0 && fingerCount == lastTapFingerCount && chord.downTime - lastTapAt <= 360
                    && Math.hypot(chord.centerX - lastTapX, chord.centerY - lastTapY) <= dp(44)) {
                resetPlacement();
                lastTapAt = 0;
                return;
            }
            lastTapAt = now;
            lastTapFingerCount = fingerCount;
            lastTapX = chord.centerX;
            lastTapY = chord.centerY;
        }

        private void finishPlacement() {
            player.afterVisualState(PetService.this::updateWindowCrop);
            if (rotationCrop) pushTouchRegion();
            scheduleCropSettle();
        }

        private void scheduleCropSettle() {
            if (!rotationCrop && !keepPinchCrop) return;
            main.removeCallbacks(settleWindowCrop);
            main.postDelayed(settleWindowCrop, CROP_SETTLE_DELAY_MS);
        }

        private final class MultiFingerGesture {
            private final int[] ids;
            private final float[] startXs, startYs;
            private final float[] xs = new float[3], ys = new float[3];
            private final float centerX;
            private final float centerY;
            private final float startRotation = desktopRotation;
            private final long downTime;
            private boolean moved;
            private boolean rotating;
            private boolean released;
            private boolean canceled;

            MultiFingerGesture(MotionEvent event) {
                downTime = event.getDownTime();
                int count = event.getPointerCount();
                ids = new int[count];
                startXs = new float[count];
                startYs = new float[count];
                float x = 0, y = 0;
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = event.getPointerId(i);
                    startXs[i] = event.getRawX(i);
                    startYs[i] = event.getRawY(i);
                    x += startXs[i];
                    y += startYs[i];
                }
                centerX = x / count;
                centerY = y / count;
                int tapSlop = touchTolerance();
                moved = event.getEventTime() - downTime > 200 || dragging
                        || Math.hypot(startXs[0] - startX, startYs[0] - startY) > tapSlop
                        || (pinching && (Math.abs(canvasWidth - initialWidth) > tapSlop
                        || Math.hypot(canvasX - windowX, canvasY - windowY) > tapSlop));
            }

            void checkMovement(MotionEvent event) {
                if (moved || canceled) return;
                for (int i = 0; i < ids.length; i++) {
                    int index = event.findPointerIndex(ids[i]);
                    if (index >= 0 && Math.hypot(event.getRawX(index) - startXs[i],
                            event.getRawY(index) - startYs[i]) > touchTolerance()) {
                        moved = true;
                        return;
                    }
                }
            }

            private int touchTolerance() { return ids.length == 2 ? slop : dp(12); }

            void rotate(MotionEvent event) {
                for (int i = 0; i < ids.length; i++) {
                    int index = event.findPointerIndex(ids[i]);
                    if (index < 0) return;
                    xs[i] = event.getRawX(index);
                    ys[i] = event.getRawY(index);
                }
                float x = (xs[0] + xs[1] + xs[2]) / 3, y = (ys[0] + ys[1] + ys[2]) / 3;
                float cross = 0, dot = 0;
                for (int i = 0; i < ids.length; i++) {
                    float dx = startXs[i] - centerX, dy = startYs[i] - centerY;
                    float nextX = xs[i] - x, nextY = ys[i] - y;
                    cross += dx * nextY - dy * nextX;
                    dot += dx * nextX + dy * nextY;
                }
                if (Math.hypot(cross, dot) < 16) return;
                float delta = (float) Math.toDegrees(Math.atan2(cross, dot));
                if (!rotating && Math.abs(delta) < 4) return;
                if (!rotating) holdRotationCrop();
                rotating = true;
                postRotation(startRotation + delta);
            }

            boolean isTap(long time) {
                return !canceled && !moved && !rotating && time - downTime <= 290;
            }
        }

        private void cancelChild(MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
    }
}
