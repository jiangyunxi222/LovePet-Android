package io.bandorilove.lovepet;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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
import android.view.animation.DecelerateInterpolator;
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
    private static final int USAGE_ACCESS = 5;
    private static final int SCENE_FILE = 6;
    private static final int WALLPAPER_PREVIEW = 7;
    private static final int WALLPAPER_LIST = 8;
    private static final int WALLPAPER_SETTINGS = 9;
    private static final int WALLPAPER_RESTORE = 10;
    private static final int WALLPAPER_RESTORE_SETTINGS = 11;
    private static final int WALLPAPER_RESTORE_PHOTO = 12;
    private static final long WALLPAPER_PICKER_LAUNCH_TIMEOUT_MS = 1500;
    private static final String WALLPAPER_PERMISSION_HINT = "若有「动态壁纸服务」权限，请开启。";
    private static final String SYSTEM_PHOTO_PICKER = "androidx.activity.result.contract.action.PICK_IMAGES";
    private ValueCallback<Uri[]> scenePicker;
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final WallpaperManager.OnColorsChangedListener wallpaperListener = (colors, which) -> {
        updateWallpaperStatus();
        updatePermissionStatus();
    };
    private PetWeb player;
    private ModelStore store;
    private FrameLayout frame;
    private Insets panelInsets = Insets.NONE;
    private FrameLayout startupCover;
    private final Runnable startupTimeout = () -> showPlayerError("应用加载超时，请重试。");
    private boolean importing;
    private boolean resumed;
    private boolean immersivePreview;
    private Uri pendingDocument;
    private boolean settingsVisible;
    private boolean awaitingUsageAccess;
    private boolean externalPicker;
    private int wallpaperPicker;
    private int wallpaperPickerAttempt;
    private boolean wallpaperPickerPaused;
    private boolean wallpaperChanging;
    private boolean wallpaperSuccessPending;
    private boolean wallpaperSuccessSent;
    private boolean wallpaperApplyAwaitingResult;
    private long wallpaperSuccessId;
    private int panelLoadGeneration;
    private boolean panelModelReady;
    private boolean choosingWallpaperPhoto;
    private int restoreWallpaperId;
    private WallpaperRecovery wallpaperRecovery;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DesktopRuntime.initialize(this);
        if (saved != null) {
            pendingDocument = saved.getParcelable("pendingDocument");
            wallpaperPicker = saved.getInt("wallpaperPicker");
            wallpaperPickerPaused = wallpaperChanging = externalPicker = wallpaperPicker != 0;
            wallpaperSuccessPending = saved.getBoolean("wallpaperSuccessPending");
            wallpaperApplyAwaitingResult = saved.getBoolean("wallpaperApplyAwaitingResult");
            wallpaperSuccessId = saved.getLong("wallpaperSuccessId");
            if (saved.getBoolean("choosingWallpaperPhoto")) {
                choosingWallpaperPhoto = externalPicker = true;
                restoreWallpaperId = saved.getInt("restoreWallpaperId");
            }
        }
        active = new WeakReference<>(this);
        store = new ModelStore(this);
        wallpaperRecovery = new WallpaperRecovery(this);
        frame = new FrameLayout(this);
        frame.setBackgroundResource(R.color.startup_background);
        frame.setOnApplyWindowInsetsListener((view, all) -> {
            Insets safe = all.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            if (!safe.equals(panelInsets)) {
                panelInsets = safe;
                updatePanelInsets();
            }
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
        FrameLayout cover = new FrameLayout(this);
        cover.setClickable(true);
        cover.setBackgroundResource(R.color.startup_background);
        float density = getResources().getDisplayMetrics().density;
        TextView loading = new TextView(this);
        loading.setText(store.active() == null ? R.string.loading_app : R.string.loading_model);
        loading.setTextColor(0xFFFFFFFF);
        loading.setTextSize(13);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xD13A2335);
        pill.setCornerRadius(12 * density);
        loading.setBackground(pill);
        int x = Math.round(12 * density), y = Math.round(6 * density);
        loading.setPadding(x, y, x, y);
        cover.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        startupCover = cover;
        Runnable open = () -> startPanel(cover);
        frame.getViewTreeObserver().registerFrameCommitCallback(() -> frame.post(open));
        frame.postDelayed(open, 150);
    }

    private void startPanel(FrameLayout cover) {
        if (startupCover != cover || player != null || isFinishing() || isDestroyed()) return;
        try {
            if (WebView.getCurrentWebViewPackage() == null) {
                throw new IllegalStateException("Android WebView provider is unavailable");
            }
            WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            player = new PetWeb(this, false, this::choose, this::togglePet, () -> {}, this::changePlacement,
                    this::setSettingsVisible, this::setImmersivePreview,
                    () -> showPlayerError("网页渲染进程已退出，请重试。若再次发生，请更新系统 WebView。"));
            frame.addView(player.view(), 0, new FrameLayout.LayoutParams(-1, -1));
            player.onApplyWallpaper(this::applyWallpaper);
            player.onApplyWallpaperScene(this::applyWallpaperScene);
            player.setPaused(!resumed);
            player.load();
            frame.postDelayed(startupTimeout, 30_000);
        } catch (RuntimeException | LinkageError error) {
            Log.e("BandoriLovePet", "WebView initialization failed", error);
            showPlayerError("系统 WebView 无法启动。请启用或更新 Android System WebView，然后重试。");
        }
    }

    void panelLoading(int generation) {
        panelLoadGeneration = generation;
        panelModelReady = false;
    }

    void panelFrameReady(int generation, boolean ready) {
        if (generation != panelLoadGeneration) return;
        panelModelReady = ready;
        FrameLayout cover = startupCover;
        if (cover == null) {
            if (player != null) player.panelFramePresented(generation, ready);
            showWallpaperSuccess();
            return;
        }
        frame.removeCallbacks(startupTimeout);
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(180).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            frame.removeView(cover);
            startupCover = null;
            if (generation == panelLoadGeneration && player != null) player.panelFramePresented(generation, panelModelReady);
            showWallpaperSuccess();
        }).start();
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
        updateDesktopVisibility();
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
        panelModelReady = false;
        wallpaperSuccessSent = false;
        frame.removeCallbacks(startupTimeout);
        if (startupCover != null) {
            startupCover.animate().cancel();
            frame.removeView(startupCover);
            startupCover = null;
        }
        if (scenePicker != null) {
            scenePicker.onReceiveValue(null);
            scenePicker = null;
        }
        if (player == null) return;
        frame.removeView(player.view());
        player.destroy();
        player = null;
    }

    @Override protected void onStart() {
        super.onStart();
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        setImmersivePreview(immersivePreview);
        DesktopRuntime.queryPet();
        if (player != null) {
            player.setPaused(false);
            player.status();
        }
        updateWallpaperStatus();
        updatePermissionStatus();
        showWallpaperSuccess();
        if (wallpaperPicker != 0 && wallpaperPickerPaused) {
            int request = wallpaperPicker;
            // A few system pickers return to the app without delivering an activity result.
            frame.postDelayed(() -> finishWallpaperPicker(request, false), 250);
        }
    }

    @Override protected void onPause() {
        if (wallpaperPicker != 0) wallpaperPickerPaused = true;
        resumed = false;
        if (player != null) player.setPaused(true);
        updateDesktopVisibility();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            updateWallpaperStatus();
            updatePermissionStatus();
            showWallpaperSuccess();
        }
    }

    @Override protected void onStop() {
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (active.get() == this) active.clear();
        updateDesktopVisibility();
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
        state.putBoolean("wallpaperSuccessPending", wallpaperSuccessPending);
        state.putBoolean("wallpaperApplyAwaitingResult", wallpaperApplyAwaitingResult);
        state.putLong("wallpaperSuccessId", wallpaperSuccessId);
        state.putBoolean("choosingWallpaperPhoto", choosingWallpaperPhoto);
        state.putInt("restoreWallpaperId", restoreWallpaperId);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request >= WALLPAPER_PREVIEW && request <= WALLPAPER_RESTORE_SETTINGS) {
            finishWallpaperPicker(request, result == RESULT_OK);
            return;
        }
        if (request == WALLPAPER_RESTORE_PHOTO) {
            if (!choosingWallpaperPhoto) return;
            choosingWallpaperPhoto = externalPicker = false;
            setSettingsVisible(settingsVisible);
            if (result == RESULT_OK && data != null && data.getData() != null) {
                restoreWallpaperPhoto(restoreWallpaperId, data.getData());
            } else updateWallpaperStatus();
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
            if (Settings.canDrawOverlays(this)) startPet();
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
                    runOnUiThread(() -> requestFolder(uri, "请选择 LPK 所在目录。"));
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
            DesktopRuntime.signal("model");
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
                DesktopRuntime.signal("model");
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
        DesktopRuntime.queryPet();
        if (DesktopRuntime.petShowing()) {
            stopService(new Intent(this, PetService.class));
        } else if (!importing && store.active() != null) {
            if (Settings.canDrawOverlays(this)) startPet();
            else player.prompt("悬浮窗权限", "请允许在其他应用上层显示。", "去设置", () -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                if (!launchPicker(intent, OVERLAY)) player.notice("无法打开悬浮窗设置");
            });
        }
    }

    private void changePlacement(boolean homeOnly) {
        if (!homeOnly || HomeVisibility.hasUsageAccess(this)) {
            PetService.setHomeOnly(this, homeOnly);
            return;
        }
        awaitingUsageAccess = openUsageAccess(true);
    }

    boolean chooseSceneFile(ValueCallback<Uri[]> callback, boolean video) {
        if (scenePicker != null) scenePicker.onReceiveValue(null);
        scenePicker = callback;
        externalPicker = true;
        setSettingsVisible(settingsVisible);
        String mediaType = video ? "video/*" : "image/*";
        if (launchPicker(new Intent(MediaStore.ACTION_PICK_IMAGES).setType(mediaType), SCENE_FILE)) return true;
        Intent fallback = new Intent(SYSTEM_PHOTO_PICKER).setType(mediaType);
        ResolveInfo systemPicker = getPackageManager().resolveActivity(fallback, PackageManager.MATCH_SYSTEM_ONLY);
        if (systemPicker != null && launchPicker(fallback.setClassName(
                systemPicker.activityInfo.packageName, systemPicker.activityInfo.name), SCENE_FILE)) return true;
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mediaType).addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, video ? new String[] {"video/mp4"} : new String[] {"image/png", "image/jpeg", "image/webp"})
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
        applyWallpaper(true);
    }

    private void applyWallpaperScene() {
        applyWallpaper(false);
    }

    private void applyWallpaper(boolean toggle) {
        if (externalPicker || wallpaperChanging || isFinishing()) {
            updateWallpaperStatus();
            return;
        }
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        if (toggle && applied) {
            updateWallpaperStatus();
            exitWallpaper();
            return;
        }
        if (store.active() == null) {
            updateWallpaperStatus();
            wallpaperMessage("请先导入模型。");
            return;
        }
        WallpaperManager manager = WallpaperManager.getInstance(this);
        if (!manager.isWallpaperSupported()) {
            updateWallpaperStatus();
            wallpaperMessage("系统不支持动态壁纸。");
            return;
        }
        wallpaperApplyAwaitingResult = true;
        wallpaperSuccessPending = wallpaperSuccessSent = false;
        if (applied) {
            // The editor has already published its scene to the independent desktop process.
            wallpaperChanging = false;
            updateWallpaperStatus();
            updatePermissionStatus();
            completeWallpaperApply();
            return;
        }
        wallpaperRecovery.rememberPrevious(manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),
                wallpaperComponent(WallpaperManager.FLAG_SYSTEM));
        // Try the direct confirmation on every brand. Fall back only when it actually fails.
        if (manager.isSetWallpaperAllowed()
                && launchWallpaperPicker(wallpaperPreview(new ComponentName(this, PetWallpaperService.class)),
                        WALLPAPER_PREVIEW)) return;
        openWallpaperList();
    }

    private void chooseWallpaperPhoto() {
        restoreWallpaperId = WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        choosingWallpaperPhoto = externalPicker = true;
        setSettingsVisible(settingsVisible);
        if (launchPicker(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), WALLPAPER_RESTORE_PHOTO)) return;
        if (launchPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE), WALLPAPER_RESTORE_PHOTO)) return;
        choosingWallpaperPhoto = externalPicker = false;
        setSettingsVisible(settingsVisible);
        offerWallpaperRestore();
    }

    private Intent wallpaperPreview(ComponentName component) {
        return new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
    }

    private boolean launchWallpaperPicker(Intent intent, int request) {
        int attempt = ++wallpaperPickerAttempt;
        wallpaperPicker = request;
        wallpaperPickerPaused = false;
        wallpaperChanging = externalPicker = true;
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
        if (launchPicker(intent, request)) {
            if (request <= WALLPAPER_SETTINGS) {
                frame.postDelayed(() -> {
                    if (wallpaperPickerAttempt == attempt && wallpaperPickerStayedInApp(request)) {
                        handleUnopenedWallpaperPicker(request);
                    }
                }, WALLPAPER_PICKER_LAUNCH_TIMEOUT_MS);
            }
            return true;
        }
        resetWallpaperPicker();
        return false;
    }

    private void resetWallpaperPicker() {
        wallpaperPicker = 0;
        wallpaperChanging = externalPicker = false;
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
    }

    private boolean wallpaperPickerStayedInApp(int request) {
        return wallpaperPicker == request && !wallpaperPickerPaused && resumed && hasWindowFocus()
                && !isFinishing() && !isDestroyed();
    }

    private void handleUnopenedWallpaperPicker(int request) {
        resetWallpaperPicker();
        if (request == WALLPAPER_PREVIEW) openWallpaperList();
        else if (request == WALLPAPER_LIST) offerWallpaperSettings();
        else wallpaperMessage(getString(R.string.wallpaper_help_manual_picker));
    }

    private void finishWallpaperPicker(int request, boolean confirmed) {
        if (isDestroyed() || wallpaperPicker != request) return;
        if (!confirmed && request <= WALLPAPER_SETTINGS && wallpaperPickerStayedInApp(request)) {
            handleUnopenedWallpaperPicker(request);
            return;
        }
        wallpaperPicker = 0;
        externalPicker = false;
        setSettingsVisible(settingsVisible);
        verifyWallpaper(request <= WALLPAPER_SETTINGS, request, confirmed, confirmed ? 8 : 1);
    }

    void updatePermissionStatus() {
        if (player == null) return;
        WallpaperManager manager = WallpaperManager.getInstance(this);
        String wallpaper = !manager.isWallpaperSupported() ? "unsupported"
                : !manager.isSetWallpaperAllowed() ? "restricted"
                // The OEM permission switch is separate from Android's wallpaper policy.
                : WallpaperSettingsActivity.usesXiaomiPermissions() ? "system"
                : hasWallpaper(WallpaperManager.FLAG_SYSTEM) || hasWallpaper(WallpaperManager.FLAG_LOCK)
                    ? "applied" : "unapplied";
        player.permissionStatus(Settings.canDrawOverlays(this), HomeVisibility.hasUsageAccess(this), wallpaper);
    }

    void openPermission(String permission) {
        if ("usage".equals(permission)) {
            openUsageAccess(false);
            return;
        }
        if ("wallpaper".equals(permission)) {
            if (!openSettingsScreen(new Intent(this, WallpaperSettingsActivity.class)) && player != null) {
                player.notice(getString(R.string.wallpaper_settings_unavailable));
            }
            return;
        }
        if (!"overlay".equals(permission)) return;
        Uri appUri = Uri.fromParts("package", getPackageName(), null);
        if (openSettingsScreen(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri))
                || openSettingsScreen(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))) return;
        if (player != null) {
            player.prompt("权限配置", "请在系统设置中授权。", null, null);
        }
    }

    private boolean openUsageAccess(boolean forPlacement) {
        Uri appUri = Uri.fromParts("package", getPackageName(), null);
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri);
        int flags = PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY;
        ComponentName appDetails = details.resolveActivity(getPackageManager());
        Intent scoped = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, appUri);
        Intent list = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        for (Intent intent : new Intent[] {scoped, list}) {
            ResolveInfo resolved = getPackageManager().resolveActivity(intent, flags);
            if (resolved == null || resolved.activityInfo == null) continue;
            ComponentName target = new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            if (target.equals(appDetails)) continue;
            intent.setComponent(target);
            if (forPlacement ? launchPicker(intent, USAGE_ACCESS) : openSettingsScreen(intent)) return true;
        }
        if (player != null) {
            player.prompt("使用情况访问",
                    "请在系统设置中搜索「使用情况访问」，选择 BandoriLovePet 并允许访问。",
                    "打开系统设置", () -> openSettingsScreen(new Intent(Settings.ACTION_SETTINGS)));
        }
        return false;
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

    private void openWallpaperList() {
        if (!launchWallpaperPicker(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), WALLPAPER_LIST)) {
            offerWallpaperSettings();
        }
    }

    private void offerWallpaperSettings() {
        if (player == null) return;
        player.prompt("设置主屏幕壁纸", "无法直接打开动态壁纸列表。请在系统壁纸中选择「"
                + getString(R.string.wallpaper_label) + "」。", getString(R.string.wallpaper_open_picker), () -> {
                    if (!launchWallpaperPicker(new Intent(Intent.ACTION_SET_WALLPAPER), WALLPAPER_SETTINGS)) {
                        wallpaperMessage(getString(R.string.wallpaper_help_manual_picker));
                    }
                });
    }

    private void exitWallpaper() {
        if (player == null) return;
        ComponentName previous = wallpaperRecovery.liveComponent();
        String alternative = "系统壁纸";
        Runnable restore = this::openWallpaperRestoreSettings;
        if (previous != null) {
            alternative = "原动态壁纸";
            restore = () -> {
                if (!launchWallpaperPicker(wallpaperPreview(previous), WALLPAPER_RESTORE)) offerWallpaperRestore();
            };
        } else if (wallpaperRecovery.hasPhoto()) {
            alternative = "使用原图";
            restore = () -> restoreWallpaperPhoto(
                    WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM), null);
        }
        player.prompt("恢复原壁纸", "请选择退出后要显示的壁纸。", "选择图片", this::chooseWallpaperPhoto,
                alternative, restore);
    }

    private void offerWallpaperRestore() {
        if (player == null) return;
        player.prompt("恢复原壁纸", "请在系统壁纸设置中选择原壁纸。", "去设置", this::openWallpaperRestoreSettings);
    }

    private void openWallpaperRestoreSettings() {
        if (!launchWallpaperPicker(new Intent(Intent.ACTION_SET_WALLPAPER), WALLPAPER_RESTORE_SETTINGS)) {
            wallpaperMessage("请长按桌面空白处更换壁纸。");
        }
    }

    private void restoreWallpaperPhoto(int expectedId, Uri selected) {
        wallpaperChanging = true;
        updateWallpaperStatus();
        imports.submit(() -> {
            try {
                WallpaperManager manager = WallpaperManager.getInstance(this);
                int screen = WallpaperManager.FLAG_SYSTEM;
                if (manager.getWallpaperId(screen) == expectedId && hasWallpaper(screen)) {
                    if (!manager.isSetWallpaperAllowed()) throw new SecurityException("Wallpaper setting restricted");
                    if (selected != null) wallpaperRecovery.rememberPhoto(expectedId, selected);
                    if (manager.getWallpaperId(screen) == expectedId && hasWallpaper(screen)) {
                        wallpaperRecovery.restorePhoto();
                    }
                }
                runOnUiThread(() -> verifyWallpaper(false, 0, true, 8));
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

    void wallpaperPageReady() {
        updatePanelInsets();
        updateWallpaperStatus();
        updatePermissionStatus();
        showWallpaperSuccess();
    }

    private void updatePanelInsets() {
        if (player != null && player.isPageReady()) player.panelInsets(panelInsets);
    }

    private void showWallpaperSuccess() {
        if (!wallpaperSuccessPending || wallpaperSuccessSent || !panelModelReady || startupCover != null || !resumed || !hasWindowFocus()
                || isFinishing() || isDestroyed() || player == null || !player.isPageReady()) return;
        wallpaperSuccessSent = true;
        player.showWallpaperSuccess(wallpaperSuccessId);
    }

    private void completeWallpaperApply() {
        if (!wallpaperApplyAwaitingResult) return;
        wallpaperApplyAwaitingResult = false;
        wallpaperSuccessPending = true;
        wallpaperSuccessSent = false;
        ++wallpaperSuccessId;
        showWallpaperSuccess();
    }

    void dismissWallpaperSuccess(long request, boolean viewDesktop) {
        if (request != wallpaperSuccessId || !wallpaperSuccessPending || !wallpaperSuccessSent) return;
        wallpaperSuccessPending = wallpaperSuccessSent = false;
        if (viewDesktop) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void verifyWallpaper(boolean wanted, int request, boolean confirmed, int retries) {
        if (isFinishing() || isDestroyed() || externalPicker) return;
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        if (applied != wanted && retries > 0) {
            frame.postDelayed(() -> verifyWallpaper(wanted, request, confirmed, retries - 1), 400);
            return;
        }
        wallpaperChanging = false;
        updateWallpaperStatus();
        updatePermissionStatus();
        Log.i("BandoriLovePet", "Wallpaper verification: request=" + request + ", requested=" + wanted + ", active=" + applied);
        if (applied == wanted) {
            if (applied) {
                completeWallpaperApply();
            } else if (player != null) player.notice("已恢复主屏幕壁纸");
            return;
        }
        if (wanted) wallpaperApplyAwaitingResult = false;
        if (player == null || !confirmed) return;
        if (!wanted) {
            if (request == WALLPAPER_RESTORE_SETTINGS) wallpaperMessage("原壁纸未恢复。");
            else offerWallpaperRestore();
        } else if (hasWallpaper(WallpaperManager.FLAG_LOCK)) {
            player.prompt("只设置了锁屏", "请再选择「主屏幕」。", "打开壁纸列表", this::openWallpaperList);
        } else if (!WallpaperManager.getInstance(this).isSetWallpaperAllowed()) {
            player.prompt("壁纸未应用", WALLPAPER_PERMISSION_HINT, "去设置", () -> openPermission("wallpaper"));
        } else {
            wallpaperMessage("壁纸未应用，请重试。");
        }
    }

    private void wallpaperMessage(String message) {
        if (player != null) player.prompt("壁纸设置", message, null, null);
    }

    private void setSettingsVisible(boolean visible) {
        settingsVisible = visible;
        updateDesktopVisibility();
    }

    private void updateDesktopVisibility() {
        DesktopRuntime.flag("settings", active.get() == this && (resumed && (immersivePreview || settingsVisible)
                || awaitingUsageAccess || externalPicker));
    }

    @Override public void onBackPressed() {
        if (settingsVisible && player != null) {
            player.closeSettings();
            return;
        }
        if (immersivePreview && player != null) {
            player.view().evaluateJavascript("if(document.documentElement.classList.contains('scene-edit'))"
                    + "{document.querySelector('#scene-back').click();}"
                    + "else{setControlsCollapsed(false);}", null);
            return;
        }
        super.onBackPressed();
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

    static void receive(JSONObject command, boolean sound) {
        MainActivity current = active.get();
        if (current != null && current.player != null) {
            current.player.receive(command, sound && current.resumed);
        }
    }
}
