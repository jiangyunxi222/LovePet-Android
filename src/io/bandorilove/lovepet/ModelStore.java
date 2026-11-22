package io.bandorilove.lovepet;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModelStore {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    static final int JSON_LIMIT = 12 * 1024 * 1024;
    static final String CORE2 = "live2d.min.js";
    static final String CORE3 = "live2dcubismcore.min.js";
    private static final String CORE3_URL = "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js";
    private static final String CORE3_DOWNLOAD_SHA = "25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f";
    private static final int RUNTIME_LIMIT = 512_000;
    static final Map<String, String> RUNTIME = Map.of(
        CORE2, "e4ea1f18bdd44b65394ffd5a1bab16982e88757d45134d1bd0737c8a6b3ddd08",
        CORE3, "913e10e881aa7abec8e65bbb8c2540db3fe9acf987c7537befa521f8a7f51009");
    static final Set<String> MEDIA = Set.of("moc", "moc3", "json", "mtn",
        "png", "jpg", "jpeg", "webp", "wav", "mp3", "ogg", "m4a", "flac");
    private final Context context;
    private final File models;
    private final File runtimes;
    private final android.content.SharedPreferences prefs;

    ModelStore(Context context) {
        this.context = context;
        models = new File(context.getFilesDir(), "models");
        runtimes = new File(context.getFilesDir(), "runtimes");
        prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE);
    }

    File active() {
        String name = DesktopRuntime.isDesktop() ? DesktopRuntime.read(context, "active") : null;
        if (name == null) name = prefs.getString("active", "");
        return model(name);
    }

    File model(String id) {
        File folder = savedFolder(id);
        return folder != null && new File(folder, "session.json").isFile() ? folder : null;
    }

    File recordedModel(String id) {
        return prefs.contains("saved:" + id) ? model(id) : null;
    }

    String archiveExtension(File model) {
        try {
            String source = new JSONObject(prefs.getString("saved:" + model.getName(), "{}"))
                    .optString("sourceName");
            String format = extension(source);
            return Set.of("zip", "lpk", "wpk").contains(format) ? format.toUpperCase(Locale.ROOT) : "";
        } catch (JSONException ignored) {
            return "";
        }
    }

    private File savedFolder(String id) {
        return id.matches("[0-9a-f-]{36}") ? new File(models, id) : null;
    }

    JSONObject savedSession(File folder) throws Exception {
        try (InputStream input = new FileInputStream(new File(folder, "session.json"))) {
            return new JSONObject(new String(readBytes(input, JSON_LIMIT), StandardCharsets.UTF_8));
        }
    }

    private JSONObject historyItem(File folder, JSONObject session, Uri source, String sourceName) throws Exception {
        return new JSONObject().put("id", folder.getName()).put("name", session.getJSONObject("catalog").getString("name"))
                .put("source", source == null ? "" : source.toString()).put("sourceName", sourceName)
                .put("added", System.currentTimeMillis());
    }

    private void rememberCurrentModel() throws Exception {
        File folder = active();
        if (folder == null || prefs.contains("saved:" + folder.getName())) return;
        JSONObject item = historyItem(folder, savedSession(folder), null, "");
        if (!prefs.edit().putString("saved:" + folder.getName(), item.toString()).commit()) {
            throw new IOException("无法保存模型记录");
        }
    }

    synchronized JSONArray history() throws IOException {
        try {
            rememberCurrentModel();
            String activeId = prefs.getString("active", "");
            List<JSONObject> items = new ArrayList<>();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith("saved:") || !(entry.getValue() instanceof String)) continue;
                JSONObject item = new JSONObject((String) entry.getValue());
                File folder = savedFolder(item.getString("id"));
                item.put("available", folder != null && new File(folder, "session.json").isFile());
                item.put("active", activeId.equals(item.getString("id")));
                item.remove("source");
                items.add(item);
            }
            items.sort(Comparator.comparingLong((JSONObject item) -> item.optLong("added")).reversed());
            return new JSONArray(items);
        } catch (Exception error) {
            throw new IOException("无法读取模型记录", error);
        }
    }

    synchronized void useSaved(String id, int costume) throws IOException {
        File folder = savedFolder(id);
        if (folder == null || (!prefs.contains("saved:" + id) && !id.equals(prefs.getString("active", "")))) {
            throw new IOException("模型记录已失效，请重新导入。");
        }
        JSONObject catalog;
        try {
            catalog = savedSession(folder).getJSONObject("catalog");
            JSONArray costumes = catalog.getJSONArray("costumes");
            for (int i = 0; i < costumes.length(); i++) {
                JSONObject item = costumes.getJSONObject(i);
                String path = safe(item.getString("file"));
                try (InputStream input = new FileInputStream(new File(folder, path))) {
                    JSONObject model = new JSONObject(new String(readBytes(input, JSON_LIMIT), StandardCharsets.UTF_8));
                    boolean modern = item.getBoolean("cubism3");
                    JSONObject refs = modern ? model.getJSONObject("FileReferences") : model;
                    checkSavedAsset(folder, path, refs.getString(modern ? "Moc" : "model"));
                    JSONArray textures = refs.getJSONArray(modern ? "Textures" : "textures");
                    for (int t = 0; t < textures.length(); t++) checkSavedAsset(folder, path, textures.getString(t));
                    if (runtime(modern ? CORE3 : CORE2) == null) throw new IOException("运行库丢失");
                }
            }
        } catch (Exception error) {
            throw new IOException("模型资源已失效，请重新导入。", error);
        }
        if (!prefs.edit().putString("active", id).commit()) throw new IOException("无法切换模型");
        if (costume >= 0 && catalog.has("id")) {
            String key = "costume:" + catalog.optString("id");
            if (catalog.optString("id").matches("[A-Za-z0-9_-]{1,80}")) DesktopRuntime.write(context, key, Integer.toString(costume));
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putInt(key, costume).apply();
        }
        DesktopRuntime.write(context, "active", id);
    }

    private static void checkSavedAsset(File folder, String modelPath, String reference) throws IOException {
        String path = relative(reference);
        if (modelPath.startsWith("plain/") && !path.startsWith("plain/")) path = "plain/" + path;
        if (!new File(folder, path).isFile()) throw new IOException("模型文件丢失");
    }

    synchronized void removeSaved(String id) throws IOException {
        File folder = savedFolder(id);
        if (folder == null) throw new IOException("模型记录无效");
        android.content.SharedPreferences.Editor edit = prefs.edit().remove("saved:" + id);
        boolean active = id.equals(prefs.getString("active", ""));
        if (active) edit.remove("active");
        if (!edit.commit()) throw new IOException("无法删除模型记录");
        if (active) DesktopRuntime.write(context, "active", "");
        releaseUnusedWallpaperModel(id);
    }

    synchronized void releaseUnusedWallpaperModel(String id) {
        File folder = savedFolder(id);
        if (folder == null || prefs.contains("saved:" + id) || id.equals(prefs.getString("active", ""))
                || WallpaperSnapshot.keepsModel(context, id)) return;
        delete(folder);
    }

    File runtime(String name) {
        File file = new File(runtimes, name);
        return RUNTIME.containsKey(name) && file.isFile() ? file : null;
    }

    String documentName(Uri uri) {
        try (Cursor data = context.getContentResolver().query(uri,
                new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return data != null && data.moveToFirst() ? data.getString(0) : "";
        }
    }

    void verifySelectedDocument(Uri folder, String name, Uri selected) throws Exception {
        try (TreeSource tree = new TreeSource(context, folder)) {
            if (tree.isSelectedDocument(name, selected)) return;
            if (tree.has(name)) {
                try (InputStream original = context.getContentResolver().openInputStream(selected);
                     InputStream granted = tree.open(name)) {
                    if (original != null && Arrays.equals(fileHash(original), fileHash(granted))) return;
                }
            }
        }
        throw new IOException("所授权目录中找不到刚选的模型文件：" + name);
    }

    private static byte[] fileHash(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[128 * 1024];
        long total = 0;
        for (int size; (size = input.read(buffer)) != -1;) {
            total += size;
            if (total > MAX_BYTES) throw new IOException("模型文件超过 2 GB");
            hash.update(buffer, 0, size);
        }
        return hash.digest();
    }

    synchronized void importModel(Uri uri, String entry) throws Exception {
        rememberCurrentModel();
        File staged = new File(models, UUID.randomUUID().toString());
        mkdir(staged);
        boolean installed = false;
        try {
            JSONObject session;
            if (entry != null) {
                if (!extension(entry).equals("lpk")) throw new IOException("请选择 ZIP、LPK 或 WPK 模型包。");
                try (TreeSource tree = new TreeSource(context, uri)) {
                    JSONObject workshop = tree.has("config.json") ? tree.object("config.json") : null;
                    File incoming = new File(staged, "incoming.lpk");
                    try (InputStream stream = tree.open(entry)) {
                        copy(stream, incoming, MAX_BYTES, null);
                    }
                    try (ZipSource source = new ZipSource(incoming)) {
                        session = ViewerPackage.loadLpk(source, staged, workshop);
                    }
                    if (!incoming.delete()) throw new IOException("无法清理临时模型包");
                }
            } else {
                String name = documentName(uri);
                String extension = extension(name);
                if (!Set.of("zip", "lpk", "wpk").contains(extension)) {
                    throw new IOException("请选择 ZIP、LPK 或 WPK 模型包。");
                }
                File incoming = new File(staged, "incoming." + extension);
                try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException("无法打开文件");
                    copy(stream, incoming, MAX_BYTES, null);
                }
                try (ZipSource source = new ZipSource(incoming)) {
                    if (extension.equals("zip")) {
                        session = indexed(source, rolePrefix(source), staged);
                    } else {
                        session = ViewerPackage.load(source, staged, extension, this::loadViewerRole);
                    }
                }
                if (!incoming.delete()) throw new IOException("无法清理临时模型包");
            }
            requireRuntime(session);
            session.put("version", staged.getName());
            writeJson(new File(staged, "session.json"), session);
            String sourceName = entry != null ? entry : documentName(uri);
            JSONObject item = historyItem(staged, session, uri, sourceName);
            if (!prefs.edit().putString("active", staged.getName())
                    .putString("saved:" + staged.getName(), item.toString()).commit()) {
                throw new IOException("无法保存当前模型");
            }
            installed = true;
            DesktopRuntime.write(context, "active", staged.getName());
        } finally {
            if (!installed) delete(staged);
        }
    }

    private JSONObject indexed(ZipSource source, String prefix, File folder) throws Exception {
        JSONObject manifest = source.object(prefix + "manifest.json");
        if (MobileRolePackage.FORMAT.equals(manifest.optString("format"))) {
            if (!prefix.isEmpty()) throw new IOException("手机版角色包的 manifest.json 必须放在 ZIP 根目录。");
            return loadMobileRole(source, manifest, folder, false);
        }
        JSONObject session = prepareIndexed(source, prefix, folder, null);
        installRuntime(source, prefix + "ControlPanel/web/vendor/", RUNTIME.keySet());
        return session;
    }

    private JSONObject loadViewerRole(ZipSource source, File folder, JSONObject workshop) throws Exception {
        JSONObject manifest = null;
        if (source.has("manifest.json")) {
            try { manifest = source.object("manifest.json"); }
            catch (IOException | JSONException ignored) { /* Third-party LPK manifests are not used. */ }
        }
        if (manifest != null && MobileRolePackage.FORMAT.equals(manifest.optString("format"))) {
            return loadMobileRole(source, manifest, folder, true);
        }
        return ViewerPackage.loadLpk(source, folder, workshop);
    }

    private JSONObject loadMobileRole(ZipSource source, JSONObject manifest, File folder, boolean lpk) throws Exception {
        JSONObject session = MobileRolePackage.extract(source, manifest, folder, lpk);
        Set<String> runtimes = source.has("runtime/" + CORE2) ? RUNTIME.keySet() : Set.of(CORE3);
        installRuntime(source, "runtime/", runtimes);
        return session;
    }

    static JSONObject prepareIndexed(Source source, String prefix, File folder, String entry) throws Exception {
        JSONObject manifest = source.object(prefix + "manifest.json");
        JSONObject hashes = manifest.getJSONObject("files_sha256");
        String panel = source.has(prefix + "Preview/library.json") ? "Preview" : "ControlPanel";
        String index = panel + "/library.json";
        JSONArray library = new JSONArray(source.text(prefix + index));
        if (library.length() != 1) throw new IOException("请选择 BandoriLove 单角色包");
        JSONObject role = library.getJSONObject(0);
        String identity = role.getString("id");
        if (!identity.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("角色编号无效");
        String catalogName = panel + "/data/" + identity + ".json";
        JSONObject catalog = source.object(prefix + catalogName);
        String parent = role.getString("entry");
        JSONArray costumes = catalog.getJSONArray("costumes");
        if (!identity.equals(catalog.getString("id")) || !".".equals(catalog.getString("directory"))
                || !parent.equals(catalog.getString("entry")) || costumes.length() == 0) {
            throw new IOException("角色索引无效");
        }
        if (entry != null && !entry.equals(parent)) throw new IOException("请选择父模型 " + parent);
        Set<String> files = new LinkedHashSet<>();
        files.add(index);
        files.add(catalogName);
        addModel(files, parent);
        for (int i = 0; i < costumes.length(); i++) addModel(files, costumes.getJSONObject(i).getString("file"));
        for (Iterator<String> keys = hashes.keys(); keys.hasNext();) {
            String file = keys.next();
            if (file.startsWith("assets/") && MEDIA.contains(extension(file))) files.add(safe(file));
        }
        long budget = MAX_BYTES;
        for (String file : files) {
            try (InputStream input = source.open(prefix + file)) {
                budget -= copy(input, new File(folder, file), budget, hashes.getString(file));
            }
        }
        return new JSONObject().put("catalog", catalog).put("limited", false);
    }

    private void requireRuntime(JSONObject session) throws Exception {
        JSONArray costumes = session.getJSONObject("catalog").getJSONArray("costumes");
        for (int i = 0; i < costumes.length(); i++) {
            if (!costumes.getJSONObject(i).getBoolean("cubism3") && runtime(CORE2) == null) {
                throw new IOException("该模型使用经典 Cubism 2，请先导入一次含运行库的 BandoriLove 角色包");
            }
        }
        if (runtime(CORE3) != null) return;
        byte[] data;
        javax.net.ssl.HttpsURLConnection connection = (javax.net.ssl.HttpsURLConnection)
                new java.net.URL(CORE3_URL).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream source = connection.getInputStream()) {
                data = readBytes(source, RUNTIME_LIMIT);
            }
        } catch (IOException error) {
            throw new IOException("无法下载 Live2D 官方运行库。请联网后重试，或导入一次完整角色包。", error);
        } finally {
            connection.disconnect();
        }
        if (!CORE3_DOWNLOAD_SHA.equals(hex(MessageDigest.getInstance("SHA-256").digest(data)))) {
            throw new IOException("Live2D 官方运行库版本已变化。可导入完整角色包使用随包运行库。");
        }
        saveRuntime(CORE3, data);
    }

    private void installRuntime(Source source, String directory, Set<String> names) throws Exception {
        Map<String, byte[]> scripts = new LinkedHashMap<>();
        for (String name : names) {
            byte[] data = source.bytes(directory + name, RUNTIME_LIMIT);
            if (!hex(MessageDigest.getInstance("SHA-256").digest(data)).equals(RUNTIME.get(name))) {
                throw new IOException("角色包的 Live2D 网页运行库版本不受支持");
            }
            scripts.put(name, data);
        }
        for (Map.Entry<String, byte[]> script : scripts.entrySet()) {
            saveRuntime(script.getKey(), script.getValue());
        }
    }

    private void saveRuntime(String name, byte[] data) throws IOException {
        mkdir(runtimes);
        File out = new File(runtimes, name);
        if (out.isFile() && Arrays.equals(java.nio.file.Files.readAllBytes(out.toPath()), data)) return;
        android.util.AtomicFile atomic = new android.util.AtomicFile(out);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(data);
            atomic.finishWrite(output);
        } catch (IOException error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    static JSONObject plain(Source source, String entry, File folder, String name) throws Exception {
        JSONObject model = source.object(entry);
        boolean modern = model.has("FileReferences");
        JSONObject refs = modern ? model.getJSONObject("FileReferences") : model;
        JSONObject preview = new JSONObject();
        Set<String> assets = new LinkedHashSet<>();
        String mocKey = modern ? "Moc" : "model";
        String texturesKey = modern ? "Textures" : "textures";
        preview.put(mocKey, reference(refs.getString(mocKey), assets, modern ? Set.of("moc3") : Set.of("moc")));
        JSONArray textures = refs.getJSONArray(texturesKey);
        if (textures.length() == 0) throw new IOException("模型缺少贴图");
        JSONArray usable = new JSONArray();
        for (int i = 0; i < textures.length(); i++) {
            usable.put(reference(textures.getString(i), assets, Set.of("png", "jpg", "jpeg", "webp")));
        }
        preview.put(texturesKey, usable);
        for (String key : modern ? List.of("Physics", "Pose") : List.of("physics", "pose")) {
            if (refs.has(key)) preview.put(key, reference(refs.getString(key), assets, Set.of("json")));
        }
        long budget = MAX_BYTES;
        for (String path : assets) {
            try (InputStream input = source.open(path)) {
                budget -= copy(input, new File(folder, "plain/" + path), budget, null);
            }
        }
        String title = name == null ? entry.replaceFirst("(?i)(?:\\.model3?)?\\.json$", "") : name;
        return renderOnly(folder, title, modern, preview);
    }

    static JSONObject renderOnly(File folder, String title, boolean modern, JSONObject preview) throws Exception {
        String file = "plain/preview.json";
        writeJson(new File(folder, file), modern
                ? new JSONObject().put("Version", 3).put("FileReferences", preview) : preview);
        JSONObject costume = new JSONObject().put("id", 1).put("name", title).put("file", file)
                .put("cubism3", modern);
        JSONObject catalog = new JSONObject().put("name", title).put("game", "Live2D")
                .put("costumes", new JSONArray().put(costume));
        return new JSONObject().put("catalog", catalog).put("limited", true);
    }

    static String rolePrefix(ZipSource zip) throws IOException {
        List<String> manifests = new ArrayList<>();
        for (String file : zip.entries()) {
            if (file.equals("manifest.json") || file.matches("[^/]+/manifest\\.json")) manifests.add(file);
        }
        if (manifests.size() != 1) {
            throw new IOException("请选择 BandoriLove 单角色 ZIP。第三方模型请选择 LPK 或 WPK。");
        }
        String manifest = manifests.get(0);
        return manifest.substring(0, manifest.length() - "manifest.json".length());
    }

    private static void addModel(Set<String> names, String name) throws IOException {
        if (name.contains("/") || !modelJson(name)) throw new IOException("角色模型路径无效");
        names.add(safe(name));
    }

    private static String reference(String value, Set<String> assets, Set<String> extensions) throws IOException {
        String file = relative(value);
        if (!extensions.contains(extension(file))) throw new IOException("不支持的模型资源：" + file);
        assets.add(file);
        return file;
    }

    static boolean modelJson(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".+\\.model3?\\.json");
    }

    static String relative(String value) throws IOException {
        String path = value.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        return safe(path);
    }

    static String safe(String path) throws IOException {
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[\\\\<>:\"|?*\\x00-\\x1f].*")) {
                throw new IOException("模型包包含无效的文件路径");
            }
        }
        return path;
    }

    static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    static long copy(InputStream input, File output, long limit, String digest) throws Exception {
        mkdir(output.getParentFile());
        MessageDigest hash = digest == null ? null : MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (OutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > limit) throw new IOException("模型资源超过 2 GB");
                stream.write(buffer, 0, count);
                if (hash != null) hash.update(buffer, 0, count);
            }
        }
        if (hash != null && !hex(hash.digest()).equals(digest)) {
            throw new IOException("角色包文件校验失败：" + output.getName());
        }
        return total;
    }

    static void mkdir(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("无法创建模型目录");
    }

    static void writeJson(File file, JSONObject value) throws IOException {
        mkdir(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static void delete(File file) {
        if (file == null) return;
        File[] entries = file.listFiles();
        if (entries != null) for (File entry : entries) delete(entry);
        file.delete();
    }

    abstract static class Source implements AutoCloseable {
        abstract InputStream open(String file) throws IOException;
        abstract boolean has(String file) throws IOException;
        byte[] bytes(String file, int max) throws IOException {
            try (InputStream stream = open(file)) {
                return readBytes(stream, max);
            }
        }
        String text(String file) throws IOException {
            return new String(bytes(file, JSON_LIMIT), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        }
        JSONObject object(String file) throws Exception { return new JSONObject(text(file)); }
        @Override public abstract void close() throws IOException;
    }

    private static byte[] readBytes(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (result.size() + count > max) throw new IOException("模型配置过大");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    static final class ZipSource extends Source {
        private final ZipFile zip;
        private final Map<String, ZipEntry> entries = new LinkedHashMap<>();
        ZipSource(File file) throws IOException {
            zip = new ZipFile(file);
            Set<String> seen = new HashSet<>();
            try {
                for (Enumeration<? extends ZipEntry> parts = zip.entries(); parts.hasMoreElements();) {
                    ZipEntry member = parts.nextElement();
                    String name = member.getName();
                    if (member.isDirectory()) {
                        safe(name.substring(0, name.length() - 1));
                        continue;
                    }
                    safe(name);
                    if (seen.size() >= 20000 || !seen.add(name.toLowerCase(Locale.ROOT))) {
                        throw new IOException("模型包包含重名或过多文件");
                    }
                    entries.put(name, member);
                }
            } catch (IOException error) {
                zip.close();
                throw error;
            }
        }
        Set<String> entries() { return entries.keySet(); }
        @Override boolean has(String name) { return entries.containsKey(name); }
        @Override InputStream open(String name) throws IOException {
            ZipEntry member = entries.get(name);
            if (member == null) throw new IOException("模型包缺少文件：" + name);
            return zip.getInputStream(member);
        }
        @Override public void close() throws IOException { zip.close(); }
    }

    private static final class TreeSource extends Source {
        private final Context context;
        private final Uri root;
        private final String rootId;
        private final Map<String, Map<String, String>> directories = new HashMap<>();
        TreeSource(Context context, Uri root) {
            this.context = context;
            this.root = root;
            this.rootId = DocumentsContract.getTreeDocumentId(root);
        }
        boolean isSelectedDocument(String name, Uri selected) throws IOException {
            return java.util.Objects.equals(root.getAuthority(), selected.getAuthority())
                    && DocumentsContract.isDocumentUri(context, selected)
                    && DocumentsContract.getDocumentId(selected).equals(find(name));
        }
        private Map<String, String> children(String parent) throws IOException {
            Map<String, String> known = directories.get(parent);
            if (known != null) return known;
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parent);
            Map<String, String> names = new HashMap<>();
            try (Cursor data = context.getContentResolver().query(uri,
                    new String[] {Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (data == null) throw new IOException("无法读取所选目录");
                while (data.moveToNext()) names.put(data.getString(0), data.getString(1));
            }
            directories.put(parent, names);
            return names;
        }
        private String find(String name) throws IOException {
            String id = rootId;
            for (String part : safe(name).split("/")) {
                id = children(id).get(part);
                if (id == null) return null;
            }
            return id;
        }
        @Override boolean has(String name) throws IOException { return find(name) != null; }
        @Override InputStream open(String name) throws IOException {
            String id = find(name);
            if (id == null) throw new IOException("模型目录缺少文件：" + name);
            InputStream stream = context.getContentResolver().openInputStream(
                    DocumentsContract.buildDocumentUriUsingTree(root, id));
            if (stream == null) throw new IOException("无法打开模型资源：" + name);
            return stream;
        }
        @Override public void close() {}
    }
}
