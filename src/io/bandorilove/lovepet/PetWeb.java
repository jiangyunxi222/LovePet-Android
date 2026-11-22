package io.bandorilove.lovepet;

import android.content.Context;
import android.app.WallpaperManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.*;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

final class PetWeb {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final String POLICY = "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'";
    private final Context context;
    private final ModelStore store;
    private final WebView view;
    private final boolean desktop;
    private volatile float desktopScale = 1;
    private volatile float desktopRotation;
    private final WallpaperMedia.Writer mediaWriter;
    private final WallpaperTilt tilt;
    private volatile boolean wallpaperMode;
    private volatile boolean wallpaperPreview;
    private volatile WallpaperSnapshot wallpaperSnapshot;
    private String lastModelState;
    private boolean wallpaperShown;
    private boolean pageReady;
    private boolean tiltShowing;
    private boolean previewTilt;
    private boolean paused;
    private boolean playbackActive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PetTouchShape touchShape = new PetTouchShape();
    private Runnable onTouchShapeChanged = () -> {};
    private Consumer<Boolean> changeWallpaper = ignored -> {};
    private Runnable wallpaperFrameReady = () -> {};
    private Runnable promptAction;
    private Runnable promptAlternative;
    private int promptId;

    PetWeb(Context context, boolean desktop, Runnable pick, Runnable toggle, Runnable openPanel, Consumer<Boolean> placement,
            Consumer<Boolean> settingsVisible, Consumer<Boolean> immersive,
            Runnable rendererGone) {
        this.context = context;
        this.desktop = desktop;
        mediaWriter = new WallpaperMedia.Writer(context);
        store = new ModelStore(context);
        view = new WebView(context);
        tilt = new WallpaperTilt(context, (x, y) -> evaluate("window.sceneLayers?.shiftBackground(" + x + "," + y + ")"));
        view.setBackgroundColor(desktop ? Color.TRANSPARENT : Color.rgb(250, 246, 249));
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView window, String url) {
                pageReady = true;
                if (wallpaperMode) wallpaperVisible(wallpaperShown);
                else if (context instanceof MainActivity) ((MainActivity) context).wallpaperPageReady();
                status();
                evaluate("window.setPlaybackActive?.(" + playbackActive + ")");
                updateWallpaperTilt();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView window, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView window, WebResourceRequest request) {
                return respond(request.getUrl());
            }
            @Override public boolean onRenderProcessGone(WebView window, RenderProcessGoneDetail detail) {
                Log.e("BandoriLovePet", "WebView renderer exited (crash: " + detail.didCrash() + ")");
                RuntimeDiagnostics.event(context, renderArea(), detail.didCrash() ? "WebView 渲染进程崩溃" : "WebView 渲染进程退出");
                rendererGone.run();
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView web, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                return context instanceof MainActivity && ((MainActivity) context).chooseSceneFile(callback,
                        Arrays.stream(params.getAcceptTypes()).anyMatch(type -> type.startsWith("video/")));
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e("BandoriLove", message.message() + " at " + message.sourceId() + ":" + message.lineNumber());
                }
                return true;
            }
        });
        view.addJavascriptInterface(new Object() {
            @JavascriptInterface public void pickModel() { if (!desktop) main.post(pick); }
            @JavascriptInterface public String runtimeDiagnostics() {
                return context instanceof MainActivity ? RuntimeDiagnostics.report((MainActivity) context) : "";
            }
            @JavascriptInterface public void reportModelRendering(String state) {
                String message;
                switch (state) {
                    case "ready": message = "Live2D 模型已渲染"; break;
                    case "empty": message = "暂无模型"; break;
                    case "error": message = "Live2D 模型加载失败"; break;
                    default: return;
                }
                if (state.equals(lastModelState)) return;
                lastModelState = state;
                RuntimeDiagnostics.event(context, renderArea(), message);
            }
            @JavascriptInterface public void reportRuntimeFailure(String feature) {
                switch (feature) {
                    case "action":
                    case "viewer-action": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.PLAYBACK_ERROR, "动作或互动播放失败"); break;
                    case "expression": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.PLAYBACK_ERROR, "表情播放失败"); break;
                    case "voice": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.PLAYBACK_ERROR, "语音播放失败"); break;
                    case "performance": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.PLAYBACK_ERROR, "演出播放失败"); break;
                    case "scene": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.SCENE_ERROR, "素材加载、播放或保存失败"); break;
                    case "scene-publish": RuntimeDiagnostics.event(context, RuntimeDiagnostics.Area.SCENE_ERROR, "壁纸素材与图层同步失败"); break;
                    default: break;
                }
            }
            @JavascriptInterface public void togglePet() { main.post(toggle); }
            @JavascriptInterface public void openPanel() { if (desktop) main.post(openPanel); }
            @JavascriptInterface public void changeWallpaper(boolean exit) {
                if (context instanceof MainActivity) main.post(() -> changeWallpaper.accept(exit));
            }
            @JavascriptInterface public String captureWallpaperConfig(String model, int costume, String outfit, String scene, String settings) {
                return context instanceof MainActivity
                        ? ((MainActivity) context).captureWallpaperConfig(model, costume, outfit, scene, settings) : null;
            }
            @JavascriptInterface public boolean prepareWallpaper(String config, String savedPreset) {
                return context instanceof MainActivity && ((MainActivity) context).prepareWallpaper(config, savedPreset);
            }
            @JavascriptInterface public String lastUsedWallpaperPreset() {
                return context instanceof MainActivity ? WallpaperSnapshot.lastUsedPreset(context) : "";
            }
            @JavascriptInterface public boolean updateWallpaperPresetIndex(String index) {
                return context instanceof MainActivity && ((MainActivity) context).updateWallpaperPresetIndex(index);
            }
            @JavascriptInterface public void panelLoading(int generation) {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).panelLoading(generation));
            }
            @JavascriptInterface public void panelFrameReady(int generation, boolean ready) {
                if (context instanceof MainActivity) main.post(() -> afterVisualState(
                        () -> ((MainActivity) context).panelFrameReady(generation, ready)));
            }
            @JavascriptInterface public void wallpaperPanelReady(long request, boolean ready) {
                if (context instanceof MainActivity) main.post(() -> afterVisualState(
                        () -> ((MainActivity) context).wallpaperPanelReady(request, ready)));
            }
            @JavascriptInterface public void wallpaperFrameReady() {
                main.post(() -> {
                    if (!wallpaperMode) return;
                    afterVisualState(wallpaperFrameReady);
                });
            }
            @JavascriptInterface public boolean hasWallpaperTilt() { return tilt.available(); }
            @JavascriptInterface public boolean wallpaperTiltEnabled() {
                return "true".equals(setting(WallpaperTilt.SETTING));
            }
            @JavascriptInterface public boolean setWallpaperTiltEnabled(boolean enabled) {
                if (!(context instanceof MainActivity) || !tilt.available()) return false;
                if (!DesktopRuntime.write(context, WallpaperTilt.SETTING, Boolean.toString(enabled))) return false;
                main.post(PetWeb.this::updateWallpaperTilt);
                return true;
            }
            @JavascriptInterface public boolean wallpaperGazeEnabled() {
                return PetWeb.this.wallpaperGazeEnabled();
            }
            @JavascriptInterface public boolean setWallpaperGazeEnabled(boolean enabled) {
                if (!(context instanceof MainActivity)) return false;
                return DesktopRuntime.write(context, PetWallpaperService.GAZE_SETTING, Boolean.toString(enabled));
            }
            @JavascriptInterface public void setWallpaperTiltPreview(boolean show, boolean enabled) {
                if (context instanceof MainActivity) main.post(() -> {
                    previewTilt = show && enabled;
                    updateWallpaperTilt();
                });
            }
            @JavascriptInterface public String desktopSetting(String key) {
                if (!sharedKey(key)) return null;
                WallpaperSnapshot snapshot = wallpaperSnapshot;
                return snapshot != null && WallpaperSnapshot.SCENE_KEY.equals(key) ? snapshot.scene() : setting(key);
            }
            @JavascriptInterface public boolean setDesktopSetting(String key, String value) {
                return context instanceof MainActivity && sharedKey(key) && !WallpaperSnapshot.SCENE_KEY.equals(key)
                        && DesktopRuntime.write(context, key, value);
            }
            @JavascriptInterface public boolean hasWallpaperMedia(String id) {
                return context instanceof MainActivity && WallpaperMedia.find(context, id) != null;
            }
            @JavascriptInterface public boolean beginWallpaperMedia(String id, String type, int length) {
                return context instanceof MainActivity && mediaWriter.begin(id, type, length);
            }
            @JavascriptInterface public boolean appendWallpaperMedia(String chunk) {
                return context instanceof MainActivity && mediaWriter.append(chunk);
            }
            @JavascriptInterface public boolean finishWallpaperMedia() {
                return context instanceof MainActivity && mediaWriter.finish();
            }
            @JavascriptInterface public void removeWallpaperMedia(String id) {
                if (context instanceof MainActivity) ((MainActivity) context).removeWallpaperMedia(id);
            }
            @JavascriptInterface public void refreshPermissions() {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).updatePermissionStatus());
            }
            @JavascriptInterface public void openPermission(String permission) {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).openPermission(permission));
            }
            @JavascriptInterface public void answerPrompt(int id, String answer) {
                main.post(() -> {
                    if (id != promptId) return;
                    Runnable action = "confirm".equals(answer) ? promptAction
                            : "alternate".equals(answer) ? promptAlternative : null;
                    promptAction = null;
                    promptAlternative = null;
                    if (action != null) action.run();
                });
            }
            @JavascriptInterface public void updateSavedModel(int request, String id, int costume, boolean remove) {
                if (context instanceof MainActivity) main.post(() ->
                        ((MainActivity) context).updateSavedModel(request, id, costume, remove));
            }
            @JavascriptInterface public void captureWallpaperPreview(int request) {
                if (context instanceof MainActivity) main.post(() ->
                        ((MainActivity) context).captureWallpaperPreview(request));
            }
            @JavascriptInterface public void setHomeOnly(boolean homeOnly) { if (!desktop) main.post(() -> placement.accept(homeOnly)); }
            @JavascriptInterface public void settingsVisible(boolean visible) { if (!desktop) main.post(() -> settingsVisible.accept(visible)); }
            @JavascriptInterface public boolean canUseHomeMode() { return HomeVisibility.hasUsageAccess(context); }
            @JavascriptInterface public boolean interactionFavoritesOnly() {
                return preference("interactionFavoritesOnly", false);
            }
            @JavascriptInterface public void setInteractionFavoritesOnly(boolean enabled) {
                if (context instanceof MainActivity) writePreference("interactionFavoritesOnly", enabled);
            }
            @JavascriptInterface public boolean captionCardsEnabled() {
                return preference("captionCardsEnabled", true);
            }
            @JavascriptInterface public String theme() { return setting("theme"); }
            @JavascriptInterface public String themePalette() { return AppTheme.definitions(context).toString(); }
            @JavascriptInterface public void setTheme(String theme) {
                if (!(context instanceof MainActivity) || theme == null || !theme.matches("[a-z]{3,12}")) return;
                if (DesktopRuntime.write(context, "theme", theme)) DesktopRuntime.signal("theme");
            }
            @JavascriptInterface public boolean touchMenuEnabled() { return preference("touchMenuEnabled", true); }
            @JavascriptInterface public boolean thirdPartyMenuEnabled() { return preference("thirdPartyMenuEnabled", false); }
            @JavascriptInterface public void setTouchMenuEnabled(boolean enabled, boolean thirdParty) {
                if (!(context instanceof MainActivity)) return;
                writePreference(thirdParty ? "thirdPartyMenuEnabled" : "touchMenuEnabled", enabled);
                main.post(() -> {
                    updateTouchMenu(enabled, thirdParty);
                    DesktopRuntime.flag(thirdParty ? "third-party-menu" : "touch-menu", enabled);
                });
            }
            @JavascriptInterface public void setCaptionCardsEnabled(boolean enabled) {
                if (!(context instanceof MainActivity)) return;
                writePreference("captionCardsEnabled", enabled);
                main.post(() -> {
                    updateCaptionCards(enabled);
                    DesktopRuntime.flag("captions", enabled);
                });
            }
            @JavascriptInterface public void command(String text) { main.post(() -> send(text)); }
            @JavascriptInterface public boolean petShowing() { return DesktopRuntime.petShowing(); }
            @JavascriptInterface public boolean petVisible() { return DesktopRuntime.petVisible(); }
            @JavascriptInterface public float desktopScale() { return desktopScale; }
            @JavascriptInterface public float desktopRotation() { return desktopRotation; }
            @JavascriptInterface public boolean homeOnly() { return PetService.homeOnly(context); }
            @JavascriptInterface public void immersivePreview(boolean on) {
                if (!desktop) main.post(() -> immersive.accept(on));
            }
            @JavascriptInterface public void controlBounds(String name, float left, float top, float right, float bottom) {
                if (!desktop) return;
                main.post(() -> {
                    touchShape.setControl(name, right > left && bottom > top ? new RectF(left, top, right, bottom) : null);
                    onTouchShapeChanged.run();
                });
            }
            @JavascriptInterface public void modelTouchMask(String mask, boolean resetGrip, float rotation) {
                if (!desktop || mask.length() != 40 * 58) return;
                main.post(() -> {
                    touchShape.setModel(mask, resetGrip, rotation);
                    onTouchShapeChanged.run();
                });
            }
            @JavascriptInterface public int costume(String identity) {
                WallpaperSnapshot snapshot = wallpaperSnapshot;
                if (snapshot != null) return snapshot.costume();
                String key = "costume:" + identity;
                if (identity.matches("[A-Za-z0-9_-]{1,80}")) {
                    String shared = DesktopRuntime.read(context, key);
                    if (shared != null) return Integer.parseInt(shared);
                }
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt(key, -1);
            }
            @JavascriptInterface public String wallpaperViewerOutfit() {
                WallpaperSnapshot snapshot = wallpaperSnapshot;
                return snapshot == null ? "" : snapshot.outfit();
            }
        }, "AndroidPet");
    }

    private static boolean sharedKey(String key) {
        return key != null && (WallpaperSnapshot.SCENE_KEY.equals(key) || key.matches("(?:favorites|performances):[A-Za-z0-9_-]{1,80}"));
    }

    private String setting(String key) {
        WallpaperSnapshot snapshot = wallpaperSnapshot;
        return wallpaperMode ? (snapshot == null ? null : snapshot.setting(key)) : DesktopRuntime.read(context, key);
    }

    private boolean preference(String key, boolean fallback) {
        String shared = setting(key);
        if (wallpaperMode) return shared == null ? fallback : Boolean.parseBoolean(shared);
        return shared == null ? context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean(key, fallback)
                : Boolean.parseBoolean(shared);
    }

    private void writePreference(String key, boolean value) {
        DesktopRuntime.write(context, key, Boolean.toString(value));
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    WebView view() { return view; }
    PetTouchShape touchShape() { return touchShape; }
    void onTouchShapeChanged(Runnable callback) { onTouchShapeChanged = callback; }
    boolean hitsControls(float x, float y) {
        if (view.getWidth() == 0 || view.getHeight() == 0) return false;
        return touchShape.hitsControls(x / view.getWidth(), y / view.getHeight());
    }
    void load() { view.loadUrl(HOME + (desktop ? "?pet=1" : "")); }
    void loadWallpaper(boolean preview) {
        WallpaperSnapshot pending = WallpaperSnapshot.pending(context);
        boolean confirmed = pending != null && !preview && WallpaperRecovery.isApplied(context, WallpaperManager.FLAG_SYSTEM)
                && WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) != pending.previousWallpaperId();
        WallpaperSnapshot snapshot = preview || confirmed ? pending : null;
        if (snapshot == null) snapshot = WallpaperSnapshot.applied(context);
        if (snapshot == null && !preview) snapshot = WallpaperSnapshot.pending(context);
        wallpaperSnapshot = snapshot == null ? WallpaperSnapshot.legacy(context, store) : snapshot;
        wallpaperPreview = preview;
        wallpaperMode = true;
        view.loadUrl(HOME + "?wallpaper=1");
    }
    private RuntimeDiagnostics.Area renderArea() {
        if (desktop) return RuntimeDiagnostics.Area.PET_RENDER;
        if (!wallpaperMode) return RuntimeDiagnostics.Area.PANEL_RENDER;
        return wallpaperPreview ? RuntimeDiagnostics.Area.WALLPAPER_PREVIEW : RuntimeDiagnostics.Area.WALLPAPER_RENDER;
    }
    void onChangeWallpaper(Consumer<Boolean> action) { changeWallpaper = action; }
    void onWallpaperFrameReady(Runnable action) { wallpaperFrameReady = action; }
    void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            setPlaybackActive(false);
            view.onPause();
        } else {
            view.onResume();
            setPlaybackActive(true);
        }
        updateWallpaperTilt();
    }
    void setPlaybackActive(boolean active) {
        if (playbackActive == active) return;
        playbackActive = active;
        if (pageReady) evaluate("window.setPlaybackActive?.(" + active + ")");
    }
    void setWallpaperTiltVisible(boolean showing) {
        tiltShowing = showing;
        updateWallpaperTilt();
    }
    void updateWallpaperTilt() {
        boolean enabled = tilt.setEnabled(wallpaperMode ? tiltShowing && "true".equals(setting(WallpaperTilt.SETTING))
                : context instanceof MainActivity && !paused && previewTilt);
        if (pageReady) evaluate("window.sceneLayers?.setTiltActive(" + enabled + ")");
    }
    boolean wallpaperGazeEnabled() { return !"false".equals(setting(PetWallpaperService.GAZE_SETTING)); }
    void wallpaperVisible(boolean visible) {
        wallpaperShown = visible;
        if (visible) view.onResume();
        evaluate("window.setWallpaperVisible?.(" + visible + ")");
        if (!visible) view.onPause();
    }
    void wallpaperTap(int x, int y) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
        evaluate("window.wallpaperTap?.(" + ((float) x / view.getWidth()) + "," + ((float) y / view.getHeight()) + ")");
    }
    void wallpaperGaze(float x, float y) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
        evaluate("window.wallpaperGaze?.(" + (x / view.getWidth()) + "," + (y / view.getHeight()) + ")");
    }
    void resetWallpaperGaze() { evaluate("window.wallpaperGaze?.()"); }
    void refresh() { evaluate("window.refreshModel?.()"); }
    void openWallpaperEditor() { evaluate("window.sceneLayers?.open()"); }
    void wallpaperPresetApplied() { evaluate("window.wallpaperPresetApplied?.()"); }
    void reloadWallpaperPanel(long request) {
        evaluate("window.reloadWallpaperPanel?.(" + request + ")");
    }
    void finishWallpaperPanelReload() { evaluate("window.finishWallpaperPanelReload?.()"); }
    void refreshAppliedWallpaper() {
        WallpaperSnapshot snapshot = WallpaperSnapshot.applied(context);
        if (snapshot == null || snapshot.sameAs(wallpaperSnapshot)) return;
        wallpaperSnapshot = snapshot;
        updateWallpaperTilt();
        if (pageReady) evaluate("window.reloadAppliedWallpaper?.()");
    }
    void updateCaptionCards(boolean enabled) { evaluate("window.setCaptionCardsEnabled?.(" + enabled + ")"); }
    void updateTheme() { evaluate("window.refreshAppTheme?.()"); }
    void updateTouchMenu(boolean enabled, boolean thirdParty) {
        evaluate("window.setTouchMenuEnabled?.(" + enabled + "," + thirdParty + ")");
    }
    void closeSettings() {
        evaluate("(document.querySelector('#app-prompt[open]') || document.querySelector('#performance-options[open]')"
                + " || document.querySelector('#theme-settings[open], #permission-config[open], #runtime-diagnostics[open], #app-library[open], #performance-settings[open]')"
                + " || document.querySelector('#settings')).close()");
    }
    void modelLibraryResult(int request, String error) {
        evaluate("window.modelLibraryResult?.(" + request + "," + JSONObject.quote(error) + ")");
    }
    void wallpaperPreviewResult(int request, String preview) {
        evaluate("window.wallpaperPreviewResult?.(" + request + "," + JSONObject.quote(preview) + ")");
    }
    void prompt(String title, String message, String action, Runnable callback) {
        prompt(title, message, action, callback, null, null);
    }
    void prompt(String title, String message, String action, Runnable callback, String alternative, Runnable onAlternative) {
        promptAction = callback;
        promptAlternative = onAlternative;
        evaluate("window.showAppPrompt?.(" + (++promptId) + "," + JSONObject.quote(title) + ","
                + JSONObject.quote(message) + "," + (action == null ? "null" : JSONObject.quote(action))
                + ",null," + (alternative == null ? "null" : JSONObject.quote(alternative)) + ")");
    }
    void notice(String message) { evaluate("window.showAppNotice?.(" + JSONObject.quote(message) + ")"); }
    boolean isPageReady() { return pageReady; }
    void wallpaperStatus(boolean applied, boolean changing) {
        evaluate("window.setHomeWallpaperState?.(" + applied + "," + changing + ")");
    }
    void runWallpaperTileAction(boolean exit) {
        evaluate("window.runWallpaperTileAction?.(" + exit + ")");
    }
    void panelInsets(Insets insets) {
        float density = view.getResources().getDisplayMetrics().density;
        evaluate("window.setPanelInsets?.(" + insets.top / density + "," + insets.right / density + ","
                + insets.bottom / density + "," + insets.left / density + ")");
    }
    void permissionStatus(boolean overlay, boolean usage, String wallpaper) {
        evaluate("window.setPermissionStatus?.(" + overlay + "," + usage + ","
                + JSONObject.quote(wallpaper) + ")");
    }
    void desktopCrop(RectF bounds) {
        evaluate("window.setDesktopCrop?.(" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + ")");
    }
    void status() {
        evaluate("window.petStatus?.(" + DesktopRuntime.petShowing() + "," + DesktopRuntime.petVisible() + ","
                + PetService.homeOnly(context) + ")");
    }
    void progress(boolean busy) { evaluate("window.importProgress?.(" + busy + ")"); }
    void desktopScale(float scale) {
        if (scale == desktopScale) return;
        desktopScale = scale;
        if (pageReady) evaluate("window.setDesktopScale?.(" + scale + ")");
    }
    void desktopGesture(boolean moving, boolean pinching) {
        evaluate("window.setDesktopGesture?.(" + moving + "," + pinching + ")");
    }
    void desktopRotation(float rotation) {
        if (rotation == desktopRotation) return;
        desktopRotation = rotation;
        if (pageReady) evaluate("window.setDesktopRotation?.(" + rotation + ")");
    }
    void error(String text) { evaluate("window.reportError?.(" + JSONObject.quote(text) + ")"); }
    void receive(JSONObject command, boolean sound) {
        evaluate("window.receiveCommand?.(" + command + "," + sound + ")");
    }
    private void evaluate(String script) { view.evaluateJavascript(script, null); }
    void afterVisualState(Runnable complete) {
        if (!view.isAttachedToWindow()) return;
        view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                if (view.isAttachedToWindow()) complete.run();
            }
        });
    }
    void destroy() {
        main.removeCallbacksAndMessages(null);
        tilt.setEnabled(false);
        wallpaperMode = false;
        wallpaperFrameReady = () -> {};
        mediaWriter.abort();
        view.removeJavascriptInterface("AndroidPet");
        view.destroy();
    }

    private void send(String text) {
        if (text.length() > 4096) return;
        try {
            JSONObject command = new JSONObject(text);
            if (!java.util.Set.of("costume", "action", "expression", "voice", "reset", "loop", "performance").contains(command.optString("type"))) return;
            if (command.optString("type").equals("costume")) {
                String identity = command.getString("role");
                if (!identity.matches("[A-Za-z0-9_-]{1,80}")) return;
                DesktopRuntime.write(context, "costume:" + identity, Integer.toString(command.getInt("id")));
            }
            boolean petSound = DesktopRuntime.petVisible();
            if (DesktopRuntime.isDesktop()) {
                PetService.receive(command, petSound);
                DesktopRuntime.command(command, false);
            } else {
                MainActivity.receive(command, !petSound);
                DesktopRuntime.command(command, petSound);
            }
        } catch (org.json.JSONException error) {
            Log.w("BandoriLove", "Invalid player command", error);
        }
    }

    private WebResourceResponse respond(Uri uri) {
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) return missing();
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) return missing();
        String name = path.substring(1);
        try {
            if (name.equals("session")) {
                File active = resourceModel();
                return response("application/json", active == null
                        ? new ByteArrayInputStream("null".getBytes(StandardCharsets.UTF_8))
                        : new FileInputStream(new File(active, "session.json")));
            }
            if (name.startsWith("wallpaper-model/") && context instanceof MainActivity) {
                File model = wallpaperPresetModel(name.substring("wallpaper-model/".length()));
                return model == null ? missing() : response("application/json", new FileInputStream(new File(model, "session.json")));
            }
            if (name.startsWith("model-record/") && context instanceof MainActivity) {
                File model = store.recordedModel(name.substring("model-record/".length()));
                return model == null ? missing() : response("application/json", new FileInputStream(new File(model, "session.json")));
            }
            if (wallpaperMode && name.startsWith("wallpaper-media/")) {
                File media = WallpaperMedia.find(context, name.substring("wallpaper-media/".length()));
                return media == null ? missing() : response(WallpaperMedia.mime(media), new FileInputStream(media));
            }
            if (name.equals("saved-models") && context instanceof MainActivity) {
                byte[] items = store.history().toString().getBytes(StandardCharsets.UTF_8);
                return response("application/json", new ByteArrayInputStream(items));
            }
            if (name.startsWith("runtime/")) {
                File script = store.runtime(name.substring("runtime/".length()));
                return script == null ? missing() : response("text/javascript", new FileInputStream(script));
            }
            if (name.startsWith("media/")) {
                int separator = name.indexOf('/', "media/".length());
                if (separator < 0) return missing();
                String id = name.substring("media/".length(), separator);
                File model = resourceModel();
                if ((model == null || !model.getName().equals(id)) && context instanceof MainActivity) {
                    model = store.recordedModel(id);
                    if (model == null) model = wallpaperPresetModel(id);
                }
                if (model == null || !model.getName().equals(id)) return missing();
                String asset = ModelStore.safe(name.substring(separator + 1));
                if (!(asset.startsWith("plain/") || asset.startsWith("assets/") || ModelStore.modelJson(asset))) return missing();
                File file = new File(model, asset);
                return file.isFile() ? response(mime(asset), new FileInputStream(file)) : missing();
            }
            if (name.equals("index.html") || name.equals("app.js") || name.equals("scene.js") || name.equals("library.js")
                    || name.equals("performance.js") || name.equals("style.css")
                    || name.equals("theme.js") || name.equals("viewer-interaction.js")
                    || name.equals("tsparticles.engine.min.js") || name.equals("tsparticles.slim.bundle.min.js") || name.equals("petal.svg")
                    || name.equals("butterfly.svg") || name.equals("note.svg") || name.equals("bokeh.svg")
                    || name.equals("favicon.svg") || name.equals("pixi.min.js") || name.equals("index.min.js")
                    || name.equals("cubism4.min.js")) {
                return response(mime(name), context.getAssets().open(name));
            }
        } catch (IOException error) {
            Log.d("BandoriLove", "Resource unavailable: " + name, error);
        }
        return missing();
    }

    private File resourceModel() {
        if (!wallpaperMode) return store.active();
        WallpaperSnapshot snapshot = wallpaperSnapshot;
        return snapshot == null ? null : store.model(snapshot.model());
    }

    private File wallpaperPresetModel(String id) {
        return WallpaperSnapshot.presetModels(context).contains(id) ? store.model(id) : null;
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse response(String mime, InputStream stream) {
        return new WebResourceResponse(mime, mime.startsWith("text/") || mime.equals("application/json") ? "utf-8" : null,
                200, "OK", Map.of("Content-Security-Policy", POLICY, "X-Content-Type-Options", "nosniff",
                "Cache-Control", "no-store"), stream);
    }

    private static String mime(String name) {
        switch (ModelStore.extension(name)) {
            case "html": return "text/html";
            case "js": return "text/javascript";
            case "css": return "text/css";
            case "json": return "application/json";
            case "svg": return "image/svg+xml";
            case "wav": return "audio/wav";
            case "mp3": return "audio/mpeg";
            case "ogg": return "audio/ogg";
            case "m4a": return "audio/mp4";
            default:
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ModelStore.extension(name));
                return mime == null ? "application/octet-stream" : mime;
        }
    }
}
