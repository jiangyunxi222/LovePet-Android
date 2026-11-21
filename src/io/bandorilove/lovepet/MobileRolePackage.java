package io.bandorilove.lovepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

final class MobileRolePackage {
    static final String FORMAT = "bandorilovepet-role";
    private static final Set<String> REGIONS = Set.of("body", "left", "right", "expression", "voice", "wardrobe", "menu");

    static JSONObject extract(ModelStore.ZipSource source, JSONObject manifest, File folder, boolean lpk) throws Exception {
        if (!Integer.valueOf(1).equals(manifest.opt("version"))) throw new IOException("手机版角色包协议版本不受支持。");
        JSONObject hashes = manifest.getJSONObject("files_sha256");
        Set<String> declared = new HashSet<>();
        declared.add("manifest.json");
        long budget = ModelStore.MAX_BYTES;
        for (Iterator<String> names = hashes.keys(); names.hasNext();) {
            String name = ModelStore.safe(names.next());
            String digest = hashes.getString(name);
            if (!declared.add(name) || !digest.matches("[a-f0-9]{64}")) throw new IOException("角色包清单无效：" + name);
            if (name.equals("runtime/" + ModelStore.CORE2) || name.equals("runtime/" + ModelStore.CORE3)) {
                if (!ModelStore.RUNTIME.get(name.substring("runtime/".length())).equals(digest)) {
                    throw new IOException("角色包的 Live2D 运行库版本不受支持：" + name);
                }
                continue;
            }
            if (!(name.equals("catalog.json") || (lpk && name.equals("config.mlve")) || (!name.contains("/") && ModelStore.modelJson(name))
                    || (name.startsWith("assets/") && ModelStore.MEDIA.contains(ModelStore.extension(name))))) {
                throw new IOException("手机版角色包包含不支持的文件：" + name);
            }
            try (InputStream input = source.open(name)) {
                budget -= ModelStore.copy(input, new File(folder, name), budget, digest);
            }
        }
        if (!declared.equals(source.entries()) || !declared.contains("catalog.json") || !declared.contains("runtime/" + ModelStore.CORE3)) {
            throw new IOException("角色包文件和清单不一致，或缺少目录索引、Live2D 运行库。");
        }
        if (lpk) {
            JSONObject config = source.object("config.mlve");
            if (!"STD2_0".equals(config.optString("type")) || !Boolean.FALSE.equals(config.opt("encrypt"))) {
                throw new IOException("手机版 LPK 的容器配置无效。");
            }
        }
        JSONObject catalog = source.object("catalog.json");
        if (!name(catalog, "id").matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("角色编号无效。");
        for (String key : Set.of("name", "game", "group")) name(catalog, key);
        JSONArray costumes = catalog.getJSONArray("costumes");
        if (costumes.length() == 0) throw new IOException("角色包至少需要一件衣装。");
        Set<String> modelFiles = new HashSet<>();
        for (int i = 0; i < costumes.length(); i++) {
            JSONObject outfit = costumes.getJSONObject(i);
            String file = outfit.getString("file");
            if (file.contains("/") || !ModelStore.modelJson(file) || !declared.contains(file) || !modelFiles.add(file)) {
                throw new IOException("衣装模型文件无效：" + file);
            }
            Object cubism = outfit.opt("cubism3");
            if (!(cubism instanceof Boolean)) throw new IOException("衣装缺少 Cubism 版本：" + file);
            boolean modern = (Boolean) cubism;
            if (modern != file.toLowerCase(java.util.Locale.ROOT).endsWith(".model3.json")) throw new IOException("衣装使用了错误的 Cubism 版本：" + file);
            if (!modern && !declared.contains("runtime/" + ModelStore.CORE2)) throw new IOException("Cubism 2 衣装缺少经典运行库。");
            validateModel(source.object(file), modern, declared);
            items(outfit.getJSONArray("actions"), "动作", declared);
            items(outfit.getJSONArray("expressions"), "表情", declared);
            JSONObject hit = outfit.optJSONObject("hitRegions");
            if (hit != null) {
                for (Iterator<String> regions = hit.keys(); regions.hasNext();) {
                    String region = regions.next();
                    JSONArray box = hit.getJSONArray(region);
                    if (!REGIONS.contains(region) || box.length() != 4 || number(box, 0) > number(box, 2) || number(box, 1) > number(box, 3)) {
                        throw new IOException("触发区域坐标无效：" + region);
                    }
                }
            }
        }
        items(costumes, "衣装", declared);
        items(catalog.getJSONArray("voices"), "语音", declared);
        return new JSONObject().put("catalog", catalog).put("limited", false);
    }

    private static void validateModel(JSONObject model, boolean modern, Set<String> paths) throws Exception {
        JSONObject refs = modern ? model.getJSONObject("FileReferences") : model;
        asset(paths, refs.getString(modern ? "Moc" : "model"), modern ? Set.of("moc3") : Set.of("moc"));
        JSONArray textures = refs.getJSONArray(modern ? "Textures" : "textures");
        if (textures.length() == 0) throw new IOException("衣装的贴图列表为空。");
        for (int i = 0; i < textures.length(); i++) asset(paths, textures.getString(i), Set.of("png", "jpg", "jpeg", "webp"));
        for (String key : modern ? Set.of("Physics", "Pose") : Set.of("physics", "pose")) {
            if (refs.has(key)) asset(paths, refs.getString(key), Set.of("json"));
        }
        JSONObject groups = refs.optJSONObject(modern ? "Motions" : "motions");
        JSONArray idle = groups == null ? null : groups.optJSONArray(modern ? "Idle" : "idle");
        for (int i = 0; idle != null && i < idle.length(); i++) {
            asset(paths, idle.getJSONObject(i).getString(modern ? "File" : "file"), Set.of("mtn", "json"));
        }
    }

    private static void items(JSONArray list, String kind, Set<String> paths) throws Exception {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>(), flags = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String name = name(item, "name");
            Object number = item.opt("id");
            if (!(number instanceof Integer) || ((Integer) number) < 0 || !ids.add((Integer) number)
                    || !names.add(name) || name.matches(".*[（(][0-9]+[）)]$")) {
                throw new IOException(kind + "编号或名称重复、不规范：" + name);
            }
            JSONArray formerIds = item.optJSONArray("aliasIds");
            for (int j = 0; formerIds != null && j < formerIds.length(); j++) {
                Object former = formerIds.get(j);
                if (!(former instanceof Integer) || (Integer) former < 0 || !ids.add((Integer) former)) {
                    throw new IOException(kind + "旧编号无效或重复：" + name);
                }
            }
            flag(flags, item.getString("flag"));
            JSONArray aliases = item.optJSONArray("aliasFlags");
            for (int j = 0; aliases != null && j < aliases.length(); j++) flag(flags, aliases.getString(j));
            switch (kind) {
                case "动作":
                    asset(paths, item.getString("file"), Set.of("mtn", "json"));
                    JSONArray regions = item.getJSONArray("categories");
                    Set<String> used = new HashSet<>();
                    for (int j = 0; j < regions.length(); j++) {
                        String region = regions.getString(j);
                        if (!Set.of("body", "left", "right").contains(region) || !used.add(region)) {
                            throw new IOException("动作触发区无效：" + name);
                        }
                    }
                    break;
                case "表情":
                    String file = item.optString("file", "");
                    if (!file.isEmpty()) asset(paths, file, Set.of("json"));
                    break;
                case "语音":
                    asset(paths, item.getString("file"), Set.of("wav", "mp3", "ogg", "m4a", "flac"));
                    name(item, "caption");
                    break;
                default: break;
            }
        }
    }

    private static void flag(Set<String> flags, String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{1,128}") || !flags.add(value)) throw new IOException("收藏标识无效或重复：" + value);
    }

    private static String name(JSONObject item, String key) throws Exception {
        Object raw = item.opt(key);
        String value = raw instanceof String ? ((String) raw).trim() : "";
        if (value.isEmpty()) throw new IOException("角色包缺少名称或说明：" + key);
        return value;
    }

    private static double number(JSONArray values, int index) throws Exception {
        Object value = values.get(index);
        if (!(value instanceof Number)) throw new IOException("触发区域坐标必须是数字。");
        return ((Number) value).doubleValue();
    }

    private static void asset(Set<String> paths, String value, Set<String> extensions) throws IOException {
        if (!value.startsWith("assets/") || !paths.contains(value) || !extensions.contains(ModelStore.extension(value))) {
            throw new IOException("角色包资源路径无效或缺少文件：" + value);
        }
    }
}
