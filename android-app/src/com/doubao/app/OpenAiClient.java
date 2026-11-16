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
 * OpenAI 兼容 Chat Completions 上游。纯 JVM 类，不依赖 Android SDK。
 *
 * POST {baseUrl}/chat/completions，Authorization: Bearer <key>，
 * 请求体 {model, stream:false, messages:[system?, ...history, user]}，
 * 响应取 choices[0].message.content。
 * 与旧 Core（第三方中转）的区别：这里原生支持 system 角色，不再用「伪装对话轮」注入设定。
 */
public final class OpenAiClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private OpenAiClient() {}

    /** 组装 messages：有设定时首条为 system，其后是持久化历史与当前问题。设定不写 history。 */
    public static JSONArray buildMessages(String prompt, JSONArray history, String setting) throws JSONException {
        return buildMessages(prompt, history, setting, "");
    }

    /** 技能提示词（upstreamPrompt）与角色设定合并为 system 注入，不写入 history。 */
    public static JSONArray buildMessages(String prompt, JSONArray history, String setting,
                                          String upstreamPrompt) throws JSONException {
        String combined = joinPrompt(setting, upstreamPrompt);
        JSONArray msgs = new JSONArray();
        if (combined.length() > 0) {
            msgs.put(new JSONObject().put("role", "system").put("content", combined));
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

    public static JSONObject payload(String model, JSONArray messages) throws JSONException {
        return payload(model, messages, "");
    }

    /** #16：思考等级作为 reasoning_effort 请求参数传给模型；空串表示不传。 */
    public static JSONObject payload(String model, JSONArray messages, String reasoningEffort) throws JSONException {
        JSONObject o = new JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", messages);
        if (reasoningEffort != null && reasoningEffort.length() > 0) o.put("reasoning_effort", reasoningEffort);
        return o;
    }

    /** 阻塞式对话。成功返回答案文本；失败返回可读中文错误（调用方据此判断不落库）。 */
    public static String chat(String baseUrl, String apiKey, String model,
                              String prompt, JSONArray history, String setting) {
        return chat(baseUrl, apiKey, model, prompt, history, setting, "");
    }

    /** upstreamPrompt：@技能 提示词只随本次请求注入，不写入 history。 */
    public static String chat(String baseUrl, String apiKey, String model,
                              String prompt, JSONArray history, String setting, String upstreamPrompt) {
        return chatWithImages(baseUrl, apiKey, model, prompt, history, setting, upstreamPrompt, null);
    }

    /**
     * AgentLoop 接缝：单次上游调用，不写 history；extra 为工具执行结果等临时上下文消息。
     * 返回的文本可能含 [调用工具:...] 指令，由 AgentLoop 解析执行。
     */
    public static String chatOnce(String baseUrl, String apiKey, String model,
                                  String prompt, JSONArray history, String setting,
                                  String upstreamPrompt, JSONArray images, JSONArray extra) {
        HttpURLConnection conn = null;
        try {
            JSONArray msgs = new JSONArray();
            String combined = joinPrompt(setting, upstreamPrompt);
            if (combined.length() > 0) {
                msgs.put(new JSONObject().put("role", "system").put("content", combined));
            }
            if (history != null) {
                for (int i = 0; i < history.length(); i++) {
                    Object o = history.opt(i);
                    if (o != null) msgs.put(o);
                }
            }
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    Object o = extra.opt(i);
                    if (o != null) msgs.put(o);
                }
            }
            boolean hasImages = images != null && images.length() > 0;
            if (!hasImages) {
                msgs.put(new JSONObject().put("role", "user").put("content", prompt));
            } else {
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", prompt));
                for (int i = 0; i < images.length(); i++) {
                    String du = images.optString(i, "");
                    if (du.startsWith("data:")) {
                        content.put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", du)));
                    }
                }
                msgs.put(new JSONObject().put("role", "user").put("content", content));
            }
            String body = payload(model, msgs, v1114Effort()).toString();
            URL url = new URL(Upstream.chatUrl(baseUrl));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && apiKey.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8)); os.close();
            return readAnswer(conn);
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 需求⑩/#1：images 为 data URL 数组（data:image/...;base64,....）。有图时 user content 变多段视觉载荷。 */
    public static String chatWithImages(String baseUrl, String apiKey, String model,
                                        String prompt, JSONArray history, String setting,
                                        String upstreamPrompt, JSONArray images) {
        if (images == null || images.length() == 0) {
            HttpURLConnection conn = null;
            try {
                JSONArray messages = buildMessages(prompt, history, setting, upstreamPrompt);
                String body = payload(model, messages, v1114Effort()).toString();
                URL url = new URL(Upstream.chatUrl(baseUrl));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(120000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                if (apiKey != null && apiKey.length() > 0) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(UTF8)); os.close();
                String answer = readAnswer(conn);
                if (answer.startsWith("请求失败") || "AI返回空内容".equals(answer)) return answer;
                appendHist(history, prompt, answer);
                return answer;
            } catch (Exception e) {
                return "请求失败: " + e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        // 有图：user content = [ {type:text}, {type:image_url}... ]
        HttpURLConnection conn = null;
        try {
            JSONArray msgs = new JSONArray();
            String combined = joinPrompt(setting, upstreamPrompt);
            if (combined.length() > 0) {
                msgs.put(new JSONObject().put("role", "system").put("content", combined));
            }
            if (history != null) {
                for (int i = 0; i < history.length(); i++) {
                    Object o = history.opt(i);
                    if (o != null) msgs.put(o);
                }
            }
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", prompt));
            for (int i = 0; i < images.length(); i++) {
                String du = images.optString(i, "");
                if (du.startsWith("data:")) {
                    content.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", du)));
                }
            }
            msgs.put(new JSONObject().put("role", "user").put("content", content));
            String body = payload(model, msgs, v1114Effort()).toString();
            URL url = new URL(Upstream.chatUrl(baseUrl));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && apiKey.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(UTF8)); os.close();
            String answer = readAnswer(conn);
            if (answer.startsWith("请求失败") || "AI返回空内容".equals(answer)) return answer;
            // 历史里只记文本（不带 base64，避免 history 无限膨胀）
            appendHist(history, prompt, answer);
            return answer;
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** #16：由 LocalGateway 在每次请求前设置的思考等级（只影响 OpenAI 兼容渠道）。 */
    private static volatile String EFFORT = "";
    public static void setReasoningEffort(String e) { EFFORT = e == null ? "" : e; }
    private static String v1114Effort() { return EFFORT; }

    /** 发完请求取回答文本（供 chatWithImages 复用）。 */
    private static String readAnswer(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code == 401 || code == 403) return "请求失败: HTTP 401/403，API 密钥无效或无权限";
        if (code == 404) {
            // v1.1.16：不再武断指定版本段（讯飞 MaaS 是 /v2，各家不同）。
            // 改成：给出实际请求地址 + 常见原因，让用户能自己判断。
            String u = String.valueOf(conn.getURL());
            String hint = u.indexOf("/v1/") < 0 && u.indexOf("/v2/") < 0
                    ? "地址里似乎缺少 /v1 或 /v2 这类版本段"
                    : "请到设置里核对渠道 Base URL 与模型名";
            return "请求失败: HTTP 404，接口地址不对（实际请求 " + u + "；" + hint + "）";
        }
        if (code < 200 || code >= 300) return "请求失败: HTTP " + code + " " + briefErr(raw);
        JSONObject root = new JSONObject(raw);
        if (root.has("error")) {
            JSONObject e = root.optJSONObject("error");
            String m = e != null ? e.optString("message", raw) : raw;
            return "请求失败: " + m;
        }
        JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String answer = msg.optString("content", "");
        if (answer.length() == 0) {
            // v1.1.16：推理模型（如讯飞 spark-x2.6）在 token 被思考过程耗尽时 content 为空，
            // 真正的内容在 reasoning_content 里。旧版直接报「AI返回空内容」让用户以为接口错了，
            // 这里如实说明原因并把已有思考内容给出，不再假装是失败。
            String reasoning = msg.optString("reasoning_content", "");
            if (reasoning != null && reasoning.length() > 0) {
                return "（模型思考过程未产出正文，可能是输出长度被截断）\n" + reasoning;
            }
            return "AI返回空内容";
        }
        return answer;
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

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), UTF8);
    }
}