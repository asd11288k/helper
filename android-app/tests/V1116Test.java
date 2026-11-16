// v1.1.16 后端契约红测试：讯飞 MaaS 真实适配（不再硬编码模型清单 / 完整端点不重复拼接 / 404 文案不误导）。
// 编译：javac -cp libs/json.jar:/tmp/v1114tests -d /tmp/v1114tests tests/V1116Test.java
// 运行：java -cp libs/json.jar:/tmp/v1114tests V1116Test
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
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

public class V1116Test {
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

    /** 极简假上游：记录最后一次请求路径与方法，固定回 OpenAI 兼容 /models 列表。 */
    static class Fake implements Runnable {
        ServerSocket ss; Thread th; volatile String lastPath = "", lastMethod = "";
        volatile int forceCode = 200;   // 用于模拟 404 等错误响应
        volatile String modelsJson = "{\"object\":\"list\",\"data\":[{\"id\":\"spark-x2.6\"},{\"id\":\"spark-x2.6-4b\"}]}";
        Fake() throws Exception { ss = new ServerSocket(0); th = new Thread(this); th.setDaemon(true); th.start(); }
        int port() { return ss.getLocalPort(); }
        public void run() {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    s.setSoTimeout(8000);
                    java.io.InputStream is = s.getInputStream();
                    java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                    int c;
                    // 读到 \r\n\r\n 结束（header 结束符）
                    while ((c = is.read()) != -1) {
                        head.write(c);
                        byte[] h = head.toByteArray();
                        int n = h.length;
                        if (n >= 4 && h[n-4] == 13 && h[n-3] == 10 && h[n-2] == 13 && h[n-1] == 10) break;
                    }
                    String hs = new String(head.toByteArray(), "UTF-8");
                    String[] lines = hs.split("\r\n");
                    String[] parts = lines.length > 0 ? lines[0].split(" ") : new String[0];
                    lastMethod = parts.length > 0 ? parts[0] : "";
                    lastPath = parts.length > 1 ? parts[1] : "";
                    int len = 0;
                    for (int i = 1; i < lines.length; i++) {
                        String l = lines[i].toLowerCase();
                        if (l.startsWith("content-length:")) {
                            try { len = Integer.parseInt(lines[i].substring(15).trim()); } catch (Exception ig) {}
                        }
                    }
                    for (int i = 0; i < len; i++) { if (is.read() < 0) break; }
                    String resp = modelsJson;
                    if (lastPath != null && lastPath.endsWith("/chat/completions")) {
                        resp = "{\"choices\":[{\"message\":{\"content\":\"答案F\"}}]}";
                    }
                    byte[] rb = resp.getBytes("UTF-8");
                    OutputStream os = s.getOutputStream();
                    String st = forceCode == 200 ? "200 OK" : forceCode + " Error";
                    os.write(("HTTP/1.1 " + st + "\r\nContent-Type: application/json\r\nContent-Length: "
                            + rb.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    os.write(rb); os.flush(); s.close();
                } catch (Exception ignored) {}
            }
        }
        void close() { try { ss.close(); } catch (Exception ignored) {} }
    }

    public static void main(String[] args) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v1116_" + System.nanoTime());
        d.mkdirs();
        File af = new File(d, "agents.json"), cf = new File(d, "channels.json");
        File sf = new File(d, "settings.json"), skf = new File(d, "skills.json");
        File td = new File(d, "toolfiles");
        LocalGateway gw = new LocalGateway(new Settings(sf), new Store(af),
                "<h1>v1116</h1>".getBytes("UTF-8"), new Channels(cf), new Skills(skf), new Tools(td));
        gw.start();
        String v = "http://127.0.0.1:" + gw.port();

