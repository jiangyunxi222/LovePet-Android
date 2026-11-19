package io.bandorilove.lovepet;

import android.content.Context;
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
    private final WallpaperMedia.Writer mediaWriter;
    private final WallpaperTilt tilt;
    private volatile boolean wallpaperMode;
    private boolean wallpaperShown;
    private boolean pageReady;
    private boolean tiltShowing;
    private boolean previewTilt;
    private boolean paused;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PetTouchShape touchShape = new PetTouchShape();
    private Runnable onTouchShapeChanged = () -> {};
    private Runnable applyWallpaper = () -> {};
    private Runnable applyWallpaperScene = () -> {};
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
            @JavascriptInterface public void togglePet() { main.post(toggle); }
            @JavascriptInterface public void openPanel() { if (desktop) main.post(openPanel); }
            @JavascriptInterface public void applyWallpaper() { if (!desktop && !wallpaperMode) main.post(applyWallpaper); }
            @JavascriptInterface public void applyWallpaperScene() { if (!desktop && !wallpaperMode) main.post(applyWallpaperScene); }
            @JavascriptInterface public void dismissWallpaperSuccess(long request, boolean viewDesktop) {
                if (context instanceof MainActivity) main.post(() ->
                        ((MainActivity) context).dismissWallpaperSuccess(request, viewDesktop));
            }
            @JavascriptInterface public void panelLoading(int generation) {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).panelLoading(generation));
            }
            @JavascriptInterface public void panelFrameReady(int generation, boolean ready) {
                if (context instanceof MainActivity) main.post(() -> afterVisualState(
                        () -> ((MainActivity) context).panelFrameReady(generation, ready)));
            }
            @JavascriptInterface public void wallpaperFrameReady() {
                main.post(() -> {
                    if (!wallpaperMode) return;
                    afterVisualState(wallpaperFrameReady);
                });
            }
            @JavascriptInterface public boolean hasWallpaperTilt() { return tilt.available(); }
            @JavascriptInterface public boolean wallpaperTiltEnabled() {
                return "true".equals(DesktopRuntime.read(context, WallpaperTilt.SETTING));
            }
            @JavascriptInterface public boolean setWallpaperTiltEnabled(boolean enabled) {
                if (!(context instanceof MainActivity) || !tilt.available()) return false;
                if (!DesktopRuntime.write(context, WallpaperTilt.SETTING, Boolean.toString(enabled))) return false;
                main.post(PetWeb.this::updateWallpaperTilt);
                DesktopRuntime.signal(WallpaperTilt.SETTING);
                return true;
            }
            @JavascriptInterface public void setWallpaperTiltPreview(boolean show) {
                if (context instanceof MainActivity) main.post(() -> {
                    previewTilt = show;
                    updateWallpaperTilt();
                });
            }
            @JavascriptInterface public String desktopSetting(String key) {
                return sharedKey(key) ? DesktopRuntime.read(context, key) : null;
            }
            @JavascriptInterface public boolean setDesktopSetting(String key, String value) {
                if (!(context instanceof MainActivity) || !sharedKey(key) || !DesktopRuntime.write(context, key, value)) return false;
                if (key.equals("wallpaper-scene-v1")) DesktopRuntime.signal("scene");
                return true;
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
                if (!(context instanceof MainActivity)) return;
                File file = WallpaperMedia.find(context, id);
                if (file != null) file.delete();
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
            @JavascriptInterface public String theme() { return DesktopRuntime.read(context, "theme"); }
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
            @JavascriptInterface public void modelTouchMask(String mask, boolean resetGrip) {
                if (!desktop || mask.length() != 40 * 58) return;
                main.post(() -> {
                    touchShape.setModel(mask, resetGrip);
                    onTouchShapeChanged.run();
                });
            }
            @JavascriptInterface public int costume(String identity) {
                String key = "costume:" + identity;
                if (identity.matches("[A-Za-z0-9_-]{1,80}")) {
                    String shared = DesktopRuntime.read(context, key);
                    if (shared != null) return Integer.parseInt(shared);
                }
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt(key, -1);
            }
        }, "AndroidPet");
    }

    private static boolean sharedKey(String key) {
        return key != null && (key.equals("wallpaper-scene-v1") || key.matches("(?:favorites|performances):[A-Za-z0-9_-]{1,80}"));
    }

    private boolean preference(String key, boolean fallback) {
        String shared = DesktopRuntime.read(context, key);
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
    void loadWallpaper() {
        wallpaperMode = true;
        view.loadUrl(HOME + "?wallpaper=1");
    }
    void onApplyWallpaper(Runnable action) { applyWallpaper = action; }
    void onApplyWallpaperScene(Runnable action) { applyWallpaperScene = action; }
    void onWallpaperFrameReady(Runnable action) { wallpaperFrameReady = action; }
    void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) view.onPause();
        else view.onResume();
        updateWallpaperTilt();
    }
    void setWallpaperTiltVisible(boolean showing) {
        tiltShowing = showing;
        updateWallpaperTilt();
    }
    void updateWallpaperTilt() {
        boolean showing = wallpaperMode ? tiltShowing : context instanceof MainActivity && !paused && previewTilt;
        boolean enabled = tilt.setEnabled(showing && "true".equals(DesktopRuntime.read(context, WallpaperTilt.SETTING)));
        if (pageReady) evaluate("window.sceneLayers?.setTiltActive(" + enabled + ")");
    }
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
    void refreshWallpaperScene() { evaluate("window.sceneLayers?.reloadDesktopScene()"); }
    void updateCaptionCards(boolean enabled) { evaluate("window.setCaptionCardsEnabled?.(" + enabled + ")"); }
    void updateTheme() { evaluate("window.refreshAppTheme?.()"); }
    void updateTouchMenu(boolean enabled, boolean thirdParty) {
        evaluate("window.setTouchMenuEnabled?.(" + enabled + "," + thirdParty + ")");
    }
    void closeSettings() {
        evaluate("(document.querySelector('#app-prompt[open]') || document.querySelector('#performance-options[open]')"
                + " || document.querySelector('#permission-config[open], #app-library[open], #performance-settings[open]')"
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
    void panelFramePresented(int generation, boolean ready) {
        evaluate("window.panelFramePresented?.(" + generation + "," + ready + ")");
    }
    void showWallpaperSuccess(long request) { evaluate("window.showWallpaperSuccess?.(" + request + ")"); }
    void wallpaperStatus(boolean applied, boolean changing) {
        evaluate("window.setHomeWallpaperState?.(" + applied + "," + changing + ")");
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
    void pinching(boolean active) { evaluate("window.setPetPinching?.(" + active + ")"); }
    void error(String text) { evaluate("window.reportError?.(" + JSONObject.quote(text) + ")"); }
    void receive(JSONObject command, boolean sound) {
        evaluate("window.receiveCommand?.(" + command + "," + sound + ")");
    }
    private void evaluate(String script) { view.evaluateJavascript(script, null); }
    private void afterVisualState(Runnable complete) {
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
                PetWallpaperService.receive(command, !petSound);
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
                File active = store.active();
                return response("application/json", active == null
                        ? new ByteArrayInputStream("null".getBytes(StandardCharsets.UTF_8))
                        : new FileInputStream(new File(active, "session.json")));
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
                File active = store.active();
                if (active == null || !name.startsWith("media/" + active.getName() + "/")) return missing();
                String asset = ModelStore.safe(name.substring("media/".length() + active.getName().length() + 1));
                if (!(asset.startsWith("plain/") || asset.startsWith("assets/") || ModelStore.modelJson(asset))) return missing();
                File file = new File(active, asset);
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
