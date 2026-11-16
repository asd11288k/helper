// v1.1.12 新契约：工具闭环 AgentLoop / now / 多会话路由 / prefs 双开关 / Anthropic 通用 / 操作手册
// 编译：javac -cp libs/json.jar:/tmp/v1112tests -d /tmp/v1112tests tests/V1112Test.java
// 运行：java -cp libs/json.jar:/tmp/v1112tests V1112Test
import com.doubao.app.AgentLoop;
import com.doubao.app.Channels;
import com.doubao.app.LocalGateway;
import com.doubao.app.Providers;
import com.doubao.app.Settings;
import com.doubao.app.Skills;
import com.doubao.app.Store;
import com.doubao.app.Tools;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

public class V1112Test {
    static int pass = 0, fail = 0;
    static final Charset UTF8 = Charset.forName("UTF-8");

    static void ok(String n, boolean c) {
        if (c) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n); }
    }
    static void eq(String n, Object w, Object g) {
        ok(n + " (want=" + w + " got=" + g + ")", w == null ? g == null : w.equals(g));
    }

    static String http(String url, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(15000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8")); os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while (in != null && (n = in.read(b)) != -1) o.write(b, 0, n);
        c.disconnect();
        return code + "|" + new String(o.toByteArray(), "UTF-8");
    }

    static String body(String resp) { return resp.substring(resp.indexOf('|') + 1); }

    public static void main(String[] args) throws Exception {
        // ---- 1) Anthropic 通用（#1）----
        System.out.println("[1] Anthropic 通用 / 操作手册 / 工具清单");
        ok("GENERAL 三项", Providers.GENERAL.length == 3);
        boolean hasAnthropicGeneral = false;
        for (int i = 0; i < Providers.GENERAL.length; i++) {
            if ("anthropic-openai".equals(Providers.GENERAL[i][0])) hasAnthropicGeneral = true;
        }
        ok("GENERAL 含 anthropic-openai", hasAnthropicGeneral);
        eq("anthropic-openai 走 openai 兼容协议", "openai",
                Providers.protocolOf("anthropic-openai"));
        eq("Anthropic 通用 Base URL 留空待用户填中转", "", Providers.GENERAL[2][2]);
        ok("/providers 列表含三项通用", Providers.list().toString().contains("anthropic-openai"));

        String guide = LocalGateway.globalGuide();
        ok("全局提示词含严格工具格式", guide.contains("[调用工具:now"));
        ok("全局提示词禁止编造占位结果", guide.contains("不要编造工具结果"));
        ok("全局提示词含文件安全红线", guide.contains("toolfiles/"));
        ok("全局提示词拼接操作手册", guide.contains(LocalGateway.manual()));
        String manual = LocalGateway.manual();
        ok("操作手册说明架构", manual.contains("WebView") && manual.contains("本地 HTTP 网关") && manual.contains("底层代码实现"));
        ok("操作手册说明路由与附件", manual.contains("/agents/<id>/chat") && manual.contains("data URL") && manual.contains("工程师技能调用信息"));

        JSONArray tl = Tools.list();
        boolean hasNow = false;
        for (int i = 0; i < tl.length(); i++) if ("now".equals(tl.optJSONObject(i).optString("id"))) hasNow = true;
        ok("工具清单含当前时间 now", hasNow);

        // ---- 2) now 工具 / 写文件安全红线 ----
        System.out.println("[2] now 当前时间 / 可执行后缀拒绝");
        File td = new File(System.getProperty("java.io.tmpdir"), "v1112_tools_" + System.nanoTime());
        Tools tools = new Tools(td);
        long before = System.currentTimeMillis();
        JSONObject now = tools.exec("now", new JSONObject().put("tz", "Asia/Shanghai"));
        ok("now 返回 ok", now.optBoolean("ok"));
        ok("now 含时区", "Asia/Shanghai".equals(now.optString("timezone")));
        ok("now 含可读时间", now.optString("local_time").contains("年"));
        ok("now 时间戳有效", now.optLong("epoch_millis") >= before - 1000
                && now.optLong("epoch_millis") <= System.currentTimeMillis() + 1000);
        JSONObject sh = tools.exec("write_file",
                new JSONObject().put("path", "x.sh").put("content", "echo hi"));
        ok("拒绝写 .sh 可执行脚本", !sh.optBoolean("ok") && sh.toString().contains("可执行"));
        JSONObject apk = tools.exec("write_file",
                new JSONObject().put("path", "x.apk").put("content", "x"));
        ok("拒绝写 .apk", !apk.optBoolean("ok"));
        new File(td, "t.txt").delete(); td.delete();

        // ---- 3) AgentLoop 解析 / 归一化 / 闭环 ----
        System.out.println("[3] AgentLoop 工具调用闭环");
        AgentLoop.Call c = AgentLoop.parse("好的[调用工具:now 参数:{\"tz\":\"Asia/Shanghai\"}]完成");
        ok("解析出工具 id", c != null && "now".equals(c.id));
        ok("解析出 JSON 参数", c != null && "Asia/Shanghai".equals(c.args.optString("tz")));
        ok("无调用指令返回 null", AgentLoop.parse("直接回答，不用工具") == null);
        AgentLoop.Call cn = AgentLoop.parse("[调用工具：搜索网页 参数：{\"query\":\"天气\"}]");
        ok("全角冒号也识别", cn != null);
        ok("中文工具名可归一化执行", cn != null
                && "search_web".equals(AgentLoop.normalizeId(cn.id, Tools.list())));
        eq("中文别名归一化", "search_web", AgentLoop.normalizeId("搜索网页", null));
        eq("英文别名归一化", "write_file", AgentLoop.normalizeId("write", null));
        ok("半截调用文本被清除", AgentLoop.isCallOnly("[调用工具:write_file 参数:{}]"));

        // 假上游：第一轮吐工具指令，第二轮基于结果给最终答案
        final int[] rounds = {0};
        final String[] extras = {""};
        File loopDir = new File(System.getProperty("java.io.tmpdir"), "v1112_loop_" + System.nanoTime());
        Tools loopTools = new Tools(loopDir);
        String answer = AgentLoop.run("现在几点", loopTools, new AgentLoop.Sender() {
            public String send(String p, JSONArray extra) {
                rounds[0]++;
                if (extra != null && extra.length() > 0) {
                    extras[0] = extra.optJSONObject(extra.length() - 1).optString("content", "");
                }
                if (rounds[0] == 1) return "[调用工具:now 参数:{\"tz\":\"Asia/Shanghai\"}]";
                return "根据工具结果，现在是北京时间。";
            }
        });
        eq("闭环返回最终答案（不是调用指令）", "根据工具结果，现在是北京时间。", answer);
        eq("共请求上游两轮", 2, rounds[0]);
        ok("第二轮注入真实工具结果", extras[0].contains("[工具 now 的执行结果]")
                && extras[0].contains("local_time"));
        ok("最终答案不含调用指令", !answer.contains("[调用工具"));

        // 上限保护：模型一直吐工具指令
        String capped = AgentLoop.run("死循环", loopTools, new AgentLoop.Sender() {
            public String send(String p, JSONArray extra) {
                return "[调用工具:now 参数:{}]";
            }
        });
        ok("超过 MAX_ROUNDS 给可读提示", capped.contains("已连续执行 " + AgentLoop.MAX_ROUNDS + " 轮"));
        eq("上限轮数", AgentLoop.MAX_ROUNDS, 6);
        new File(loopDir, "t.txt").delete(); loopDir.delete();

        // ---- 4) 网关：会话路由 / prefs 双开关 / 空文本带图 / 版本 ----
        System.out.println("[4] 本地网关 会话 / 开关 / 图片 / 版本");
        File d = new File(System.getProperty("java.io.tmpdir"), "v1112_gw_" + System.nanoTime());
        d.mkdirs();
        File cf = new File(d, "channels.json"), sf = new File(d, "settings.json");
        File af = new File(d, "agents.json"), skf = new File(d, "skills.json");
        File toolDir = new File(d, "toolfiles");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af),
                "<h1>v1112</h1>".getBytes("UTF-8"), new Channels(cf),
                new Skills(skf), new Tools(toolDir));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        String ags = http(v + "/agents", "GET", null);
        JSONObject pub = new JSONObject(body(ags)).getJSONArray("agents").getJSONObject(0);
        String aid = pub.getString("id");
        ok("publicAgent 暴露 sessions 摘要", pub.has("sessions") && pub.getJSONArray("sessions").length() >= 1);
        ok("publicAgent 暴露 activeSessionId", pub.has("activeSessionId"));
        String s1 = pub.getJSONArray("sessions").getJSONObject(0).getString("id");

        String sGet = http(v + "/agents/" + aid + "/sessions", "GET", null);
        ok("GET /sessions 200", sGet.startsWith("200|"));
        ok("GET /sessions 含完整消息数组", body(sGet).contains("\"messages\""));

        String sNew = http(v + "/agents/" + aid + "/sessions", "POST", "{\"name\":\"新会话\"}");
        ok("POST /sessions 201", sNew.startsWith("201|"));
        String s2 = new JSONObject(body(sNew)).getJSONObject("session").getString("id");
        ok("新会话 id 不同于原会话", !s2.equals(s1));
        String sList2 = http(v + "/agents/" + aid + "/sessions", "GET", null);
        ok("新建后会话数为 2", new JSONObject(body(sList2)).getJSONArray("sessions").length() == 2);
        ok("新建会话自动激活",
                s2.equals(new JSONObject(body(sList2)).optString("activeSessionId")));

        String sSel = http(v + "/agents/" + aid + "/sessions/" + s1, "PUT", "{\"active\":true}");
        ok("PUT /sessions/<sid> 切换成功", sSel.startsWith("200|"));
        ok("切换后 activeSessionId 生效",
                s1.equals(new JSONObject(body(http(v + "/agents/" + aid + "/sessions", "GET", null)))
                        .optString("activeSessionId")));
        ok("重命名会话", http(v + "/agents/" + aid + "/sessions/" + s2, "PUT",
                "{\"name\":\"改名后\"}").startsWith("200|")
                && body(http(v + "/agents/" + aid + "/sessions", "GET", null)).contains("改名后"));
        ok("不存在的会话 404",
                http(v + "/agents/" + aid + "/sessions/nope", "PUT", "{\"active\":true}").startsWith("404|"));
        ok("删除会话 200", http(v + "/agents/" + aid + "/sessions/" + s2, "DELETE", null).startsWith("200|"));
        ok("删后至少保留一个会话",
                new JSONObject(body(http(v + "/agents/" + aid + "/sessions", "GET", null)))
                        .getJSONArray("sessions").length() == 1);

        // prefs 双开关：v1.1.14 #8 起默认全开，写入后可回读，且其它字段写入不冲掉
        String p0 = http(v + "/prefs", "GET", null);
        ok("prefs 默认 stream=true（v1.1.14 #8 默认全开）", body(p0).contains("\"stream\":true"));
        ok("prefs 默认 agentLoop=true", body(p0).contains("\"agentLoop\":true"));
        ok("PUT stream=true 生效",
                body(http(v + "/prefs", "PUT", "{\"stream\":true}")).contains("\"stream\":true"));
        ok("PUT agentLoop=false 生效",
                body(http(v + "/prefs", "PUT", "{\"agentLoop\":false}")).contains("\"agentLoop\":false"));
        String p1 = body(http(v + "/prefs", "GET", null));
        ok("GET 回读两个开关", p1.contains("\"stream\":true") && p1.contains("\"agentLoop\":false"));
        ok("改主题不冲掉开关",
                body(http(v + "/prefs", "PUT", "{\"theme\":\"dark\"}")).contains("\"stream\":true")
                && body(http(v + "/prefs", "GET", null)).contains("\"agentLoop\":false"));
        File prefsFile = new File(d, "prefs.json");
        ok("prefs 已落盘", prefsFile.exists());

        // 老数据迁移（历史不丢）
        File oldAf = new File(d, "old_agents.json");
        OutputStream ow = new java.io.FileOutputStream(oldAf);
        ow.write(("{\"agents\":[{\"id\":\"old\",\"name\":\"老智能体\",\"setting\":\"设定\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"历史一问\"}]}]}").getBytes("UTF-8"));
        ow.close();
        Store migrated = new Store(oldAf);
        JSONObject oldA = migrated.find("old");
        ok("老 agents.json 自动补 sessions",
                oldA != null && oldA.optJSONArray("sessions") != null
                && oldA.getJSONArray("sessions").length() == 1);
        ok("迁移保留历史消息", oldA.getJSONArray("sessions").getJSONObject(0)
                .getJSONArray("messages").toString().contains("历史一问"));
        ok("迁移写回 activeSessionId", "s1".equals(oldA.optString("activeSessionId")));
        oldAf.delete();

        // 空文本带图片：不再 400（用不存在渠道让上游快速失败，避免测试真联网）
        String imgChat = http(v + "/agents/" + aid + "/chat", "POST",
                "{\"prompt\":\"\",\"images\":[\"data:image/jpeg;base64,AAAA\"],"
                + "\"channelId\":\"no_such_channel\",\"sessionId\":\"" + s1 + "\"}");
        ok("只发图片不再 400", imgChat.startsWith("200|") && !body(imgChat).contains("prompt required"));
        ok("空文本纯请求仍 400",
                http(v + "/agents/" + aid + "/chat", "POST", "{\"prompt\":\"\"}").startsWith("400|"));

        // /clear 带 sessionId
        ok("/clear 带 sessionId 正常",
                http(v + "/agents/" + aid + "/clear", "POST", "{\"sessionId\":\"" + s1 + "\"}")
                        .startsWith("200|"));
        ok("/about 版本 1.1.22",
                body(http(v + "/about", "GET", null)).contains("\"version\":\"1.1.22\""));

        gw.stop();
        cf.delete(); sf.delete(); af.delete(); skf.delete(); prefsFile.delete();
        File[] left = toolDir.listFiles();
        if (left != null) for (int i = 0; i < left.length; i++) left[i].delete();
        toolDir.delete(); d.delete();

        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
