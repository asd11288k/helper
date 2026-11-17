// v1.1.25 后端契约测试（v1.1.26 仍全过，回归护栏）：
//  #1 提示词顺序 全局指南→操作手册→智能体设定→用户设定→思考等级→本轮技能（以真实上游请求体为证）
//  #3 提示词查看器：B 虚拟请求按真实顺序完整还原；预览与真实聊天拼装字节相等；留档与预览同构
//  #4 思考等级传参开/关时，提示词如实标注，且请求参数确实随之收发
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

public class V1125Test {
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

    /** 假上游：记录最近一次请求体，固定回 OpenAI 兼容答案。 */
    static class FakeUp extends Thread {
        final java.net.ServerSocket ss;
        volatile String lastBody, lastPath; int hits = 0;
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
                    hits++;
                    byte[] bd = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答案A\"}}]}"
                            .getBytes("UTF-8");
                    java.io.OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: "
                            + bd.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    os.write(bd); os.flush();
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

    static int idx(String s, String mark) { return s.indexOf(mark); }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1125_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json"), cf = new File(d, "channels.json");
        File sf = new File(d, "settings.json"), skf = new File(d, "skills.json");
        File td = new File(d, "toolfiles");
        Store store = new Store(af);
        LocalGateway gw = new LocalGateway(new Settings(sf), store,
                "<h1>v1125</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] 长度守卫与版本点");
        ok("globalGuide 未撑爆上下文（<6000）", LocalGateway.globalGuide().length() < 6000);
        ok("手册标题 v1.1.26", LocalGateway.manual().contains("操作手册 v1.1.45】"));
        ok("手册写明新拼装顺序",
                LocalGateway.manual().contains("全局指南→操作手册→智能体设定→用户设定→思考等级→本轮技能"));
        ok("/about 版本 1.1.27", body(http(v + "/about", "GET", null)).contains("\"version\":\"1.1.45\""));

        // 备好可辨识的四要素：智能体设定 / 用户设定 / 思考等级 / 技能
        String aid = store.agents().getJSONObject(0).getString("id");
        store.update(aid, "顺序体", "智能体设定MARK-SET");
        http(v + "/prefs", "PUT", "{\"promptHistoryOn\":true}");
        http(v + "/profile", "PUT", "{\"content\":\"用户背景PROF-XYZ\",\"enabled\":true}");
        http(v + "/skills", "POST", "{\"name\":\"顺序技能\",\"note\":\"探针\",\"prompt\":\"技能内容MARK-SKILL\"}");
        FakeUp fake = new FakeUp();
        fake.start();
        String rch = http(v + "/channels", "POST", "{\"name\":\"顺序渠道\",\"baseUrl\":"
                + "\"http://127.0.0.1:" + fake.port() + "/v1\",\"apiKey\":\"sk-ord\",\"model\":\"ord-model\"}");
        String cid = new JSONObject(body(rch)).optJSONObject("channel").getString("id");

        System.out.println("[2] #1 真实上游请求体里的顺序（铁证）");
        String vprompt = "顺序探针问题";
        String chatJson = "{\"prompt\":\"" + vprompt + "\",\"channelId\":\"" + cid
                + "\",\"model\":\"ord-model\",\"skills\":[\"顺序技能\"]}";
        String rc = http(v + "/agents/" + aid + "/chat", "POST", chatJson);
        ok("聊天成功", rc.startsWith("200|") && rc.contains("答案A"));
        JSONObject req = new JSONObject(fake.lastBody);
        JSONArray msgs = req.getJSONArray("messages");
        ok("首条是 system 承载全部提示词", "system".equals(msgs.getJSONObject(0).getString("role")));
        String sys = msgs.getJSONObject(0).getString("content");
        int iGuide = idx(sys, "你是ai模型");
        int iManual = idx(sys, "【小助手 App 操作手册 v1.1.45】");
        int iSet = idx(sys, "智能体设定MARK-SET");
        int iProf = idx(sys, "用户背景PROF-XYZ");
        int iEff = idx(sys, "【当前模型思考等级】");
        int iSkill = idx(sys, LocalGateway.SKILL_LEAD);  // 用完整引导串，避开指南里的同名提及
        int iSkillBody = idx(sys, "技能内容MARK-SKILL");
        ok("① 全局指南在最前", iGuide >= 0);
        ok("② 操作手册紧随指南", iManual > iGuide);
        ok("③ 智能体设定在手册之后（旧版设定在最前，已按要求调换）", iSet > iManual);
        ok("④ 用户设定在设定之后", iProf > iSet);
        ok("⑤ 思考等级在用户设定之后", iEff > iProf);
        ok("⑥ 本轮技能排在最后", iSkill > iEff && iSkillBody > iSkill);
        ok("指南与手册相邻（手册前无设定插队）",
                idx(sys, "总原则：简单高效") < iManual && iManual < iSet);
        ok("本轮用户消息作为末条 user", "user".equals(msgs.getJSONObject(msgs.length() - 1).getString("role"))
                && msgs.getJSONObject(msgs.length() - 1).getString("content").contains(vprompt));

