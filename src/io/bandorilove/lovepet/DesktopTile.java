package io.bandorilove.lovepet;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.IOException;

public abstract class DesktopTile extends TileService {
    private static final String PANEL_ACTION = "panelAction";
    enum PanelAction { START_PET, APPLY_WALLPAPER, RESTORE_WALLPAPER }

    abstract boolean isEnabled();
    abstract void runAction();

    @Override public void onStartListening() {
        super.onStartListening();
        updateState(isEnabled());
    }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::runAction);
        else runAction();
    }

    final void updateState(boolean enabled) {
        Tile tile = getQsTile();
        if (tile == null) return;
        int state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        if (tile.getState() == state) return;
        tile.setState(state);
        tile.updateTile();
    }

    final void runInPanel(PanelAction action) {
        Intent intent = new Intent(this, PanelActionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PANEL_ACTION, action.name());
        launchFromTile(intent, action.ordinal());
    }

    final void launchFromTile(Intent intent, int request) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, request, intent,
                    PendingIntent.FLAG_IMMUTABLE));
        } else startActivityAndCollapse(intent);
    }

    private static Intent panelIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public static final class Pet extends DesktopTile {
        private static Pet listening;

        @Override public void onCreate() {
            super.onCreate();
            DesktopRuntime.initialize(this);
        }

        @Override public void onStartListening() {
            listening = this;
            super.onStartListening();
        }

        @Override public void onStopListening() {
            if (listening == this) listening = null;
            super.onStopListening();
        }

        @Override public void onDestroy() {
            if (listening == this) listening = null;
            super.onDestroy();
        }

        static void onPetStateChanged(boolean showing) {
            if (listening != null) listening.updateState(showing);
        }

        @Override boolean isEnabled() { return DesktopRuntime.isPetRunning(this); }

        @Override void runAction() {
            Intent service = new Intent(this, PetService.class);
            if (isEnabled()) {
                stopService(service);
                RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "控制中心请求关闭桌宠");
            } else if (new ModelStore(this).active() == null || !Settings.canDrawOverlays(this)) {
                RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "桌宠快捷键已转到应用检查模型或权限");
                runInPanel(PanelAction.START_PET);
            } else {
                try {
                    startForegroundService(service);
                    RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "控制中心请求启动桌宠");
                } catch (IllegalStateException | SecurityException error) {
                    Log.w("BandoriLovePet", "Quick Settings could not start the pet service", error);
                    RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "桌宠快捷键启动受系统限制，已转到应用");
                    runInPanel(PanelAction.START_PET);
                }
            }
        }
    }

    public static final class Wallpaper extends DesktopTile {
        private static boolean quietChanging;
        private static long quietUntil;
        private final WallpaperManager.OnColorsChangedListener listener = (colors, which) -> updateState(isEnabled());
        private boolean listening;

        static boolean isQuietChange() { return quietChanging || SystemClock.uptimeMillis() < quietUntil; }

        private static void finishQuietChange() {
            quietChanging = false;
            quietUntil = SystemClock.uptimeMillis() + 1200;
            MainActivity.refreshFromWallpaperTile();
        }

        @Override public void onStartListening() {
            super.onStartListening();
            if (!listening) {
                WallpaperManager.getInstance(this).addOnColorsChangedListener(listener, null);
                listening = true;
            }
        }

        @Override public void onStopListening() {
            stopObserving();
            super.onStopListening();
        }

        @Override public void onDestroy() {
            stopObserving();
            super.onDestroy();
        }

        private void stopObserving() {
            if (!listening) return;
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(listener);
            listening = false;
        }

        @Override boolean isEnabled() {
            return WallpaperRecovery.isApplied(this, WallpaperManager.FLAG_SYSTEM);
        }

        @Override void runAction() {
            if (quietChanging) return;
            boolean exit = isEnabled();
            RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, exit ? "控制中心请求退出壁纸" : "控制中心请求应用壁纸");
            if (exit) {
                WallpaperRecovery recovery = new WallpaperRecovery(this);
                if (!recovery.hasChosenPhoto()) runInPanel(PanelAction.RESTORE_WALLPAPER);
                else restorePhoto(recovery);
                return;
            }
            WallpaperSnapshot latest = WallpaperSnapshot.latest(this);
            if (latest == null || !latest.available(this, new ModelStore(this))) runInPanel(PanelAction.APPLY_WALLPAPER);
            else launchFromTile(new Intent(this, WallpaperActionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 10);
        }

        private void restorePhoto(WallpaperRecovery recovery) {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            int expected = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            Handler main = new Handler(Looper.getMainLooper());
            quietChanging = true;
            new Thread(() -> {
                try {
                    if (manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) == expected && isEnabled()) {
                        recovery.restorePhoto();
                        recovery.cancelExit();
                        recovery.acknowledgeExit();
                        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "控制中心已恢复上次选图");
                    }
                } catch (IOException | RuntimeException error) {
                    Log.w("BandoriLovePet", "Quick Settings could not restore the previous photo", error);
                    RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "控制中心恢复上次选图失败");
                } finally {
                    main.post(() -> {
                        finishQuietChange();
                        if (listening) updateState(isEnabled());
                    });
                }
            }, "WallpaperPhotoRestore").start();
        }
    }

    public static final class WallpaperActionActivity extends Activity {
        private static final int PICK_WALLPAPER = 1;
        private final Handler main = new Handler(Looper.getMainLooper());
        private String request;
        private boolean pickerPaused;
        private boolean usingList;
        private boolean verifying;

        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            DesktopRuntime.initialize(this);
            Wallpaper.quietChanging = true;
            if (saved != null) {
                request = saved.getString("request");
                pickerPaused = saved.getBoolean("pickerPaused");
                usingList = saved.getBoolean("usingList");
                return;
            }
            WallpaperManager manager = WallpaperManager.getInstance(this);
            if (WallpaperRecovery.isApplied(this, WallpaperManager.FLAG_SYSTEM)) { finish(); return; }
            WallpaperSnapshot snapshot = WallpaperSnapshot.latest(this);
            if (snapshot == null || !snapshot.available(this, new ModelStore(this))) {
                MainActivity.queueTileAction(PanelAction.APPLY_WALLPAPER);
                startActivity(panelIntent(this));
                finish();
                return;
            }
            try {
                int id = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                new WallpaperRecovery(this).rememberPrevious(id, WallpaperRecovery.activeComponent(this, WallpaperManager.FLAG_SYSTEM));
                if (!snapshot.stage(this, id)) { finish(); return; }
                request = snapshot.request();
                Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, PetWallpaperService.class));
                if (!manager.isSetWallpaperAllowed() || !openPicker(preview)) {
                    openWallpaperList();
                } else {
                    main.postDelayed(() -> {
                        if (!pickerPaused && !usingList && !verifying && !isFinishing() && hasWindowFocus()) openWallpaperList();
                    }, 1500);
                }
            } catch (org.json.JSONException | RuntimeException error) {
                Log.w("BandoriLovePet", "Quick Settings could not prepare the saved wallpaper", error);
                complete(false);
            }
        }

        private boolean openPicker(Intent intent) {
            try { startActivityForResult(intent, PICK_WALLPAPER); return true; }
            catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
        }

        private void openWallpaperList() {
            usingList = true;
            if (!openPicker(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) complete(false);
        }

        @Override protected void onPause() {
            pickerPaused = true;
            super.onPause();
        }

        @Override protected void onResume() {
            super.onResume();
            if (pickerPaused) main.postDelayed(() -> startVerification(3), 250);
        }

        @Override protected void onActivityResult(int code, int result, Intent data) {
            super.onActivityResult(code, result, data);
            if (code != PICK_WALLPAPER) return;
            if (result != RESULT_OK && !pickerPaused && !usingList) openWallpaperList();
            else startVerification(result == RESULT_OK ? 8 : 3);
        }

        private void startVerification(int retries) {
            if (verifying || isFinishing() || isDestroyed()) return;
            verifying = true;
            verify(retries);
        }

        private void verify(int retries) {
            if (isFinishing() || isDestroyed()) return;
            if (WallpaperRecovery.isApplied(this, WallpaperManager.FLAG_SYSTEM)) complete(true);
            else if (retries > 0) main.postDelayed(() -> verify(retries - 1), 350);
            else complete(false);
        }

        private void complete(boolean applied) {
            WallpaperSnapshot snapshot = WallpaperSnapshot.pending(this);
            if (snapshot != null && snapshot.request().equals(request)) {
                if (!applied || snapshot.apply(this)) {
                    WallpaperSnapshot.clearPending(this);
                    if (applied) {
                        new WallpaperRecovery(this).acknowledgeExit();
                        DesktopRuntime.signal(WallpaperSnapshot.APPLIED_KEY);
                        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, "控制中心已应用保存的壁纸");
                    }
                }
            }
            finish();
        }

        @Override protected void onSaveInstanceState(Bundle saved) {
            super.onSaveInstanceState(saved);
            saved.putString("request", request);
            saved.putBoolean("pickerPaused", pickerPaused);
            saved.putBoolean("usingList", usingList);
        }

        @Override protected void onDestroy() {
            main.removeCallbacksAndMessages(null);
            if (!isChangingConfigurations()) Wallpaper.finishQuietChange();
            super.onDestroy();
        }
    }

    public static final class PanelActionActivity extends Activity {
        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            String action = getIntent().getStringExtra(PANEL_ACTION);
            if (saved == null && action != null) {
                MainActivity.queueTileAction(PanelAction.valueOf(action));
                startActivity(panelIntent(this));
            }
            finish();
        }
    }
}
