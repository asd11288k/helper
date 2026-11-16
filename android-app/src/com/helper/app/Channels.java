package com.helper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * 多渠道配置。内置模型置顶、不可编辑、默认启用且不可删除。
 * 其余渠道为 OpenAI 兼容上游：{id,name,baseUrl,apiKey,model,models,enabled}。
 * models 是该渠道快速填入的多个模型，model 是当前启用的那个；都持久化。
 * 纯 JVM 类，不依赖 Android SDK；密钥只存 App 私有目录，publicJson 绝不回显。
 */
public final class Channels {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String BUILTIN_ID = "builtin";
    public static final String BUILTIN_NAME = "内置模型";

    private final File file;
    private JSONArray channels;

    public Channels(File file) {
        this.file = file;
        this.channels = load();
    }

    public File file() { return file; }

    private JSONArray load() {
        try {
            if (!file.exists()) return defaults();
            byte[] b = readAll(file);
            if (b.length == 0) return defaults();
            JSONObject root = new JSONObject(new String(b, UTF8));
            JSONArray a = root.optJSONArray("channels");
            if (a == null) return defaults();
            // 保证内置渠道永远存在且置顶
            return withBuiltinFirst(a);
        } catch (Exception e) {
            return defaults();
        }
    }

    private static JSONArray defaults() {
        JSONArray a = new JSONArray();
        a.put(builtin());
        return a;
    }

