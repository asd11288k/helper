// v1.1.15 新契约：删除单条消息 / 会话编号唯一 / 新 prefs 开关 / 提示词证据核对 / 沙箱绝对地址 / 国内搜索源
// 编译：javac -cp libs/json.jar:/tmp/v1112tests -d /tmp/v1112tests tests/V1113Test.java
// 运行：java -cp libs/json.jar:/tmp/v1112tests V1113Test
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
import java.nio.charset.Charset;

public class V1113Test {
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

    public static void main(String[] args) throws Exception {
        System.out.println("[1] 提示词：证据核对 + 沙箱绝对地址");
        String guide = LocalGateway.globalGuide();
        ok("提示词要求先核对再报完成", guide.contains("核对") && guide.contains("证据"));
        ok("提示词禁止无证据说完成", guide.contains("不要只说") || guide.contains("不许"));
        ok("提示词不撑爆上下文（长度受控）", guide.length() < 6000);
        String manual = LocalGateway.manual();
        ok("操作手册写明沙箱绝对地址", manual.contains("/data/data/com.helper.app/files/toolfiles")
                || manual.contains("getFilesDir()/toolfiles"));
        ok("操作手册说明文件工具范围", manual.contains("toolfiles"));

        System.out.println("[2] 搜索工具：国内可达端点 + 多源兜底");
        // 不联网，只验证源码契约（可达域名与聚合顺序由 check_build 静态保证）
        ok("搜索工具仍注册 search_web", Tools.list().toString().contains("search_web"));

        System.out.println("[3] Store：会话编号唯一 / 删除单条消息");
        File d = new File(System.getProperty("java.io.tmpdir"), "v1113_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json");
        Store st = new Store(af);
        JSONObject a = st.create("甲", "设定");
        String aid = a.getString("id");
        // 连开多个会话，验证 id 不重复
        JSONObject s1 = st.createSession(aid, "");
        JSONObject s2 = st.createSession(aid, "");
        JSONObject s3 = st.createSession(aid, "");
        ok("连续新建会话 id 互不重复",
                !s1.getString("id").equals(s2.getString("id"))
                && !s2.getString("id").equals(s3.getString("id"))
                && !s1.getString("id").equals(s3.getString("id")));
        ok("会话名按序号递增不重复",
                !s1.optString("name").equals(s2.optString("name"))
                && !s2.optString("name").equals(s3.optString("name")));
        String sid = s3.getString("id");
        // 写三条历史消息，删中间一条，验证只剩两条且顺序不变
        JSONArray ms = st.find(aid).getJSONArray("sessions")
                .getJSONObject(st.find(aid).getJSONArray("sessions").length() - 1)
                .getJSONArray("messages");
        ms.put(new JSONObject().put("role", "user").put("content", "一"));
        ms.put(new JSONObject().put("role", "assistant").put("content", "二"));
        ms.put(new JSONObject().put("role", "user").put("content", "三"));
        st.save();
        ok("删除单条消息（下标 1）成功", st.deleteMessage(aid, sid, 1));
        JSONArray after = st.findSession(aid, sid).getJSONArray("messages");
        ok("删除后只剩 2 条", after.length() == 2);
        ok("被删的「二」不再存在", !after.toString().contains("\"二\""));
        ok("其余消息顺序保持", after.optJSONObject(0).optString("content").equals("一")
                && after.optJSONObject(1).optString("content").equals("三"));
        ok("越界下标被拒绝", !st.deleteMessage(aid, sid, 99));

        System.out.println("[4] 网关：/messages/<idx> 路由 + 五开关 prefs + 版本");
        File cf = new File(d, "channels.json"), sf = new File(d, "settings.json");
        File skf = new File(d, "skills.json"), td = new File(d, "toolfiles");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af),
                "<h1>v1113</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();
        // 找到刚建的智能体（Store 重新加载）
        String ags = body(http(v + "/agents", "GET", null));
        JSONObject pub = null;
        JSONArray arr = new JSONObject(ags).getJSONArray("agents");
        for (int i = 0; i < arr.length(); i++) {
            if (aid.equals(arr.optJSONObject(i).optString("id"))) pub = arr.optJSONObject(i);
        }
        ok("新建智能体在列表中", pub != null);
        // 通过网关删单条消息
        String del = http(v + "/agents/" + aid + "/messages/0", "DELETE",
                "{\"sessionId\":\"" + sid + "\"}");
        ok("DELETE /messages/<idx> 200", del.startsWith("200|"));
        ok("删除返回 ok", body(del).contains("\"ok\":true"));
        ok("不存在的消息下标 404",
                http(v + "/agents/" + aid + "/messages/999", "DELETE",
                        "{\"sessionId\":\"" + sid + "\"}").startsWith("404|"));
        // prefs 五开关（stream / agentLoop 原有 + 新增三）
        String p0 = body(http(v + "/prefs", "GET", null));
        ok("prefs 默认 showThink=true", p0.contains("\"showThink\":true"));
        ok("prefs 默认 showTiming=true（#8 默认全开）", p0.contains("\"showTiming\":true"));
        ok("prefs 默认 stream=true（#8 默认全开）", p0.contains("\"stream\":true"));
        ok("prefs 默认 showTokens=false（前端已删此开关）", p0.contains("\"showTokens\":false"));
        ok("PUT showThink=false 生效",
                body(http(v + "/prefs", "PUT", "{\"showThink\":false}")).contains("\"showThink\":false"));
        ok("PUT showTiming/showTokens 生效",
                body(http(v + "/prefs", "PUT", "{\"showTiming\":true,\"showTokens\":true}"))
                        .contains("\"showTiming\":true") && body(http(v + "/prefs", "PUT",
                        "{\"showTiming\":true,\"showTokens\":true}")).contains("\"showTokens\":true"));
        String p1 = body(http(v + "/prefs", "GET", null));
        ok("GET 回读三开关", p1.contains("\"showThink\":false")
                && p1.contains("\"showTiming\":true") && p1.contains("\"showTokens\":true"));
        ok("改主题不冲掉三开关",
                body(http(v + "/prefs", "PUT", "{\"theme\":\"dark\"}")).contains("\"showTokens\":true"));
        ok("/about 版本 1.1.45", body(http(v + "/about", "GET", null)).contains("1.1.45"));
        // 需求⑨：编辑渠道回显密钥（列表接口仍不回显）
        String chAdd = body(http(v + "/channels", "POST",
                "{\"name\":\"带密钥\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"sk-real-key\",\"model\":\"m1\"}"));
        String cid = new JSONObject(chAdd).getJSONObject("channel").getString("id");
        ok("列表接口不回显密钥", !chAdd.contains("sk-real-key") && chAdd.contains("\"hasKey\":true"));
        String keyResp = body(http(v + "/channels/" + cid + "/key", "GET", null));
        ok("GET /channels/<id>/key 回显真实密钥", keyResp.contains("\"apiKey\":\"sk-real-key\""));
        ok("不存在的渠道 key 404",
                http(v + "/channels/nope/key", "GET", null).startsWith("404|"));
        gw.stop();
        cf.delete(); sf.delete(); af.delete(); skf.delete(); new File(d, "prefs.json").delete();
        File[] left = td.listFiles(); if (left != null) for (int i = 0; i < left.length; i++) left[i].delete();
        td.delete(); d.delete();

        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}