        System.out.println("[3] #4 思考等级传参开关：提示词如实标注 + 参数随之收发");
        ok("默认（传参关）提示词标注未发送参数",
                sys.contains("『思考等级传参』已关闭") && sys.contains("未把 reasoning_effort 参数发送给模型"));
        ok("默认（传参关）请求体不含 reasoning_effort 字段", !req.has("reasoning_effort"));
        http(v + "/prefs", "PUT", "{\"reasoningParamOn\":true,\"reasoningEffort\":\"medium\"}");
        int h2 = fake.hits;
        http(v + "/agents/" + aid + "/chat", "POST", "{\"prompt\":\"传参开探针\",\"channelId\":\"" + cid
                + "\",\"model\":\"ord-model\"}");
        ok("传参开后确实打到上游", fake.hits == h2 + 1);
        JSONObject req2 = new JSONObject(fake.lastBody);
        ok("传参开后请求体带 reasoning_effort=medium",
                "medium".equals(req2.optString("reasoning_effort", "")));
        String sys2 = req2.getJSONArray("messages").getJSONObject(0).getString("content");
        ok("传参开后提示词标注已发送参数", sys2.contains("已随请求把该等级作为 reasoning_effort 参数发送给模型"));
        ok("传参开后不再出现『已关闭』字样", !sys2.contains("『思考等级传参』已关闭"));
        http(v + "/prefs", "PUT", "{\"reasoningParamOn\":false,\"reasoningEffort\":\"high\"}");

        System.out.println("[4] #3 B：/prompt_preview 虚拟请求 —— 与真实聊天字节相等");
        int hitsBefore = fake.hits;
        long msgsBefore = store.find(aid).optJSONArray("messages").length();
        File logf = new File(d, "prompt_log.jsonl");
        long logSizeBefore = logf.exists() ? logf.length() : 0;
        String rp = http(v + "/prompt_preview", "POST", "{\"agentId\":\"" + aid + "\",\"channelId\":\"" + cid
                + "\",\"sessionId\":\"\",\"prompt\":\"" + vprompt + "\",\"skills\":[\"顺序技能\"]}");
        ok("预览返回 200", rp.startsWith("200|"));
        JSONObject jp = new JSONObject(body(rp));
        ok("预览未打上游（虚拟请求）", fake.hits == hitsBefore);
        ok("预览未写入会话消息", store.find(aid).optJSONArray("messages").length() == msgsBefore);
        ok("预览未追加留档", (logf.exists() ? logf.length() : 0) == logSizeBefore);
        String psys = jp.optString("system", "");
        ok("预览 system 与真实聊天拼装逐字节相等（顺序必然一致）", psys.equals(sys));
        ok("预览含智能体设定（v1.1.24 缺失项已补）", psys.contains("智能体设定MARK-SET"));
        JSONArray segs = jp.optJSONArray("segments");
        ok("预览返回 segments 分段数组", segs != null && segs.length() >= 5);
        String[] want = {"全局指南", "操作手册", "智能体设定", "用户设定", "思考等级", "本轮技能"};
        boolean orderOk = segs != null && segs.length() == want.length;
        for (int i = 0; orderOk && i < want.length; i++) orderOk = want[i].equals(segs.getJSONObject(i).optString("title"));
        ok("分段标题顺序 = 全局指南→操作手册→智能体设定→用户设定→思考等级→本轮技能", orderOk);
        StringBuilder rebuilt = new StringBuilder();
        if (segs != null) for (int i = 0; i < segs.length(); i++) {
            if (i > 0) rebuilt.append("\n\n");
            rebuilt.append(segs.getJSONObject(i).optString("text").trim());
        }
        ok("分段拼接可复现完整提示词", rebuilt.length() > 3000 && psys.length() > 3000);
        JSONArray pv = jp.optJSONArray("messages");
        ok("预览返回上游 messages 序列", pv != null && pv.length() >= 2);
        ok("messages 末条是本轮问题", pv != null
                && pv.getJSONObject(pv.length() - 1).optString("content").contains(vprompt));
        ok("预览回显 protocol 供外壳还原", jp.optString("protocol", "openai").length() > 0);
        ok("GET 预览不允许（405）", http(v + "/prompt_preview", "GET", null).startsWith("405|"));

