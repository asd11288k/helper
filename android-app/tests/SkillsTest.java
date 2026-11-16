// 纯 JVM 单元测试：Skills 持久化 + @技能名 提示词解析
// 编译：javac -cp libs/json.jar -d tests/out3 tests/SkillsTest.java src/com/helper/app/Skills.java
// 运行：java -cp libs/json.jar:tests/out3 SkillsTest
import com.helper.app.Skills;
import org.json.JSONObject;

import java.io.File;

public class SkillsTest {
    static int pass = 0, fail = 0;
    static void eq(String n, Object w, Object g) {
        boolean ok = w == null ? g == null : w.equals(g);
        if (ok) { pass++; System.out.println("  ok  " + n); }
        else { fail++; System.out.println("  FAIL " + n + " want=" + w + " got=" + g); }
    }
    static void ok(String n, boolean c) { eq(n, true, c); }

    public static void main(String[] args) throws Exception {
        System.out.println("[Skills] 持久化 + @技能解析");
        File dir = new File(System.getProperty("java.io.tmpdir"), "skills_test_" + System.nanoTime());
        dir.mkdirs();
        File f = new File(dir, "skills.json");

        Skills s = new Skills(f);
        eq("空库无技能", 0, s.publicJson().length());
        eq("空文本无注入", "", s.promptFor("你好"));

        JSONObject a = s.create("校对", "逐字检查", "请逐字校对文本，指出错别字。");
        String aid = a.getString("id");
        ok("创建返回 id", aid.length() > 0);
        eq("创建返回名称", "校对", a.getString("name"));
        eq("创建返回备注", "逐字检查", a.getString("note"));
        eq("创建返回提示词", "请逐字校对文本，指出错别字。", a.getString("prompt"));
        eq("库内 1 条", 1, s.publicJson().length());

        JSONObject b = s.create("润色", "", "请把文字改得更通顺。");
        String bid = b.getString("id");
        eq("库内 2 条", 2, s.publicJson().length());
        JSONObject c = s.create("校对大师", "", "请做大师级校对。");
        String cid = c.getString("id");
        ok("同毫秒批量创建 id 不重复", !aid.equals(bid) && !bid.equals(cid) && !aid.equals(cid));
        // v1.1.20 #1：只认激活 token「@\u200b名\u200b」（点列表才产生），手打 @名 一律不触发。
        eq("长技能名 token 只注入自身", "请做大师级校对。", s.promptFor("@\u200b校对大师\u200b 看看"));
        ok("手打 @校对 无 token 不触发", s.promptFor("@校对 帮我看看").length() == 0);

        eq("激活 token 命中", "请逐字校对文本，指出错别字。", s.promptFor("@\u200b校对\u200b 帮我看看"));
        ok("无 @ 不命中", s.promptFor("帮我看看").length() == 0);
        ok("多个 token 按顺序拼接", s.promptFor("@\u200b校对\u200b @\u200b润色\u200b 这段")
                .contains("请逐字校对文本") && s.promptFor("@\u200b校对\u200b @\u200b润色\u200b 这段").contains("请把文字改得更通顺"));
        eq("token 精确不误命中长名", "请逐字校对文本，指出错别字。", s.promptFor("@\u200b校对\u200b 一次"));
        ok("promptForNames 显式名单注入", "请逐字校对文本，指出错别字。".equals(
                s.promptForNames(new org.json.JSONArray().put("校对"))));

        Skills reloaded = new Skills(f);
        eq("重载后仍有 3 条", 3, reloaded.publicJson().length());
        eq("重载后 token 提示词完整", "请逐字校对文本，指出错别字。", reloaded.promptFor("@\u200b校对\u200b 看看"));

        JSONObject up = reloaded.update(aid, "精校", "改了备注", "请严格逐字校对。");
        ok("更新非空", up != null);
        eq("更新后名称", "精校", up.getString("name"));
        eq("更新后 token 提示词生效", "请严格逐字校对。", reloaded.promptFor("@\u200b精校\u200b 看看"));
        ok("旧名 token 不再命中", reloaded.promptFor("@\u200b校对\u200b 看看").length() == 0);
        eq("更新不存在的 id 返回 null", null, reloaded.update("nope", "x", "", "y"));

        ok("删除成功", reloaded.delete(aid));
        eq("删除后剩 2 条", 2, reloaded.publicJson().length());
        ok("删除后 token 不再命中", reloaded.promptFor("@\u200b精校\u200b 看看").length() == 0);
        ok("重复删除返回 false", !reloaded.delete(aid));
        ok("删除润色", reloaded.delete(bid));
        ok("删除校对大师", reloaded.delete(cid));
        eq("删空", 0, reloaded.publicJson().length());

        Skills again = new Skills(f);
        eq("删空后重载仍为空", 0, again.publicJson().length());

        f.delete(); dir.delete();
        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}