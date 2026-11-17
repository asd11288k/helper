// v1.1.26 后端契约测试：
//  #1 提示词查看器：messages 序列必须自解释——协议外壳伪装轮标 shell=true，
//     真实对话历史与本轮消息不带 shell；前端据此输出「一份按顺序的完整清单」。
//  #2 输入栏：四控件高度唯一来源 #row --ctlH（静态契约在 v1126_red.py / check_build.sh 锁死）。
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

public class V1126Test {
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
    static boolean isShell(JSONObject m) { return m.optBoolean("shell", false); }

    /** 假上游：捕获请求体并固定回 OpenAI 兼容答案，使第一轮 chat 真正成功并写入历史。 */
    static class FakeUp extends Thread {
        final java.net.ServerSocket ss;
        volatile String lastBody; int hits = 0;
        FakeUp() throws Exception {
            ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            setDaemon(true);
        }
        int port() { return ss.getLocalPort(); }
        static String rd(InputStream in) throws Exception {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') return new String(b.toByteArray(), "US-ASCII");
                if (c != '\r') b.write(c);
            }
            return b.size() == 0 ? null : new String(b.toByteArray(), "US-ASCII");
        }
        public void run() {
            while (!ss.isClosed()) {
                try (java.net.Socket s = ss.accept()) {
                    InputStream in = new java.io.BufferedInputStream(s.getInputStream());
                    if (rd(in) == null) continue;
                    String line; int len = 0;
                    while ((line = rd(in)) != null && !line.isEmpty())
                        if (line.toLowerCase().startsWith("content-length:"))
                            len = Integer.parseInt(line.substring(15).trim());
                    byte[] buf = new byte[len]; int off = 0;
                    while (off < len) { int n = in.read(buf, off, len - off); if (n < 0) break; off += n; }
                    lastBody = new String(buf, 0, off, "UTF-8");
                    hits++;
                    byte[] bd = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答一\"}}]}".getBytes("UTF-8");
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: "
                            + bd.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    os.write(bd); os.flush();
                } catch (Exception e) { /* ignore */ }
            }
        }
        void close() throws Exception { ss.close(); }
    }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1126_" + System.nanoTime());
        d.mkdirs();
        Store store = new Store(new File(d, "agents.json"));
        LocalGateway gw = new LocalGateway(new Settings(new File(d, "settings.json")), store,
                "<h1>v1126</h1>".getBytes("UTF-8"), new Channels(new File(d, "channels.json")),
                new Skills(new File(d, "skills.json")), new Tools(new File(d, "toolfiles")));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] 版本点 1.1.27 / code 20");
        ok("globalGuide < 4000", LocalGateway.globalGuide().length() < 6000);
        ok("手册标题 v1.1.26", LocalGateway.manual().contains("操作手册 v1.1.45】"));
        ok("手册说明是一份完整清单（含常驻设定+对话历史+本轮消息）",
                LocalGateway.manual().contains("完整清单")
                        && LocalGateway.manual().contains("常驻设定")
                        && LocalGateway.manual().contains("对话历史"));
        ok("手册不再承诺『与 messages』双视角", !LocalGateway.manual().contains("与 messages"));
        ok("/about 1.1.27", body(http(v + "/about", "GET", null)).contains("\"version\":\"1.1.45\""));

        String aid = store.agents().getJSONObject(0).getString("id");
        store.update(aid, "外壳体", "智能体设定MARK-SET");
        http(v + "/prefs", "PUT", "{\"promptHistoryOn\":true}");
        http(v + "/profile", "PUT", "{\"content\":\"用户背景PROF-XYZ\",\"enabled\":true}");

        System.out.println("[2] openai 协议：首条 system 标 shell，历史/本轮不标");
        FakeUp fake = new FakeUp();
        fake.start();
        String oai = new JSONObject(body(http(v + "/channels", "POST",
                "{\"name\":\"oai\",\"baseUrl\":\"http://127.0.0.1:" + fake.port() + "/v1\",\"apiKey\":\"k\",\"model\":\"m\"}")))
                .optJSONObject("channel").getString("id");
        // 先发一轮制造历史
        String c1 = http(v + "/agents/" + aid + "/chat", "POST",
                "{\"prompt\":\"第一问\",\"channelId\":\"" + oai + "\",\"model\":\"m\"}");
        ok("第一轮 chat 真正成功（历史才会写入）", c1.startsWith("200|") && c1.contains("答一"));
        String pv1 = body(http(v + "/prompt_preview", "POST",
                "{\"agentId\":\"" + aid + "\",\"channelId\":\"" + oai + "\",\"prompt\":\"第二问\",\"model\":\"m\"}"));
        JSONObject j1 = new JSONObject(pv1);
        JSONArray m1 = j1.getJSONArray("messages");
        ok("openai: 首条 system 且 shell=true",
                "system".equals(m1.getJSONObject(0).getString("role")) && isShell(m1.getJSONObject(0)));
        ok("openai: system 内容含智能体设定与思考等级",
                m1.getJSONObject(0).getString("content").contains("智能体设定MARK-SET")
                        && m1.getJSONObject(0).getString("content").contains("【当前模型思考等级】"));
        ok("openai: 历史轮不带 shell", m1.getJSONObject(1).getString("role").equals("user")
                && !isShell(m1.getJSONObject(1)));
        JSONObject cur1 = m1.getJSONObject(m1.length() - 1);
        ok("openai: 末条=本轮 user 且不带 shell（必须显示）",
                "user".equals(cur1.getString("role")) && cur1.getString("content").contains("第二问")
                        && !isShell(cur1));

        System.out.println("[3] core（内置）协议：两条伪装轮都标 shell");
        String pv2 = body(http(v + "/prompt_preview", "POST",
                "{\"agentId\":\"" + aid + "\",\"channelId\":\"builtin\",\"prompt\":\"内置探针\",\"model\":\"\"}"));
        JSONArray m2 = new JSONObject(pv2).getJSONArray("messages");
        ok("core: 首条 user 是伪装外壳且 shell=true",
                "user".equals(m2.getJSONObject(0).getString("role")) && isShell(m2.getJSONObject(0))
                        && m2.getJSONObject(0).getString("content")
                                .contains("请在整个对话中严格扮演并遵守以下角色设定"));
        ok("core: 第二条 assistant 假确认轮 shell=true",
                "assistant".equals(m2.getJSONObject(1).getString("role")) && isShell(m2.getJSONObject(1)));
        JSONObject cur2 = m2.getJSONObject(m2.length() - 1);
        ok("core: 末条=本轮真实 user，不带 shell",
                "内置探针".equals(cur2.getString("content")) && !isShell(cur2));

        System.out.println("[4] 预览分段标题顺序 = 常驻设定五段依次排列");
        JSONArray segs1 = j1.getJSONArray("segments");
        ok("segments 非空且首段=全局指南", segs1.length() >= 4
                && "全局指南".equals(segs1.getJSONObject(0).getString("title")));
        ok("segments 顺序：指南→手册→设定→用户设定→思考等级",
                "操作手册".equals(segs1.getJSONObject(1).getString("title"))
                        && "智能体设定".equals(segs1.getJSONObject(2).getString("title"))
                        && "用户设定".equals(segs1.getJSONObject(3).getString("title"))
                        && "思考等级".equals(segs1.getJSONObject(4).getString("title")));

        System.out.println("[5] 留档条目同样带 shell 标记的 messages（历史查看器同构）");
        http(v + "/agents/" + aid + "/chat", "POST",
                "{\"prompt\":\"留档探针问题\",\"channelId\":\"" + oai + "\",\"model\":\"m\"}");
        JSONArray items = new JSONObject(body(http(v + "/prompt_log", "GET", null)))
                .optJSONArray("items");
        ok("留档有内容", items != null && items.length() > 0);
        JSONObject last = items.getJSONObject(items.length() - 1);
        JSONArray lm = last.getJSONArray("messages");
        ok("留档 messages 首条 system 标 shell",
                "system".equals(lm.getJSONObject(0).getString("role")) && isShell(lm.getJSONObject(0)));
        JSONObject lcur = lm.getJSONObject(lm.length() - 1);
        ok("留档末条=该轮真实 user，不标 shell",
                lcur.getString("content").contains("留档探针问题") && !isShell(lcur));

        gw.stop();
        System.out.println("");
        if (fail == 0) System.out.println("全部通过 ✅ " + pass + " 项");
        else System.out.println(fail + " 项失败 ❌（共 " + (pass + fail) + " 项）");
        System.exit(fail == 0 ? 0 : 1);
    }
}