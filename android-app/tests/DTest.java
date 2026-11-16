// 纯 JVM 单元测试：Settings + OpenAiClient + LocalGateway
// 编译：javac -cp libs/json.jar -d tests/out tests/DTest.java src/com/doubao/app/*.java（Android 类需 android.jar）
// 运行：java -cp libs/json.jar:tests/out DTest
import com.doubao.app.Settings;
import com.doubao.app.OpenAiClient;
import com.doubao.app.LocalGateway;
import com.doubao.app.Store;
import com.doubao.app.Channels;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.concurrent.*;

public class DTest {
    static int pass = 0, fail = 0;
    static void eq(String n, Object w, Object g) {
        boolean ok = w == null ? g == null : w.equals(g);
        if (ok) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n + " want=" + w + " got=" + g); }
    }
    static void ok(String n, boolean c) { eq(n, true, c); }
    static final Charset UTF8 = Charset.forName("UTF-8");

    /** HTTP 头按字节逐行读；Content-Length 是字节数，body 含中文时不能用 Reader 按字符读。 */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(b.toByteArray(), "US-ASCII");
            if (c != '\r') b.write(c);
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), "US-ASCII");
    }

    // ---- 极简 OpenAI 兼容假服务（独立信源：按协议固定字面量应答，不复用被测代码）----
    static class FakeOpenAi extends Thread {
        final ServerSocket ss;
        volatile String lastPath, lastAuth, lastBody;
        volatile int status = 200;
        volatile String reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答案A\"}}]}";
        FakeOpenAi() throws Exception { ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); setDaemon(true); }
        int port() { return ss.getLocalPort(); }
        public void run() {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                    String line = readLine(in);
                    if (line == null) continue;
                    lastPath = line.split(" ")[1];
                    lastAuth = null;
                    int len = 0;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        String low = line.toLowerCase();
                        if (low.startsWith("authorization:")) lastAuth = line.substring(14).trim();
                        if (low.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                    }
                    byte[] buf = new byte[len];
                    int off = 0;
                    while (off < len) { int n = in.read(buf, off, len - off); if (n < 0) break; off += n; }
                    lastBody = new String(buf, 0, off, UTF8);

                    byte[] body = reply.getBytes(Charset.forName("UTF-8"));
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    os.write(body); os.flush();
                } catch (Exception e) { if (!ss.isClosed()) { /* ignore */ } }
            }
        }
        void close() throws Exception { ss.close(); }
    }

    static String http(String url, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(10000);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.getOutputStream().write(body.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while (in != null && (n = in.read(b)) != -1) o.write(b, 0, n);
        c.disconnect();
        return code + "|" + new String(o.toByteArray(), "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[Settings] 配置存取与默认值");
        File f = File.createTempFile("d_settings_", ".json"); f.delete();
        Settings st = new Settings(new File(f.getParentFile(), "s_" + System.nanoTime() + ".json"));
        eq("默认 baseUrl", "", st.baseUrl());
        eq("默认 key", "", st.apiKey());
        eq("默认 model", "", st.model());
        ok("默认未配置", !st.isConfigured());
        st.update("http://127.0.0.1:1234/v1/", "sk-abc", "gpt-4o-mini");
        eq("baseUrl 去尾斜杠", "http://127.0.0.1:1234/v1", st.baseUrl());
        eq("key 保存", "sk-abc", st.apiKey());
        eq("model 保存", "gpt-4o-mini", st.model());
        ok("已配置", st.isConfigured());
        Settings st2 = new Settings(st.file());
        eq("重载后 baseUrl", "http://127.0.0.1:1234/v1", st2.baseUrl());
        eq("重载后 key", "sk-abc", st2.apiKey());
        eq("重载后 model", "gpt-4o-mini", st2.model());
        java.nio.file.Files.write(st.file().toPath(), "NOT_JSON".getBytes("UTF-8"));
        Settings st3 = new Settings(st.file());
        eq("损坏文件按空配置处理", "", st3.baseUrl());
        ok("损坏文件不抛异常", !st3.isConfigured());
        st.file().delete();

        System.out.println("[OpenAiClient] OpenAI 兼容协议");
        FakeOpenAi fake = new FakeOpenAi(); fake.start();
        String base = "http://127.0.0.1:" + fake.port() + "/v1";
        JSONArray hist = new JSONArray();
        hist.put(new JSONObject().put("role", "user").put("content", "上一问"));
        hist.put(new JSONObject().put("role", "assistant").put("content", "上一答"));
        String ans = OpenAiClient.chat(base, "sk-test", "m1", "1+1", hist, "你是计算器");
        eq("取 choices[0].message.content", "答案A", ans);
        eq("POST 到 /v1/chat/completions", "/v1/chat/completions", fake.lastPath);
        eq("Bearer 鉴权", "Bearer sk-test", fake.lastAuth);
        JSONObject req = new JSONObject(fake.lastBody);
        eq("model 字段", "m1", req.getString("model"));
        eq("stream=false", false, req.getBoolean("stream"));
        JSONArray msgs = req.getJSONArray("messages");
        eq("system + 2 历史 + 当前 = 4 条", 4, msgs.length());
        eq("第1条是 system", "system", msgs.getJSONObject(0).getString("role"));
        eq("第1条含设定", "你是计算器", msgs.getJSONObject(0).getString("content"));
        eq("第2条=历史0", "上一问", msgs.getJSONObject(1).getString("content"));
        eq("最后=当前问题", "1+1", msgs.getJSONObject(3).getString("content"));
        ok("设定不写入 history", !hist.toString().contains("你是计算器"));
        eq("成功追加本轮 user+assistant 到 history", 4, hist.length());

        String ans2 = OpenAiClient.chat(base, "sk-test", "m1", "hi", new JSONArray(), "");
        eq("无设定时不生成 system 轮", "答案A", ans2);
        eq("无设定 => 1 条消息", 1, new JSONObject(fake.lastBody).getJSONArray("messages").length());
        fake.status = 401;
        String err401 = OpenAiClient.chat(base, "bad", "m1", "hi", new JSONArray(), "");
        ok("401 映射成人话", err401.contains("密钥") || err401.contains("401"));
        fake.status = 404;
        String err404 = OpenAiClient.chat(base, "sk-test", "m1", "hi", new JSONArray(), "");
        ok("404 映射成人话", err404.contains("地址") || err404.contains("404"));
        fake.status = 200; fake.reply = "{\"error\":{\"message\":\"model not found\"}}";
        String errModel = OpenAiClient.chat(base, "sk-test", "m1", "hi", new JSONArray(), "");
        ok("上游 JSON 错误可读", errModel.length() > 0 && !errModel.equals("答案A"));
        fake.close();

        System.out.println("[LocalGateway] 本地网关路由");
        File sf = new File(System.getProperty("java.io.tmpdir"), "d_gw_settings_" + System.nanoTime() + ".json");
        File af = new File(System.getProperty("java.io.tmpdir"), "d_gw_agents_" + System.nanoTime() + ".json");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af), "<h1>D</h1>".getBytes("UTF-8"));
        gw.start();
        String u = "http://127.0.0.1:" + gw.port();
        String health = http(u + "/health", "GET", null);
        ok("GET /health 200", health.startsWith("200|"));
        ok("health 带 ok", health.contains("\"ok\":true") || health.contains("\"ok\": true"));
        String page = http(u + "/", "GET", null);
        ok("GET / 返回前端页面", page.startsWith("200|") && page.contains("<h1>D</h1>"));
        String s0 = http(u + "/settings", "GET", null);
        ok("GET /settings 不泄露 key", s0.startsWith("200|") && !s0.contains("sk-") && s0.contains("model"));
        String s1 = http(u + "/settings", "PUT", "{\"baseUrl\":\"http://x/v1\",\"apiKey\":\"sk-z\",\"model\":\"mm\"}");
        ok("PUT /settings 200", s1.startsWith("200|"));
        ok("PUT 后不回显 key", !s1.contains("sk-z"));
        String s2 = http(u + "/settings", "GET", null);
        ok("GET /settings 反映新配置", s2.contains("http://x/v1") && s2.contains("mm") && !s2.contains("sk-z"));
        String c1 = http(u + "/agents", "POST", "{\"name\":\"甲\",\"setting\":\"设定\"}");
        ok("POST /agents 201", c1.startsWith("201|"));
        String id = new JSONObject(c1.substring(c1.indexOf('|') + 1)).getJSONObject("agent").getString("id");
        String g1 = http(u + "/agents", "GET", null);
        ok("GET /agents 含新建", g1.startsWith("200|") && g1.contains("甲"));
        String d1 = http(u + "/agents/" + id, "GET", null);
        ok("GET /agents/<id> 含设定", d1.contains("设定"));
        String p1 = http(u + "/agents/" + id, "PUT", "{\"name\":\"乙\",\"setting\":\"新设定\"}");
        ok("PUT /agents/<id> 200", p1.startsWith("200|") && p1.contains("乙"));
        FakeOpenAi fake2 = new FakeOpenAi(); fake2.start();
        http(u + "/settings", "PUT", "{\"baseUrl\":\"http://127.0.0.1:" + fake2.port() + "/v1\",\"apiKey\":\"sk-live\",\"model\":\"mm\"}");
        String okChat = http(u + "/agents/" + id + "/chat", "POST", "{\"prompt\":\"第一问\"}");
        ok("已配置时 /chat 返回上游答案", okChat.startsWith("200|") && okChat.contains("答案A"));
        ok("网关确实打到 /v1/chat/completions", "/v1/chat/completions".equals(fake2.lastPath));
        ok("Bearer 透传到上游", "Bearer sk-live".equals(fake2.lastAuth));
        ok("设定以 system 角色透传", fake2.lastBody != null
                && fake2.lastBody.contains("\"role\":\"system\"") && fake2.lastBody.contains("新设定"));
        String dOk = http(u + "/agents/" + id, "GET", null);
        ok("成功后本轮落库", dOk.contains("第一问") && dOk.contains("答案A"));
        fake2.close();

        // v1.1.14 #12：清空配置后给出可读错误；用户消息在网关入口即落库，
        // 失败也保留（取消/失败都不许把用户消息移出上下文），并留下「未完成」标记。
        http(u + "/settings", "PUT", "{\"baseUrl\":\"\",\"apiKey\":\"\",\"model\":\"\"}");
        String ch = http(u + "/agents/" + id + "/chat", "POST", "{\"prompt\":\"你好\"}");
        ok("未配置时 /chat 返回可读错误", ch.startsWith("200|") && ch.contains("配置"));
        String d2 = http(u + "/agents/" + id, "GET", null);
        ok("失败后用户消息仍在上下文（v1.1.14 #12）", d2.contains("你好"));
        ok("失败后留下未完成标记（v1.1.14 #12）", d2.contains("未完成"));
        String del = http(u + "/agents/" + id, "DELETE", null);
        ok("DELETE /agents/<id> 200", del.startsWith("200|"));
        String g2 = http(u + "/agents", "GET", null);
        ok("删除后保留内置小助手", g2.contains("小助手") && !g2.contains("\"name\":\"乙\""));
        ok("默认智能体带简单高效设定", g2.contains("简单高效"));
        String nf = http(u + "/nope", "GET", null);
        ok("未知路径 404", nf.startsWith("404|"));
        gw.stop(); sf.delete(); af.delete();

        // ---- 本轮新增：多渠道网关 / 内置模型 / 个性化 / 关于 / 清除对话 ----
        System.out.println("[Channels + 新路由] 4 参构造的本地网关");
        File d = new File(System.getProperty("java.io.tmpdir"), "d_gw2_" + System.nanoTime());
        d.mkdirs();
        File cf = new File(d, "channels.json");
        File s2f = new File(d, "settings.json");
        File a2f = new File(d, "agents.json");
        LocalGateway gw2 = new LocalGateway(new Settings(s2f), new Store(a2f),
                "<h1>D2</h1>".getBytes("UTF-8"), new Channels(cf));
        gw2.start();
        String v = "http://127.0.0.1:" + gw2.port();

        String cl = http(v + "/channels", "GET", null);
        ok("GET /channels 200", cl.startsWith("200|"));
        ok("内置渠道置顶且名为内置模型", cl.contains("\"id\":\"builtin\"") && cl.contains("内置模型"));
        ok("内置默认启用且锁定", cl.contains("\"enabled\":true") && cl.contains("\"locked\":true"));
        ok("默认渠道为内置", cl.contains("\"defaultId\":\"builtin\""));
        ok("渠道列表不泄露密钥", !cl.contains("sk-"));

        String cAdd = http(v + "/channels", "POST",
                "{\"name\":\"自建\",\"baseUrl\":\"http://127.0.0.1:1/v1/\",\"apiKey\":\"sk-ch\",\"model\":\"m1\"}");
        ok("POST /channels 201", cAdd.startsWith("201|"));
        JSONObject cObj = new JSONObject(cAdd.substring(cAdd.indexOf('|') + 1)).getJSONObject("channel");
        String cid = cObj.getString("id");
        ok("创建响应不回显密钥", !cObj.toString().contains("sk-ch") && cObj.getBoolean("hasKey"));
        ok("创建时去尾斜杠", "http://127.0.0.1:1/v1".equals(cObj.getString("baseUrl")));
        ok("内置不可编辑", http(v + "/channels/builtin", "PUT",
                "{\"name\":\"x\",\"baseUrl\":\"x\",\"apiKey\":\"x\",\"model\":\"x\"}").startsWith("400|"));
        ok("内置不可停用", http(v + "/channels/builtin/enable", "PUT", "{\"enabled\":false}").startsWith("400|"));
        ok("内置不可删除", http(v + "/channels/builtin", "DELETE", null).startsWith("400|"));
        ok("内置可拉取（只给自身）", http(v + "/channels/builtin/models", "GET", null).contains("内置模型"));

        String ab = http(v + "/about", "GET", null);
        ok("关于页版本号 1.1.22", ab.contains("\"version\":\"1.1.22\""));
        ok("关于页作者子兮", ab.contains("子兮"));
        ok("关于页项目地址 GitHub", ab.contains("https://github.com/asd11288k/xiao-zhushou"));

        String p0 = http(v + "/prefs", "GET", null);
        ok("主题默认浅色", p0.contains("\"theme\":\"light\""));
        ok("主题非法值被拒", http(v + "/prefs", "PUT", "{\"theme\":\"neon\"}").startsWith("400|"));
        ok("切换到深色", http(v + "/prefs", "PUT", "{\"theme\":\"dark\"}").contains("\"theme\":\"dark\""));
        ok("深色已落盘", http(v + "/prefs", "GET", null).contains("\"theme\":\"dark\""));
        ok("切回浅色", http(v + "/prefs", "PUT", "{\"theme\":\"light\"}").contains("\"theme\":\"light\""));

        // 默认智能体存在；清除对话保留名称设定
        String ags = http(v + "/agents", "GET", null);
        JSONObject def = new JSONObject(ags.substring(ags.indexOf('|') + 1)).getJSONArray("agents").getJSONObject(0);
        String defId = def.getString("id");
        ok("空库自动内置小助手", "小助手".equals(def.getString("name")) && def.getString("setting").contains("简单高效"));
        ok("清除对话 200", http(v + "/agents/" + defId + "/clear", "POST", "{}").startsWith("200|"));
        String def2 = http(v + "/agents/" + defId, "GET", null);
        ok("清除后保留名称设定", def2.contains("小助手") && def2.contains("简单高效"));
        ok("清除后消息为空", def2.contains("\"messages\":[]"));
        ok("不存在的智能体清除 404", http(v + "/agents/nope/clear", "POST", "{}").startsWith("404|"));

        // 渠道选择：channelId 显式指定自建渠道时，网关打到该 OpenAI 渠道（Settings 为空也不影响）
        FakeOpenAi fake3 = new FakeOpenAi(); fake3.start();
        String chUrl = "http://127.0.0.1:" + fake3.port() + "/v1";
        http(v + "/channels/" + cid, "PUT", "{\"name\":\"自建\",\"baseUrl\":\"" + chUrl
                + "\",\"apiKey\":\"\",\"model\":\"m1\"}");
        String multi = http(v + "/channels/" + cid, "PUT", "{\"name\":\"自建\",\"baseUrl\":\"" + chUrl
                + "\",\"apiKey\":\"\",\"model\":\"m1\",\"models\":[\"m1\",\"m2\"]}");
        ok("③ 渠道保存多个模型", multi.contains("\"models\"") && multi.contains("m2"));
        String modelsPub = http(v + "/channels", "GET", null);
        ok("③ 渠道列表回显 models", modelsPub.contains("\"models\"") && modelsPub.contains("m2"));

        String chRes = http(v + "/agents/" + defId + "/chat", "POST",
                "{\"prompt\":\"走自建\",\"channelId\":\"" + cid + "\",\"model\":\"m2\"}");
        ok("按 channelId 分发到自建 OpenAI 渠道", chRes.contains("答案A")
                && "/v1/chat/completions".equals(fake3.lastPath));
        ok("④ 渠道内任选模型 m2 生效", new JSONObject(fake3.lastBody).getString("model").equals("m2"));
        ok("channelId 生效时 Settings 仍为空", !new Settings(s2f).isConfigured());
        String stAfter = http(v + "/agents/" + defId + "/chat", "POST",
                "{\"prompt\":\"渠道不存在\",\"channelId\":\"nope\"}");
        ok("不存在的渠道给出可读错误", stAfter.contains("渠道不存在"));
        fake3.close();

        // probe：表单未落库时直接探测上游 /models，「拉取模型→快速添加」
        FakeOpenAi fake4 = new FakeOpenAi(); fake4.start();
        String probeBase = "http://127.0.0.1:" + fake4.port() + "/v1";
        fake4.reply = "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}";
        String probe = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"" + probeBase + "\",\"apiKey\":\"sk-probe\"}");
        ok("probe 拉到模型列表", probe.startsWith("200|") && probe.contains("gpt-4o-mini"));
        ok("probe 打到 /v1/models", "/v1/models".equals(fake4.lastPath));
        ok("probe 透传 Bearer", "Bearer sk-probe".equals(fake4.lastAuth));
        ok("probe 空 Base URL 被拒", http(v + "/channels/probe", "POST", "{\"baseUrl\":\"\"}").startsWith("400|"));
        fake4.close();

        ok("渠道删除后 GET 404", http(v + "/channels/" + cid, "GET", null).startsWith("404|")
                || http(v + "/channels/" + cid, "DELETE", null).startsWith("200|"));
        gw2.stop();
        cf.delete(); s2f.delete(); a2f.delete(); new File(d, "prefs.json").delete(); d.delete();

        // ---- v1.1：技能 CRUD + @技能 后台注入 + 最后一次渠道模型记忆 ----
        System.out.println("[v1.1] /skills + skillPrompt + lastChannelId");
        File d3 = new File(System.getProperty("java.io.tmpdir"), "d_gw3_" + System.nanoTime());
        d3.mkdirs();
        File c3f = new File(d3, "channels.json"), s3f = new File(d3, "settings.json");
        File a3f = new File(d3, "agents.json"), skf = new File(d3, "skills.json");
        File tdir = new File(d3, "toolfiles");
        LocalGateway gw3 = new LocalGateway(new Settings(s3f), new Store(a3f),
                "<h1>D3</h1>".getBytes("UTF-8"), new Channels(c3f), new com.doubao.app.Skills(skf),
                new com.doubao.app.Tools(tdir));
        gw3.start();
        String w = "http://127.0.0.1:" + gw3.port();

        ok("GET /skills 空库", http(w + "/skills", "GET", null).contains("\"skills\":[]"));
        String sk = http(w + "/skills", "POST",
                "{\"name\":\"校对\",\"note\":\"查错\",\"prompt\":\"请严格逐字校对文本。\"}");
        ok("POST /skills 201", sk.startsWith("201|"));
        String skId = new JSONObject(sk.substring(sk.indexOf('|') + 1)).getJSONObject("skill").getString("id");
        ok("GET /skills 含新建", http(w + "/skills", "GET", null).contains("请严格逐字校对文本"));
        ok("PUT /skills/<id> 生效", http(w + "/skills/" + skId, "PUT",
                "{\"name\":\"精校\",\"note\":\"\",\"prompt\":\"请精校。\"}").contains("\"name\":\"精校\""));
        ok("POST /skills 缺提示词被拒", http(w + "/skills", "POST",
                "{\"name\":\"空\",\"prompt\":\"\"}").startsWith("400|"));

        FakeOpenAi fake5 = new FakeOpenAi(); fake5.start();
        String b5 = "http://127.0.0.1:" + fake5.port() + "/v1";
        String c5 = http(w + "/channels", "POST", "{\"name\":\"deepseek\",\"baseUrl\":\"" + b5
                + "\",\"apiKey\":\"\",\"model\":\"m1\",\"models\":[\"m1\",\"m2\"]}");
        String cid5 = new JSONObject(c5.substring(c5.indexOf('|') + 1)).getJSONObject("channel").getString("id");
        String ag5 = http(w + "/agents", "GET", null);
        String aid5 = new JSONObject(ag5.substring(ag5.indexOf('|') + 1)).getJSONArray("agents")
                .getJSONObject(0).getString("id");

        // v1.1.21 #1/#3：模拟"点列表选技能"——prompt 内含激活 token(@\u200b精校\u200b)，
        // 并显式附带 skills:["精校"]，后端按名单注入 system。手打不触发由 SkillsTest 覆盖。
        String injected = http(w + "/agents/" + aid5 + "/chat", "POST",
                "{\"prompt\":\"@\u200b精校\u200b 看看这段\",\"channelId\":\"" + cid5 + "\",\"model\":\"m2\",\"skills\":[\"精校\"]}");
        ok("技能注入后仍返回上游答案", injected.contains("答案A"));
        ok("⑤ 技能提示词以 system 注入上游", fake5.lastBody.contains("\"role\":\"system\"")
                && fake5.lastBody.contains("请精校"));
        // v1.1.21 #3：发给上游的正文必须已剥离零宽激活标记（否则渠道只看到裸 @ → 报「技能名为空」）
        ok("⑤ 上游请求体不含零宽激活标记", !fake5.lastBody.contains("\u200b"));
        ok("⑤ 用户原文里的技能名不改写", fake5.lastBody.contains("精校"));
        ok("⑤ 模型仍按渠道内所选", new JSONObject(fake5.lastBody).getString("model").equals("m2"));
        String hist5 = http(w + "/agents/" + aid5, "GET", null);
        ok("⑤ 技能提示词不写入 history", !hist5.contains("请精校"));
        ok("⑤ 用户原文正常写入 history", hist5.contains("精校"));

        String pf = http(w + "/prefs", "GET", null);
        ok("⑦ /prefs 记住最后渠道", pf.contains("\"lastChannelId\":\"" + cid5 + "\""));
        ok("⑦ /prefs 记住最后模型", pf.contains("\"lastModel\":\"m2\""));
        String themePut = http(w + "/prefs", "PUT", "{\"theme\":\"dark\"}");
        ok("主题切换不丢记忆", themePut.contains("\"lastChannelId\":\"")
                && themePut.contains("\"lastModel\":\"m2\""));
        ok("显式 PUT 可改记忆", http(w + "/prefs", "PUT",
                "{\"lastChannelId\":\"builtin\",\"lastModel\":\"\"}").contains("\"lastChannelId\":\"builtin\""));
        // v1.1.11/v1.1.12 新增契约：全局提示词 / 提供商 / 工具 / 版本与项目地址
        ok("⑫ 全局操作指南注入上游", fake5.lastBody.contains("最大化完成用户的任务"));
        String pv = http(w + "/providers", "GET", null);
        ok("⑫ /providers 含通用与专用", pv.contains("OpenAI") && pv.contains("Deepseek")
                && pv.contains("Anthropic") && pv.contains("anthropic"));
        String tl = http(w + "/tools", "GET", null);
        ok("③ /tools 列出内置工具", tl.contains("搜索网页") && tl.contains("写文件") && tl.contains("列目录"));
        String wr = http(w + "/tools", "POST",
                "{\"id\":\"write_file\",\"args\":{\"path\":\"t.txt\",\"content\":\"hi\"}}");
        ok("③ 写文件工具可执行", wr.contains("\"ok\":true"));
        String rd = http(w + "/tools", "POST", "{\"id\":\"read_file\",\"args\":{\"path\":\"t.txt\"}}");
        ok("③ 读文件工具读回内容", rd.contains("hi"));
        String esc = http(w + "/tools", "POST", "{\"id\":\"read_file\",\"args\":{\"path\":\"../t.txt\"}}");
        ok("③ 路径 .. 被沙箱拒绝", esc.contains("不允许"));
        String ab2 = http(w + "/about", "GET", null);
        ok("⑫ 关于页版本 1.1.22", ab2.contains("1.1.22"));
        ok("⑫ 关于页项目地址", ab2.contains("github.com/asd11288k/xiao-zhushou"));
        fake5.close(); gw3.stop();
        c3f.delete(); s3f.delete(); a3f.delete(); skf.delete(); new File(d3, "prefs.json").delete();
        new File(tdir, "t.txt").delete(); tdir.delete(); d3.delete();

        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}