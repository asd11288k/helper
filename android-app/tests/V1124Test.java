// v1.1.24 后端契约测试：提示词查看器（B 预览 / A 历史留档）+ 测试模式三开关 + 技能路由。
import com.helper.app.Channels;
import com.helper.app.LocalGateway;
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

public class V1124Test {
    static int pass = 0, fail = 0;
    static void ok(String n, boolean c) {
        if (c) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n); }
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


    /** 极简假上游：固定回 OpenAI 兼容答案，用于验证留档真实落盘。 */
    static class FakeUp extends Thread {
        final java.net.ServerSocket ss;
        volatile String lastBody, lastPath;
        FakeUp() throws Exception {
            ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            setDaemon(true);
        }
        int port() { return ss.getLocalPort(); }
        public void run() {
            while (!ss.isClosed()) {
                try (java.net.Socket s = ss.accept()) {
                    java.io.BufferedInputStream in = new java.io.BufferedInputStream(s.getInputStream());
                    String line = readLine(in);
                    if (line == null) continue;
                    lastPath = line.split(" ")[1];
                    int len = 0;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:"))
                            len = Integer.parseInt(line.substring(15).trim());
                    }
                    byte[] buf = new byte[len]; int off = 0;
                    while (off < len) { int n = in.read(buf, off, len - off); if (n < 0) break; off += n; }
                    lastBody = new String(buf, 0, off, "UTF-8");
                    byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答案A\"}}]}"
                            .getBytes("UTF-8");
                    java.io.OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    os.write(body); os.flush();
                } catch (Exception e) { if (!ss.isClosed()) { /* ignore */ } }
            }
        }
        void close() throws Exception { ss.close(); }
    }
    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(b.toByteArray(), "US-ASCII");
            if (c != '\r') b.write(c);
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), "US-ASCII");
    }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1124_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json"), cf = new File(d, "channels.json");
        File sf = new File(d, "settings.json"), skf = new File(d, "skills.json");
        File td = new File(d, "toolfiles");
        Store store = new Store(af);
        LocalGateway gw = new LocalGateway(new Settings(sf), store,
                "<h1>v1124</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] prefs 三键默认关 + PUT 可落盘");
        JSONObject p0 = new JSONObject(body(http(v + "/prefs", "GET", null)));
        ok("testMode 默认 false", p0.optBoolean("testMode", true) == false);
        ok("promptViewerOn 默认 false", p0.optBoolean("promptViewerOn", true) == false);
        ok("promptHistoryOn 默认 false", p0.optBoolean("promptHistoryOn", true) == false);
        http(v + "/prefs", "PUT", "{\"testMode\":true,\"promptViewerOn\":true,\"promptHistoryOn\":true}");
        JSONObject p1 = new JSONObject(body(http(v + "/prefs", "GET", null)));
        ok("PUT 后三键均为 true", p1.optBoolean("testMode") && p1.optBoolean("promptViewerOn")
                && p1.optBoolean("promptHistoryOn"));
        ok("既有键未被破坏（theme 仍在）", p1.has("theme"));

        System.out.println("[2] B：/prompt_preview 现场拼装，不发消息");
        String aid = store.agents().getJSONObject(0).getString("id");
        store.update(aid, "预览体", "你是一个只会说喵的猫");
        String rp = http(v + "/prompt_preview", "POST", "{\"agentId\":\"" + aid + "\"}");
        ok("返回 200", rp.startsWith("200|"));
        JSONObject jp = new JSONObject(body(rp));
        String sys = jp.optString("system", "");
        ok("system 含全局指南", sys.contains("你是ai模型"));
        ok("system 含操作手册 v1.1.45", sys.contains("操作手册 v1.1.45"));
        ok("system 含思考等级段落", sys.contains("【当前模型思考等级】"));
        ok("setting 回显该智能体设定", "你是一个只会说喵的猫".equals(jp.optString("setting", "")));
        ok("GET 不允许（405）", http(v + "/prompt_preview", "GET", null).startsWith("405|"));
        ok("预览不产生留档 / 不写入会话消息",
                store.find(aid).optJSONArray("messages").length() == 0);

        System.out.println("[3] A：/prompt_log 读取与清空契约");
        File logf = new File(d, "prompt_log.jsonl");
        ok("未聊天时留档为空", !logf.exists() || logf.length() == 0);
        String rl = http(v + "/prompt_log", "GET", null);
        ok("GET 返回 200", rl.startsWith("200|"));
        JSONObject jl = new JSONObject(body(rl));
        ok("返回 items 数组", jl.optJSONArray("items") != null);
        ok("items 数量受 PROMPT_LOG_MAX=20 约束", jl.optJSONArray("items").length() <= 20);
        ok("enabled 反映 promptHistoryOn=true", jl.optBoolean("enabled", false));
        ok("DELETE 清空返回 200", http(v + "/prompt_log", "DELETE", null).startsWith("200|"));

        System.out.println("[4] A 真实留档：开历史开关后聊天必须落盘，关开关后不再新增");
        FakeUp fake = new FakeUp();
        fake.start();
        http(v + "/prefs", "PUT", "{\"promptHistoryOn\":true}");
        // 网关带 Channels 时 /settings 不再参与聊天路由，必须显式建渠道指向上假上游
        String rch = http(v + "/channels", "POST", "{\"name\":\"留档渠道\",\"baseUrl\":"
                + "\"http://127.0.0.1:" + fake.port() + "/v1\",\"apiKey\":\"sk-log\",\"model\":\"log-model\"}");
        ok("创建留档渠道成功", rch.startsWith("200|") || rch.startsWith("201|"));
        String cid = new JSONObject(body(rch)).optJSONObject("channel").getString("id");
        String chatBody = "{\"prompt\":\"留档探针问题\",\"channelId\":\"" + cid
                + "\",\"model\":\"log-model\"}";
        String rc = http(v + "/agents/" + aid + "/chat", "POST", chatBody);
        ok("聊天本身成功（留档不影响主链路）", rc.startsWith("200|") && rc.contains("答案A"));
        JSONObject jl1 = new JSONObject(body(http(v + "/prompt_log", "GET", null)));
        JSONArray it1 = jl1.optJSONArray("items");
        ok("留档新增 1 轮", it1 != null && it1.length() == 1);
        JSONObject rec = it1.getJSONObject(0);
        ok("留档含本轮用户消息", rec.optString("prompt", "").contains("留档探针问题"));
        ok("留档含系统提示词（与 B 同源）", rec.optString("system", "").contains("操作手册 v1.1.45"));
        ok("留档含智能体设定", rec.optString("setting", "").contains("只会说喵"));
        ok("留档含历史上下文数组", rec.optJSONArray("history") != null);
        ok("留档记录渠道与模型", rec.optString("model", "").equals("log-model")
                || rec.optString("channel", "").length() > 0);
        ok("prompt_log.jsonl 文件真实存在", logf.exists() && logf.length() > 0);
        ok("确实打到了假上游 /v1/chat/completions", "/v1/chat/completions".equals(fake.lastPath));
        // 关掉开关后不再留档
        http(v + "/prefs", "PUT", "{\"promptHistoryOn\":false}");
        http(v + "/agents/" + aid + "/chat", "POST", "{\"prompt\":\"关闭后不该留档\",\"channelId\":\""
                + cid + "\",\"model\":\"log-model\"}");
        JSONObject jl2 = new JSONObject(body(http(v + "/prompt_log", "GET", null)));
        ok("关闭 promptHistoryOn 后轮数不再增长", jl2.optJSONArray("items").length() == 1);
        ok("enabled 变为 false", jl2.optBoolean("enabled", true) == false);
        // 清空
        http(v + "/prompt_log", "DELETE", null);
        JSONObject jl3 = new JSONObject(body(http(v + "/prompt_log", "GET", null)));
        ok("DELETE 后留档为空", jl3.optJSONArray("items").length() == 0);
        fake.close();

        System.out.println("[5] #2 依赖：/skills 可被启动预载读取");
        http(v + "/skills", "POST", "{\"name\":\"v1124probe\",\"note\":\"探针\",\"prompt\":\"只回答：OK\"}");
        String rs = http(v + "/skills", "GET", null);
        ok("GET /skills 返回 200", rs.startsWith("200|"));
        JSONArray sks = new JSONObject(body(rs)).optJSONArray("skills");
        ok("新建技能立即可读到（无需进技能页）",
                sks != null && sks.toString().contains("v1124probe"));

        System.out.println("[6] 版本一致");
        String ab = body(http(v + "/about", "GET", null));
        ok("/about 版本 1.1.27", ab.contains("\"version\":\"1.1.45\""));
        ok("手册标题 v1.1.26", LocalGateway.manual().contains("操作手册 v1.1.45】"));

        gw.stop();
        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
