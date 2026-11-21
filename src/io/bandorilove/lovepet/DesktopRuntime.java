package io.bandorilove.lovepet;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

final class DesktopRuntime {
    private static final String EVENT = "io.bandorilove.lovepet.DESKTOP_EVENT";
    private static final String PERMISSION = "io.bandorilove.lovepet.permission.DESKTOP_RUNTIME";
    private static final boolean DESKTOP = Application.getProcessName().endsWith(":desktop");
    private static Context app;
    private static volatile boolean remoteShowing;
    private static volatile boolean remoteVisible;

    static boolean isDesktop() { return DESKTOP; }

    static void initialize(Context context) {
        if (app != null) return;
        if (DESKTOP) WebView.setDataDirectorySuffix("desktop");
        app = context.getApplicationContext();
        BroadcastReceiver listener = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getBooleanExtra("desktop", false) == DESKTOP) return;
                receive(intent);
            }
        };
        IntentFilter filter = new IntentFilter(EVENT);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(listener, filter, PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(listener, filter, PERMISSION, null);
    }

    private static void receive(Intent intent) {
        String type = intent.getStringExtra("type");
        if (type == null) return;
        switch (type) {
            case "command":
                try {
                    JSONObject command = new JSONObject(intent.getStringExtra("value"));
                    if (DESKTOP) {
                        PetService.receive(command, intent.getBooleanExtra("flag", false));
                        PetWallpaperService.receive(command, false);
                    } else MainActivity.receive(command, false);
                } catch (JSONException ignored) {}
                break;
            case "model":
                if (DESKTOP) { PetService.refresh(); PetWallpaperService.refresh(); }
                break;
            case "scene":
                if (DESKTOP) PetWallpaperService.refreshScene();
                break;
            case "wallpaper-preview-visible":
                if (!DESKTOP) MainActivity.wallpaperPreviewVisible();
                break;
            case WallpaperTilt.SETTING:
                if (DESKTOP) PetWallpaperService.refreshTilt();
                break;
            case "captions":
                if (DESKTOP) {
                    boolean enabled = intent.getBooleanExtra("flag", true);
                    PetService.updateCaptions(enabled);
                    PetWallpaperService.updateCaptions(enabled);
                }
                break;
            case "theme":
                if (DESKTOP) { PetService.updateTheme(); PetWallpaperService.updateTheme(); }
                break;
            case "touch-menu":
            case "third-party-menu":
                if (DESKTOP) {
                    boolean thirdParty = "third-party-menu".equals(type);
                    boolean enabled = intent.getBooleanExtra("flag", !thirdParty);
                    PetService.updateTouchMenu(enabled, thirdParty);
                    PetWallpaperService.updateTouchMenu(enabled, thirdParty);
                }
                break;
            case "settings":
                if (DESKTOP) PetService.suspendForSettings(intent.getBooleanExtra("flag", false));
                break;
            case "placement":
                if (DESKTOP) PetService.applyHomeOnly(intent.getBooleanExtra("flag", false));
                break;
            case "query":
                if (DESKTOP) reportPet();
                break;
            case "status":
                if (!DESKTOP) {
                    remoteShowing = intent.getBooleanExtra("showing", false);
                    remoteVisible = intent.getBooleanExtra("flag", false);
                    MainActivity.petChanged();
                    DesktopTile.Pet.onPetStateChanged(remoteShowing);
                }
                break;
            default:
                break;
        }
    }

    private static Intent event(String type) {
        return new Intent(EVENT).setPackage(app.getPackageName()).putExtra("type", type).putExtra("desktop", DESKTOP);
    }

    static void signal(String type) { app.sendBroadcast(event(type), PERMISSION); }
    static void flag(String type, boolean value) { app.sendBroadcast(event(type).putExtra("flag", value), PERMISSION); }
    static void command(JSONObject value, boolean desktopSound) {
        app.sendBroadcast(event("command").putExtra("value", value.toString()).putExtra("flag", desktopSound), PERMISSION);
    }
    static void reportPet() {
        if (DESKTOP) PetWallpaperService.updatePetStatus();
        app.sendBroadcast(event("status").putExtra("showing", PetService.showing()).putExtra("flag", PetService.visible()), PERMISSION);
    }

    static void queryPet() {
        remoteShowing = isPetRunning(app);
        if (!remoteShowing) remoteVisible = false;
        signal("query");
    }

    static boolean isPetRunning(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        return manager.getRunningServices(Integer.MAX_VALUE).stream()
                .anyMatch(service -> service.service.getClassName().equals(PetService.class.getName()));
    }

    static boolean petShowing() { return DESKTOP ? PetService.showing() : remoteShowing; }
    static boolean petVisible() { return DESKTOP ? PetService.visible() : remoteVisible; }

    static String read(Context context, String key) {
        try {
            byte[] value = Files.readAllBytes(setting(context, key).toPath());
            return new String(value, StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return null;
        }
    }

    static boolean write(Context context, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512 * 1024) return false;
        File file = setting(context, key);
        File pending = null;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            pending = File.createTempFile("value-", ".tmp", file.getParentFile());
            try (FileOutputStream stream = new FileOutputStream(pending)) { stream.write(bytes); }
            if (!pending.renameTo(file)) throw new IOException("Could not publish setting");
            return true;
        } catch (IOException error) {
            Log.w("BandoriLovePet", "Cannot save desktop setting", error);
            return false;
        } finally {
            if (pending != null) pending.delete();
        }
    }

    private static File setting(Context context, String key) {
        return new File(new File(context.getFilesDir(), "desktop-settings"), key.replace(':', '.'));
    }

    static File media(Context context, String name) {
        return new File(new File(context.getFilesDir(), "desktop-media"), name);
    }
}
