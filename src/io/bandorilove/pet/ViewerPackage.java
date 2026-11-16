package io.bandorilove.pet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ViewerPackage {
    private static final Pattern SUBMODEL = Pattern.compile("(?:^|;)\\s*add_submodel\\s+([0-9a-f]{32}\\.bin3?)(?=\\s|;|$)");
    private static final Pattern TEXTURE = Pattern.compile("(?:^|;)\\s*replace_tex\\s+(\\d{1,5})\\s+([0-9a-f]{32}\\.bin3?)(?=\\s|;|$)");
    private final ModelStore.ZipSource source;
    private final Function mask;
    private final File folder;
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
            if (parts.carrier) parts = loader.submodel(parts);
            JSONObject preview = loader.assets(parts);
            String title = workshop == null ? "" : workshop.optString("title");
            if (title.isEmpty()) title = meta.optString("name", "Live2D 模型");
            return ModelStore.renderOnly(output, title, parts.modern, preview);
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
        if (workshop == null) throw new IOException("创意工坊 LPK 请使用完整 WPK，或通过模型目录同时导入 config.json");
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

    private Parts submodel(Parts root) throws Exception {
        List<String> commands = root.commands(root.modern ? "Start" : "start");
        commands.addAll(root.commands("carrier_switch_001"));
        for (String command : commands) {
            Matcher match = SUBMODEL.matcher(command);
            if (match.find() && source.has(match.group(1))) {
                Parts child = Parts.read(model(match.group(1)));
                if (child != null) return child;
            }
        }
        return root;
    }

    private JSONObject assets(Parts parts) throws Exception {
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
                "model", parts.modern ? "moc3" : "moc"));
        JSONArray textures = new JSONArray();
        for (int i = 0; i < parts.textures.size(); i++) {
            textures.put(asset(parts.textures.get(i), "texture-" + i, "texture"));
        }
        result.put(parts.modern ? "Textures" : "textures", textures);
        for (String key : parts.modern ? List.of("Physics", "Pose") : List.of("physics", "pose")) {
            if (parts.refs.has(key)) result.put(key, asset(parts.refs.getString(key), key.toLowerCase(Locale.ROOT), "json"));
        }
        return result;
    }

    private String asset(String reference, String label, String kind) throws Exception {
        String name = ModelStore.relative(reference);
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
            String file = label + suffix(buffer, count, kind);
            long offset = 0;
            try (OutputStream out = new FileOutputStream(new File(root, file))) {
                while (count >= 0) {
                    remaining -= count;
                    if (remaining < 0) throw new IOException("模型资源超过 2 GB");
                    if (offset > 0) decode(buffer, count, key, offset);
                    out.write(buffer, 0, count);
                    offset += count;
                    count = packed.read(buffer);
                }
            }
            return file;
        }
    }

    private static String suffix(byte[] data, int count, String kind) throws IOException {
        if (count >= 4) {
            String magic = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
            if (kind.equals("moc3") && magic.equals("MOC3")) return ".moc3";
            if (kind.equals("moc") && magic.startsWith("moc")) return ".moc";
            if (kind.equals("json") && text(data, count).trim().startsWith("{")) return ".json";
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
        final boolean carrier;
        final JSONObject refs;
        final String mocKey;
        final List<String> textures;
        private Parts(boolean modern, boolean carrier, JSONObject refs, String mocKey, List<String> textures) {
            this.modern = modern;
            this.carrier = carrier;
            this.refs = refs;
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
            boolean carrier = options != null && options.optString(modern ? "Id" : "id").contains("l2d_carrier_");
            return new Parts(modern, carrier, refs, moc, textureFiles);
        }
        List<String> commands(String group) throws Exception {
            List<String> result = new ArrayList<>();
            JSONObject groups = refs.optJSONObject(modern ? "Motions" : "motions");
            JSONArray entries = groups == null ? null : groups.optJSONArray(group);
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                result.add(entry.optString(modern ? "Command" : "command"));
                result.add(entry.optString(modern ? "PostCommand" : "post_command"));
            }
            return result;
        }
    }
}
