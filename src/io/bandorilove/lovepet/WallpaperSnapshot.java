package io.bandorilove.lovepet;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

final class WallpaperSnapshot {
    static final String SCENE_KEY = "wallpaper-scene-v1";
    static final String APPLIED_KEY = "wallpaper-applied-v1";
    static final String PRESET_INDEX_KEY = "wallpaper-preset-index";
    private static final String PRESET_KEY_PREFIX = "wallpaper-used-preset-";
    private static final String PENDING_KEY = "wallpaper-pending-v1";
    private static final String[] OPTIONS = {"theme", "captionCardsEnabled", "touchMenuEnabled",
            "thirdPartyMenuEnabled", "interactionFavoritesOnly", WallpaperTilt.SETTING, PetWallpaperService.GAZE_SETTING};
    private final JSONObject data;

    private WallpaperSnapshot(JSONObject data) { this.data = data; }

    String model() { return data.optString("model"); }
    int costume() { return data.optInt("costume", -1); }
    String outfit() { return data.optString("outfit"); }
    int previousWallpaperId() { return data.optInt("previousWallpaperId", -1); }
    String scene() { return data.optJSONArray("scene").toString(); }
    String setting(String key) { return data.optJSONObject("settings").optString(key, null); }
    boolean sameAs(WallpaperSnapshot other) { return other != null && data.toString().equals(other.data.toString()); }

    Set<String> media() {
        Set<String> ids = new HashSet<>();
        JSONArray scene = data.optJSONArray("scene");
        for (int index = 0; index < scene.length(); index++) {
            JSONObject layer = scene.optJSONObject(index);
            if (layer != null && layer.has("media")) ids.add(layer.optString("media"));
        }
        return ids;
    }

    static WallpaperSnapshot applied(Context context) { return read(context, APPLIED_KEY); }
    static WallpaperSnapshot pending(Context context) { return read(context, PENDING_KEY); }

    static boolean keepsModel(Context context, String id) {
        WallpaperSnapshot applied = applied(context), pending = pending(context);
        return (applied != null && id.equals(applied.model())) || (pending != null && id.equals(pending.model()))
                || presetModels(context).contains(id);
    }

    static Set<String> presetModels(Context context) {
        try { return modelIds(presetIndex(context).optJSONArray("models")); }
        catch (JSONException error) { return Set.of(); }
    }

    static Set<String> modelIds(JSONArray models) throws JSONException {
        Set<String> ids = new HashSet<>();
        if (models == null) return ids;
        for (int index = 0; index < models.length(); index++) {
            String id = models.getString(index);
            if (!id.matches("[0-9a-f-]{36}")) throw new JSONException("Invalid wallpaper model ID");
            ids.add(id);
        }
        return ids;
    }

    static JSONObject presetIndex(Context context) {
        try {
            String index = DesktopRuntime.read(context, PRESET_INDEX_KEY);
            if (index != null) return new JSONObject(index);
        } catch (JSONException error) {
            Log.w("BandoriLovePet", "Cannot read wallpaper preset index", error);
        }
        return new JSONObject();
    }

    static WallpaperSnapshot latest(Context context) {
        JSONObject index = presetIndex(context);
        String id = lastUsedPreset(context);
        if (hasPreset(index, id)) {
            WallpaperSnapshot used = read(context, PRESET_KEY_PREFIX + id);
            if (used != null) return used;
        }
        return decode(index.optJSONObject("latest"));
    }

    static String lastUsedPreset(Context context) {
        WallpaperSnapshot applied = applied(context);
        return applied == null ? "" : applied.data.optString("lastUsedPreset");
    }

    private static boolean hasPreset(JSONObject index, String id) {
        if (!id.matches("[a-z0-9-]{1,80}")) return false;
        JSONArray ids = index.optJSONArray("ids");
        if (ids != null) for (int i = 0; i < ids.length(); i++) if (id.equals(ids.optString(i))) return true;
        return false;
    }

