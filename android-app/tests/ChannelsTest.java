// Channels 纯 JVM 单测：内置渠道置顶/不可编辑、CRUD、密钥不回显、损坏文件降级
// 编译：javac -cp libs/json.jar -d tests/out tests/ChannelsTest.java src/com/doubao/app/Channels.java
import com.doubao.app.Channels;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public class ChannelsTest {
    static int pass = 0, fail = 0;
    static void eq(String n, Object w, Object g) {
        boolean ok = w == null ? g == null : w.equals(g);
        if (ok) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n + " want=" + w + " got=" + g); }
    }
    static void ok(String n, boolean c) { eq(n, true, c); }

    public static void main(String[] args) throws Exception {
        File f = new File(System.getProperty("java.io.tmpdir"), "ch_test_" + System.nanoTime() + ".json");
        Channels c = new Channels(f);

        System.out.println("[内置渠道]");
        eq("默认只有 1 个渠道", 1, c.publicJson().length());
        JSONObject b = c.publicJson().getJSONObject(0);
        eq("内置 id", Channels.BUILTIN_ID, b.getString("id"));
        eq("内置名称", "内置模型", b.getString("name"));
        eq("默认启用", true, b.getBoolean("enabled"));
        eq("不可编辑", true, b.getBoolean("locked"));
        eq("默认选中内置", Channels.BUILTIN_ID, c.defaultId());
        ok("内置不可修改", c.update(Channels.BUILTIN_ID, "改名", "http://x", "k", "m") == null);
        ok("内置不可停用", c.setEnabled(Channels.BUILTIN_ID, false) == null);
        ok("内置不可删除", !c.delete(Channels.BUILTIN_ID));
        eq("内置仍在", 1, c.publicJson().length());

        System.out.println("[OpenAI 渠道 CRUD]");
        JSONObject a = c.create("我的网关", "http://127.0.0.1:1234/v1/", "sk-secret", "gpt-4o-mini");
        String id = a.getString("id");
        eq("去尾斜杠", "http://127.0.0.1:1234/v1", a.getString("baseUrl"));
        ok("不回显密钥", !a.toString().contains("sk-secret") && a.getBoolean("hasKey"));
        eq("新增后 2 个", 2, c.publicJson().length());
        eq("新增位列第二", id, c.publicJson().getJSONObject(1).getString("id"));
        ok("find 能取到密钥", "sk-secret".equals(c.find(id).getString("apiKey")));

        JSONObject u = c.update(id, "改名后", "https://api.x/v1/", "", "model-B");
        eq("改名", "改名后", u.getString("name"));
        eq("改 URL 去尾斜杠", "https://api.x/v1", u.getString("baseUrl"));
        eq("key 留空保留旧值", "sk-secret", c.find(id).getString("apiKey"));
        eq("停用", false, c.setEnabled(id, false).getBoolean("enabled"));
        eq("重新启用", true, c.setEnabled(id, true).getBoolean("enabled"));

        System.out.println("[重载与降级]");
        Channels c2 = new Channels(f);
        eq("重载后仍 2 个", 2, c2.publicJson().length());
        eq("重载后内置仍置顶", Channels.BUILTIN_ID, c2.publicJson().getJSONObject(0).getString("id"));
        ok("重载后不回显密钥", !c2.publicJson().toString().contains("sk-secret"));
        eq("重载后密钥仍在", "sk-secret", c2.find(id).getString("apiKey"));
        java.nio.file.Files.write(f.toPath(), "BROKEN".getBytes("UTF-8"));
        Channels c3 = new Channels(f);
        eq("损坏文件降级为内置", 1, c3.publicJson().length());
        eq("损坏文件内置仍置顶", Channels.BUILTIN_ID, c3.publicJson().getJSONObject(0).getString("id"));
        ok("删除自建渠道", c.delete(id));
        eq("删除后只剩内置", 1, c.publicJson().length());
        f.delete();

        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}