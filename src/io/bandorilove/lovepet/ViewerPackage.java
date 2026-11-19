package io.bandorilove.lovepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ViewerPackage {
    static final class WorkshopConfigRequired extends IOException {
        WorkshopConfigRequired() { super("创意工坊 LPK 需要同目录的 config.json，也可以导入完整 WPK"); }
    }
    private static final Pattern SUBMODEL = Pattern.compile("(?:^|;)\\s*add_submodel\\s+([0-9a-f]{32}\\.bin3?)(?=\\s|;|$)");
    private static final Pattern TEXTURE = Pattern.compile("(?:^|;)\\s*replace_tex\\s+(\\d{1,5})\\s+([0-9a-f]{32}\\.bin3?)(?=\\s|;|$)");
    private static final Pattern EXPRESSION = Pattern.compile("(?:^|;)\\s*set_exp\\s+([^\\s;]+)");
    private final ModelStore.ZipSource source;
    private final Function mask;
    private final File folder;
    private final Map<String, String> exported = new HashMap<>();
    private long remaining = ModelStore.MAX_BYTES;

    private ViewerPackage(ModelStore.ZipSource source, Function mask, File folder) {
        this.source = source;
        this.mask = mask;
        this.folder = folder;
    }

    static JSONObject load(ModelStore.ZipSource source, File output, String extension) throws Exception {
        if (extension.equals("lpk")) return loadLpk(source, output, null);
        String index = "config.json";
        if (!source.has(index)) {
            List<String> matches = new ArrayList<>();
            for (String file : source.entries()) if (file.endsWith("/config.json")) matches.add(file);
            if (matches.size() != 1) throw new IOException("WPK 缺少唯一的创意工坊模型配置");
            index = matches.get(0);
        }
        JSONObject config = source.object(index);
        String name = ModelStore.relative(config.getString("lpkFile"));
        if (!ModelStore.extension(name).equals("lpk")) throw new IOException("WPK 的模型配置无效");
        String nested = index.substring(0, index.lastIndexOf('/') + 1) + name;
        File embedded = new File(output, "workshop.lpk");
        try {
            try (InputStream packed = source.open(source.has(nested) ? nested : name)) {
                ModelStore.copy(packed, embedded, ModelStore.MAX_BYTES, null);
            }
            try (ModelStore.ZipSource lpk = new ModelStore.ZipSource(embedded)) {
                return loadLpk(lpk, output, config);
            }
        } finally {
            embedded.delete();
        }
    }

    static JSONObject loadLpk(ModelStore.ZipSource source, File output, JSONObject workshop) throws Exception {
        String hashName = ModelStore.hex(MessageDigest.getInstance("MD5").digest(
                "config.mlve".getBytes(StandardCharsets.UTF_8)));
        JSONObject meta = source.object(source.has(hashName) ? hashName : "config.mlve");
        String kind = meta.optString("type");
        if (!Set.of("STD2_0", "STM_1_0").contains(kind)) throw new IOException("暂不支持此 LPK 类型：" + kind);
        Function masks = masks(meta, workshop);
        Set<String> entries = entries(meta, source);
        if (kind.equals("STM_1_0") && !meta.optBoolean("encrypt", true) && !entries.isEmpty()) {
            try (InputStream sample = source.open(entries.iterator().next())) {
                byte[] prefix = new byte[128];
                int count = sample.read(prefix);
                if (count > 0 && !text(prefix, count).trim().startsWith("{")) masks = name -> mask("");
            }
        }
        ViewerPackage loader = new ViewerPackage(source, masks, output);
        for (String entry : entries) {
            Parts parts = Parts.read(loader.model(entry));
            if (parts == null) continue;
            String title = workshop == null ? "" : workshop.optString("title");
            if (title.isEmpty()) title = meta.optString("name", "Live2D 模型");
            return loader.importGraph(entry, parts, title);
        }
        throw new IOException("模型包里没有可预览的 Live2D 模型");
    }

    private static Set<String> entries(JSONObject metadata, ModelStore.ZipSource source) throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        JSONArray list = metadata.optJSONArray("list");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONArray costumes = list.getJSONObject(i).optJSONArray("costume");
            if (costumes == null) continue;
            for (int j = 0; j < costumes.length(); j++) {
                String path = costumes.getJSONObject(j).optString("path");
                if (!path.isEmpty()) entries.add(ModelStore.relative(path));
            }
        }
        if (entries.isEmpty()) {
            for (String name : source.entries()) if (ModelStore.modelJson(name)) entries.add(name);
        }
        return entries;
    }

    private interface Function {
        byte[] forMember(String name);
    }

    private static Function masks(JSONObject meta, JSONObject workshop) throws Exception {
        if (!meta.optBoolean("encrypt", true)) return name -> new byte[0];
        String id = meta.optString("id");
        if (id.isEmpty() || id.length() > 128) throw new IOException("LPK 缺少有效的模型编号");
        if (!meta.optString("type").equals("STM_1_0")) return name -> mask(id + name);
        if (workshop == null) throw new WorkshopConfigRequired();
        String item = workshop.optString("fileId");
        if (item.isEmpty()) {
            String file = ModelStore.relative(workshop.getString("lpkFile"));
            item = file.substring(file.lastIndexOf('/') + 1).replaceFirst("(?i)\\.lpk$", "");
        }
        String tag = workshop.optString("metaData");
        if (!item.matches("[0-9]{1,24}") || tag.isEmpty() || tag.length() > 256) {
            throw new IOException("创意工坊 LPK 配置缺少物品编号或元数据");
        }
        String prefix = id + item;
        return name -> mask(prefix + name + tag);
    }

    private static byte[] mask(String seed) {
        long key = 0;
        PrimitiveIterator.OfInt characters = seed.codePoints().iterator();
        while (characters.hasNext()) key = (key * 31 + characters.nextInt()) & 0xffffffffL;
        byte[] mask = new byte[1024];
        for (int i = 0; i < mask.length; i++) {
            key = ((2531011 + 214013 * key) >>> 16) & 0xffff;
            mask[i] = (byte) key;
        }
        return mask;
    }

    private static void decode(byte[] data, int count, byte[] mask, long offset) {
        if (mask.length == 0) return;
        for (int i = 0; i < count; i++) data[i] ^= mask[(int) ((offset + i) & 1023)];
    }

    private static String text(byte[] data, int count) {
        return new String(data, 0, count, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
    }

    private JSONObject model(String name) throws Exception {
        byte[] data = source.bytes(name, ModelStore.JSON_LIMIT);
        decode(data, data.length, mask.forMember(name), 0);
        return new JSONObject(text(data, data.length));
    }

    private JSONObject importGraph(String root, Parts first, String title) throws Exception {
        Map<String, Parts> pending = new LinkedHashMap<>();
        JSONObject graph = new JSONObject();
        pending.put(root, first);
        while (!pending.isEmpty() && graph.length() < 32) {
            Iterator<Map.Entry<String, Parts>> cursor = pending.entrySet().iterator();
            Map.Entry<String, Parts> entry = cursor.next();
            String name = entry.getKey();
            Parts parts = entry.getValue();
            cursor.remove();
            boolean layer = parts.id.endsWith("_hits") || parts.id.endsWith("_menu");
            JSONObject refs = assets(parts, layer);
            JSONObject resources = new JSONObject();
            graph.put(name, new JSONObject().put("id", parts.id).put("modern", parts.modern)
                    .put("model", refs).put("areas", parts.areas).put("groups", parts.groups)
                    .put("expressions", parts.expressions).put("resources", resources));
            Iterator<String> groups = parts.groups.keys();
            while (groups.hasNext()) {
                JSONArray rows = parts.groups.optJSONArray(groups.next());
                if (rows == null) continue;
                for (int i = 0; i < Math.min(512, rows.length()); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    optional(row.optString(parts.key("File", "file")), parts.modern ? "motion3" : "motion", resources);
                    optional(row.optString(parts.key("Sound", "sound")), "sound", resources);
                    String command = row.optString(parts.key("Command", "command"));
                    Matcher expression = EXPRESSION.matcher(command);
                    while (expression.find()) optional(expression.group(1), "expression", resources);
                    Matcher child = SUBMODEL.matcher(command);
                    while (child.find() && pending.size() < 32) {
                        String ref = child.group(1);
                        if (!source.has(ref) || graph.has(ref) || pending.containsKey(ref)) continue;
                        Parts next = Parts.read(model(ref));
                        if (next != null) pending.put(ref, next);
                    }
                }
            }
            for (int i = 0; i < parts.expressions.length(); i++) {
                JSONObject expression = parts.expressions.optJSONObject(i);
                if (expression != null) optional(expression.optString(parts.key("File", "file")), "expression", resources);
            }
        }
        String visible = root;
        if (first.id.contains("l2d_carrier_")) {
            List<String> groups = new ArrayList<>();
            first.groups.keys().forEachRemaining(group -> {
                if (group.startsWith("carrier_switch_")) groups.add(group);
            });
            Collections.sort(groups);
            groups.add(0, first.key("Start", "start"));
            find: for (String group : groups) {
                for (String command : first.commands(group)) {
                    Matcher match = SUBMODEL.matcher(command);
                    while (match.find()) {
                        JSONObject child = graph.optJSONObject(match.group(1));
                        if (child != null && child.getJSONObject("model").has(
                                child.getBoolean("modern") ? "Textures" : "textures")) {
                            visible = match.group(1);
                            break find;
                        }
                    }
                }
            }
        }
        ModelStore.writeJson(new File(folder, "plain/viewer.json"),
                new JSONObject().put("root", root).put("initial", visible).put("models", graph));
        JSONObject selected = graph.getJSONObject(visible);
        return ModelStore.renderOnly(folder, title, selected.getBoolean("modern"), selected.getJSONObject("model"))
                .put("viewerPackage", true);
    }

    private void optional(String reference, String kind, JSONObject resources) throws Exception {
        if (reference.isEmpty() || resources.has(reference)) return;
        try {
            resources.put(reference, asset(reference, kind));
        } catch (IOException ignored) {
            // An unsupported optional motion or sound does not prevent previewing the model.
        }
    }

    private JSONObject assets(Parts parts, boolean layer) throws Exception {
        for (String command : parts.commands(parts.modern ? "Start" : "start")) {
            Matcher match = TEXTURE.matcher(command);
            while (match.find()) {
                int index = Integer.parseInt(match.group(1));
                if (index < parts.textures.size() && source.has(match.group(2))) {
                    parts.textures.set(index, match.group(2));
                }
            }
        }
        JSONObject result = new JSONObject().put(parts.mocKey, asset(parts.refs.getString(parts.mocKey),
                parts.modern ? "moc3" : "moc"));
        if (layer) return result;
        JSONArray textures = new JSONArray();
        for (int i = 0; i < parts.textures.size(); i++) {
            textures.put(asset(parts.textures.get(i), "texture"));
        }
        result.put(parts.modern ? "Textures" : "textures", textures);
        for (String key : parts.modern ? List.of("Physics", "Pose") : List.of("physics", "pose")) {
            if (parts.refs.has(key)) result.put(key, asset(parts.refs.getString(key), "json"));
        }
        return result;
    }

    private String asset(String reference, String kind) throws Exception {
        String name = ModelStore.relative(reference);
        String cacheKey = kind + ":" + name;
        if (exported.containsKey(cacheKey)) return exported.get(cacheKey);
        byte[] key = mask.forMember(name);
        byte[] buffer = new byte[128 * 1024];
        File root = new File(folder, "plain");
        ModelStore.mkdir(root);
        try (InputStream packed = new BufferedInputStream(source.open(name))) {
            int count = 0;
            while (count < 128) {
                int n = packed.read(buffer, count, 128 - count);
                if (n < 0) break;
                count += n;
            }
            decode(buffer, count, key, 0);
            String file = "resource-" + exported.size() + suffix(buffer, count, kind);
            long offset = 0;
            File target = new File(root, file);
            try (OutputStream out = new FileOutputStream(target)) {
                while (count >= 0) {
                    remaining -= count;
                    if (remaining < 0) throw new IOException("模型资源超过 2 GB");
                    if (offset > 0) decode(buffer, count, key, offset);
                    out.write(buffer, 0, count);
                    offset += count;
                    count = packed.read(buffer);
                }
            } catch (IOException failure) {
                target.delete();
                throw failure;
            }
            exported.put(cacheKey, file);
            return file;
        }
    }

    private static String suffix(byte[] data, int count, String kind) throws IOException {
        if (count >= 4) {
            String magic = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
            if (kind.equals("moc3") && magic.equals("MOC3")) return ".moc3";
            if (kind.equals("moc") && magic.startsWith("moc")) return ".moc";
            if (kind.equals("json") && text(data, count).trim().startsWith("{")) return ".json";
            if (kind.equals("motion3") && text(data, count).trim().startsWith("{")) return ".motion3.json";
            if (kind.equals("expression") && text(data, count).trim().startsWith("{")) return ".exp3.json";
            if (kind.equals("motion") && !text(data, count).contains("\0")) return ".mtn";
            if (kind.equals("sound")) {
                if (count >= 12 && magic.equals("RIFF") && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) return ".wav";
                if (magic.equals("OggS")) return ".ogg";
                if (magic.startsWith("ID3") || (data[0] & 255) == 255 && (data[1] & 224) == 224) return ".mp3";
            }
            if (kind.equals("texture")) {
                if (magic.equals("\u0089PNG")) return ".png";
                if ((data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255) return ".jpg";
                if (count >= 12 && magic.equals("RIFF") && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return ".webp";
            }
        }
        throw new IOException("模型资源格式与配置不符");
    }

    private static final class Parts {
        final boolean modern;
        final String id;
        final JSONObject refs;
        final JSONObject groups;
        final JSONArray areas;
        final JSONArray expressions;
        final String mocKey;
        final List<String> textures;
        private Parts(boolean modern, String id, JSONObject raw, JSONObject refs, String mocKey, List<String> textures) {
            this.modern = modern;
            this.id = id;
            this.refs = refs;
            JSONObject motionGroups = refs.optJSONObject(key("Motions", "motions"));
            groups = motionGroups == null ? new JSONObject() : motionGroups;
            JSONArray hitAreas = raw.optJSONArray(key("HitAreas", "hit_areas"));
            areas = hitAreas == null ? new JSONArray() : hitAreas;
            JSONArray definitions = refs.optJSONArray(key("Expressions", "expressions"));
            expressions = definitions == null ? new JSONArray() : definitions;
            this.mocKey = mocKey;
            this.textures = textures;
        }
        static Parts read(JSONObject model) throws Exception {
            boolean modern = model.has("FileReferences");
            JSONObject refs = modern ? model.optJSONObject("FileReferences") : model;
            if (refs == null) return null;
            String moc = modern ? "Moc" : "model";
            JSONArray textures = refs.optJSONArray(modern ? "Textures" : "textures");
            if (refs.optString(moc).isEmpty() || textures == null || textures.length() == 0) return null;
            List<String> textureFiles = new ArrayList<>();
            for (int i = 0; i < textures.length(); i++) textureFiles.add(textures.getString(i));
            JSONObject options = model.optJSONObject(modern ? "Options" : "options");
            String id = options == null ? "" : options.optString(modern ? "Id" : "id");
            return new Parts(modern, id, model, refs, moc, textureFiles);
        }
        String key(String modernKey, String legacyKey) { return modern ? modernKey : legacyKey; }

        List<String> commands(String group) throws Exception {
            List<String> result = new ArrayList<>();
            JSONArray entries = groups.optJSONArray(group);
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                result.add(entry.optString(modern ? "Command" : "command"));
                result.add(entry.optString(modern ? "PostCommand" : "post_command"));
            }
            return result;
        }
    }
}
