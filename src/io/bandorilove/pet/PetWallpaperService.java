package io.bandorilove.pet;

import android.app.Presentation;
import android.app.WallpaperManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.service.wallpaper.WallpaperService;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class PetWallpaperService extends WallpaperService {
    private static final Set<PetEngine> engines = new HashSet<>();

    static void refresh() {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.refresh();
        }
    }

    static void updateCaptions(boolean enabled) {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.updateCaptionCards(enabled);
        }
    }

    static boolean visible() {
        for (PetEngine engine : engines) if (engine.visible && engine.player != null) return true;
        return false;
    }

    static void receive(JSONObject command) {
        for (PetEngine engine : engines) {
            if (engine.player != null) engine.player.receive(command, engine.visible && !PetService.visible());
        }
    }

    @Override public Engine onCreateEngine() { return new PetEngine(); }

    private final class PetEngine extends Engine {
        private VirtualDisplay display;
        private Presentation presentation;
        private PetWeb player;
        private boolean visible;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            engines.add(this);
            setTouchEventsEnabled(isPreview());
            setOffsetNotificationsEnabled(false);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (width <= 0 || height <= 0) return;
            if (display != null) {
                display.resize(width, height, getResources().getDisplayMetrics().densityDpi);
                display.setSurface(holder.getSurface());
                return;
            }
            try {
                display = getSystemService(DisplayManager.class).createVirtualDisplay(
                        "BandoriLovePet wallpaper", width, height, getResources().getDisplayMetrics().densityDpi,
                        holder.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                if (display == null) throw new IllegalStateException("Wallpaper display unavailable");
                presentation = new Presentation(PetWallpaperService.this, display.getDisplay());
                presentation.setCancelable(false);
                Window window = presentation.getWindow();
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setDecorFitsSystemWindows(false);
                player = new PetWeb(presentation.getContext(), false, () -> {}, () -> {}, () -> {},
                        ignored -> {}, ignored -> {}, ignored -> {}, this::release);
                presentation.setContentView(player.view());
                presentation.show();
                if (window.getInsetsController() != null) window.getInsetsController().hide(WindowInsets.Type.systemBars());
                player.loadWallpaper();
                player.wallpaperVisible(visible);
            } catch (RuntimeException error) {
                Log.e("BandoriLovePet", "Wallpaper renderer failed", error);
                release();
            }
        }

        @Override public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (player != null) player.wallpaperVisible(visible);
        }

        @Override public void onTouchEvent(MotionEvent event) {
            if (isPreview() && player != null && visible) {
                MotionEvent touch = MotionEvent.obtain(event);
                player.view().dispatchTouchEvent(touch);
                touch.recycle();
            }
        }

        @Override public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action) && player != null && visible && !isPreview()) {
                player.wallpaperTap(x, y);
            }
            return null;
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            release();
            super.onSurfaceDestroyed(holder);
        }

        private void release() {
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
            release();
            super.onDestroy();
        }
    }
}