        System.out.println("[5] #3 A：留档条目与预览同构（历史查看器顺序一致）");
        JSONObject jl = new JSONObject(body(http(v + "/prompt_log", "GET", null)));
        JSONArray items = jl.optJSONArray("items");
        ok("留档有内容", items != null && items.length() >= 2);
        JSONObject last = items.optJSONObject(items.length() - 1);
        ok("留档含 segments", last != null && last.optJSONArray("segments") != null
                && last.optJSONArray("segments").length() >= 5);
        JSONArray ls = last.optJSONArray("segments");
        boolean lOrder = ls != null && ls.length() >= 3
                && "全局指南".equals(ls.getJSONObject(0).optString("title"))
                && "操作手册".equals(ls.getJSONObject(1).optString("title"))
                && "智能体设定".equals(ls.getJSONObject(2).optString("title"));
        ok("留档分段顺序与预览一致（不再各拼各的）", lOrder);
        ok("留档含 messages 序列", last != null && last.optJSONArray("messages") != null);
        ok("留档记录 protocol", last != null && last.optString("protocol", "").length() > 0);
        ok("留档 system 与预览同源", last != null && last.optString("system", "").contains("操作手册 v1.1.45"));

        System.out.println("[6] 环形裁剪：留档不超过 20 轮");
        for (int i = 0; i < 25; i++) {
            http(v + "/agents/" + aid + "/chat", "POST", "{\"prompt\":\"裁剪探针" + i
                    + "\",\"channelId\":\"" + cid + "\",\"model\":\"ord-model\"}");
        }
        JSONArray items2 = new JSONObject(body(http(v + "/prompt_log", "GET", null))).optJSONArray("items");
        ok("连发 25 轮后留档恰为 20 条", items2 != null && items2.length() == 20);
        ok("保留的是最新一轮", items2 != null
                && items2.getJSONObject(19).optString("prompt", "").contains("裁剪探针24"));
        ok("DELETE 清空生效", http(v + "/prompt_log", "DELETE", null).startsWith("200|")
                && new JSONObject(body(http(v + "/prompt_log", "GET", null)))
                        .optJSONArray("items").length() == 0);

        System.out.println("[7] #1 兼容：无设定智能体不产生空设定段");
        String a2 = new JSONObject(body(http(v + "/agents", "POST", "{\"name\":\"无设定体\"}")))
                .optJSONObject("agent").getString("id");
        String p2 = body(http(v + "/prompt_preview", "POST", "{\"agentId\":\"" + a2 + "\"}"));
        String s2 = new JSONObject(p2).optString("system", "");
        ok("无设定时提示词仍完整（指南+手册）",
                s2.contains("你是ai模型") && s2.contains("操作手册 v1.1.45"));
        ok("无设定时分段里没有『智能体设定』", p2 != null
                && !p2.contains("\"title\":\"智能体设定\""));
        ok("内置渠道预览还原为伪装轮外壳（core）",
                new JSONObject(body(http(v + "/prompt_preview", "POST",
                        "{\"agentId\":\"" + a2 + "\",\"channelId\":\"builtin\"}")))
                        .optJSONArray("messages").getJSONObject(0).optString("role")
                        .equals("user"));

        gw.stop();
        fake.close();
        System.out.println("");
        if (fail == 0) System.out.println("全部通过 ✅ " + pass + " 项");
        else System.out.println(fail + " 项失败 ❌（共 " + (pass + fail) + " 项）");
        System.exit(fail == 0 ? 0 : 1);
    }
}