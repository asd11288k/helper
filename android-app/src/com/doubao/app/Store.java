package com.doubao.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.io.RandomAccessFile;

/**
 * 智能体持久化：与网页版 agents.json 结构完全一致 {"agents":[{id,name,setting,messages}]}。
 * 纯 JVM 类，不依赖 Android SDK；写入采用 tmp + rename 原子替换（对齐 Python 版 os.replace）。
 */
public final class Store {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final File file;
    private JSONArray agents;

    public Store(File file) {
        this.file = file;
        this.agents = load();
        migrateSessions();
        // 首次启动（或损坏文件降级为空）时内置默认智能体
        if (agents.length() == 0) {
            try {
                JSONObject a = new JSONObject()
                        .put("id", "default")
                        .put("name", "小助手")
                        .put("setting", "你是用户的小助手，对话风格简单高效，皆是为了完成用户的任务。")
                        .put("sessions", new JSONArray().put(
                                new JSONObject().put("id", "s1").put("name", "会话 1")
                                        .put("messages", new JSONArray())))
                        .put("activeSessionId", "s1")
                        .put("messages", new JSONArray());
                agents.put(a);
                save();
            } catch (JSONException ignored) {}
        }
    }

    public File file() { return file; }

    public JSONArray agents() { return agents; }

    private JSONArray load() {
        try {
            if (!file.exists()) return new JSONArray();
            byte[] b = readAll(file);
            if (b.length == 0) return new JSONArray();
            JSONObject root = new JSONObject(new String(b, UTF8));
            JSONArray a = root.optJSONArray("agents");
            return a != null ? a : new JSONArray();
        } catch (Exception e) {
            return new JSONArray(); // 损坏文件按空处理，与 Python 版一致
        }
    }

