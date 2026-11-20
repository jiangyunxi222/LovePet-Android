package io.bandorilove.lovepet;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class WallpaperRecovery {
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    private final Context context;
    private final SharedPreferences preferences;
    private final AtomicFile photo;

    WallpaperRecovery(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("wallpaper-recovery", Context.MODE_PRIVATE);
        photo = new AtomicFile(new File(context.getFilesDir(), "previous-wallpaper.img"));
    }

    void beginExit(int wallpaperId) {
        preferences.edit().putInt("exitWallpaperId", wallpaperId).apply();
    }

    boolean exitChangedWallpaper() {
        if (!preferences.contains("exitWallpaperId")) return false;
        int current = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        return current != preferences.getInt("exitWallpaperId", 0);
    }

    void cancelExit() {
        preferences.edit().remove("exitWallpaperId").apply();
    }

    void confirmExit() {
        preferences.edit().remove("exitWallpaperId").putBoolean("exitConfirmed", true).apply();
    }

    boolean exitConfirmationPending() {
        return preferences.getBoolean("exitConfirmed", false);
    }

    void acknowledgeExit() {
        preferences.edit().remove("exitConfirmed").apply();
    }

    private boolean matches(int wallpaperId) {
        return preferences.contains("id") && preferences.getInt("id", 0) == wallpaperId
                && (liveComponent() != null || hasPhoto());
    }

    ComponentName liveComponent() {
        return ComponentName.unflattenFromString(preferences.getString("component", ""));
    }

    void rememberPrevious(int wallpaperId, ComponentName previous) {
        if (matches(wallpaperId)) return;
        if (previous == null) {
            preferences.edit().remove("id").remove("component").apply();
        } else {
            preferences.edit().putInt("id", wallpaperId).putString("component", previous.flattenToString()).apply();
        }
    }

    boolean hasPhoto() {
        return liveComponent() == null && preferences.contains("id") && photo.getBaseFile().isFile();
    }

    void rememberPhoto(int wallpaperId, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取原壁纸图片。");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("请选择可读取的原壁纸图片。");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取原壁纸图片。");
            FileOutputStream output = photo.startWrite();
            try {
                int total = 0;
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("原壁纸图片不能超过 64 MB。");
                    output.write(buffer, 0, read);
                }
                photo.finishWrite(output);
            } catch (IOException | RuntimeException error) {
                photo.failWrite(output);
                throw error;
            }
        }
        if (!preferences.edit().putInt("id", wallpaperId).remove("component").commit()) {
            throw new IOException("无法保存原壁纸。");
        }
    }

    void restorePhoto() throws IOException {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        try (FileInputStream input = photo.openRead()) {
            int id = manager.setStream(input, null, false, WallpaperManager.FLAG_SYSTEM);
            if (id <= 0) throw new IOException("系统未接受原壁纸图片。");
            preferences.edit().putInt("id", id).apply();
        }
    }
}
