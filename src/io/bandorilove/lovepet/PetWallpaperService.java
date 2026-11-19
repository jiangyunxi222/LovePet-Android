package io.bandorilove.lovepet;

import android.app.Presentation;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class PetWallpaperService extends WallpaperService {
    private static final long RENDERER_RETRY_DELAY_MS = 500;
    private static final long RENDERER_RETRY_INTERVAL_MS = 30_000;
    private static final long SURFACE_GRACE_MS = 5_000;
    private static final long GAZE_HOLD_MS = 300;
    private static final Set<PetEngine> engines = new HashSet<>();
    private final ExecutorService frameIo = Executors.newSingleThreadExecutor();

    static void refresh() {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.refresh();
        }
    }

    static void refreshScene() {
        for (PetEngine engine : engines) if (engine.player != null) engine.player.refreshWallpaperScene();
    }

    static void updateCaptions(boolean enabled) {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.updateCaptionCards(enabled);
        }
    }

    static void updateTheme() {
        for (PetEngine engine : engines) if (engine.player != null) engine.player.updateTheme();
    }

    static void updateTouchMenu(boolean enabled, boolean thirdParty) {
        for (PetEngine engine : engines) if (engine.player != null) engine.player.updateTouchMenu(enabled, thirdParty);
    }

    static void receive(JSONObject command, boolean sound) {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.receive(command, sound && engine.visible);
        }
    }

    @Override public Engine onCreateEngine() { return new PetEngine(); }

    @Override public void onCreate() {
        super.onCreate();
        DesktopRuntime.initialize(this);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        frameIo.shutdown();
    }

    private final class PetEngine extends Engine {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Runnable retryRenderer = this::startRenderer;
        private final Runnable releaseDetachedRenderer = this::release;
        private final Runnable captureFrame = this::captureFrame;
        private final Runnable startGaze = () -> {
            followingGaze = true;
            updateGaze();
        };
        private final Runnable updateGazeFrame = this::updateGaze;
        private final ModelStore models = new ModelStore(PetWallpaperService.this);
        private final WallpaperFrame frameCache = new WallpaperFrame(getCacheDir());
        private VirtualDisplay display;
        private Presentation presentation;
        private PetWeb player;
        private ImageView previousFrame;
        private int surfaceWidth;
        private int surfaceHeight;
        private int displayWidth;
        private int displayHeight;
        private boolean visible;
        private boolean frameReady;
        private boolean followingGaze;
        private int gazePointer = -1;
        private float downX, downY, gazeX, gazeY;
        private long suppressTapUntil;
        private long nextRendererRetryAt;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            engines.add(this);
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(false);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (width <= 0 || height <= 0) return;
            surfaceWidth = width;
            surfaceHeight = height;
            main.removeCallbacks(releaseDetachedRenderer);
            if (display != null) {
                if (width != displayWidth || height != displayHeight) {
                    display.resize(width, height, getResources().getDisplayMetrics().densityDpi);
                    displayWidth = width;
                    displayHeight = height;
                }
                if (display.getSurface() != holder.getSurface()) display.setSurface(holder.getSurface());
                player.wallpaperVisible(visible);
                return;
            }
            startRenderer();
        }

        private void startRenderer() {
            main.removeCallbacks(retryRenderer);
            SurfaceHolder holder = getSurfaceHolder();
            if (display != null || surfaceWidth <= 0 || !holder.getSurface().isValid()) return;
            try {
                display = getSystemService(DisplayManager.class).createVirtualDisplay(
                        "BandoriLovePet wallpaper", surfaceWidth, surfaceHeight, getResources().getDisplayMetrics().densityDpi,
                        holder.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                if (display == null) throw new IllegalStateException("Wallpaper display unavailable");
                displayWidth = surfaceWidth;
                displayHeight = surfaceHeight;
                presentation = new Presentation(PetWallpaperService.this, display.getDisplay());
                presentation.setCancelable(false);
                Window window = presentation.getWindow();
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setDecorFitsSystemWindows(false);
                player = new PetWeb(presentation.getContext(), false, () -> {}, () -> {}, () -> {},
                        ignored -> {}, ignored -> {}, ignored -> {}, this::rendererFailed);
                PetWeb current = player;
                current.onWallpaperFrameReady(() -> showLiveFrame(current));
                FrameLayout content = new FrameLayout(presentation.getContext());
                content.addView(current.view(), new FrameLayout.LayoutParams(-1, -1));
                if (!isPreview()) {
                    previousFrame = new ImageView(presentation.getContext());
                    previousFrame.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    content.addView(previousFrame, new FrameLayout.LayoutParams(-1, -1));
                    loadPreviousFrame(previousFrame);
                }
                presentation.setContentView(content);
                presentation.show();
                if (window.getInsetsController() != null) window.getInsetsController().hide(WindowInsets.Type.systemBars());
                player.loadWallpaper();
                player.wallpaperVisible(visible);
            } catch (RuntimeException error) {
                Log.e("BandoriLovePet", "Wallpaper renderer failed", error);
                rendererFailed();
            }
        }

        @Override public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (player != null) player.wallpaperVisible(visible && surfaceWidth > 0);
            if (!visible) {
                endGaze();
                main.removeCallbacks(retryRenderer);
                main.removeCallbacks(captureFrame);
                nextRendererRetryAt = 0;
            } else if (player == null) {
                startRenderer();
            }
        }

        private void loadPreviousFrame(ImageView target) {
            File model = models.active();
            if (model == null || frameIo.isShutdown()) return;
            int width = surfaceWidth, height = surfaceHeight;
            frameIo.execute(() -> {
                Bitmap bitmap = frameCache.load(model.getName(), width, height);
                if (bitmap != null) main.post(() -> {
                    if (previousFrame == target && !frameReady) target.setImageBitmap(bitmap);
                    else bitmap.recycle();
                });
            });
        }

        private void showLiveFrame(PetWeb current) {
            if (player != current) return;
            frameReady = true;
            ImageView cover = previousFrame;
            previousFrame = null;
            if (cover != null) {
                // Swap on the next draw so the live frame is already in the compositor queue.
                cover.postOnAnimation(() -> {
                    if (cover.getParent() instanceof ViewGroup) ((ViewGroup) cover.getParent()).removeView(cover);
                    cover.setImageDrawable(null);
                });
            }
            main.removeCallbacks(captureFrame);
            if (!isPreview() && visible) main.postDelayed(captureFrame, 250);
        }

        private void captureFrame() {
            PetWeb current = player;
            if (current == null || !visible || surfaceWidth <= 0 || frameIo.isShutdown()) return;
            current.canCacheWallpaperFrame(ready -> {
                if (!ready || player != current || !visible || surfaceWidth <= 0 || frameIo.isShutdown()) return;
                File model = models.active();
                int width = current.view().getWidth(), height = current.view().getHeight();
                if (model == null || width <= 0 || height <= 0) return;
                float scale = Math.min(1f, 1600f / Math.max(width, height));
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
                try {
                    PixelCopy.request(presentation.getWindow(), bitmap, result -> {
                        if (result == PixelCopy.SUCCESS && player == current && !frameIo.isShutdown()) {
                            frameIo.execute(() -> frameCache.save(model.getName(), bitmap));
                        } else bitmap.recycle();
                    }, main);
                } catch (IllegalArgumentException error) {
                    bitmap.recycle();
                }
            });
        }

        private void rendererFailed() {
            release();
            long now = SystemClock.uptimeMillis();
            if (visible && now >= nextRendererRetryAt) {
                nextRendererRetryAt = now + RENDERER_RETRY_INTERVAL_MS;
                main.postDelayed(retryRenderer, RENDERER_RETRY_DELAY_MS);
            }
        }

        @Override public void onTouchEvent(MotionEvent event) {
            if (player == null || !visible || surfaceWidth <= 0) return;
            if (isPreview()) {
                MotionEvent touch = MotionEvent.obtain(event);
                player.view().dispatchTouchEvent(touch);
                touch.recycle();
                return;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    endGaze();
                    suppressTapUntil = 0;
                    gazePointer = event.getPointerId(0);
                    downX = gazeX = event.getX();
                    downY = gazeY = event.getY();
                    main.postDelayed(startGaze, GAZE_HOLD_MS);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(gazePointer);
                    if (index < 0) return;
                    gazeX = event.getX(index);
                    gazeY = event.getY(index);
                    if (!followingGaze && event.getEventTime() - event.getDownTime() >= GAZE_HOLD_MS) {
                        main.removeCallbacks(startGaze);
                        startGaze.run();
                    } else if (followingGaze) {
                        if (!main.hasCallbacks(updateGazeFrame)) main.postDelayed(updateGazeFrame, 16);
                    } else {
                        int slop = ViewConfiguration.get(PetWallpaperService.this).getScaledTouchSlop();
                        if (Math.hypot(gazeX - downX, gazeY - downY) > slop) endGaze();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (gazePointer >= 0 && event.getEventTime() - event.getDownTime() >= GAZE_HOLD_MS) suppressNextTap();
                    endGaze();
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    endGaze();
                    break;
                default:
                    break;
            }
        }

        private void updateGaze() {
            if (followingGaze && player != null) player.wallpaperGaze(gazeX, gazeY);
        }

        private void endGaze() {
            main.removeCallbacks(startGaze);
            main.removeCallbacks(updateGazeFrame);
            gazePointer = -1;
            if (!followingGaze) return;
            followingGaze = false;
            suppressNextTap();
            if (player != null) player.resetWallpaperGaze();
        }

        private void suppressNextTap() { suppressTapUntil = SystemClock.uptimeMillis() + 240; }

        @Override public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action) && player != null && !isPreview()) {
                PetWeb current = player;
                // The launcher command and the mirrored touch arrive on separate channels.
                main.postDelayed(() -> {
                    if (player == current && visible && surfaceWidth > 0 && !followingGaze
                            && SystemClock.uptimeMillis() >= suppressTapUntil) current.wallpaperTap(x, y);
                }, 55);
            }
            return null;
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            clearSurfaceState();
            if (display != null) {
                player.wallpaperVisible(false);
                display.setSurface(null);
                main.postDelayed(releaseDetachedRenderer, SURFACE_GRACE_MS);
            }
            super.onSurfaceDestroyed(holder);
        }

        private void clearSurfaceState() {
            endGaze();
            main.removeCallbacks(retryRenderer);
            main.removeCallbacks(releaseDetachedRenderer);
            main.removeCallbacks(captureFrame);
            surfaceWidth = 0;
            surfaceHeight = 0;
            nextRendererRetryAt = 0;
        }

        private void release() {
            endGaze();
            main.removeCallbacks(captureFrame);
            frameReady = false;
            previousFrame = null;
            if (player != null) {
                if (player.view().getParent() instanceof ViewGroup) {
                    ((ViewGroup) player.view().getParent()).removeView(player.view());
                }
                player.destroy();
                player = null;
            }
            if (presentation != null) {
                presentation.dismiss();
                presentation = null;
            }
            if (display != null) {
                display.release();
                display = null;
            }
        }

        @Override public void onDestroy() {
            engines.remove(this);
            clearSurfaceState();
            release();
            super.onDestroy();
        }
    }
}