        System.out.println("[1] #1 拉取模型必须是真实 GET，不能对任何含 xf-yun.com 的地址返回固定清单");
        Fake f = new Fake();
        // 关键回归：路径里带 xf-yun.com 的地址，旧版 isXfyun 会命中并直接吐硬编码清单
        String spoof = "http://127.0.0.1:" + f.port() + "/xf-yun.com/v1";
        String r1 = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"" + spoof + "\",\"apiKey\":\"ak-test\"}");
        ok("含 xf-yun.com 的地址也走真实 /models 请求（旧版这里返回硬编码清单）", r1.startsWith("200|"));
        ok("假上游确实收到了 /models 请求（path=" + f.lastPath + "）",
                f.lastPath != null && f.lastPath.endsWith("/models"));
        JSONArray got = new JSONObject(body(r1)).optJSONArray("models");
        ok("返回的是上游真实型号 spark-x2.6", got != null && got.toString().contains("spark-x2.6"));
        ok("不再混入讯飞文档硬编码型号（lite/generalv3.5）",
                got != null && !got.toString().contains("generalv3.5") && !got.toString().contains("lite"));

        System.out.println("[2] #1 用户直接粘完整 /v2/chat/completions 也能拉到模型（不重复拼接）");
        String full = "http://127.0.0.1:" + f.port() + "/v2/chat/completions";
        String r2 = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"" + full + "\",\"apiKey\":\"ak-test\"}");
        ok("完整端点 probe 返回 200", r2.startsWith("200|"));
        ok("请求路径归一化为 /v2/models（实际=" + f.lastPath + "）",
                "/v2/models".equals(f.lastPath));
        ok("拉到模型列表", new JSONObject(body(r2)).optJSONArray("models") != null
                && new JSONObject(body(r2)).optJSONArray("models").length() > 0);

        System.out.println("[3] #1 对话请求也不能把 chat/completions 拼两次");
        String ans = com.doubao.app.OpenAiClient.chat(full, "ak-test", "spark-x2.6",
                "你好", new JSONArray(), "", "");
        ok("对话成功拿到上游答案（" + ans + "）", ans.contains("答案F"));
        ok("上游收到的路径是 /v2/chat/completions（实际=" + f.lastPath + "）",
                "/v2/chat/completions".equals(f.lastPath));
        String plain = "http://127.0.0.1:" + f.port() + "/v2";
        String ans2 = com.doubao.app.OpenAiClient.chat(plain, "ak-test", "spark-x2.6",
                "你好", new JSONArray(), "", "");
        ok("只填 /v2 根路径也能正常对话", ans2.contains("答案F"));
        ok("根路径自动补成 /v2/chat/completions（实际=" + f.lastPath + "）",
                "/v2/chat/completions".equals(f.lastPath));
        f.close();

        System.out.println("[4] #1 404 错误文案不再误导成『Base URL 需以 /v1 结尾』");
        String bad = http(v + "/channels/probe", "POST",
                "{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"x\"}");
        ok("不可达地址返回 4xx", bad.startsWith("4"));
        ok("文案不再写死 /v1 结尾", !body(bad).contains("需以 /v1 结尾"));
        ok("文案带上真实请求地址便于排查", body(bad).contains("models"));
        // 404 文案：区分「缺版本段」与「核对模型名」，且不再武断 /v1（用真返回 404 的假上游）
        Fake f404 = new Fake(); f404.forceCode = 404;
        String noVer = com.doubao.app.OpenAiClient.chat(
                "http://127.0.0.1:" + f404.port(), "k", "m", "hi", new JSONArray(), "", "");
        ok("缺版本段的 404 会指出缺少 /v1 或 /v2（实际=" + noVer + "）",
                noVer.contains("404") && noVer.contains("版本段"));
        String withVer = com.doubao.app.OpenAiClient.chat(
                "http://127.0.0.1:" + f404.port() + "/v2", "k", "m", "hi", new JSONArray(), "", "");
        ok("有版本段的 404 指向核对模型名（实际=" + withVer + "）",
                withVer.contains("404") && withVer.contains("模型名"));
        f404.close();

        System.out.println("[5] #1 讯飞预设指向 MaaS（用户实际在用的地址）");
        JSONArray pl = Providers.list();
        String xfBase = "", xfModel = "";
        for (int i = 0; i < pl.length(); i++) {
            JSONObject p = pl.optJSONObject(i);
            if (p != null && "xfyun".equals(p.optString("id", ""))) {
                xfBase = p.optString("baseUrl", ""); xfModel = p.optString("model", "");
            }
        }
        ok("讯飞预设 Base URL 为 MaaS v2（实际=" + xfBase + "）",
                xfBase.contains("maas-api.cn-huabei-1.xf-yun.com"));
        ok("讯飞预设模型为 spark-x2.6（实际=" + xfModel + "）", "spark-x2.6".equals(xfModel));

        System.out.println("[6] #1 已保存渠道 /models 同样走真实请求");
        String lc = body(http(v + "/channels", "POST",
                "{\"name\":\"maas2\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"k\",\"model\":\"m\"}"));
        String lcid = new JSONObject(lc).getJSONObject("channel").getString("id");
        String lr = http(v + "/channels/" + lcid + "/models", "GET", null);
        ok("不可达渠道不再假装成功返回固定清单", lr.startsWith("4"));

        System.out.println("[7] 版本 /about = 1.1.22");
        ok("/about 版本 1.1.22", body(http(v + "/about", "GET", null)).contains("1.1.22"));

        gw.stop();
        cf.delete(); sf.delete(); af.delete(); skf.delete();
        new File(d, "prefs.json").delete(); new File(d, "profile.json").delete();
        File[] left = td.listFiles(); if (left != null) for (int i = 0; i < left.length; i++) left[i].delete();
        td.delete(); d.delete();
        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
