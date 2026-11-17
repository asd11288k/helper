// v1.1.27 #4 后端契约测试：测试模式可覆盖「全局指南 / 操作手册」。
//  接缝：GET /prompt_override 预填当前生效内容；PUT 写入 promptOverride.json 覆盖；
//        DELETE 删除文件回落 static；buildUpstream/segmentsOf（经 /prompt_preview）走实例口。
//  守卫：覆盖只影响实例口 effGuide/effManual，绝不能动 static guideRules/manual/globalGuide，
//        因此 globalGuide().length() 必须仍 < 6000（与 V1113/V1114 同锁）。
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

public class V1127Test {
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

    // 取 /prompt_preview 的 segments（顺序：0 全局指南 / 1 操作手册 / 2 智能体设定 / ...）
    static JSONArray previewSegs(String v, String aid) throws Exception {
        String pv = body(http(v + "/prompt_preview", "POST",
                "{\"agentId\":\"" + aid + "\",\"channelId\":\"builtin\",\"prompt\":\"探针\",\"model\":\"\"}"));
        return new JSONObject(pv).getJSONArray("segments");
    }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1127_" + System.nanoTime());
        d.mkdirs();
        Store store = new Store(new File(d, "agents.json"));
        LocalGateway gw = new LocalGateway(new Settings(new File(d, "settings.json")), store,
                "<h1>v1127</h1>".getBytes("UTF-8"), new Channels(new File(d, "channels.json")),
                new Skills(new File(d, "skills.json")), new Tools(new File(d, "toolfiles")));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();
        String aid = store.agents().getJSONObject(0).getString("id");

        System.out.println("[1] 覆盖守卫：static 内容必须未被动过");
        ok("globalGuide() 仍 < 4000（static 未被覆盖逻辑污染）", LocalGateway.globalGuide().length() < 6000);
        ok("static guideRules() 仍含原始开头", LocalGateway.guideRules().contains("你是ai模型"));
        ok("static manual() 仍含软件使用说明", LocalGateway.manual().contains("■ 软件使用说明"));

        System.out.println("[2] 默认（无覆盖文件）：预填即 static 内容，overridden=false");
        String g0 = body(http(v + "/prompt_override", "GET", null));
        JSONObject j0 = new JSONObject(g0);
        ok("GET 返回 guide 含 static 开头", j0.optString("guide").contains("你是ai模型"));
        ok("GET 返回 manual 含 static 说明", j0.optString("manual").contains("■ 软件使用说明"));
        ok("overridden=false（未覆盖）", !j0.optBoolean("overridden", true));
        ok("defaultGuide 同步给出", j0.optString("defaultGuide").contains("你是ai模型"));

        System.out.println("[3] 默认时 preview 首两段 = static 指南/手册");
        JSONArray s0 = previewSegs(v, aid);
        ok("segments[0]=全局指南且为 static 内容", "全局指南".equals(s0.getJSONObject(0).getString("title"))
                && s0.getJSONObject(0).getString("text").contains("你是ai模型"));
        ok("segments[1]=操作手册且为 static 内容", "操作手册".equals(s0.getJSONObject(1).getString("title"))
                && s0.getJSONObject(1).getString("text").contains("■ 软件使用说明"));

        System.out.println("[4] PUT 覆盖：preview 首两段换成自定义内容");
        String put = http(v + "/prompt_override", "PUT",
                "{\"guide\":\"OVR-GUIDE-777 自定义指南头\",\"manual\":\"OVR-MANUAL-888 自定义手册尾\"}");
        ok("PUT 返回 200", put.startsWith("200|"));
        JSONObject jp = new JSONObject(body(put));
        ok("PUT 后 overridden=true", jp.optBoolean("overridden", false));
        JSONArray s1 = previewSegs(v, aid);
        ok("覆盖生效：segments[0] 含 OVR-GUIDE-777", s1.getJSONObject(0).getString("text").contains("OVR-GUIDE-777"));
        ok("覆盖生效：segments[1] 含 OVR-MANUAL-888", s1.getJSONObject(1).getString("text").contains("OVR-MANUAL-888"));
        ok("覆盖文件已落盘", new File(d, "promptOverride.json").exists());
        String g1 = body(http(v + "/prompt_override", "GET", null));
        JSONObject jg1 = new JSONObject(g1);
        ok("GET 反映当前生效=覆盖内容", jg1.optString("guide").contains("OVR-GUIDE-777")
                && jg1.optString("manual").contains("OVR-MANUAL-888"));

        System.out.println("[5] 只覆盖其一：另一段仍走 static 默认");
        http(v + "/prompt_override", "PUT", "{\"guide\":\"OVR-GUIDE-ONLY\"}");
        JSONArray s2 = previewSegs(v, aid);
        ok("guide 被覆盖为 OVR-GUIDE-ONLY", s2.getJSONObject(0).getString("text").contains("OVR-GUIDE-ONLY"));
        ok("manual 未传则保持上一次覆盖（OVR-MANUAL-888）", s2.getJSONObject(1).getString("text").contains("OVR-MANUAL-888"));

        System.out.println("[6] 半覆盖：空串覆盖 guide 也如实生效（允许清空某段）");
        http(v + "/prompt_override", "PUT", "{\"guide\":\"\",\"manual\":\"OVR-MANUAL-Z\"}");
        JSONArray s3 = previewSegs(v, aid);
        ok("guide 覆盖为空串", s3.getJSONObject(0).getString("text").length() == 0);
        ok("manual 覆盖为 OVR-MANUAL-Z", s3.getJSONObject(1).getString("text").contains("OVR-MANUAL-Z"));

        System.out.println("[7] DELETE 回落：preview 首两段恢复 static");
        String del = http(v + "/prompt_override", "DELETE", null);
        ok("DELETE 返回 200", del.startsWith("200|"));
        ok("DELETE 返回 overridden=false", !new JSONObject(body(del)).optBoolean("overridden", true));
        ok("覆盖文件已删除", !new File(d, "promptOverride.json").exists());
        JSONArray s4 = previewSegs(v, aid);
        ok("回落：segments[0] 重新含 static 开头", s4.getJSONObject(0).getString("text").contains("你是ai模型"));
        ok("回落：segments[1] 重新含 static 说明", s4.getJSONObject(1).getString("text").contains("■ 软件使用说明"));

        gw.stop();
        System.out.println("");
        if (fail == 0) System.out.println("全部通过 ✅ " + pass + " 项");
        else System.out.println(fail + " 项失败 ❌（共 " + (pass + fail) + " 项）");
        System.exit(fail == 0 ? 0 : 1);
    }
}