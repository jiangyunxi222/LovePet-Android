package io.bandorilove.pet;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Base64;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int ARCHIVE = 1;
    private static final int FOLDER = 2;
    private static final int OVERLAY = 3;
    private static final int NOTIFICATION = 4;
    private static final int USAGE_ACCESS = 5;
    private static final int SCENE_FILE = 6;
    private static final int WALLPAPER_PREVIEW = 7;
    private static final int WALLPAPER_LIST = 8;
    private static final int WALLPAPER_SETTINGS = 9;
    private static final int WALLPAPER_RESTORE = 10;
    private static final int WALLPAPER_RESTORE_SETTINGS = 11;
    private static final int WALLPAPER_BACKUP = 12;
    private static final String WALLPAPER_PERMISSION_HINT =
            "如果应用信息的「权限」或「其他权限」里有「动态壁纸服务」，请允许后重试。";
    private static final String SYSTEM_PHOTO_PICKER = "androidx.activity.result.contract.action.PICK_IMAGES";
    private ValueCallback<Uri[]> scenePicker;
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private PetWeb player;
    private ModelStore store;
    private FrameLayout frame;
    private boolean importing;
    private boolean resumed;
    private boolean immersivePreview;
    private Uri pendingDocument;
    private boolean settingsVisible;
    private boolean awaitingUsageAccess;
    private boolean externalPicker;
    private int wallpaperPicker;
    private boolean wallpaperPickerPaused;
    private boolean wallpaperChanging;
    private boolean restoringFromPhoto;
    private boolean choosingWallpaperBackup;
    private int backupWallpaperId;
    private WallpaperRecovery wallpaperRecovery;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            pendingDocument = saved.getParcelable("pendingDocument");
            wallpaperPicker = saved.getInt("wallpaperPicker");
            wallpaperPickerPaused = wallpaperChanging = externalPicker = wallpaperPicker != 0;
            if (saved.getBoolean("choosingWallpaperBackup")) {
                choosingWallpaperBackup = externalPicker = true;
                restoringFromPhoto = saved.getBoolean("restoringFromPhoto");
                backupWallpaperId = saved.getInt("backupWallpaperId");
            }
        }
        active = new WeakReference<>(this);
        store = new ModelStore(this);
        wallpaperRecovery = new WallpaperRecovery(this);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(250, 246, 249));
        frame.setOnApplyWindowInsetsListener((view, all) -> {
            android.graphics.Insets bars = immersivePreview ? android.graphics.Insets.NONE
                    : all.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return all;
        });
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(frame);
        frame.post(() -> {
            android.view.WindowInsetsController bars = frame.getWindowInsetsController();
            if (bars == null) return;
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(appearance, appearance);
        });
        attachPlayer();
    }

    private void attachPlayer() {
        frame.removeAllViews();
        try {
            if (WebView.getCurrentWebViewPackage() == null) {
                throw new IllegalStateException("Android WebView provider is unavailable");
            }
            WebView.setWebContentsDebuggingEnabled(true);
            player = new PetWeb(this, false, this::choose, this::togglePet, () -> {}, this::changePlacement,
                    this::setSettingsVisible, this::setImmersivePreview,
                    () -> showPlayerError("网页渲染进程已退出，请重试。若再次发生，请更新系统 WebView。"));
            frame.addView(player.view(), new FrameLayout.LayoutParams(-1, -1));
            player.onApplyWallpaper(this::applyWallpaper);
            player.load();
        } catch (RuntimeException | LinkageError error) {
            Log.e("BandoriLovePet", "WebView initialization failed", error);
            showPlayerError("系统 WebView 无法启动。请启用或更新 Android System WebView，然后重试。");
        }
    }

    private void showPlayerError(String message) {
        setSettingsVisible(false);
        setImmersivePreview(false);
        releasePlayer();
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextSize(16);
        detail.setGravity(Gravity.CENTER);
        status.addView(detail);
        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(view -> attachPlayer());
        status.addView(retry);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setImmersivePreview(boolean immersive) {
        immersivePreview = immersive;
        PetService.updatePreviewVisibility();
        if (frame != null) frame.requestApplyInsets();
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        if (immersive) {
            controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        } else {
            controller.show(WindowInsets.Type.systemBars());
        }
    }

    private void releasePlayer() {
        if (scenePicker != null) {
            scenePicker.onReceiveValue(null);
            scenePicker = null;
        }
        if (player == null) return;
        frame.removeView(player.view());
        player.destroy();
        player = null;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        setImmersivePreview(immersivePreview);
        PetService.updatePreviewVisibility();
        if (player != null) player.status();
        updateWallpaperStatus();
        updatePermissionStatus();
        if (wallpaperPicker != 0 && wallpaperPickerPaused) {
            int request = wallpaperPicker;
            // A few system pickers return to the app without delivering an activity result.
            frame.postDelayed(() -> finishWallpaperPicker(request), 250);
        }
    }

    @Override protected void onPause() {
        if (wallpaperPicker != 0) wallpaperPickerPaused = true;
        resumed = false;
        PetService.updatePreviewVisibility();
        super.onPause();
    }

    static boolean showingImmersivePreview() {
        MainActivity activity = active.get();
        return activity != null && activity.resumed && activity.immersivePreview;
    }

    @Override protected void onDestroy() {
        if (active.get() == this) active.clear();
        if (settingsVisible || awaitingUsageAccess || externalPicker) PetService.suspendForSettings(false);
        imports.shutdown();
        releasePlayer();
        super.onDestroy();
    }

    private void choose() {
        if (importing || player == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        externalPicker = true;
        setSettingsVisible(settingsVisible);
        if (launchPicker(intent, ARCHIVE)) return;
        externalPicker = false;
        setSettingsVisible(settingsVisible);
        player.notice("系统未提供模型文件选择器");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("pendingDocument", pendingDocument);
        state.putInt("wallpaperPicker", wallpaperPicker);
        state.putBoolean("choosingWallpaperBackup", choosingWallpaperBackup);
        state.putBoolean("restoringFromPhoto", restoringFromPhoto);
        state.putInt("backupWallpaperId", backupWallpaperId);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request >= WALLPAPER_PREVIEW && request <= WALLPAPER_RESTORE_SETTINGS) {
            finishWallpaperPicker(request);
            return;
        }
        if (request == WALLPAPER_BACKUP) {
            choosingWallpaperBackup = externalPicker = false;
            setSettingsVisible(settingsVisible);
            if (result == RESULT_OK && data != null && data.getData() != null) rememberWallpaperPhoto(data.getData());
            else updateWallpaperStatus();
            return;
        }
        if (request == SCENE_FILE || request == ARCHIVE || request == FOLDER) {
            externalPicker = false;
            setSettingsVisible(settingsVisible);
        }
        if (request == SCENE_FILE) {
            if (scenePicker != null) {
                scenePicker.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null
                        ? new Uri[] {data.getData()} : null);
                scenePicker = null;
            }
            return;
        }
        if (request == OVERLAY) {
            if (Settings.canDrawOverlays(this)) launchPet();
            return;
        }
        if (request == USAGE_ACCESS) {
            if (HomeVisibility.hasUsageAccess(this)) PetService.setHomeOnly(this, true);
            awaitingUsageAccess = false;
            setSettingsVisible(settingsVisible);
            if (player != null) player.status();
            return;
        }
        Uri selected = request == FOLDER ? pendingDocument : null;
        if (request == FOLDER) pendingDocument = null;
        if (result != RESULT_OK || data == null || data.getData() == null || importing || player == null) return;
        Uri uri = data.getData();
        if (request == ARCHIVE) {
            startImport(uri, null, false);
        } else if (request == FOLDER && selected != null) {
            startImport(uri, selected, true);
        }
    }

    private void requestFolder(Uri document, String message) {
        if (isDestroyed() || player == null) return;
        importing = false;
        player.progress(false);
        player.prompt("授权模型目录", message, "授权目录", () -> {
            pendingDocument = document;
            Intent folder = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            folder.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            folder.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialFolder(document));
            externalPicker = true;
            setSettingsVisible(settingsVisible);
            if (!launchPicker(folder, FOLDER)) {
                pendingDocument = null;
                externalPicker = false;
                setSettingsVisible(settingsVisible);
                player.notice("系统未提供目录选择器");
            }
        });
    }

    private Uri initialFolder(Uri document) {
        String authority = document.getAuthority();
        if ("com.android.externalstorage.documents".equals(authority)
                && DocumentsContract.isDocumentUri(this, document)) {
            String id = DocumentsContract.getDocumentId(document);
            int slash = id.lastIndexOf('/');
            if (slash >= 0) return DocumentsContract.buildDocumentUri(authority, id.substring(0, slash));
        }
        return document;
    }

    private void startImport(Uri uri, Uri selected, boolean persistFolder) {
        importing = true;
        player.progress(true);
        imports.submit(() -> {
            try {
                String entry = selected == null ? null : store.documentName(selected);
                if (selected != null) store.verifySelectedDocument(uri, entry, selected);
                store.importModel(uri, entry);
                if (persistFolder) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                        // Some document providers grant access only for this import.
                    }
                }
                runOnUiThread(() -> finishImport(null));
            } catch (ViewerPackage.WorkshopConfigRequired error) {
                if (selected == null) {
                    runOnUiThread(() -> requestFolder(uri, "这个 LPK 需要读取同目录的 config.json。"));
                } else runOnUiThread(() -> finishImport(error));
            } catch (Exception error) {
                runOnUiThread(() -> finishImport(error));
            }
        });
    }

    private void finishImport(Exception error) {
        if (isDestroyed()) return;
        importing = false;
        if (player == null) return;
        if (error == null) {
            player.refresh();
            PetService.refresh();
            PetWallpaperService.refresh();
        } else {
            player.progress(false);
            player.prompt("导入模型", error instanceof java.io.IOException ? error.getMessage() : "模型配置无效，无法导入。", null, null);
            android.util.Log.e("BandoriLove", "Model import", error);
        }
    }

    void updateSavedModel(int request, String id, int costume, boolean remove) {
        if (player == null || isDestroyed()) return;
        PetWeb requester = player;
        if (importing) {
            requester.modelLibraryResult(request, "请等待模型导入完成。");
            return;
        }
        importing = true;
        imports.submit(() -> {
            String failure = "";
            try {
                if (remove) store.removeSaved(id);
                else store.useSaved(id, costume);
            } catch (Exception error) {
                failure = error instanceof java.io.IOException ? error.getMessage() : "无法更新模型记录。";
                Log.w("BandoriLovePet", "Saved model unavailable", error);
            }
            String result = failure;
            runOnUiThread(() -> {
                importing = false;
                if (isDestroyed() || player != requester) return;
                requester.modelLibraryResult(request, result);
                if (!result.isEmpty()) return;
                if (store.active() == null) stopService(new Intent(this, PetService.class));
                PetService.refresh();
                PetWallpaperService.refresh();
            });
        });
    }

    void captureWallpaperPreview(int request) {
        PetWeb requester = player;
        if (!resumed || requester == null) return;
        WebView web = requester.view();
        int width = web.getWidth(), height = web.getHeight();
        if (width <= 0 || height <= 0) {
            requester.wallpaperPreviewResult(request, "");
            return;
        }
        int[] origin = new int[2];
        web.getLocationInWindow(origin);
        Rect area = new Rect(origin[0], origin[1], origin[0] + width, origin[1] + height);
        float scale = 360f / Math.max(width, height);
        Bitmap thumbnail = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), area, thumbnail, result -> {
                String preview = "";
                if (result == PixelCopy.SUCCESS) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    thumbnail.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, output);
                    preview = "data:image/webp;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
                }
                thumbnail.recycle();
                if (player == requester && !isDestroyed()) requester.wallpaperPreviewResult(request, preview);
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException error) {
            thumbnail.recycle();
            requester.wallpaperPreviewResult(request, "");
        }
    }

    private void togglePet() {
        if (PetService.showing()) {
            stopService(new Intent(this, PetService.class));
        } else if (!importing && store.active() != null) {
            if (Settings.canDrawOverlays(this)) launchPet();
            else player.prompt("桌宠权限", "显示桌宠需要允许 BandoriLovePet 显示在其他应用上层。", "前往设置", () -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                if (!launchPicker(intent, OVERLAY)) player.notice("系统未提供悬浮窗权限设置");
            });
        }
    }

    private void changePlacement(boolean homeOnly) {
        if (!homeOnly || HomeVisibility.hasUsageAccess(this)) {
            PetService.setHomeOnly(this, homeOnly);
            return;
        }
        try {
            startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), USAGE_ACCESS);
            awaitingUsageAccess = true;
        } catch (ActivityNotFoundException | SecurityException error) {
            player.prompt("使用情况访问", "系统未提供使用情况访问设置。", null, null);
        }
    }

    boolean chooseSceneFile(ValueCallback<Uri[]> callback) {
        if (scenePicker != null) scenePicker.onReceiveValue(null);
        scenePicker = callback;
        externalPicker = true;
        setSettingsVisible(settingsVisible);
        // The system picker accepts both photos and videos when no MIME type is set.
        if (launchPicker(new Intent(MediaStore.ACTION_PICK_IMAGES), SCENE_FILE)) return true;
        Intent fallback = new Intent(SYSTEM_PHOTO_PICKER);
        ResolveInfo systemPicker = getPackageManager().resolveActivity(fallback, PackageManager.MATCH_SYSTEM_ONLY);
        if (systemPicker != null && launchPicker(fallback.setClassName(
                systemPicker.activityInfo.packageName, systemPicker.activityInfo.name), SCENE_FILE)) return true;
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/png", "image/jpeg", "image/webp", "video/mp4"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (launchPicker(files, SCENE_FILE)) return true;
        scenePicker.onReceiveValue(null);
        scenePicker = null;
        externalPicker = false;
        setSettingsVisible(settingsVisible);
        player.notice("系统未提供媒体选择器");
        return true;
    }

    private void applyWallpaper() {
        if (externalPicker || wallpaperChanging || isFinishing()) {
            updateWallpaperStatus();
            return;
        }
        if (hasWallpaper(WallpaperManager.FLAG_SYSTEM)) {
            updateWallpaperStatus();
            exitWallpaper();
            return;
        }
        if (store.active() == null) {
            updateWallpaperStatus();
            wallpaperMessage("请先导入模型，再应用壁纸。");
            return;
        }
        WallpaperManager manager = WallpaperManager.getInstance(this);
        if (!manager.isWallpaperSupported()) {
            updateWallpaperStatus();
            wallpaperMessage("当前设备或用户配置不支持设置壁纸。");
            return;
        }
        if (!manager.isSetWallpaperAllowed()) {
            updateWallpaperStatus();
            wallpaperMessage("当前用户或工作资料的系统策略限制了壁纸设置。");
            return;
        }
        int currentId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        ComponentName current = wallpaperComponent(WallpaperManager.FLAG_SYSTEM);
        if (wallpaperRecovery.matches(currentId)
                || (current != null && wallpaperRecovery.rememberLive(currentId, current))) {
            showWallpaper();
        } else {
            updateWallpaperStatus();
            player.prompt("保留原壁纸", "请从相册选一次你当前的主屏幕壁纸原图。退出 BandoriLovePet 壁纸时，会用这张图恢复。",
                    "选择原壁纸", () -> chooseWallpaperPhoto(false));
        }
    }

    private void showWallpaper() {
        Intent preview = wallpaperPreview(new ComponentName(this, PetWallpaperService.class));
        if (launchWallpaperPicker(preview, WALLPAPER_PREVIEW)) return;
        openWallpaperList();
    }

    private void chooseWallpaperPhoto(boolean restoreNow) {
        restoringFromPhoto = restoreNow;
        backupWallpaperId = WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        choosingWallpaperBackup = externalPicker = true;
        setSettingsVisible(settingsVisible);
        if (launchPicker(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), WALLPAPER_BACKUP)) return;
        if (launchPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE), WALLPAPER_BACKUP)) return;
        choosingWallpaperBackup = externalPicker = false;
        setSettingsVisible(settingsVisible);
        wallpaperMessage("无法打开相册来选择原壁纸。");
    }

    private void rememberWallpaperPhoto(Uri uri) {
        int previousId = backupWallpaperId;
        boolean restoreNow = restoringFromPhoto;
        wallpaperChanging = true;
        updateWallpaperStatus();
        imports.submit(() -> {
            try {
                wallpaperRecovery.rememberPhoto(previousId, uri);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (restoreNow) restoreWallpaperPhoto();
                    else if (WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM) == previousId) showWallpaper();
                    else {
                        wallpaperChanging = false;
                        updateWallpaperStatus();
                        wallpaperMessage("选择图片期间主屏幕壁纸已更改。请重新选择当前使用的壁纸。");
                    }
                });
            } catch (IOException | SecurityException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    wallpaperChanging = false;
                    updateWallpaperStatus();
                    wallpaperMessage("无法保存原壁纸图片。请选择一张可读取、大小不超过 64 MB 的图片。");
                });
            }
        });
    }

    private Intent wallpaperPreview(ComponentName component) {
        return new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
    }

    private boolean launchWallpaperPicker(Intent intent, int request) {
        wallpaperPicker = request;
        wallpaperPickerPaused = false;
        wallpaperChanging = externalPicker = true;
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
        if (launchPicker(intent, request)) return true;
        wallpaperPicker = 0;
        wallpaperChanging = externalPicker = false;
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
        return false;
    }

    private void finishWallpaperPicker(int request) {
        if (isDestroyed() || wallpaperPicker != request) return;
        wallpaperPicker = 0;
        externalPicker = false;
        setSettingsVisible(settingsVisible);
        verifyWallpaper(request <= WALLPAPER_SETTINGS, request, 8);
    }

    void updatePermissionStatus() {
        if (player == null) return;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = notifications.getNotificationChannel(PetService.NOTIFICATION_CHANNEL);
        boolean canNotify = notifications.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        WallpaperManager manager = WallpaperManager.getInstance(this);
        String wallpaper = !manager.isWallpaperSupported() ? "unsupported" : !manager.isSetWallpaperAllowed() ? "restricted" : "system";
        player.permissionStatus(Settings.canDrawOverlays(this), HomeVisibility.hasUsageAccess(this), canNotify, wallpaper);
    }

    void openPermission(String permission) {
        Uri appUri = Uri.fromParts("package", getPackageName(), null);
        Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri);
        Intent screen;
        switch (permission) {
            case "overlay":
                screen = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri);
                break;
            case "usage":
                screen = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                break;
            case "notifications":
                screen = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                break;
            case "wallpaper":
                screen = appInfo;
                break;
            default:
                return;
        }
        if (openSettingsScreen(screen) || (screen != appInfo && openSettingsScreen(appInfo))) return;
        if (player != null) {
            player.prompt("权限配置", "无法自动打开权限页。请在系统设置中找到 BandoriLovePet 的应用信息。", null, null);
        }
    }

    private boolean openSettingsScreen(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("BandoriLovePet", "Cannot open settings " + intent.getAction(), error);
            return false;
        }
    }

    private void offerWallpaperList(String reason) {
        if (player == null) return;
        player.prompt("显示壁纸", reason + "\n" + WALLPAPER_PERMISSION_HINT
                + "\n也可从系统动态壁纸列表选择 BandoriLovePet，再选「主屏幕」。",
                "打开壁纸列表", this::openWallpaperList);
    }

    private void openWallpaperList() {
        if (!launchWallpaperPicker(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), WALLPAPER_LIST)) {
            offerWallpaperSettings("系统没有提供独立的动态壁纸列表。");
        }
    }

    private void offerWallpaperSettings(String reason) {
        if (player == null) return;
        player.prompt("壁纸未切换", reason + "\n请在系统壁纸设置中找到 BandoriLovePet，再设为主屏幕壁纸。",
                "打开系统壁纸设置", () -> {
                    if (!launchWallpaperPicker(new Intent(Intent.ACTION_SET_WALLPAPER), WALLPAPER_SETTINGS)) {
                        wallpaperMessage("无法自动打开系统壁纸设置。请从桌面长按空白处进入壁纸设置。");
                    }
                });
    }

    private void exitWallpaper() {
        ComponentName previous = wallpaperRecovery.liveComponent();
        if (previous != null) {
            if (!launchWallpaperPicker(wallpaperPreview(previous), WALLPAPER_RESTORE)) offerWallpaperRestore();
        } else if (wallpaperRecovery.hasPhoto()) {
            restoreWallpaperPhoto();
        } else {
            player.prompt("恢复原壁纸", "尚未保存原壁纸。请从相册选原图，便可恢复到主屏幕。",
                    "选择原壁纸", () -> chooseWallpaperPhoto(true));
        }
    }

    private void offerWallpaperRestore() {
        if (player == null) return;
        player.prompt("恢复原壁纸", "系统尚未恢复原壁纸。可从系统壁纸设置中重新选择原来的壁纸。",
                "打开系统壁纸设置", () -> {
                    if (!launchWallpaperPicker(new Intent(Intent.ACTION_SET_WALLPAPER), WALLPAPER_RESTORE_SETTINGS)) {
                        wallpaperMessage("无法自动打开系统壁纸设置。请从桌面长按空白处进入壁纸设置。");
                    }
                });
    }

    private void restoreWallpaperPhoto() {
        wallpaperChanging = true;
        updateWallpaperStatus();
        imports.submit(() -> {
            try {
                // Recheck at confirmation so an independently selected wallpaper is kept.
                if (hasWallpaper(WallpaperManager.FLAG_SYSTEM)) {
                    WallpaperManager manager = WallpaperManager.getInstance(this);
                    if (!manager.isSetWallpaperAllowed()) throw new SecurityException("Wallpaper setting restricted");
                    wallpaperRecovery.restorePhoto();
                }
                runOnUiThread(() -> verifyWallpaper(false, 0, 8));
            } catch (IOException | SecurityException error) {
                Log.w("BandoriLovePet", "Could not restore wallpaper", error);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    wallpaperChanging = false;
                    updateWallpaperStatus();
                    offerWallpaperRestore();
                });
            }
        });
    }

    private boolean launchPicker(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("BandoriLovePet", "Cannot open " + intent.getAction(), error);
            return false;
        }
    }

    private boolean hasWallpaper(int which) {
        return new ComponentName(this, PetWallpaperService.class).equals(wallpaperComponent(which));
    }

    private ComponentName wallpaperComponent(int which) {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            WallpaperInfo info = Build.VERSION.SDK_INT >= 34
                    ? manager.getWallpaperInfo(which)
                    : which == WallpaperManager.FLAG_SYSTEM ? manager.getWallpaperInfo() : null;
            return info == null ? null : info.getComponent();
        } catch (SecurityException error) {
            Log.w("BandoriLovePet", "Cannot verify home wallpaper", error);
            return null;
        }
    }

    void updateWallpaperStatus() {
        if (player != null) player.wallpaperStatus(hasWallpaper(WallpaperManager.FLAG_SYSTEM), wallpaperChanging);
    }

    private void verifyWallpaper(boolean wanted, int request, int retries) {
        if (isFinishing() || isDestroyed() || externalPicker) return;
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        if (applied != wanted && retries > 0) {
            frame.postDelayed(() -> verifyWallpaper(wanted, request, retries - 1), 400);
            return;
        }
        wallpaperChanging = false;
        updateWallpaperStatus();
        Log.i("BandoriLovePet", "Wallpaper verification: request=" + request + ", requested=" + wanted + ", active=" + applied);
        if (player == null) return;
        if (applied == wanted) {
            if (applied) {
                player.prompt("壁纸已应用", "BandoriLovePet 已设为主屏幕壁纸。", "查看桌面", () ->
                        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            } else player.notice("已恢复主屏幕壁纸");
        } else if (!wanted) {
            if (request == WALLPAPER_RESTORE_SETTINGS) wallpaperMessage("主屏幕仍在使用 BandoriLovePet。");
            else offerWallpaperRestore();
        } else {
            String reason = hasWallpaper(WallpaperManager.FLAG_LOCK)
                    ? "BandoriLovePet 目前只应用到了锁屏。"
                    : "系统返回后，主屏幕仍在使用原壁纸。";
            if (request == WALLPAPER_PREVIEW) offerWallpaperList(reason);
            else if (request == WALLPAPER_LIST) offerWallpaperSettings(reason + "\n" + WALLPAPER_PERMISSION_HINT);
            else wallpaperMessage(reason + "\n" + WALLPAPER_PERMISSION_HINT);
        }
    }

    private void wallpaperMessage(String message) {
        if (player != null) player.prompt("壁纸设置", message, null, null);
    }

    private void setSettingsVisible(boolean visible) {
        settingsVisible = visible;
        PetService.suspendForSettings(visible || awaitingUsageAccess || externalPicker);
    }

    @Override public void onBackPressed() {
        if (settingsVisible && player != null) {
            player.closeSettings();
            return;
        }
        if (immersivePreview && player != null) {
            player.view().evaluateJavascript("if(document.documentElement.classList.contains('scene-edit'))"
                    + "{document.querySelector('#scene-back').click();}"
                    + "else{controlsCollapsed=false;localStorage.setItem('controlsCollapsed','false');updateControlsVisibility();}", null);
            return;
        }
        super.onBackPressed();
    }

    private void launchPet() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedForPetNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForPetNotifications", true).apply();
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION);
            return;
        }
        startPet();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(request, permissions, grantResults);
        if (request == NOTIFICATION) updatePermissionStatus();
        if (request == NOTIFICATION && Settings.canDrawOverlays(this)) startPet();
    }

    private void startPet() {
        try {
            startForegroundService(new Intent(this, PetService.class));
        } catch (IllegalStateException | SecurityException error) {
            if (player != null) player.notice("请保持 BandoriLovePet 在前台并重试");
        }
    }

    static void petChanged() {
        MainActivity current = active.get();
        if (current != null && current.player != null) current.player.status();
    }

    static void receive(JSONObject command) {
        MainActivity current = active.get();
        if (current != null && current.player != null) {
            current.player.receive(command, !PetService.visible() && !PetWallpaperService.visible());
        }
    }
}
