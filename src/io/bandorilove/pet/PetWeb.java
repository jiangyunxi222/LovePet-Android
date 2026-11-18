package io.bandorilove.pet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.*;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
    private boolean wallpaperMode;
    private boolean wallpaperShown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PetTouchShape touchShape = new PetTouchShape();
    private Runnable onTouchShapeChanged = () -> {};
    private Runnable applyWallpaper = () -> {};
    private Runnable promptAction;
    private int promptId;

    PetWeb(Context context, boolean desktop, Runnable pick, Runnable toggle, Runnable openPanel, Consumer<Boolean> placement,
            Consumer<Boolean> settingsVisible, Consumer<Boolean> immersive,
            Runnable rendererGone) {
        this.context = context;
        this.desktop = desktop;
        store = new ModelStore(context);
        view = new WebView(context);
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
                if (wallpaperMode) wallpaperVisible(wallpaperShown);
                else if (context instanceof MainActivity) ((MainActivity) context).updateWallpaperStatus();
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
                return context instanceof MainActivity && ((MainActivity) context).chooseSceneFile(callback);
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
            @JavascriptInterface public void refreshPermissions() {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).updatePermissionStatus());
            }
            @JavascriptInterface public void openPermission(String permission) {
                if (context instanceof MainActivity) main.post(() -> ((MainActivity) context).openPermission(permission));
            }
            @JavascriptInterface public void answerPrompt(int id, boolean confirmed) {
                main.post(() -> {
                    if (id != promptId) return;
                    Runnable action = promptAction;
                    promptAction = null;
                    if (confirmed && action != null) action.run();
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
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("interactionFavoritesOnly", false);
            }
            @JavascriptInterface public void setInteractionFavoritesOnly(boolean enabled) {
                if (!desktop) context.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
                        .putBoolean("interactionFavoritesOnly", enabled).apply();
            }
            @JavascriptInterface public boolean captionCardsEnabled() {
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("captionCardsEnabled", true);
            }
            @JavascriptInterface public void setCaptionCardsEnabled(boolean enabled) {
                if (!(context instanceof MainActivity)) return;
                context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("captionCardsEnabled", enabled).apply();
                main.post(() -> {
                    updateCaptionCards(enabled);
                    PetService.updateCaptions(enabled);
                    PetWallpaperService.updateCaptions(enabled);
                });
            }
            @JavascriptInterface public void command(String text) { main.post(() -> send(text)); }
            @JavascriptInterface public boolean petShowing() { return PetService.showing(); }
            @JavascriptInterface public boolean petVisible() { return PetService.visible(); }
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
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("costume:" + identity, -1);
            }
        }, "AndroidPet");
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
    void refresh() { evaluate("window.refreshModel?.()"); }
    void updateCaptionCards(boolean enabled) { evaluate("window.setCaptionCardsEnabled?.(" + enabled + ")"); }
    void closeSettings() {
        evaluate("(document.querySelector('#app-prompt[open]') || document.querySelector('#permission-config[open], #app-library[open]')"
                + " || document.querySelector('#settings')).close()");
    }
    void modelLibraryResult(int request, String error) {
        evaluate("window.modelLibraryResult?.(" + request + "," + JSONObject.quote(error) + ")");
    }
    void wallpaperPreviewResult(int request, String preview) {
        evaluate("window.wallpaperPreviewResult?.(" + request + "," + JSONObject.quote(preview) + ")");
    }
    void prompt(String title, String message, String action, Runnable callback) {
        promptAction = callback;
        evaluate("window.showAppPrompt?.(" + (++promptId) + "," + JSONObject.quote(title) + ","
                + JSONObject.quote(message) + "," + (action == null ? "null" : JSONObject.quote(action)) + ")");
    }
    void notice(String message) { evaluate("window.showAppNotice?.(" + JSONObject.quote(message) + ")"); }
    void wallpaperStatus(boolean applied, boolean changing) {
        evaluate("window.setHomeWallpaperState?.(" + applied + "," + changing + ")");
    }
    void permissionStatus(boolean overlay, boolean usage, boolean notifications, String wallpaper) {
        evaluate("window.setPermissionStatus?.(" + overlay + "," + usage + "," + notifications + ","
                + JSONObject.quote(wallpaper) + ")");
    }
    void desktopCrop(RectF bounds) {
        evaluate("window.setDesktopCrop?.(" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + ")");
    }
    void status() {
        evaluate("window.petStatus?.(" + PetService.showing() + "," + PetService.visible() + ","
                + PetService.homeOnly(context) + ")");
    }
    void progress(boolean busy) { evaluate("window.importProgress?.(" + busy + ")"); }
    void pinching(boolean active) { evaluate("window.setPetPinching?.(" + active + ")"); }
    void error(String text) { evaluate("window.reportError?.(" + JSONObject.quote(text) + ")"); }
    void receive(JSONObject command, boolean sound) {
        evaluate("window.receiveCommand?.(" + command + "," + sound + ")");
    }
    private void evaluate(String script) { view.evaluateJavascript(script, null); }
    void destroy() {
        main.removeCallbacksAndMessages(null);
        view.removeJavascriptInterface("AndroidPet");
        view.destroy();
    }

    private void send(String text) {
        if (text.length() > 4096) return;
        try {
            JSONObject command = new JSONObject(text);
            if (!java.util.Set.of("costume", "action", "expression", "voice", "reset", "loop").contains(command.optString("type"))) return;
            if (command.optString("type").equals("costume")) {
                String identity = command.getString("role");
                if (!identity.matches("[A-Za-z0-9_-]{1,80}")) return;
                context.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
                        .putInt("costume:" + identity, command.getInt("id")).apply();
            }
            MainActivity.receive(command);
            PetService.receive(command);
            PetWallpaperService.receive(command);
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
            if (name.equals("index.html") || name.equals("app.js") || name.equals("scene.js") || name.equals("library.js") || name.equals("style.css")
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
