package com.helper.app;

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
 * 上游协议层：与 Python 版 api.py 完全对齐，且不依赖 Android SDK（可在 JVM 上单测）。
 *
 * 关键实测结论：该第三方中转忽略 system 角色消息和 user_content 前缀，
 * 只认 messages 里的 user/assistant 对话轮，因此设定以「用户下达 + AI 确认」伪装注入。
 */
public final class Core {
    public static final String API_URL = "http://b20260825.chat.aiznyy.com/api/chat/ajax_completions";
    public static final String TOKEN = "c9d03ee55889e6556ed4c19ee74b8e6070b29880";
    // 服务端实际固定走豆包 Seed 2.0 Lite，此名称不生效，但保留与网页版一致的 payload
    public static final String MODEL = "gemini-3.1-pro-preview";
    public static final String SETTING_WRAPPER = "请在整个对话中严格扮演并遵守以下角色设定：\n";
    public static final String SETTING_ACK = "好的，我会严格按照以上设定与你对话。";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Core() {}

    /** 组装 messages：设定轮在最前，其后是持久化历史与当前问题。设定轮不写入 history。 */
    public static JSONArray buildMessages(String prompt, JSONArray history, String setting) throws JSONException {
        return buildMessages(prompt, history, setting, "");
    }

    /** 技能提示词（upstreamPrompt）与角色设定合并后一起后台注入，两者都不写入 history。 */
    public static JSONArray buildMessages(String prompt, JSONArray history, String setting,
                                          String upstreamPrompt) throws JSONException {
        String combined = joinPrompt(setting, upstreamPrompt);
        JSONArray msgs = new JSONArray();
        if (combined.length() > 0) {
            msgs.put(new JSONObject()
                    .put("role", "user")
                    .put("content", SETTING_WRAPPER + combined));
            msgs.put(new JSONObject()
                    .put("role", "assistant")
                    .put("content", SETTING_ACK));
        }
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                Object o = history.opt(i);
                if (o != null) msgs.put(o);
            }
        }
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        return msgs;
    }

    private static String joinPrompt(String setting, String upstreamPrompt) {
        String a = setting == null ? "" : setting.trim();
        String b = upstreamPrompt == null ? "" : upstreamPrompt.trim();
        if (a.length() == 0) return b;
        if (b.length() == 0) return a;
        return a + "\n\n" + b;
    }

    /** 上游请求体，字段逐一对应 Python 版 _payload()。 */
    public static JSONObject payload(String prompt, JSONArray messages) throws JSONException {
        return new JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("key", "")
                .put("is_vision", false)
                .put("user_images", new JSONArray())
                .put("user_content", prompt)
                .put("is_done", 1)
                .put("thinking", "enabled")
                .put("is_url", "1")
                .put("chat_group_id", 5573739)
                .put("conversation_id", "");
    }

    /**
     * 解析 [-DATA-:] 分隔的流式 JSON。
     * complete_answer 是逐段累积增长的，取最长的有效值；否则回退累加增量 answer。
     */
    public static String extractAnswer(String rawText) {
        if (rawText == null) return "";
        String lastComplete = "";
        StringBuilder incremental = new StringBuilder();
        String[] parts = rawText.split("\\[-DATA-:\\]");
        for (String part : parts) {
            String s = part.trim();
            if (s.length() == 0 || s.equals("[DONE]")) continue;
            JSONObject d;
            try {
                d = new JSONObject(s);
            } catch (Exception e) {
                continue; // 脏分片忽略
            }
            String c = d.optString("complete_answer", "");
            if (c.length() > 0 && !c.contains("正在思考") && c.length() > lastComplete.length()) {
                lastComplete = c;
            }
            String a = d.optString("answer", "");
            if (a.length() > 0 && !a.contains("正在思考")) {
                incremental.append(a);
            }
        }
        String result = lastComplete.length() > 0 ? lastComplete : incremental.toString();
        return result.replace("正在思考中，请稍后...", "").trim();
    }

    /** 阻塞式对话；成功时把本轮 user/assistant 追加进 history（与 Python 版一致）。 */
    public static String chat(String prompt, JSONArray history, String setting) {
        return chat(prompt, history, setting, "");
    }

    /** AgentLoop 接缝：单次调用内置中转，不写 history；extra 为工具结果临时消息。 */
    public static String chatOnce(String prompt, JSONArray history, String setting,
                                  String upstreamPrompt, JSONArray extra) {
        HttpURLConnection conn = null;
        try {
            JSONArray messages = buildMessages(prompt, history, setting, upstreamPrompt);
            if (extra != null && extra.length() > 0) {
                // 当前 user 轮移到最后：先移除末尾，插 extra，再补回
                JSONObject last = messages.optJSONObject(messages.length() - 1);
                if (last != null) messages.remove(messages.length() - 1);
                for (int i = 0; i < extra.length(); i++) {
                    Object o = extra.opt(i);
                    if (o != null) messages.put(o);
                }
                if (last != null) messages.put(last);
            }
            String body = payload(prompt, messages).toString();
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("token", TOKEN);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36");
            conn.setRequestProperty("Origin", "http://b20260825.chat.aiznyy.com");
            conn.setRequestProperty("X-Requested-With", "com.tencent.mm");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8)); os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "请求失败: HTTP " + code;
            String raw = readAll(conn.getInputStream());
            String answer = extractAnswer(raw);
            return answer.length() > 0 ? answer : "AI返回空内容";
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** upstreamPrompt：@技能 的提示词，只随本次请求注入上游，不写入 history。 */
    public static String chat(String prompt, JSONArray history, String setting, String upstreamPrompt) {
        if (history == null) history = new JSONArray();
        HttpURLConnection conn = null;
        try {
            JSONArray messages = buildMessages(prompt, history, setting, upstreamPrompt);
            String body = payload(prompt, messages).toString();
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("token", TOKEN);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36");
            conn.setRequestProperty("Origin", "http://b20260825.chat.aiznyy.com");
            conn.setRequestProperty("X-Requested-With", "com.tencent.mm");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8));
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return "请求失败: HTTP " + code;
            }
            String raw = readAll(conn.getInputStream());
            String answer = extractAnswer(raw);
            if (answer.length() > 0) {
                history.put(new JSONObject().put("role", "user").put("content", prompt));
                history.put(new JSONObject().put("role", "assistant").put("content", answer));
                return answer;
            }
            return "AI返回空内容";
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), UTF8);
    }
}