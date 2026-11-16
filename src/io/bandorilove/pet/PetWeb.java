package io.bandorilove.pet;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.*;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class PetWeb {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final String POLICY = "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'";
    private final Context context;
    private final ModelStore store;
    private final WebView view;
    private final boolean desktop;
    private final Handler main = new Handler(Looper.getMainLooper());

    PetWeb(Context context, boolean desktop, Runnable pick, Runnable toggle, Runnable openPanel) {
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
            @Override public boolean shouldOverrideUrlLoading(WebView window, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView window, WebResourceRequest request) {
                return respond(request.getUrl());
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
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
            @JavascriptInterface public void command(String text) { main.post(() -> send(text)); }
            @JavascriptInterface public boolean petShowing() { return PetService.showing(); }
            @JavascriptInterface public int costume(String identity) {
                return context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("costume:" + identity, -1);
            }
        }, "AndroidPet");
    }

    WebView view() { return view; }
    void load() { view.loadUrl(HOME + (desktop ? "?pet=1" : "")); }
    void refresh() { evaluate("window.refreshModel?.()"); }
    void status() { evaluate("window.petStatus?.(" + PetService.showing() + ")"); }
    void progress(boolean busy) { evaluate("window.importProgress?.(" + busy + ")"); }
    void error(String text) { evaluate("window.reportError?.(" + JSONObject.quote(text) + ")"); }
    void receive(JSONObject command, boolean sound) {
        evaluate("window.receiveCommand?.(" + command + "," + sound + ")");
    }
    private void evaluate(String script) { view.evaluateJavascript(script, null); }
    void destroy() {
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
            if (name.equals("index.html") || name.equals("app.js") || name.equals("style.css")
                    || name.equals("favicon.svg") || name.equals("pixi.min.js") || name.equals("index.min.js")) {
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
