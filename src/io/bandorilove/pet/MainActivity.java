package io.bandorilove.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int ARCHIVE = 1;
    private static final int FOLDER = 2;
    private static final int OVERLAY = 3;
    private static final int NOTIFICATION = 4;
    private static final int JSON = 5;
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private PetWeb player;
    private ModelStore store;
    private FrameLayout frame;
    private boolean importing;
    private Uri pendingDocument;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) pendingDocument = saved.getParcelable("pendingDocument");
        active = new WeakReference<>(this);
        store = new ModelStore(this);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(250, 246, 249));
        frame.setOnApplyWindowInsetsListener((view, all) -> {
            android.graphics.Insets bars = all.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
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
            player = new PetWeb(this, false, this::choose, this::togglePet, () -> {},
                    () -> showPlayerError("网页渲染进程已退出，请重试。若再次发生，请更新系统 WebView。"));
            frame.addView(player.view(), new FrameLayout.LayoutParams(-1, -1));
            player.load();
        } catch (RuntimeException | LinkageError error) {
            Log.e("BandoriLovePet", "WebView initialization failed", error);
            showPlayerError("系统 WebView 无法启动。请启用或更新 Android System WebView，然后重试。");
        }
    }

    private void showPlayerError(String message) {
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

    private void releasePlayer() {
        if (player == null) return;
        frame.removeView(player.view());
        player.destroy();
        player = null;
    }

    @Override protected void onResume() {
        super.onResume();
        if (player != null) player.status();
    }

    @Override protected void onDestroy() {
        if (active.get() == this) active.clear();
        imports.shutdown();
        releasePlayer();
        super.onDestroy();
    }

    private void choose() {
        if (importing || player == null) return;
        new AlertDialog.Builder(this).setTitle("选择模型")
                .setItems(new String[] {"ZIP（仅限发行包）、LPK 或 WPK", "选择已解压的模型 JSON"}, (dialog, index) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, index == 0 ? ARCHIVE : JSON);
                }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("pendingDocument", pendingDocument);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY) {
            if (Settings.canDrawOverlays(this)) launchPet();
            return;
        }
        Uri selected = request == FOLDER ? pendingDocument : null;
        if (request == FOLDER) pendingDocument = null;
        if (result != RESULT_OK || data == null || data.getData() == null || importing || player == null) return;
        Uri uri = data.getData();
        if (request == ARCHIVE) {
            startImport(uri, null, false);
        } else if (request == JSON) {
            findModelFolder(uri);
        } else if (request == FOLDER && selected != null) {
            startImport(uri, selected, true);
        }
    }

    private void findModelFolder(Uri document) {
        importing = true;
        player.progress(true);
        imports.submit(() -> {
            try {
                String name = store.documentName(document);
                if (!ModelStore.extension(name).equals("json")) throw new java.io.IOException("请选择模型 JSON 文件");
                Uri granted = store.grantedFolder(document, name);
                if (granted != null) {
                    store.importModel(granted, name);
                    runOnUiThread(() -> finishImport(null));
                } else {
                    runOnUiThread(() -> requestFolder(document, "授权模型所在目录后，即可读取同目录的贴图和动作。"));
                }
            } catch (Exception error) {
                runOnUiThread(() -> finishImport(error));
            }
        });
    }

    private void requestFolder(Uri document, String message) {
        if (isDestroyed() || player == null) return;
        importing = false;
        player.progress(false);
        new AlertDialog.Builder(this).setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("授权目录", (dialog, button) -> {
                    pendingDocument = document;
                    Intent folder = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    folder.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    folder.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialFolder(document));
                    startActivityForResult(folder, FOLDER);
                }).show();
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
        } else {
            player.progress(false);
            player.error(error instanceof java.io.IOException ? error.getMessage() : "模型配置无效，无法导入");
            android.util.Log.e("BandoriLove", "Model import", error);
        }
    }

    private void togglePet() {
        if (PetService.showing()) {
            stopService(new Intent(this, PetService.class));
        } else if (!importing && store.active() != null) {
            if (Settings.canDrawOverlays(this)) launchPet();
            else new AlertDialog.Builder(this).setMessage("显示桌宠需要允许 BandoriLovePet 显示在其他应用上层。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往设置", (dialog, button) -> startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY))
                    .show();
        }
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
        if (request == NOTIFICATION && Settings.canDrawOverlays(this)) startPet();
    }

    private void startPet() {
        try {
            startForegroundService(new Intent(this, PetService.class));
        } catch (IllegalStateException | SecurityException error) {
            Toast.makeText(this, "请保持 BandoriLovePet 在前台并重试", Toast.LENGTH_SHORT).show();
        }
    }

    static void petChanged() {
        MainActivity current = active.get();
        if (current != null && current.player != null) current.player.status();
    }

    static void receive(JSONObject command) {
        MainActivity current = active.get();
        if (current != null && current.player != null) current.player.receive(command, !PetService.showing());
    }
}
