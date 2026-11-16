package com.doubao.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * OpenAI 兼容上游配置：Base URL / API Key / 模型名。纯 JVM 类，不依赖 Android SDK。
 * 存到 App 私有目录的 settings.json；密钥不进入 WebView 的 DOM。
 */
public final class Settings {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final File file;
    private String baseUrl = "", apiKey = "", model = "";

    public Settings(File file) {
        this.file = file;
        load();
    }

    public File file() { return file; }
    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public boolean isConfigured() {
        return baseUrl.length() > 0 && model.length() > 0;
    }

    /** 保存；baseUrl 去掉尾部斜杠，避免拼出 //chat/completions。 */
    public synchronized void update(String url, String key, String m) {
        baseUrl = url == null ? "" : url.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        apiKey = key == null ? "" : key.trim();
        model = m == null ? "" : m.trim();
        save();
    }

    /** 给前端看的配置：绝不返回 apiKey，只告诉是否已设置。 */
    public synchronized JSONObject publicJson() {
        // Android 版 org.json 的 put() 抛受检 JSONException，这里显式构造以兼容两端
        JSONObject o = new JSONObject();
        try {
            o.put("baseUrl", baseUrl);
            o.put("model", model);
            o.put("hasKey", apiKey.length() > 0);
            o.put("configured", isConfigured());
        } catch (JSONException e) {
            // 键均为合法字符串，正常不会走到这里
        }
        return o;
    }

    private void load() {
        try {
            if (!file.exists()) return;
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b); raf.close();
            if (b.length == 0) return;
            JSONObject o = new JSONObject(new String(b, UTF8));
            baseUrl = o.optString("baseUrl", "").trim();
            while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            apiKey = o.optString("apiKey", "");
            model = o.optString("model", "");
        } catch (Exception e) {
            baseUrl = ""; apiKey = ""; model = ""; // 损坏文件按空配置处理
        }
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String text = new JSONObject()
                    .put("baseUrl", baseUrl).put("apiKey", apiKey).put("model", model).toString();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(text); w.close();
            if (file.exists() && !file.delete()) { /* ignore */ }
            if (!tmp.renameTo(file)) {
                Writer w2 = new OutputStreamWriter(new FileOutputStream(file), UTF8);
                w2.write(text); w2.close();
                tmp.delete();
            }
        } catch (Exception e) {
            // 落盘失败不抛，避免聊天中断
        }
    }
}