    static void dropRemovedPresetSnapshots(Context context, JSONObject previous, JSONObject next) {
        JSONArray ids = previous.optJSONArray("ids");
        if (ids == null) return;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            if (id.matches("[a-z0-9-]{1,80}") && !hasPreset(next, id)) DesktopRuntime.remove(context, PRESET_KEY_PREFIX + id);
        }
    }

    static boolean keepsMedia(Context context, String id) {
        WallpaperSnapshot applied = applied(context), pending = pending(context);
        if ((applied != null && applied.media().contains(id)) || (pending != null && pending.media().contains(id))) return true;
        JSONArray saved = presetIndex(context).optJSONArray("media");
        if (saved != null) for (int i = 0; i < saved.length(); i++) if (saved.optString(i).equals(id)) return true;
        return false;
    }

    static String captureEditor(Context context, ModelStore store, String model, int costume, String outfit,
            String scene, String settings) {
        try {
            File folder = store.model(model);
            if (folder == null || scene.length() > 64_000 || outfit.length() > 1024) return null;
            WallpaperSnapshot snapshot = capture(context, store, folder, costume, outfit, new JSONArray(scene),
                    settings.isEmpty() ? null : new JSONObject(settings));
            return snapshot.data.toString();
        } catch (Exception error) {
            Log.w("BandoriLovePet", "Cannot capture wallpaper configuration", error);
            return null;
        }
    }

    static boolean stage(Context context, ModelStore store, String encoded, String savedPreset, int previousWallpaperId) {
        try {
            WallpaperSnapshot snapshot = decode(encoded);
            File active = store.active();
            if (snapshot == null || store.model(snapshot.model()) == null) return false;
            if ((active == null || !active.getName().equals(snapshot.model())) && !presetModels(context).contains(snapshot.model())) return false;
            WallpaperSnapshot preset = decode(savedPreset);
            if (preset != null) {
                String id = preset.data.optString("presetId");
                if (hasPreset(presetIndex(context), id) && preset.available(context, store)) {
                    if (!preset.write(context, PRESET_KEY_PREFIX + id)) return false;
                    snapshot.data.put("presetId", id);
                }
            }
            return snapshot.stage(context, previousWallpaperId);
        } catch (Exception error) {
            Log.w("BandoriLovePet", "Cannot prepare wallpaper", error);
            return false;
        }
    }

    boolean available(Context context, ModelStore store) {
        if (store.model(model()) == null) return false;
        for (String id : media()) if (WallpaperMedia.find(context, id) == null) return false;
        return true;
    }

    boolean stage(Context context, int previousWallpaperId) throws JSONException {
        String id = data.optString("presetId");
        String lastUsed = lastUsedPreset(context);
        if (hasPreset(presetIndex(context), id)) {
            // Keep the saved version for the shortcut even if the editor changed its applied copy.
            if (read(context, PRESET_KEY_PREFIX + id) == null && !write(context, PRESET_KEY_PREFIX + id)) return false;
            lastUsed = id;
        }
        data.put("lastUsedPreset", lastUsed);
        data.put("previousWallpaperId", previousWallpaperId);
        data.put("request", java.util.UUID.randomUUID().toString());
        for (String mediaId : media()) if (WallpaperMedia.find(context, mediaId) == null) return false;
        return write(context, PENDING_KEY);
    }

    String request() { return data.optString("request"); }

    static WallpaperSnapshot legacy(Context context, ModelStore store) {
        File active = store.active();
        if (active == null) return null;
        try {
            String encoded = DesktopRuntime.read(context, SCENE_KEY);
            return capture(context, store, active, -1, "", encoded == null ? new JSONArray() : new JSONArray(encoded), null);
        } catch (Exception error) {
            Log.w("BandoriLovePet", "Cannot restore the previous wallpaper configuration", error);
            return null;
        }
    }

    boolean apply(Context context) { return write(context, APPLIED_KEY); }
    static void clearPending(Context context) { DesktopRuntime.write(context, PENDING_KEY, ""); }

    private static WallpaperSnapshot capture(Context context, ModelStore store, File active, int costume,
            String outfit, JSONArray scene, JSONObject inherited) throws Exception {
        String role = store.savedSession(active).getJSONObject("catalog").optString("id");
        JSONObject settings = new JSONObject();
        for (String option : OPTIONS) settings.put(option, setting(context, inherited, option));
        if (role.matches("[A-Za-z0-9_-]{1,80}")) {
            for (String key : new String[]{"favorites:" + role, "performances:" + role}) {
                settings.put(key, setting(context, inherited, key));
            }
            if (costume < 0) {
                String key = "costume:" + role;
                String saved = DesktopRuntime.read(context, key);
                costume = saved == null ? context.getSharedPreferences("player", Context.MODE_PRIVATE).getInt(key, -1)
                        : Integer.parseInt(saved);
            }
        }
        return new WallpaperSnapshot(new JSONObject().put("model", active.getName()).put("costume", costume)
                .put("outfit", outfit).put("scene", scene).put("settings", settings));
    }

    private static String setting(Context context, JSONObject inherited, String key) {
        return inherited == null ? DesktopRuntime.read(context, key) : inherited.optString(key, null);
    }

    private static WallpaperSnapshot read(Context context, String key) {
        String value = DesktopRuntime.read(context, key);
        try {
            return decode(value);
        } catch (JSONException error) {
            Log.w("BandoriLovePet", "Cannot read wallpaper configuration", error);
        }
        return null;
    }

    private static WallpaperSnapshot decode(String value) throws JSONException {
        if (value == null || value.isEmpty()) return null;
        return decode(new JSONObject(value));
    }

    private static WallpaperSnapshot decode(JSONObject data) {
        if (data == null || data.optJSONArray("scene") == null || data.optJSONObject("settings") == null) return null;
        return new WallpaperSnapshot(data);
    }

    private boolean write(Context context, String key) { return DesktopRuntime.write(context, key, data.toString()); }
}
