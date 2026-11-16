package com.doubao.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 需求⑫(1)：非 OpenAI 协议的上游客户端（Gemini / Anthropic 原生格式）。
 * 统一入口 chat()，成功把「user/assistant」轮追加进 history（与 OpenAiClient 契约一致）。
 * images：data URL 数组（data:image/png;base64,....），识图模型直接视觉识别。
 * 纯 JVM 类，不依赖 Android SDK。
 */
public final class ProtocolClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private ProtocolClient() {}

    public static String chat(String protocol, String baseUrl, String apiKey, String model,
                              String prompt, JSONArray history, String setting,
                              String upstreamPrompt, JSONArray images) {
        if ("gemini".equals(protocol)) {
            return gemini(baseUrl, apiKey, model, prompt, history, setting, upstreamPrompt, images);
        }
        if ("anthropic".equals(protocol)) {
            return anthropic(baseUrl, apiKey, model, prompt, history, setting, upstreamPrompt, images);
        }
        return "请求失败: 未知协议 " + protocol;
    }

    // ---------- Gemini 原生：POST {base}/models/{model}:generateContent?key=KEY ----------
    private static String gemini(String base, String key, String model, String prompt,
                                 JSONArray hist, String setting, String skill, JSONArray images) {
        try {
            JSONArray contents = new JSONArray();
            // 历史：user/model 交替；设定+技能拼成首条 user 伪装轮（Gemini 有 systemInstruction，但为兼容旧语义统一走 contents）
            StringBuilder sys = new StringBuilder();
            String a = setting == null ? "" : setting.trim();
            String b = skill == null ? "" : skill.trim();
            if (a.length() > 0) sys.append(a);
            if (b.length() > 0) { if (sys.length() > 0) sys.append("\n\n"); sys.append(b); }
            if (sys.length() > 0) {
                contents.put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", sys.toString()))));
                contents.put(new JSONObject().put("role", "model")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", "好的，我会按以上要求执行。"))));
            }
            if (hist != null) {
                for (int i = 0; i < hist.length(); i++) {
                    JSONObject m = hist.optJSONObject(i);
                    if (m == null) continue;
                    String role = "user".equals(m.optString("role")) ? "user" : "model";
                    contents.put(new JSONObject().put("role", role)
                            .put("parts", new JSONArray().put(new JSONObject().put("text", m.optString("content", "")))));
                }
            }
            // 当前轮：文本 + 可选图片
            JSONArray parts = new JSONArray();
            if (sys.length() > 0) { /* 设定已作为伪装轮 */ }
            parts.put(new JSONObject().put("text", prompt == null ? "" : prompt));
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    String du = images.optString(i, "");
                    int comma = du.indexOf(",base64,");
                    if (comma < 0) continue;
                    String mime = du.substring(5, comma);          // data:image/png;base64 → image/png
                    String b64 = du.substring(comma + 8);
                    JSONObject inline = new JSONObject().put("mime_type", mime).put("data", b64);
                    parts.put(new JSONObject().put("inline_data", inline));
                }
            }
            contents.put(new JSONObject().put("role", "user").put("parts", parts));

            JSONObject payload = new JSONObject().put("contents", contents)
                    .put("generationConfig", new JSONObject().put("temperature", 0.7));
            String url = base + "/models/" + model + ":generateContent?key=" + key;
            String raw = post(url, payload.toString(), "Content-Type", "application/json", null);
            JSONObject root = new JSONObject(raw);
            JSONArray cands = root.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) {
                JSONObject err = root.optJSONObject("error");
                return "请求失败: " + (err != null ? err.optString("message", raw) : "无候选结果");
            }
            String answer = cands.getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).optString("text", "");
            if (answer.length() == 0) return "AI返回空内容";
            appendHist(hist, prompt, answer);
            return answer;
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        }
    }

    // ---------- Anthropic 原生：POST {base}/v1/messages，x-api-key + anthropic-version ----------
    private static String anthropic(String base, String key, String model, String prompt,
                                    JSONArray hist, String setting, String skill, JSONArray images) {
        try {
            JSONObject payload = new JSONObject()
                    .put("model", model).put("max_tokens", 4096);
            String sys = join(setting, skill);
            if (sys.length() > 0) payload.put("system", sys);
            JSONArray msgs = new JSONArray();
            if (hist != null) {
                for (int i = 0; i < hist.length(); i++) {
                    JSONObject m = hist.optJSONObject(i);
                    if (m == null) continue;
                    String role = "user".equals(m.optString("role")) ? "user" : "assistant";
                    JSONObject um = new JSONObject().put("role", role);
                    JSONArray content = new JSONArray();
                    if ("assistant".equals(role)) {
                        content.put(new JSONObject().put("type", "text").put("text", m.optString("content", "")));
                    } else {
                        content.put(new JSONObject().put("type", "text").put("text", m.optString("content", "")));
                    }
                    um.put("content", content);
                    msgs.put(um);
                    // Claude 要求 user/assistant 严格交替，历史缺轮时补齐
                    if ("user".equals(role)) { /* 下一轮 assistant 缺失时由 Anthropic 报错，此处保持原样 */ }
                }
            }
            // 当前轮 user：text + image 块
            JSONArray cur = new JSONArray();
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    String du = images.optString(i, "");
                    int comma = du.indexOf(",base64,");
                    if (comma < 0) continue;
                    String media = du.substring(5, comma);   // image/png
                    String b64 = du.substring(comma + 8);
                    JSONObject src = new JSONObject().put("type", "base64")
                            .put("media_type", media).put("data", b64);
                    cur.put(new JSONObject().put("type", "image").put("source", src));
                }
            }
            cur.put(new JSONObject().put("type", "text").put("text", prompt == null ? "" : prompt));
            msgs.put(new JSONObject().put("role", "user").put("content", cur));
            payload.put("messages", msgs);

            String body = payload.toString();
            URL u = new URL(base + "/v1/messages");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000); conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", key == null ? "" : key);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8)); os.close();
            int code = conn.getResponseCode();
            String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 401 || code == 403) return "请求失败: HTTP 401/403，API 密钥无效或无权限";
            if (code < 200 || code >= 300) return "请求失败: HTTP " + code + " " + briefErr(raw);
            JSONObject root = new JSONObject(raw);
            if (root.has("error")) {
                JSONObject e = root.optJSONObject("error");
                return "请求失败: " + (e != null ? e.optString("message", raw) : raw);
            }
            JSONArray content = root.optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject blk = content.optJSONObject(i);
                    if (blk != null && "text".equals(blk.optString("type"))) sb.append(blk.optString("text", ""));
                }
            }
            String answer = sb.toString().trim();
            if (answer.length() == 0) return "AI返回空内容";
            appendHist(hist, prompt, answer);
            return answer;
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        }
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.length() == 0) return y;
        if (y.length() == 0) return x;
        return x + "\n\n" + y;
    }

    private static void appendHist(JSONArray hist, String prompt, String answer) {
        try {
            if (hist != null) {
                hist.put(new JSONObject().put("role", "user").put("content", prompt));
                hist.put(new JSONObject().put("role", "assistant").put("content", answer));
            }
        } catch (JSONException ignored) {}
    }

    private static String briefErr(String raw) {
        try {
            JSONObject e = new JSONObject(raw).optJSONObject("error");
            if (e != null) return e.optString("message", "");
        } catch (Exception ignored) {}
        return "";
    }

    private static String post(String url, String body, String h1k, String h1v, String h2) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000); conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty(h1k, h1v);
            if (h2 != null) conn.setRequestProperty(h2.split(":")[0], h2.split(":").length > 1 ? h2.split(":")[1] : "");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8)); os.close();
            int code = conn.getResponseCode();
            String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + " " + briefErr(raw));
            return raw;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), UTF8);
    }
}