package com.helper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求⑪⑫：工具调用闭环。
 *
 * 模型回复里出现 [调用工具:&lt;id&gt; 参数:{...}] 时，本类负责：
 *   1. 执行工具（Tools.exec）
 *   2. 把执行结果作为一条 user 消息回填到会话上下文
 *   3. 通过 Sender 再请求模型，直到模型给出最终答复或达到轮次上限
 *
 * 外部只看到最终答案；中间的「开始调用工具」「等待结果」不会落到 UI 上。
 * 纯 JVM 类，不依赖 Android SDK，Sender 作为接缝便于单测注入假上游。
 */
public final class AgentLoop {
    /** 一次对话最多连续执行几轮工具，防止模型死循环。 */
    public static final int MAX_ROUNDS = 6;

    /** 上游回调：给定「本轮要发给模型的 prompt / 追加的上下文」，返回模型回复。 */
    public interface Sender {
        String send(String prompt, JSONArray extraMessages) throws Exception;
    }

    /** 解析出的单次调用。 */
    public static final class Call {
        public final String id;
        public final JSONObject args;
        public final int start;
        public final int end;
        Call(String id, JSONObject args, int start, int end) {
            this.id = id; this.args = args; this.start = start; this.end = end;
        }
    }

    // 宽容匹配：允许全角/半角冒号、参数里含 JSON 对象、换行；
    // id 允许中文/其它语言字母（模型常写「搜索网页」，由 normalizeId 归一成真实工具 id）
    private static final Pattern CALL = Pattern.compile(
            "\\[\\s*调用工具\\s*[:：]\\s*([\\p{L}\\p{N}_\\-]+)\\s+参数\\s*[:：]\\s*(\\{[\\s\\S]*?\\})\\s*\\]");

    private AgentLoop() {}

    /** 从模型回复里找出第一个工具调用；没有返回 null。 */
    public static Call parse(String reply) {
        if (reply == null) return null;
        Matcher m = CALL.matcher(reply);
        if (!m.find()) return null;
        JSONObject args;
        try {
            args = new JSONObject(m.group(2));
        } catch (Exception e) {
            args = new JSONObject();
            try { args.put("_parse_error", "参数不是合法 JSON: " + e.getMessage()); }
            catch (JSONException ignored) {}
        }
        return new Call(m.group(1), args, m.start(), m.end());
    }

    /** 把工具 id 归一化：模型可能写中文名或别名。 */
    public static String normalizeId(String id, JSONArray toolList) {
        if (id == null) return "";
        String s = id.trim().toLowerCase();
        String[][] alias = {
            {"search_web", "search", "web_search", "搜索网页", "搜索", "网页搜索"},
            {"fetch_web", "fetch", "web_fetch", "抓取网页", "抓取", "读取网页"},
            {"read_file", "read", "读文件", "读取文件"},
            {"write_file", "write", "写文件", "写入文件"},
            {"list_files", "list", "列目录", "文件列表"},
        };
        for (int i = 0; i < alias.length; i++) {
            for (int j = 0; j < alias[i].length; j++) {
                if (alias[i][j].equals(s)) return alias[i][0];
            }
        }
        if (toolList != null) {
            for (int i = 0; i < toolList.length(); i++) {
                JSONObject t = toolList.optJSONObject(i);
                if (t == null) continue;
                if (s.equals(t.optString("id", "")) || s.equals(t.optString("name", "")))
                    return t.optString("id", "");
            }
        }
        return s;
    }

    /**
     * 执行闭环。prompt 是用户当前问题；sender 每次收到「本轮 prompt」与「累积的工具结果消息」。
     * 返回最终答案（不含任何工具调用指令）。
     */
    public static String run(String prompt, Tools tools, Sender sender) {
        return run(prompt, tools, sender, null);
    }

    /** #15：trace 非空时逐轮记录工具调用详情（工具名/参数/耗时/结果关键值）。 */
    public static String run(String prompt, Tools tools, Sender sender, JSONArray trace) {
        if (sender == null) return "请求失败: 无可用上游";
        JSONArray feedback = new JSONArray();
        String current = prompt;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String reply;
            try {
                reply = sender.send(current, feedback);
            } catch (Exception e) {
                return "请求失败: " + e.getMessage();
            }
            if (reply == null || reply.startsWith("请求失败") || "AI返回空内容".equals(reply)) {
                return reply == null ? "AI返回空内容" : reply;
            }
            Call c = parse(reply);
            if (c == null) return stripLooseCall(reply);
            if (tools == null) {
                return stripLooseCall(reply) + "\n\n（工具子系统未启用，无法执行该调用）";
            }
            String id = normalizeId(c.id, Tools.list());
            long t0 = System.currentTimeMillis();
            JSONObject result = tools.exec(id, c.args);
            if (trace != null) {
                try {
                    JSONObject rec = new JSONObject()
                            .put("round", round + 1)
                            .put("tool", id)
                            .put("rawId", c.id)
                            .put("args", c.args)
                            .put("elapsedMs", System.currentTimeMillis() - t0);
                    JSONArray rs = result.optJSONArray("results");
                    rec.put("resultCount", rs == null ? 0 : rs.length());
                    rec.put("ok", !result.has("error"));
                    rec.put("error", result.optString("error", ""));
                    rec.put("source", result.optString("source", ""));
                    String full = result.toString();
                    rec.put("result", full.length() > 3000 ? full.substring(0, 3000) + "…（已截断）" : full);
                    trace.put(rec);
                } catch (JSONException ignored) {}
            }
            // 结果只回传给模型，不写用户可见 history
            String text = "[工具 " + id + " 的执行结果]\n" + compact(result)
                    + "\n请基于以上真实结果继续完成用户任务；若信息已足够，直接给出最终答案，不要再输出调用指令。";
            try {
                feedback.put(new JSONObject().put("role", "user").put("content", text));
            } catch (JSONException ignored) {}
            current = "继续完成用户任务：" + prompt;
        }
        return "已连续执行 " + MAX_ROUNDS + " 轮工具仍未得到最终答案，请缩小问题范围后重试。";
    }

    /** 工具结果压缩，避免把巨量网页正文塞回上下文。 */
    private static String compact(JSONObject r) {
        try {
            String s = r.toString();
            return s.length() > 6000 ? s.substring(0, 6000) + "…（结果已截断）" : s;
        } catch (Exception e) { return "{}"; }
    }

    /** 没匹配到严格格式时，清掉残留的半截调用文本，避免 UI 出现「调用工具:xxx」乱码。 */
    private static String stripLooseCall(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[\\s*调用工具[^\\]]*\\]", "").trim();
    }

    /** 判断一段文本是否是「只有工具指令、没有实质回答」。 */
    public static boolean isCallOnly(String s) {
        if (s == null) return false;
        String t = stripLooseCall(s).trim();
        return t.length() == 0;
    }
}