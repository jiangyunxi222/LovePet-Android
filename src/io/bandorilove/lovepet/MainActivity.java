package io.bandorilove.lovepet;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Base64;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
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
import java.io.File;
import java.io.IOException;
import java.util.Set;
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
    private static final int WALLPAPER_PERMISSION_HELP = 13;
    private static final long WALLPAPER_PICKER_LAUNCH_TIMEOUT_MS = 1500;
    private static final long WALLPAPER_COVER_ENTER_MS = 240;
    private static final long WALLPAPER_COVER_HOLD_MS = 500;
    private static final long WALLPAPER_COVER_EXIT_MS = 360;
    private static final long WALLPAPER_RESULT_SETTLE_MS = 150;
    private static final String SYSTEM_PHOTO_PICKER = "androidx.activity.result.contract.action.PICK_IMAGES";
    private enum WallpaperTransition { IDLE, ENTERING, COVERED, RELOADING, HOLDING, REVEALING, SETTLING }
    private ValueCallback<Uri[]> scenePicker;
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private static DesktopTile.PanelAction tileAction;
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final WallpaperManager.OnColorsChangedListener wallpaperListener = (colors, which) -> {
        updateWallpaperStatus();
        updatePermissionStatus();
    };
    private PetWeb player;
    private ModelStore store;
    private FrameLayout frame;
    private Insets panelInsets = Insets.NONE;
    private View panelCover;
    private boolean panelCoverFading;
    private boolean startupPanelCover;
    private final Runnable startupTimeout = () -> showPlayerError("应用加载超时，请重试。");
    private final Runnable advanceWallpaperTransition = this::presentWallpaperResult;
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
    private boolean wallpaperPreviewShown;
    private long wallpaperPickerStarted;
    private boolean wallpaperChanging;
    private Boolean wallpaperObserved;
    private boolean wallpaperApplySuccessPending;
    private volatile Boolean wallpaperDirectAllowed;
    private boolean wallpaperDirectNeedsCheck = true;
    private int wallpaperApplyInitialId = -1;
    private WallpaperTransition wallpaperTransition = WallpaperTransition.IDLE;
    private long wallpaperReloadRequest;
    private long wallpaperTransitionUntil;
    private boolean wallpaperApplyAwaitingResult;
    private int panelLoadGeneration;
    private boolean panelFrameResolved;
    private boolean panelModelReady;
    private boolean choosingWallpaperPhoto;
    private int restoreWallpaperId;
    private WallpaperRecovery wallpaperRecovery;
    private WallpaperResultDialog wallpaperResultDialog;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DesktopRuntime.initialize(this);
        if (saved != null) {
            pendingDocument = saved.getParcelable("pendingDocument");
            wallpaperPicker = saved.getInt("wallpaperPicker");
            wallpaperPickerPaused = wallpaperChanging = externalPicker = wallpaperPicker != 0;
            wallpaperPreviewShown = saved.getBoolean("wallpaperPreviewShown");
            wallpaperPickerStarted = saved.getLong("wallpaperPickerStarted");
            wallpaperApplySuccessPending = saved.getBoolean("wallpaperSuccessPending");
            if (saved.containsKey("wallpaperObserved")) wallpaperObserved = saved.getBoolean("wallpaperObserved");
            wallpaperApplyAwaitingResult = saved.getBoolean("wallpaperApplyAwaitingResult");
            wallpaperApplyInitialId = saved.getInt("wallpaperApplyInitialId", -1);
            if (saved.getBoolean("choosingWallpaperPhoto")) {
                choosingWallpaperPhoto = externalPicker = true;
                restoreWallpaperId = saved.getInt("restoreWallpaperId");
            }
        }
        active = new WeakReference<>(this);
        store = new ModelStore(this);
        wallpaperRecovery = new WallpaperRecovery(this);
        restoreWallpaperSnapshot();
        if (getPreferences(MODE_PRIVATE).contains("wallpaperDirectAllowed")) {
            wallpaperDirectAllowed = getPreferences(MODE_PRIVATE).getBoolean("wallpaperDirectAllowed", false);
        }
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
        View cover = showPanelCover();
        startupPanelCover = true;
        Runnable open = () -> startPanel(cover);
        frame.getViewTreeObserver().registerFrameCommitCallback(() -> frame.post(open));
        frame.postDelayed(open, 150);
    }

    private View showPanelCover() {
        panelCover = getLayoutInflater().inflate(R.layout.wallpaper_recovery_cover, frame, false);
        panelCover.setClickable(true);
        frame.addView(panelCover);
        return panelCover;
    }

    private void startPanel(View cover) {
        if (panelCover != cover || player != null || isFinishing() || isDestroyed()) return;
        try {
            if (WebView.getCurrentWebViewPackage() == null) {
                throw new IllegalStateException("Android WebView provider is unavailable");
            }
            WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            player = new PetWeb(this, false, this::choose, this::togglePet, () -> {}, this::changePlacement,
                    this::setSettingsVisible, this::setImmersivePreview,
                    () -> showPlayerError("网页渲染进程已退出，请重试。若再次发生，请更新系统 WebView。"));
            frame.addView(player.view(), 0, new FrameLayout.LayoutParams(-1, -1));
            player.onChangeWallpaper(this::changeWallpaper);
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
        panelFrameResolved = false;
        panelModelReady = false;
        if (panelCoverFading && wallpaperTransition == WallpaperTransition.IDLE) {
            panelCover.animate().cancel();
            panelCover.setAlpha(1f);
            panelCoverFading = false;
        }
    }

    void panelFrameReady(int generation, boolean ready) {
        if (generation != panelLoadGeneration) return;
        panelFrameResolved = true;
        panelModelReady = ready;
        if (ready && wallpaperTransition == WallpaperTransition.HOLDING) {
            wallpaperTransitionUntil = SystemClock.uptimeMillis() + WALLPAPER_COVER_HOLD_MS;
        }
        presentWallpaperResult();
        fadeStartupCover();
        runTileAction();
    }

    private void fadeStartupCover() {
        if (!startupPanelCover || panelCoverFading || !panelFrameResolved || !resumed
                || wallpaperChanging || wallpaperTransition != WallpaperTransition.IDLE) return;
        fadePanelCover(180, this::runTileAction);
    }

    private void fadePanelCover(long duration, Runnable finished) {
        View cover = panelCover;
        panelCoverFading = true;
        frame.removeCallbacks(startupTimeout);
        cover.animate().alpha(0f).setDuration(duration).withLayer()
                .setInterpolator(startupPanelCover && wallpaperTransition == WallpaperTransition.IDLE
                        ? new DecelerateInterpolator() : new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    frame.removeView(cover);
                    panelCover = null;
                    panelCoverFading = false;
                    startupPanelCover = false;
                    finished.run();
                }).start();
    }

    private void showPlayerError(String message) {
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.PANEL_RENDER, message);
        RuntimeDiagnostics.processEvent(this, message);
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
        Button diagnostics = new Button(this);
        diagnostics.setText("运行诊断");
        diagnostics.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("运行诊断")
                .setMessage(RuntimeDiagnostics.report(this)).setPositiveButton("知道了", null).show());
        status.addView(diagnostics);
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
        panelFrameResolved = false;
        panelModelReady = false;
        wallpaperTransition = WallpaperTransition.IDLE;
        startupPanelCover = false;
        panelCoverFading = false;
        frame.removeCallbacks(startupTimeout);
        frame.removeCallbacks(advanceWallpaperTransition);
        if (panelCover != null) {
            panelCover.animate().cancel();
            frame.removeView(panelCover);
            panelCover = null;
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
        if (wallpaperPicker != 0 && wallpaperPickerPaused) {
            boolean wanted = wallpaperPicker <= WALLPAPER_SETTINGS;
            boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
            if (wanted ? wallpaperApplySucceeded(applied, false) : !applied) beginWallpaperTransition(true);
        }
        setImmersivePreview(immersivePreview);
        DesktopRuntime.queryPet();
        if (player != null) {
            player.setPaused(false);
            player.status();
        }
        updateWallpaperStatus();
        updatePermissionStatus();
        fadeStartupCover();
        runTileAction();
        if (wallpaperPicker != 0 && wallpaperPickerPaused) {
            int request = wallpaperPicker;
            int attempt = wallpaperPickerAttempt;
            // A few system pickers return to the app without delivering an activity result.
            frame.postDelayed(() -> {
                if (attempt == wallpaperPickerAttempt) finishWallpaperPicker(request, false);
            }, 250);
        }
    }

    @Override protected void onPause() {
        if (wallpaperPicker != 0) wallpaperPickerPaused = true;
        else if (!wallpaperChanging && !externalPicker) wallpaperDirectNeedsCheck = true;
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
            if (wallpaperPickerStayedInApp(wallpaperPicker)
                    && SystemClock.uptimeMillis() - wallpaperPickerStarted >= WALLPAPER_PICKER_LAUNCH_TIMEOUT_MS) {
                handleUnopenedWallpaperPicker(wallpaperPicker);
            }
            runTileAction();
        }
    }

    @Override protected void onStop() {
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (wallpaperResultDialog != null) {
            wallpaperResultDialog.setOnDismissListener(null);
            wallpaperResultDialog.dismiss();
            wallpaperResultDialog = null;
        }
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
        state.putBoolean("wallpaperPreviewShown", wallpaperPreviewShown);
        state.putLong("wallpaperPickerStarted", wallpaperPickerStarted);
        state.putBoolean("wallpaperSuccessPending", wallpaperApplySuccessPending);
        if (wallpaperObserved != null) state.putBoolean("wallpaperObserved", wallpaperObserved);
        state.putBoolean("wallpaperApplyAwaitingResult", wallpaperApplyAwaitingResult);
        state.putInt("wallpaperApplyInitialId", wallpaperApplyInitialId);
        state.putBoolean("choosingWallpaperPhoto", choosingWallpaperPhoto);
        state.putInt("restoreWallpaperId", restoreWallpaperId);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == WALLPAPER_PERMISSION_HELP) {
            if (result == RESULT_OK) changeWallpaper(false, true);
            else discardPreparedWallpaper();
            return;
        }
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
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.MODEL_LIBRARY,
                error == null ? "模型导入成功" : "模型导入失败（" + error.getClass().getSimpleName() + "）");
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
            RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.MODEL_LIBRARY,
                    (remove ? "删除模型记录" : "切换已导入模型") + (result.isEmpty() ? "成功" : "失败"));
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

    private void changeWallpaper(boolean exit) {
        changeWallpaper(exit, false);
    }

    String captureWallpaperConfig(String model, int costume, String outfit, String scene, String settings) {
        synchronized (store) { return WallpaperSnapshot.captureEditor(this, store, model, costume, outfit, scene, settings); }
    }

    boolean updateWallpaperPresetIndex(String index) {
        synchronized (store) {
            try {
                JSONObject nextIndex = new JSONObject(index);
                JSONObject previousIndex = WallpaperSnapshot.presetIndex(this);
                Set<String> next = WallpaperSnapshot.modelIds(nextIndex.getJSONArray("models"));
                Set<String> previous = WallpaperSnapshot.modelIds(previousIndex.optJSONArray("models"));
                if (!DesktopRuntime.write(this, WallpaperSnapshot.PRESET_INDEX_KEY, index)) return false;
                WallpaperSnapshot.dropRemovedPresetSnapshots(this, previousIndex, nextIndex);
                for (String id : previous) if (!next.contains(id) && !imports.isShutdown()) {
                    imports.submit(() -> store.releaseUnusedWallpaperModel(id));
                }
                return true;
            } catch (org.json.JSONException error) { return false; }
        }
    }

    void removeWallpaperMedia(String id) {
        synchronized (store) {
            if (WallpaperSnapshot.keepsMedia(this, id)) return;
            File file = WallpaperMedia.find(this, id);
            if (file != null) file.delete();
        }
    }

    boolean prepareWallpaper(String configuration, String savedPreset) {
        synchronized (store) {
            WallpaperSnapshot previous = WallpaperSnapshot.pending(this);
            int wallpaperId = WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            if (!WallpaperSnapshot.stage(this, store, configuration, savedPreset, wallpaperId)) return false;
            if (previous != null) runOnUiThread(() -> releaseWallpaperResources(previous));
            return true;
        }
    }

    private void restoreWallpaperSnapshot() {
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        WallpaperSnapshot pending = WallpaperSnapshot.pending(this);
        if (wallpaperPicker == 0 && !wallpaperApplyAwaitingResult && !DesktopTile.Wallpaper.isQuietChange() && pending != null) {
            int id = WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            if (applied && id != pending.previousWallpaperId()) applyPreparedWallpaper();
            else WallpaperSnapshot.clearPending(this);
        }
        if (applied) keepLegacyWallpaper();
    }

    private void keepLegacyWallpaper() {
        if (WallpaperSnapshot.applied(this) != null) return;
        WallpaperSnapshot legacy = WallpaperSnapshot.legacy(this, store);
        if (legacy != null && legacy.apply(this)) DesktopRuntime.signal(WallpaperSnapshot.APPLIED_KEY);
    }

    private boolean applyPreparedWallpaper() {
        WallpaperSnapshot previous;
        synchronized (store) {
            WallpaperSnapshot pending = WallpaperSnapshot.pending(this);
            if (pending == null) return true;
            previous = WallpaperSnapshot.applied(this);
            if (!pending.apply(this)) return false;
            WallpaperSnapshot.clearPending(this);
        }
        DesktopRuntime.signal(WallpaperSnapshot.APPLIED_KEY);
        if (player != null) player.wallpaperPresetApplied();
        releaseWallpaperResources(previous);
        return true;
    }

    private void discardPreparedWallpaper() {
        WallpaperSnapshot pending;
        synchronized (store) {
            pending = WallpaperSnapshot.pending(this);
            if (pending == null) return;
            WallpaperSnapshot.clearPending(this);
        }
        releaseWallpaperResources(pending);
    }

    private void releaseWallpaperResources(WallpaperSnapshot previous) {
        if (previous == null || frame == null) return;
        frame.postDelayed(() -> {
            if (imports.isShutdown()) return;
            imports.submit(() -> {
                synchronized (store) {
                    store.releaseUnusedWallpaperModel(previous.model());
                    for (String id : previous.media()) {
                        if (WallpaperSnapshot.keepsMedia(this, id)) continue;
                        File media = WallpaperMedia.find(this, id);
                        if (media != null) media.delete();
                    }
                }
            });
        }, 5_000);
    }

    private void changeWallpaper(boolean exit, boolean preferList) {
        if (externalPicker || wallpaperChanging || isFinishing()) {
            updateWallpaperStatus();
            return;
        }
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        if (exit) {
            updateWallpaperStatus();
            if (applied) exitWallpaper();
            return;
        }
        if (WallpaperSnapshot.pending(this) == null) {
            updateWallpaperStatus();
            if (preferList && player != null && store.active() != null) player.runWallpaperTileAction(false);
            else wallpaperMessage("请先导入模型。");
            return;
        }
        WallpaperManager manager = WallpaperManager.getInstance(this);
        wallpaperApplyAwaitingResult = true;
        wallpaperApplySuccessPending = false;
        wallpaperApplyInitialId = applied ? manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) : -1;
        boolean supported = manager.isWallpaperSupported();
        boolean allowed = supported && manager.isSetWallpaperAllowed();
        if (applied && allowed && Boolean.TRUE.equals(wallpaperDirectAllowed) && !wallpaperDirectNeedsCheck) {
            wallpaperChanging = false;
            updateWallpaperStatus();
            updatePermissionStatus();
            completeWallpaperApply();
            return;
        }
        if (supported && !applied) {
            rememberWallpaperBeforeApply(manager);
        }
        if (!allowed) {
            fallbackFromWallpaperPreview();
        } else if (preferList || (Boolean.FALSE.equals(wallpaperDirectAllowed) && !wallpaperDirectNeedsCheck)) {
            openWallpaperList();
        } else if (!launchWallpaperPicker(wallpaperPreview(new ComponentName(this, PetWallpaperService.class)),
                WALLPAPER_PREVIEW)) {
            fallbackFromWallpaperPreview();
        }
    }

    private void rememberWallpaperBeforeApply(WallpaperManager manager) {
        int screen = WallpaperManager.FLAG_SYSTEM;
        ComponentName previous = wallpaperComponent(screen);
        if (!new ComponentName(this, PetWallpaperService.class).equals(previous)) {
            wallpaperRecovery.rememberPrevious(manager.getWallpaperId(screen), previous);
        }
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
        wallpaperPreviewShown = false;
        wallpaperPickerStarted = SystemClock.uptimeMillis();
        wallpaperChanging = externalPicker = true;
        if (request >= WALLPAPER_RESTORE) {
            wallpaperRecovery.beginExit(WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM));
        }
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
        if (launchPicker(intent, request)) {
            frame.postDelayed(() -> {
                if (wallpaperPickerAttempt == attempt && wallpaperPickerStayedInApp(request)) {
                    handleUnopenedWallpaperPicker(request);
                }
            }, WALLPAPER_PICKER_LAUNCH_TIMEOUT_MS);
            return true;
        }
        resetWallpaperPicker();
        return false;
    }

    private void resetWallpaperPicker() {
        if (wallpaperPicker >= WALLPAPER_RESTORE) wallpaperRecovery.cancelExit();
        wallpaperPicker = 0;
        wallpaperChanging = externalPicker = false;
        setSettingsVisible(settingsVisible);
        updateWallpaperStatus();
    }

    private boolean wallpaperPickerStayedInApp(int request) {
        return request >= WALLPAPER_PREVIEW && request <= WALLPAPER_RESTORE_SETTINGS
                && wallpaperPicker == request && !wallpaperPickerPaused && resumed && hasWindowFocus()
                && !isFinishing() && !isDestroyed();
    }

    private void handleUnopenedWallpaperPicker(int request) {
        resetWallpaperPicker();
        if (request == WALLPAPER_PREVIEW) fallbackFromWallpaperPreview();
        else if (request == WALLPAPER_RESTORE) offerWallpaperRestore();
        else if (request == WALLPAPER_RESTORE_SETTINGS) wallpaperMessage("请长按桌面空白处更换壁纸。");
        else offerWallpaperPermission();
    }

    static void wallpaperPreviewVisible() {
        MainActivity current = active.get();
        if (current != null && current.wallpaperPicker == WALLPAPER_PREVIEW) current.wallpaperPreviewShown = true;
    }

    private void finishWallpaperPicker(int request, boolean confirmed) {
        if (isDestroyed() || wallpaperPicker != request) return;
        boolean wanted = request <= WALLPAPER_SETTINGS;
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        boolean succeeded = wanted ? wallpaperApplySucceeded(applied, confirmed) : !applied;
        if (!confirmed && wanted && !succeeded) {
            boolean failedToOpen = request == WALLPAPER_PREVIEW
                    ? !wallpaperPreviewShown || !WallpaperManager.getInstance(this).isSetWallpaperAllowed()
                    : !wallpaperPickerPaused;
            if (failedToOpen) {
                handleUnopenedWallpaperPicker(request);
                return;
            }
        }
        if (confirmed || succeeded) beginWallpaperTransition(true);
        wallpaperPicker = 0;
        externalPicker = false;
        setSettingsVisible(settingsVisible);
        verifyWallpaper(wanted, request, confirmed, confirmed ? 8 : 1);
    }

    private boolean wallpaperApplySucceeded(boolean applied, boolean confirmed) {
        return applied && (confirmed || wallpaperApplyInitialId < 0
                || WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM) != wallpaperApplyInitialId);
    }

    void updatePermissionStatus() {
        if (player == null) return;
        player.permissionStatus(Settings.canDrawOverlays(this), HomeVisibility.hasUsageAccess(this), wallpaperPermissionState());
    }

    String wallpaperPermissionState() {
        WallpaperManager manager = WallpaperManager.getInstance(this);
        Boolean allowed = wallpaperDirectAllowed;
        if (Boolean.FALSE.equals(allowed)) return "disabled";
        if (!manager.isWallpaperSupported()) return "unsupported";
        if (!manager.isSetWallpaperAllowed()) return "disabled";
        return Boolean.TRUE.equals(allowed) ? "enabled" : "system";
    }

    private void recordWallpaperDirectResult(boolean allowed) {
        wallpaperDirectNeedsCheck = false;
        if (Boolean.valueOf(allowed).equals(wallpaperDirectAllowed)) return;
        wallpaperDirectAllowed = allowed;
        getPreferences(MODE_PRIVATE).edit().putBoolean("wallpaperDirectAllowed", allowed).apply();
    }

    void openPermission(String permission) {
        if ("usage".equals(permission)) {
            openUsageAccess(false);
            return;
        }
        if ("wallpaper".equals(permission)) {
            if (!launchPicker(new Intent(this, WallpaperSettingsActivity.class), WALLPAPER_PERMISSION_HELP)
                    && player != null) {
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
        Intent usage = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        Intent details = new Intent(usage).setData(Uri.fromParts("package", getPackageName(), null));
        int flags = PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY;
        for (Intent page : new Intent[] {details, usage}) {
            ResolveInfo resolved = getPackageManager().resolveActivity(page, flags);
            if (resolved == null || resolved.activityInfo == null) continue;
            if (page == details && !"com.android.settings.Settings$AppUsageAccessSettingsActivity"
                    .equals(resolved.activityInfo.name)) continue;
            page.setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            if (forPlacement ? launchPicker(page, USAGE_ACCESS) : openSettingsScreen(page)) return true;
        }
        if (player != null) {
            player.prompt("使用情况访问",
                    "请在系统设置中搜索「使用情况访问」，选择 " + getString(R.string.app_name) + " 并允许访问。",
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
        wallpaperApplyAwaitingResult = true;
        if (!launchWallpaperPicker(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), WALLPAPER_LIST)) {
            offerWallpaperPermission();
        } else {
            RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, "已打开系统动态壁纸列表");
        }
    }

    private void fallbackFromWallpaperPreview() {
        recordWallpaperDirectResult(false);
        updatePermissionStatus();
        openWallpaperList();
    }

    private void offerWallpaperPermission() {
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, "系统未能应用动态壁纸，已提示检查权限");
        wallpaperApplyAwaitingResult = false;
        updatePermissionStatus();
        if (player != null) {
            player.prompt("动态壁纸权限配置", "系统未能应用动态壁纸。请在权限配置中检查动态壁纸服务。"
                    + "若系统提供「动态壁纸服务」开关，请开启后重试。",
                    "权限配置", () -> openPermission("wallpaper"));
        }
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
        if (wallpaperChanging || isFinishing() || isDestroyed()) return;
        wallpaperChanging = true;
        beginWallpaperTransition(selected != null);
        wallpaperRecovery.beginExit(expectedId);
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
                RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, "原图恢复失败");
                runOnUiThread(() -> {
                    wallpaperRecovery.cancelExit();
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
        return WallpaperRecovery.isApplied(this, which);
    }

    private ComponentName wallpaperComponent(int which) {
        return WallpaperRecovery.activeComponent(this, which);
    }

    void updateWallpaperStatus() {
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        if (DesktopTile.Wallpaper.isQuietChange()) {
            publishWallpaperStatus(applied);
            return;
        }
        boolean idle = !wallpaperChanging && !externalPicker && !wallpaperApplyAwaitingResult;
        boolean changedOutsidePicker = idle && wallpaperObserved != null && wallpaperObserved != applied;
        publishWallpaperStatus(applied);
        if (applied) {
            if (changedOutsidePicker) completeWallpaperApply();
        } else if (idle) {
            if (changedOutsidePicker || wallpaperRecovery.exitChangedWallpaper()) completeWallpaperExit();
        }
        presentWallpaperResult();
    }

    private void publishWallpaperStatus(boolean applied) {
        wallpaperObserved = applied;
        if (player != null) player.wallpaperStatus(applied, wallpaperChanging);
    }

    void wallpaperPageReady() {
        updatePanelInsets();
        updateWallpaperStatus();
        updatePermissionStatus();
    }

    private void updatePanelInsets() {
        if (player != null && player.isPageReady()) player.panelInsets(panelInsets);
    }

    private void completeWallpaperApply() {
        if (wallpaperApplyAwaitingResult && !applyPreparedWallpaper()) {
            wallpaperApplyAwaitingResult = false;
            wallpaperMessage("无法保存壁纸配置，请检查可用存储空间后重试。");
            presentWallpaperResult();
            return;
        }
        keepLegacyWallpaper();
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, "壁纸已应用");
        wallpaperApplyAwaitingResult = false;
        wallpaperRecovery.acknowledgeExit();
        wallpaperApplySuccessPending = true;
        presentWallpaperResult();
    }

    private void completeWallpaperExit() {
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, "壁纸已退出");
        wallpaperApplyAwaitingResult = false;
        wallpaperApplySuccessPending = false;
        wallpaperRecovery.confirmExit();
        presentWallpaperResult();
    }

    private void presentWallpaperResult() {
        if (!resumed || isFinishing() || isDestroyed() || wallpaperChanging || externalPicker || wallpaperResultDialog != null
                || wallpaperObserved == null) return;
        boolean applied = wallpaperObserved;
        boolean pending = applied ? wallpaperApplySuccessPending : wallpaperRecovery.exitConfirmationPending();
        if (wallpaperTransition == WallpaperTransition.IDLE) {
            if (!pending) return;
            beginWallpaperTransition(!hasWindowFocus());
        }
        switch (wallpaperTransition) {
            case COVERED:
                if (!pending) {
                    revealWallpaperPanel(false);
                } else if (player != null && player.isPageReady() && panelFrameResolved) {
                    if (startupPanelCover) {
                        if (panelModelReady) holdWallpaperCover();
                        else showPlayerError("模型重新加载失败，请重试。");
                    } else {
                        wallpaperTransition = WallpaperTransition.RELOADING;
                        frame.postDelayed(startupTimeout, 30_000);
                        player.reloadWallpaperPanel(++wallpaperReloadRequest);
                    }
                }
                return;
            case HOLDING:
                if (!pending || (panelModelReady && wallpaperTransitionTimeReached())) {
                    revealWallpaperPanel(pending);
                }
                return;
            case SETTLING:
                if (!pending) {
                    finishWallpaperTransition();
                    return;
                }
                if (!panelModelReady || !hasWindowFocus() || !wallpaperTransitionTimeReached()) return;
                break;
            default:
                return;
        }
        wallpaperResultDialog = new WallpaperResultDialog(this, applied, this::viewDesktop);
        wallpaperResultDialog.setOnDismissListener(dialog -> {
            wallpaperResultDialog = null;
            finishWallpaperTransition();
            if (applied) wallpaperApplySuccessPending = false;
            else wallpaperRecovery.acknowledgeExit();
            frame.post(this::presentWallpaperResult);
        });
        wallpaperResultDialog.show();
        Log.d("BandoriLovePet", "Wallpaper result shown");
    }

    private void beginWallpaperTransition(boolean returningFromPicker) {
        if (wallpaperTransition != WallpaperTransition.IDLE) return;
        boolean hasCover = panelCover != null;
        View cover = hasCover ? panelCover : showPanelCover();
        if (panelCoverFading) {
            cover.animate().cancel();
            panelCoverFading = false;
        }
        if (returningFromPicker || (hasCover && cover.getAlpha() == 1f)) {
            cover.setAlpha(1f);
            wallpaperTransition = WallpaperTransition.COVERED;
            return;
        }
        wallpaperTransition = WallpaperTransition.ENTERING;
        if (!hasCover) cover.setAlpha(0f);
        cover.animate().alpha(1f).setDuration(WALLPAPER_COVER_ENTER_MS).withLayer()
                .setInterpolator(new AccelerateDecelerateInterpolator()).withEndAction(() -> {
                    wallpaperTransition = WallpaperTransition.COVERED;
                    presentWallpaperResult();
                }).start();
    }

    void wallpaperPanelReady(long request, boolean ready) {
        if (request != wallpaperReloadRequest || wallpaperTransition != WallpaperTransition.RELOADING) return;
        if (!ready) {
            showPlayerError("模型重新加载失败，请重试。");
            return;
        }
        panelModelReady = true;
        holdWallpaperCover();
    }

    private void holdWallpaperCover() {
        frame.removeCallbacks(startupTimeout);
        wallpaperTransition = WallpaperTransition.HOLDING;
        wallpaperTransitionUntil = SystemClock.uptimeMillis() + WALLPAPER_COVER_HOLD_MS;
        Log.d("BandoriLovePet", "Wallpaper panel ready; holding cover");
        wallpaperTransitionTimeReached();
    }

    private boolean wallpaperTransitionTimeReached() {
        frame.removeCallbacks(advanceWallpaperTransition);
        long remaining = wallpaperTransitionUntil - SystemClock.uptimeMillis();
        if (remaining <= 0) return true;
        frame.postDelayed(advanceWallpaperTransition, remaining);
        return false;
    }

    private void revealWallpaperPanel(boolean pending) {
        frame.removeCallbacks(advanceWallpaperTransition);
        wallpaperTransition = WallpaperTransition.REVEALING;
        if (pending) Log.d("BandoriLovePet", "Wallpaper cover fade started");
        fadePanelCover(WALLPAPER_COVER_EXIT_MS, () -> {
            if (!pending) {
                finishWallpaperTransition();
                presentWallpaperResult();
                return;
            }
            Log.d("BandoriLovePet", "Wallpaper cover fade finished");
            wallpaperTransition = WallpaperTransition.SETTLING;
            wallpaperTransitionUntil = SystemClock.uptimeMillis() + WALLPAPER_RESULT_SETTLE_MS;
            presentWallpaperResult();
        });
    }

    private void finishWallpaperTransition() {
        wallpaperTransition = WallpaperTransition.IDLE;
        if (player != null) player.finishWallpaperPanelReload();
    }

    private void viewDesktop() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void verifyWallpaper(boolean wanted, int request, boolean confirmed, int retries) {
        if (isFinishing() || isDestroyed() || externalPicker) return;
        boolean applied = hasWallpaper(WallpaperManager.FLAG_SYSTEM);
        boolean succeeded = wanted ? wallpaperApplySucceeded(applied, confirmed) : !applied;
        if (!succeeded && retries > 0) {
            frame.postDelayed(() -> verifyWallpaper(wanted, request, confirmed, retries - 1), 400);
            return;
        }
        wallpaperChanging = false;
        publishWallpaperStatus(applied);
        if (request == WALLPAPER_PREVIEW && succeeded) recordWallpaperDirectResult(true);
        updatePermissionStatus();
        Log.i("BandoriLovePet", "Wallpaper verification: request=" + request + ", requested=" + wanted + ", active=" + applied);
        if (succeeded) {
            if (!wanted) {
                completeWallpaperExit();
            } else if (wallpaperApplyAwaitingResult) {
                completeWallpaperApply();
            }
            return;
        }
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE,
                (wanted ? "应用壁纸" : "退出壁纸") + (confirmed ? "未通过系统状态验证" : "未完成"));
        if (!wanted) wallpaperRecovery.cancelExit();
        if (player == null || !confirmed) {
            if (wanted) {
                wallpaperApplyAwaitingResult = false;
                discardPreparedWallpaper();
            }
            presentWallpaperResult();
            fadeStartupCover();
            return;
        }
        if (!wanted) {
            if (request == WALLPAPER_RESTORE_SETTINGS) wallpaperMessage("原壁纸未恢复。");
            else offerWallpaperRestore();
        } else if (request == WALLPAPER_PREVIEW) {
            fallbackFromWallpaperPreview();
        } else if (hasWallpaper(WallpaperManager.FLAG_LOCK)) {
            wallpaperApplyAwaitingResult = false;
            player.prompt("只设置了锁屏", "请再选择「主屏幕」。", "打开壁纸列表", this::openWallpaperList);
        } else {
            offerWallpaperPermission();
        }
        presentWallpaperResult();
        fadeStartupCover();
    }

    private void wallpaperMessage(String message) {
        RuntimeDiagnostics.event(this, RuntimeDiagnostics.Area.WALLPAPER_CHANGE, message);
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
            RuntimeDiagnostics.petServiceEvent(this, "系统未能启动桌宠服务（" + error.getClass().getSimpleName() + "）");
            if (player != null) player.notice("请保持 " + getString(R.string.app_name) + " 在前台并重试");
        }
    }

    static void queueTileAction(DesktopTile.PanelAction action) {
        tileAction = action;
    }

    private void runTileAction() {
        if (tileAction == null || player == null || !resumed || !hasWindowFocus()
                || !panelFrameResolved || startupPanelCover) return;
        DesktopTile.PanelAction action = tileAction;
        tileAction = null;
        if (action == DesktopTile.PanelAction.START_PET) {
            if (store.active() == null) player.notice("请先导入模型");
            else if (!DesktopRuntime.isPetRunning(this)) togglePet();
            return;
        }
        boolean exit = action == DesktopTile.PanelAction.RESTORE_WALLPAPER;
        if (exit != hasWallpaper(WallpaperManager.FLAG_SYSTEM)) return;
        if (!exit && !panelModelReady) {
            player.notice(store.active() == null ? "请先导入模型" : "请等待模型加载完成后重试");
            return;
        }
        if (exit) player.runWallpaperTileAction(true);
        else player.openWallpaperEditor();
    }

    static void refreshFromWallpaperTile() {
        MainActivity current = active.get();
        if (current != null && !current.isDestroyed()) {
            current.updateWallpaperStatus();
            if (current.player != null) current.player.wallpaperPresetApplied();
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