    private static JSONObject builtin() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", BUILTIN_ID).put("name", BUILTIN_NAME)
                    .put("type", "builtin").put("enabled", true)
                    .put("locked", true).put("baseUrl", "").put("model", "")
                    .put("models", new JSONArray().put(BUILTIN_NAME));
        } catch (JSONException ignored) {}
        return o;
    }

    /** 返回新数组：内置渠道置顶（忽略存储里任何伪造的 builtin），其余保持顺序。旧渠道补 providerId。 */
    private static JSONArray withBuiltinFirst(JSONArray stored) {
        JSONArray out = new JSONArray();
        out.put(builtin());
        for (int i = 0; i < stored.length(); i++) {
            JSONObject c = stored.optJSONObject(i);
            if (c == null) continue;
            if (BUILTIN_ID.equals(c.optString("id", ""))) continue;
            if (!c.has("providerId")) {
                try { c.put("providerId", ""); } catch (JSONException ignored) {}
            }
            out.put(c);
        }
        return out;
    }

    public JSONArray channels() { return channels; }

    /** 给前端：不含 apiKey，只给 hasKey。 */
    public JSONArray publicJson() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.optJSONObject(i);
            if (c == null) continue;
            JSONObject p = new JSONObject();
            try {
                p.put("id", c.optString("id", ""));
                p.put("name", c.optString("name", ""));
                p.put("type", c.optString("type", "openai"));
                p.put("providerId", c.optString("providerId", ""));
                p.put("enabled", c.optBoolean("enabled", true));
                p.put("locked", c.optBoolean("locked", false));
                p.put("baseUrl", c.optString("baseUrl", ""));
                p.put("model", c.optString("model", ""));
                p.put("models", modelsArray(c));
                p.put("hasKey", c.optString("apiKey", "").length() > 0);
            } catch (JSONException ignored) {}
            out.put(p);
        }
        return out;
    }

    public JSONObject find(String id) {
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.optJSONObject(i);
            if (c != null && id.equals(c.optString("id", ""))) return c;
        }
        return null;
    }

    /** 新建 OpenAI 兼容渠道；返回给前端的公开对象。 */
    public synchronized JSONObject create(String name, String baseUrl, String apiKey, String model) throws JSONException {
        return create(name, baseUrl, apiKey, model, null);
    }

    /** models：一个渠道可快速填入并保存多个模型；model 仍作为当前启用项。 */
    public synchronized JSONObject create(String name, String baseUrl, String apiKey,
                                           String model, JSONArray models) throws JSONException {
        return createFull(name, baseUrl, apiKey, model, models, "");
    }

    /** 带 providerId 的创建（需求⑫1：API 提供商协议）。 */
    public synchronized JSONObject createFull(String name, String baseUrl, String apiKey,
                                              String model, JSONArray models, String providerId) throws JSONException {
        String id = "ch" + System.currentTimeMillis() + (int) (Math.random() * 10000);
        String url = trimSlash(baseUrl);
        JSONObject c = new JSONObject()
                .put("id", id).put("name", name.trim())
                .put("type", "openai").put("enabled", true).put("locked", false)
                .put("providerId", providerId == null ? "" : providerId.trim())
                .put("baseUrl", url).put("apiKey", apiKey == null ? "" : apiKey.trim())
                .put("model", model == null ? "" : model.trim())
                .put("models", normModels(models, model));
        channels.put(c);
        save();
        return publicOf(c);
    }

    /** 更新；内置渠道拒绝修改。key 留空表示保留旧值。 */
    public synchronized JSONObject update(String id, String name, String baseUrl,
                                           String apiKey, String model) throws JSONException {
        return update(id, name, baseUrl, apiKey, model, null);
    }

    /** 带 models 的更新；models 省略时保留渠道已存的多模型列表（并把 model 补进去）。 */
    public synchronized JSONObject update(String id, String name, String baseUrl,
                                           String apiKey, String model, JSONArray models) throws JSONException {
        if (BUILTIN_ID.equals(id)) return null;
        JSONObject c = find(id);
        if (c == null) return null;
        c.put("name", name.trim());
        c.put("baseUrl", trimSlash(baseUrl));
        if (apiKey != null && apiKey.trim().length() > 0) c.put("apiKey", apiKey.trim());
        c.put("model", model == null ? "" : model.trim());
        if (models != null) c.put("models", normModels(models, model));
        else c.put("models", withModel(c.optJSONArray("models"), model));
        save();
        return publicOf(c);
    }

    /** 拉取 /models 后把列表并入渠道的多模型字段（去重，保留当前 model）。 */
    public synchronized JSONArray mergeModels(String id, JSONArray pulled) throws JSONException {
        JSONObject c = find(id);
        if (c == null) return new JSONArray();
        String cur = c.optString("model", "");
        c.put("models", normModels(mergeArrays(c.optJSONArray("models"), pulled), cur));
        save();
        return c.optJSONArray("models");
    }

    private static JSONArray mergeArrays(JSONArray a, JSONArray b) {
        JSONArray out = new JSONArray();
        if (a != null) for (int i = 0; i < a.length(); i++) out.put(a.opt(i));
        if (b != null) for (int i = 0; i < b.length(); i++) out.put(b.opt(i));
        return out;
    }

    public synchronized JSONObject setEnabled(String id, boolean enabled) throws JSONException {
        if (BUILTIN_ID.equals(id)) return null; // 内置不可停用
        JSONObject c = find(id);
        if (c == null) return null;
        c.put("enabled", enabled);
        save();
        return publicOf(c);
    }

    public synchronized boolean delete(String id) {
        if (BUILTIN_ID.equals(id)) return false; // 内置不可删除
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.optJSONObject(i);
            if (c != null && id.equals(c.optString("id", ""))) { removed = true; continue; }
            next.put(c);
        }
        if (removed) { channels = next; save(); }
        return removed;
    }

    /** 默认选中的渠道：优先内置（需求：默认内置模型）。 */
    public String defaultId() { return BUILTIN_ID; }

    private static JSONObject publicOf(JSONObject c) throws JSONException {
        return new JSONObject()
                .put("id", c.optString("id", ""))
                .put("name", c.optString("name", ""))
                .put("type", c.optString("type", "openai"))
                .put("enabled", c.optBoolean("enabled", true))
                .put("locked", c.optBoolean("locked", false))
                .put("baseUrl", c.optString("baseUrl", ""))
                .put("model", c.optString("model", ""))
                .put("models", modelsArray(c))
                .put("hasKey", c.optString("apiKey", "").length() > 0);
    }

    /** 公开的模型列表：始终包含当前 model，避免旧渠道没有 models 字段。 */
    private static JSONArray modelsArray(JSONObject c) {
        return withModel(c.optJSONArray("models"), c.optString("model", ""));
    }

    private static JSONArray normModels(JSONArray src, String model) {
        JSONArray out = new JSONArray();
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                String m = src.optString(i, "").trim();
                if (m.length() > 0 && !contains(out, m)) out.put(m);
            }
        }
        if (model != null && model.trim().length() > 0 && !contains(out, model.trim())) out.put(model.trim());
        return out;
    }

    private static JSONArray withModel(JSONArray src, String model) {
        return normModels(src, model);
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i, ""))) return true;
        return false;
    }

    private static String trimSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String text = new JSONObject().put("channels", channels).toString();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(text); w.close();
            if (file.exists() && !file.delete()) { /* ignore */ }
            if (!tmp.renameTo(file)) {
                Writer w2 = new OutputStreamWriter(new FileOutputStream(file), UTF8);
                w2.write(text); w2.close();
                tmp.delete();
            }
        } catch (Exception e) { /* 落盘失败不抛，避免聊天中断 */ }
    }

    private static byte[] readAll(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) raf.length()];
        raf.readFully(b);
        raf.close();
        return b;
    }
}