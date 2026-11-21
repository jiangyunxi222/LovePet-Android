package io.bandorilove.lovepet;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

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
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, action.ordinal(), intent,
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
        private final WallpaperManager.OnColorsChangedListener listener = (colors, which) -> updateState(isEnabled());
        private boolean listening;

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
            boolean exit = isEnabled();
            RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.QUICK_SETTINGS, exit ? "控制中心请求退出壁纸" : "控制中心请求应用壁纸");
            runInPanel(exit ? PanelAction.RESTORE_WALLPAPER : PanelAction.APPLY_WALLPAPER);
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
