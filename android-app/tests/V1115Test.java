// v1.1.15 后端契约红测试：讯飞无 /models 端点返回内置清单 / 搜索并发早停+提速 / 版本。
// 编译：javac -cp libs/json.jar:/tmp/v1114tests -d /tmp/v1114tests tests/V1115Test.java
// 运行：java -cp libs/json.jar:/tmp/v1114tests V1115Test
import com.helper.app.Channels;
import com.helper.app.LocalGateway;
import com.helper.app.Providers;
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

public class V1115Test {
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

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1115_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json"), cf = new File(d, "channels.json");
        File sf = new File(d, "settings.json"), skf = new File(d, "skills.json");
        File td = new File(d, "toolfiles");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af),
                "<h1>v1115</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] #3 讯飞：拉取模型一律真实 GET（v1.1.16 起不再有硬编码兜底）");
        // v1.1.16：旧的「按域名吐固定清单」已删除。这里用不可达地址验证：
        // 任何地址都不会再被当成讯飞而返回 lite/generalv3.5 这类文档型号。
        String probe1 = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"https://spark-api-open.xf-yun.com/v1\",\"apiKey\":\"ak-test\"}");
        ok("旧的 spark-api-open 地址不再假装成功返回固定清单",
                !body(probe1).contains("generalv3.5"));
        String probe2 = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"x\"}");
        ok("不可达地址如实报错并带出请求地址",
                probe2.startsWith("4") && body(probe2).contains("/v1/models"));
        ok("错误文案不再误导成『需以 /v1 结尾』", !body(probe2).contains("需以 /v1 结尾"));

        System.out.println("[2] #2 搜索提速：并发早停，单次 search_web 不再等慢源卡满 8 秒");
        Tools t = new Tools(td);
        long s = System.currentTimeMillis();
        JSONObject r = t.exec("search_web", new JSONObject().put("query", "天气"));
        long ms = System.currentTimeMillis() - s;
        ok("单次 search_web 耗时 < 6000ms（实测旧版 8000ms+）  got=" + ms + "ms", ms < 6000);
        ok("仍是并发（concurrent=true）", r.optBoolean("concurrent", false));
        ok("结果结构完整（results/tried/sources）",
                r.has("results") && r.has("tried") && r.has("sources"));
        JSONArray stats = r.optJSONArray("sources");
        ok("逐源统计里不再有卡满 8 秒的源",
                stats != null && stats.length() > 0 && maxElapsed(stats) < 6000);
        ok("note 如实说明早停/并发", r.optString("note", "").contains("并发"));

        System.out.println("[3] 讯飞预设指向 MaaS（v1.1.16 起）");
        JSONArray pl = Providers.list();
        String xfBase = "", xfModel = "";
        for (int i = 0; i < pl.length(); i++) {
            JSONObject p = pl.optJSONObject(i);
            if (p != null && "xfyun".equals(p.optString("id", ""))) {
                xfBase = p.optString("baseUrl", ""); xfModel = p.optString("model", "");
            }
        }
        ok("讯飞预设 Base URL 指向 MaaS 实际可用端点", xfBase.contains("maas-api.cn-huabei-1.xf-yun.com"));
        ok("讯飞预设模型为 MaaS 真实型号 spark-x2.6", "spark-x2.6".equals(xfModel));

        gw.stop();
        cf.delete(); sf.delete(); af.delete(); skf.delete();
        new File(d, "prefs.json").delete(); new File(d, "profile.json").delete();
        File[] left = td.listFiles(); if (left != null) for (int i = 0; i < left.length; i++) left[i].delete();
        td.delete(); d.delete();
        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
    static long maxElapsed(JSONArray stats) {
        long mx = 0;
        for (int i = 0; i < stats.length(); i++) {
            long e = stats.optJSONObject(i) == null ? 0 : stats.optJSONObject(i).optLong("elapsedMs", 0);
            if (e > mx) mx = e;
        }
        return mx;
    }
}
