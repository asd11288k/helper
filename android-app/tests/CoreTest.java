// 纯 JVM 单元测试：Core（上游协议/设定注入/流式解析）+ Store（持久化）
// 编译：javac -cp libs/json.jar -d tests/out tests/CoreTest.java src/com/doubao/app/Core.java src/com/doubao/app/Store.java
// 运行：java -cp libs/json.jar:tests/out CoreTest
import org.json.JSONArray;
import org.json.JSONObject;
import com.doubao.app.Core;
import com.doubao.app.Store;

import java.io.File;

public class CoreTest {
    static int pass = 0, fail = 0;

    static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) { pass++; System.out.println("  ok  " + name); }
        else { fail++; System.out.println("  FAIL " + name + " want=" + want + " got=" + got); }
    }
    static void ok(String name, boolean cond) { eq(name, true, cond); }

    static JSONArray hist() throws Exception {
        JSONArray h = new JSONArray();
        h.put(new JSONObject().put("role", "user").put("content", "上一问"));
        h.put(new JSONObject().put("role", "assistant").put("content", "上一答"));
        return h;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[Core.buildMessages] 设定注入顺序与不落库");
        JSONArray h = hist();
        JSONArray m = Core.buildMessages("1+1", h, "无论问什么都答香蕉苹果");
        eq("有设定+2条历史 => 5条", 5, m.length());
        eq("第1条是设定user", "user", m.getJSONObject(0).getString("role"));
        ok("第1条含wrapper", m.getJSONObject(0).getString("content").startsWith(Core.SETTING_WRAPPER));
        ok("第1条含设定内容", m.getJSONObject(0).getString("content").contains("香蕉苹果"));
        eq("第2条是设定确认", "assistant", m.getJSONObject(1).getString("role"));
        eq("第2条内容", Core.SETTING_ACK, m.getJSONObject(1).getString("content"));
        eq("第3条=历史0", "上一问", m.getJSONObject(2).getString("content"));
        eq("第4条=历史1", "上一答", m.getJSONObject(3).getString("content"));
        eq("第5条=当前问题", "1+1", m.getJSONObject(4).getString("content"));
        eq("设定不写入 history", 2, h.length());

        JSONArray m2 = Core.buildMessages("hi", new JSONArray(), "");
        eq("无设定 => 1条", 1, m2.length());
        eq("无设定时就是当前问题", "hi", m2.getJSONObject(0).getString("content"));
        JSONArray m3 = Core.buildMessages("hi", new JSONArray(), null);
        eq("setting=null => 1条", 1, m3.length());
        JSONArray m4 = Core.buildMessages("hi", new JSONArray(), "   ");
        eq("setting=空白 => 1条", 1, m4.length());

        System.out.println("[Core.payload] 上游字段对齐 Python 版");
        JSONObject p = Core.payload("问题X", m2);
        eq("model", "gemini-3.1-pro-preview", p.getString("model"));
        eq("user_content=当前问题", "问题X", p.getString("user_content"));
        eq("is_vision=false", false, p.getBoolean("is_vision"));
        eq("is_done=1", 1, p.getInt("is_done"));
        eq("chat_group_id", 5573739, p.getInt("chat_group_id"));
        eq("messages 长度一致", 1, p.getJSONArray("messages").length());
        ok("含 thinking 字段", p.has("thinking"));

        System.out.println("[Core.extractAnswer] 流式解析");
        String s1 = "[-DATA-:]{\"answer\":\"香\",\"complete_answer\":\"香\"}"
                  + "[-DATA-:]{\"answer\":\"蕉\",\"complete_answer\":\"香蕉\"}"
                  + "[-DATA-:]{\"answer\":\"苹果\",\"complete_answer\":\"香蕉苹果\"}";
        eq("取最长的累积 complete_answer", "香蕉苹果", Core.extractAnswer(s1));

        String s2 = "[-DATA-:]{\"complete_answer\":\"正在思考中，请稍后...\"}"
                  + "[-DATA-:]{\"answer\":\"2\",\"complete_answer\":\"2\"}";
        eq("跳过含'正在思考'的分片", "2", Core.extractAnswer(s2));

        String s3 = "[-DATA-:]{\"answer\":\"你\"}[-DATA-:]{\"answer\":\"好\"}[-DATA-:]{\"answer\":\"！\"}";
        eq("无 complete_answer 时累加增量", "你好！", Core.extractAnswer(s3));

        String s4 = "[-DATA-:]garbage"
                  + "[-DATA-:]"
                  + "[-DATA-:][DONE]"
                  + "[-DATA-:]{\"answer\":\"A\",\"complete_answer\":\"A\"}"
                  + "[-DATA-:]{\"answer\":\"正在思考\",\"complete_answer\":\"正在思考中，请稍后...\"}";
        eq("脏分片/空片/DONE/思考片都跳过", "A", Core.extractAnswer(s4));

        String s5 = "[-DATA-:]{\"complete_answer\":\"答\"}[-DATA-:]{\"complete_answer\":\"案从这里被截断\"}";
        eq("累积值增长时取更长的", "案从这里被截断", Core.extractAnswer(s5));

        String s6 = "[-DATA-:]{\"complete_answer\":\"  结果  正在思考中，请稍后...\"}";
        eq("整片含思考占位则整片跳过", "", Core.extractAnswer(s6));

        // 对齐 Python 版：长度倒退的分片不会覆盖更长的累积值
        String s7 = "[-DATA-:]{\"complete_answer\":\"完整答案\"}[-DATA-:]{\"complete_answer\":\"短\"}";
        eq("长度倒退时保留更长的", "完整答案", Core.extractAnswer(s7));
        eq("rawText=null 返回空串", "", Core.extractAnswer(null));

        System.out.println("[Store] 智能体 CRUD + 落盘重载");
        File tmp = File.createTempFile("doubao_store_", ".json");
        tmp.delete();
        Store st = new Store(new File(tmp.getParentFile(), "agents_" + System.nanoTime() + ".json"));
        eq("初始为空", 0, st.agents().length());
        JSONObject a = st.create("翻译官", "只做翻译");
        ok("create 返回 id", a.getString("id").length() > 0);
        eq("create 名称", "翻译官", a.getString("name"));
        eq("create 设定", "只做翻译", a.getString("setting"));
        eq("create 消息为空", 0, a.getJSONArray("messages").length());
        eq("列表长度=1", 1, st.agents().length());

        JSONObject found = st.find(a.getString("id"));
        ok("find 命中", found != null && "翻译官".equals(found.getString("name")));
        ok("find 不存在返回 null", st.find("nope") == null);

        JSONObject upd = st.update(a.getString("id"), "译者", "中译英");
        ok("update 非空", upd != null);
        eq("update 后名称", "译者", st.find(a.getString("id")).getString("name"));
        eq("update 后设定", "中译英", st.find(a.getString("id")).getString("setting"));

        // 追加消息后落盘，重建 Store 验证持久化
        st.find(a.getString("id")).getJSONArray("messages")
          .put(new JSONObject().put("role", "user").put("content", "hello"))
          .put(new JSONObject().put("role", "assistant").put("content", "你好"));
        st.save();
        Store st2 = new Store(st.file());
        eq("重载后列表长度", 1, st2.agents().length());
        eq("重载后名称", "译者", st2.agents().getJSONObject(0).getString("name"));
        eq("重载后消息数", 2, st2.agents().getJSONObject(0).getJSONArray("messages").length());

        st.delete(a.getString("id"));
        eq("delete 后为空", 0, st.agents().length());
        st.file().delete();

        System.out.println("\n" + (fail == 0 ? "全部通过 ✅ " + pass + " 项" : fail + " 项失败 ❌"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