    public JSONObject find(String id) {
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = agents.optJSONObject(i);
            if (a != null && id.equals(a.optString("id", ""))) return a;
        }
        return null;
    }

    /** 老版 agents.json 只有 messages：升级为单会话结构，历史一条不丢。 */
    private void migrateSessions() {
        boolean changed = false;
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = agents.optJSONObject(i);
            if (a == null) continue;
            JSONArray ss = a.optJSONArray("sessions");
            if (ss != null && ss.length() > 0) {
                if (a.optString("activeSessionId", "").length() == 0) {
                    String first = ss.optJSONObject(0).optString("id", "s1");
                    try { a.put("activeSessionId", first); changed = true; }
                    catch (JSONException ignored) {}
                }
                continue;
            }
            JSONArray old = a.optJSONArray("messages");
            if (old == null) old = new JSONArray();
            try {
                JSONObject s1 = new JSONObject().put("id", "s1").put("name", "会话 1").put("messages", old);
                a.put("sessions", new JSONArray().put(s1));
                a.put("activeSessionId", "s1");
                changed = true;
            } catch (JSONException ignored) {}
        }
        if (changed) save();
    }

    public JSONObject create(String name, String setting) throws JSONException {
        JSONObject a = new JSONObject()
                .put("id", String.valueOf(System.currentTimeMillis()))
                .put("name", name)
                .put("setting", setting)
                .put("sessions", new JSONArray().put(
                        new JSONObject().put("id", "s1").put("name", "会话 1")
                                .put("messages", new JSONArray())))
                .put("activeSessionId", "s1")
                .put("messages", new JSONArray());
        agents.put(a);
        save();
        return a;
    }

    public JSONObject update(String id, String name, String setting) throws JSONException {
        JSONObject a = find(id);
        if (a == null) return null;
        a.put("name", name).put("setting", setting);
        save();
        return a;
    }

    /** v1.1.21 #2：判断是否已存在同名智能体（excludeId 用于编辑时排除自身；可空）。 */
    public boolean nameExists(String name, String excludeId) {
        if (name == null) return false;
        String n = name.trim();
        if (n.length() == 0) return false;
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = agents.optJSONObject(i);
            if (a == null) continue;
            if (excludeId != null && excludeId.equals(a.optString("id", ""))) continue;
            if (n.equals(a.optString("name", ""))) return true;
        }
        return false;
    }

    public void delete(String id) {
        JSONArray next = new JSONArray();
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = agents.optJSONObject(i);
            if (a != null && !id.equals(a.optString("id", ""))) next.put(a);
        }
        agents = next;
        save();
    }

    // ---------- 多会话（需求⑦） ----------
    /** 取智能体的会话数组；不存在则自动补一个。 */
    public JSONArray sessions(String agentId) {
        JSONObject a = find(agentId);
        if (a == null) return null;
        JSONArray ss = a.optJSONArray("sessions");
        if (ss == null) {
            ss = new JSONArray();
            try {
                JSONObject s1 = new JSONObject().put("id", "s1").put("name", "会话 1")
                        .put("messages", a.optJSONArray("messages") == null
                                ? new JSONArray() : a.optJSONArray("messages"));
                ss.put(s1);
                a.put("sessions", ss).put("activeSessionId", "s1");
                save();
            } catch (JSONException ignored) {}
        }
        return ss;
    }

    public JSONObject findSession(String agentId, String sessionId) {
        JSONArray ss = sessions(agentId);
        if (ss == null) return null;
        String sid = sessionId == null || sessionId.length() == 0
                ? find(agentId).optString("activeSessionId", "s1") : sessionId;
        for (int i = 0; i < ss.length(); i++) {
            JSONObject o = ss.optJSONObject(i);
            if (o != null && sid.equals(o.optString("id", ""))) return o;
        }
        return ss.length() > 0 ? ss.optJSONObject(0) : null;
    }

    public JSONObject createSession(String agentId, String name) throws JSONException {
        JSONObject a = find(agentId);
        if (a == null) return null;
        JSONArray ss = sessions(agentId);
        // 需求⑧：id 用「毫秒 + 自增序号」，同一毫秒连开也不重复；名字取已用最大序号 +1
        // 注意：不能用 findSession 判重——它找不到时会回退到 sessions[0]，会死循环
        String id;
        int seq = 0;
        boolean dup;
        do {
            id = "s" + System.currentTimeMillis() + "_" + (seq++);
            dup = false;
            for (int i = 0; i < ss.length(); i++) {
                JSONObject o = ss.optJSONObject(i);
                if (o != null && id.equals(o.optString("id", ""))) { dup = true; break; }
            }
        } while (dup);
        int maxNo = 0;
        for (int i = 0; i < ss.length(); i++) {
            String n = ss.optJSONObject(i) == null ? "" : ss.optJSONObject(i).optString("name", "");
            try {
                int no = Integer.parseInt(n.replaceAll("[^0-9]", ""));
                if (no > maxNo) maxNo = no;
            } catch (Exception ignored) {}
            if (n.startsWith("会话 ")) {
                try {
                    int no = Integer.parseInt(n.substring(3).trim());
                    if (no > maxNo) maxNo = no;
                } catch (Exception ignored) {}
            }
        }
        String finalName = name == null || name.trim().length() == 0
                ? "会话 " + (maxNo + 1) : name.trim();
        JSONObject s1 = new JSONObject().put("id", id)
                .put("name", finalName)
                .put("messages", new JSONArray());
        ss.put(s1);
        a.put("activeSessionId", id);
        save();
        return s1;
    }

    /** 需求⑬：删除指定会话里的单条历史消息；删后不再注入上游上下文。 */
    /** 增量追加一条消息到指定会话（调用方持有 store 锁；并发请求不会互相覆盖）。 */
    public boolean appendMessage(String agentId, String sessionId, String role, String content) {
        return appendMessage(agentId, sessionId, role, content, 0);
    }

    /** v1.1.18 #3：可选把本轮耗时 elapsedMs 一并持久化进消息对象，
     *  切换会话后历史仍能重建「回复耗时」标注（0 表示不记录）。 */
    public boolean appendMessage(String agentId, String sessionId, String role, String content, long elapsedMs) {
        JSONObject s1 = findSession(agentId, sessionId);
        if (s1 == null) return false;
        JSONArray ms = s1.optJSONArray("messages");
        if (ms == null) ms = new JSONArray();
        try {
            JSONObject m = new JSONObject().put("role", role).put("content", content);
            if (elapsedMs > 0) m.put("elapsedMs", elapsedMs);
            ms.put(m);
            s1.put("messages", ms);
            JSONObject a = find(agentId);
            if (a != null) a.put("messages", ms);
            save();
            return true;
        } catch (JSONException e) { return false; }
    }

    /** 长按编辑：直接改持久化上下文里的这条消息。 */
    public boolean updateMessage(String agentId, String sessionId, int index, String content) {
        JSONObject s1 = findSession(agentId, sessionId);
        if (s1 == null) return false;
        JSONArray ms = s1.optJSONArray("messages");
        if (ms == null || index < 0 || index >= ms.length()) return false;
        JSONObject m = ms.optJSONObject(index);
        if (m == null) return false;
        try {
            m.put("content", content == null ? "" : content);
            JSONObject a = find(agentId);
            if (a != null) a.put("messages", ms);
            save();
            return true;
        } catch (JSONException e) { return false; }
    }

    public boolean deleteMessage(String agentId, String sessionId, int index) {
        JSONObject s1 = findSession(agentId, sessionId);
        if (s1 == null) return false;
        JSONArray ms = s1.optJSONArray("messages");
        if (ms == null || index < 0 || index >= ms.length()) return false;
        // JSONArray.remove(int) 在 org.json 老版本可能不存在，手工重建保序
        JSONArray next = new JSONArray();
        for (int i = 0; i < ms.length(); i++) {
            if (i != index) next.put(ms.opt(i));
        }
        try {
            s1.put("messages", next);
            JSONObject a = find(agentId);
            if (a != null) a.put("messages", next);  // 旧顶层镜像同步，避免下次聊天又带回来
            save();
            return true;
        } catch (JSONException e) { return false; }
    }

    public boolean selectSession(String agentId, String sessionId) {
        JSONObject a = find(agentId);
        if (a == null || findSession(agentId, sessionId) == null) return false;
        try { a.put("activeSessionId", sessionId); save(); return true; }
        catch (JSONException e) { return false; }
    }

    public boolean renameSession(String agentId, String sessionId, String name) {
        JSONObject s1 = findSession(agentId, sessionId);
        if (s1 == null || name == null || name.trim().length() == 0) return false;
        try { s1.put("name", name.trim()); save(); return true; }
        catch (JSONException e) { return false; }
    }

    /** 删除会话：至少保留一个，最后一个只清空消息。 */
    public boolean deleteSession(String agentId, String sessionId) {
        JSONObject a = find(agentId);
        JSONArray ss = sessions(agentId);
        if (a == null || ss == null) return false;
        if (ss.length() <= 1) {
            JSONObject only = ss.optJSONObject(0);
            try { only.put("messages", new JSONArray()); save(); return true; }
            catch (JSONException e) { return false; }
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < ss.length(); i++) {
            JSONObject o = ss.optJSONObject(i);
            if (o != null && !sessionId.equals(o.optString("id", ""))) next.put(o);
        }
        try {
            a.put("sessions", next);
            if (sessionId.equals(a.optString("activeSessionId", "")))
                a.put("activeSessionId", next.optJSONObject(0).optString("id", "s1"));
            save();
            return true;
        } catch (JSONException e) { return false; }
    }

    /** 清空某智能体的全部对话（保留名称与设定，兼容旧 /clear 契约）。 */
    public boolean clearMessages(String id) {
        return clearMessages(id, "");
    }

    /** 需求⑦：只清指定会话；sessionId 为空时清当前激活会话。 */
    public boolean clearMessages(String id, String sessionId) {
        JSONObject a = find(id);
        if (a == null) return false;
        try {
            a.put("messages", new JSONArray());
            JSONObject s1 = findSession(id, sessionId);
            if (s1 != null) s1.put("messages", new JSONArray());
            save();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** 落盘。调用方在修改 messages 后需显式调用（对齐网页版每次聊天后写回）。 */
    public void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            String text = new JSONObject().put("agents", agents).toString();
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(text);
            w.close();
            if (file.exists() && !file.delete()) { /* 忽略，下面 rename 仍覆盖 */ }
            if (!tmp.renameTo(file)) {
                // renameTo 在某些文件系统失败时退化为直接写目标
                Writer w2 = new OutputStreamWriter(new FileOutputStream(file), UTF8);
                w2.write(text);
                w2.close();
                tmp.delete();
            }
        } catch (Exception e) {
            // 落盘失败不抛，避免聊天中断；与 Python 版无显式处理的行为一致
        }
    }

    private static byte[] readAll(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) raf.length()];
        raf.readFully(b);
        raf.close();
        return b;
    }
}