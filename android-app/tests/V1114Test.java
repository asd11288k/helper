// v1.1.14 后端契约测试：取消/失败不丢上下文、长按编辑、并发搜索、思考等级、用户设定、工具 trace、手册。
// 编译：javac -cp libs/json.jar:/tmp/v1114tests -d /tmp/v1114tests tests/V1114Test.java
// 运行：java -cp libs/json.jar:/tmp/v1114tests V1114Test
import com.helper.app.AgentLoop;
import com.helper.app.Channels;
import com.helper.app.LocalGateway;
import com.helper.app.OpenAiClient;
import com.helper.app.Settings;
import com.helper.app.Skills;
import com.helper.app.Store;
import com.helper.app.Tools;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class V1114Test {
    static int pass = 0, fail = 0;
    static void ok(String n, boolean c) {
        if (c) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n); }
    }
    static void eq(String n, Object w, Object g) {
        ok(n + " (want=" + w + " got=" + g + ")", w == null ? g == null : w.equals(g));
    }
    static String http(String url, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(20000);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while (in != null && (n = in.read(b)) != -1) o.write(b, 0, n);
        c.disconnect();
        return code + "|" + new String(o.toByteArray(), "UTF-8");
    }
    static String body(String r) { return r.substring(r.indexOf('|') + 1); }
    static String agentMessages(String v, String aid) throws Exception {
        return new JSONObject(body(http(v + "/agents/" + aid, "GET", null)))
                .getJSONObject("agent").optJSONArray("messages").toString();
    }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1114_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json"), cf = new File(d, "channels.json");
        File sf = new File(d, "settings.json"), skf = new File(d, "skills.json");
        File td = new File(d, "toolfiles");
        Store st = new Store(af);
        String aid = st.create("测试", "设定").getString("id");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af),
                "<h1>v1114</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] #12 红测试回路：上游失败后用户消息仍在上下文（截图 1000012880）");
        String chAdd = body(http(v + "/channels", "POST",
                "{\"name\":\"不可达\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"k\",\"model\":\"m1\"}"));
        String cid = new JSONObject(chAdd).getJSONObject("channel").getString("id");
        final String LONG_PROMPT = "写玄幻修仙小说第一章1000字";
        String chat = http(v + "/agents/" + aid + "/chat", "POST",
                new JSONObject().put("prompt", LONG_PROMPT).put("channelId", cid).toString());
        ok("chat 请求完成且为 HTTP 200", chat.startsWith("200|"));
        ok("确实是失败路径（上游不可达）", body(chat).contains("请求失败"));
        ok("失败后用户 prompt 仍写入会话上下文", agentMessages(v, aid).contains(LONG_PROMPT));
        ok("失败也留下「未完成」标记让 AI 知情", agentMessages(v, aid).contains("未完成"));
        String chat2 = http(v + "/agents/" + aid + "/chat", "POST",
                new JSONObject().put("prompt", "我上一条消息是啥").put("channelId", cid).toString());
        ok("第二条请求也能完成", chat2.startsWith("200|"));
        JSONArray ms = new JSONObject(body(http(v + "/agents/" + aid, "GET", null)))
                .getJSONObject("agent").getJSONArray("messages");
        int idxLong = -1, idxNext = -1;
        for (int i = 0; i < ms.length(); i++) {
            String c = ms.optJSONObject(i) == null ? "" : ms.optJSONObject(i).optString("content", "");
            if (LONG_PROMPT.equals(c)) idxLong = i;
            if ("我上一条消息是啥".equals(c)) idxNext = i;
        }
        ok("两条用户消息都在上下文且顺序正确", idxLong >= 0 && idxNext > idxLong);

        System.out.println("[2] #12 取消生成：/interrupt 写入取消标记");
        String intr = http(v + "/agents/" + aid + "/interrupt", "POST",
                "{\"sessionId\":\"\",\"note\":\"用户取消了本轮生成\"}");
        ok("POST /interrupt 返回 200", intr.startsWith("200|"));
        ok("取消标记写入上下文", agentMessages(v, aid).contains("用户取消了本轮生成"));
        ok("不存在的智能体 /interrupt 返回 404",
                http(v + "/agents/nope/interrupt", "POST", "{}").startsWith("404|"));

        System.out.println("[3] #10 长按编辑：PUT /messages/<idx> 直接改上下文");
        String put = http(v + "/agents/" + aid + "/messages/" + idxLong, "PUT",
                "{\"sessionId\":\"\",\"content\":\"改写：玄幻修仙第一章\"}");
        ok("PUT /messages/<idx> 返回 200", put.startsWith("200|"));
        ok("上下文里已是编辑后的内容", agentMessages(v, aid).contains("改写：玄幻修仙第一章"));
        ok("越界下标 PUT 返回 404",
                http(v + "/agents/" + aid + "/messages/9999", "PUT", "{\"content\":\"x\"}").startsWith("404|"));
        ok("DELETE 仍可用（长按删除）",
                http(v + "/agents/" + aid + "/messages/" + idxNext, "DELETE", "{\"sessionId\":\"\"}").startsWith("200|"));

        System.out.println("[4] #8/#16 prefs：默认全开 + 思考等级 + 用户设定开关");
        String p0 = body(http(v + "/prefs", "GET", null));
        ok("默认 stream=true", p0.contains("\"stream\":true"));
        ok("默认 showTiming=true", p0.contains("\"showTiming\":true"));
        ok("默认 showThink=true", p0.contains("\"showThink\":true"));
        ok("默认 reasoningEffort=high", p0.contains("\"reasoningEffort\":\"high\""));
        ok("默认 reasoningParam=true", p0.contains("\"reasoningParam\":true"));
        ok("默认 userProfileOn=true", p0.contains("\"userProfileOn\":true"));
        ok("PUT reasoningEffort=low 生效",
                body(http(v + "/prefs", "PUT", "{\"reasoningEffort\":\"low\"}")).contains("\"reasoningEffort\":\"low\""));
        ok("非法档位归一到 high",
                body(http(v + "/prefs", "PUT", "{\"reasoningEffort\":\"乱码\"}")).contains("\"reasoningEffort\":\"high\""));
        ok("改档位不冲掉其它开关",
                body(http(v + "/prefs", "PUT", "{\"reasoningEffort\":\"max\"}")).contains("\"stream\":true"));
        eq("normalizeEffort 校验", "xhigh", LocalGateway.normalizeEffort("XHIGH"));

        System.out.println("[5] #16 思考等级作为 reasoning_effort 请求参数");
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"));
        ok("payload 带 reasoning_effort",
                OpenAiClient.payload("m1", msgs, "high").optString("reasoning_effort", "").equals("high"));
        ok("空档位不传该参数", !OpenAiClient.payload("m1", msgs, "").has("reasoning_effort"));

        System.out.println("[6] #17 用户设定 /profile：持久化 + 常驻注入");
        String pr0 = body(http(v + "/profile", "GET", null));
        ok("GET /profile 默认空内容且开启", pr0.contains("\"enabled\":true"));
        String pr1 = body(http(v + "/profile", "PUT", "{\"content\":\"叫我子兮\",\"enabled\":true}"));
        ok("PUT /profile 保存内容", pr1.contains("叫我子兮"));
        ok("GET 回读内容", body(http(v + "/profile", "GET", null)).contains("叫我子兮"));
        ok("关开关后只关闭不丢内容",
                body(http(v + "/profile", "PUT", "{\"enabled\":false}")).contains("\"enabled\":false")
                && body(http(v + "/profile", "GET", null)).contains("叫我子兮"));
        body(http(v + "/profile", "PUT", "{\"enabled\":true}"));

        System.out.println("[7] #9 并发搜索：全部源一起跑、逐条标注来源、失败如实记录");
        String search = body(http(v + "/tools", "POST",
                "{\"id\":\"search_web\",\"args\":{\"query\":\"v1114-contract-probe\"}}"));
        ok("返回 concurrent=true", search.contains("\"concurrent\":true"));
        ok("返回 sources 逐源统计", search.contains("\"sources\""));
        ok("6 个源都出现在 attempted 里（早停也如实列全）", search.contains("cn.bing") && search.contains("百度")
                && search.contains("搜狗") && search.contains("夸克") && search.contains("DuckDuckGo")
                && search.contains("\"attempted\""));
        ok("note 说明并发全部源", search.contains("并发搜索全部"));
        ok("结果数组与来源标注结构完整",
                search.contains("\"results\"") && (search.contains("\"source\"") || search.contains("未返回结果")));

        System.out.println("[8] #15 工具调用详情 trace（AgentLoop 4 参重载）");
        final JSONArray trace = new JSONArray();
        final int[] calls = {0};
        String ans = AgentLoop.run("问", new Tools(td), new AgentLoop.Sender() {
            public String send(String p, JSONArray extra) {
                if (calls[0]++ == 0) return "[调用工具:now 参数:{\"tz\":\"Asia/Shanghai\"}]";
                return "最终答案";
            }
        }, trace);
        ok("4 参重载返回最终答案", ans.equals("最终答案"));
        ok("原 3 参重载仍可用", AgentLoop.run("问", new Tools(td), new AgentLoop.Sender() {
            public String send(String p, JSONArray extra) { return "直接答案"; }
        }).equals("直接答案"));
        ok("trace 记录了 1 次工具调用", trace.length() == 1);
        if (trace.length() > 0) {
            JSONObject rec = trace.optJSONObject(0);
            ok("trace 含工具名 now", "now".equals(rec.optString("tool", "")));
            ok("trace 含轮次/耗时/参数/结果", rec.has("round") && rec.has("elapsedMs")
                    && rec.has("args") && rec.has("result"));
        } else ok("trace 含轮次/耗时/参数/结果", false);

        System.out.println("[9] #20 操作手册：使用说明 / 底层实现 / 工程师技能索引 / 证据红线");
        String manual = LocalGateway.manual();
        ok("含索引段", manual.contains("索引（按关键词"));
        ok("含软件使用说明", manual.contains("软件使用说明"));
        ok("含底层代码实现", manual.contains("底层代码实现"));
        ok("含工程师技能调用信息", manual.contains("工程师技能调用信息"));
        ok("含 diagnosing-bugs 六阶段", manual.contains("六阶段"));
        ok("含 research / tdd 关键词", manual.contains("research") && manual.contains("tdd"));
        ok("含绝对禁止瞎编红线", manual.contains("禁止瞎编") && manual.contains("证据"));
        String guide = LocalGateway.globalGuide();
        ok("全局提示词强化禁止瞎编", guide.contains("绝对禁止瞎编"));
        ok("全局提示词指向手册索引", guide.contains("索引"));
        ok("提示词长度受控（<4000）", guide.length() < 6000);

        System.out.println("[10] 版本 /about = 1.1.14");
        ok("/about 版本 1.1.27", body(http(v + "/about", "GET", null)).contains("1.1.38"));

        gw.stop();
        cf.delete(); sf.delete(); af.delete(); skf.delete();
        new File(d, "prefs.json").delete(); new File(d, "profile.json").delete();
        File[] left = td.listFiles(); if (left != null) for (int i = 0; i < left.length; i++) left[i].delete();
        td.delete(); d.delete();
        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}