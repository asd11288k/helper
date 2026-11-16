package com.helper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * 技能库：{id,name,note,prompt}。prompt 是后台注入给上游的系统级提示词，
 * 用户在聊天框用 @技能名 引用；注入内容不写入对话历史，也不回显给用户。
 * 纯 JVM 类，不依赖 Android SDK；只用 API 23 可用的 java.io。
 */
public final class Skills {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File file;
    private JSONArray skills;
    private long idSeq = 0;

    public Skills(File file) {
        this.file = file;
        this.skills = load();
    }

    public File file() { return file; }

    private JSONArray load() {
        try {
            if (!file.exists()) return new JSONArray();
            byte[] b = readAll(file);
            if (b.length == 0) return new JSONArray();
            JSONObject root = new JSONObject(new String(b, UTF8));
            JSONArray a = root.optJSONArray("skills");
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public JSONArray skills() { return skills; }

    public JSONArray publicJson() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            try {
                out.put(new JSONObject()
                        .put("id", s.optString("id", ""))
                        .put("name", s.optString("name", ""))
                        .put("note", s.optString("note", ""))
                        .put("prompt", s.optString("prompt", "")));
            } catch (JSONException ignored) {}
        }
        return out;
    }

    public JSONObject find(String id) {
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s != null && id.equals(s.optString("id", ""))) return s;
        }
        return null;
    }

    /** v1.1.21 #2：判断是否已存在同名技能（excludeId 用于编辑时排除自身；可空）。 */
    public synchronized boolean nameExists(String name, String excludeId) {
        if (name == null) return false;
        String n = name.trim();
        if (n.length() == 0) return false;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (excludeId != null && excludeId.equals(s.optString("id", ""))) continue;
            if (n.equals(s.optString("name", ""))) return true;
        }
        return false;
    }

    public synchronized JSONObject create(String name, String note, String prompt) throws JSONException {
        JSONObject s = new JSONObject()
                .put("id", "sk" + System.currentTimeMillis() + "_" + (++idSeq))
                .put("name", name == null ? "" : name.trim())
                .put("note", note == null ? "" : note.trim())
                .put("prompt", prompt == null ? "" : prompt.trim());
        skills.put(s);
        save();
        return publicOf(s);
    }

    public synchronized JSONObject update(String id, String name, String note, String prompt) throws JSONException {
        JSONObject s = find(id);
        if (s == null) return null;
        s.put("name", name == null ? "" : name.trim());
        s.put("note", note == null ? "" : note.trim());
        s.put("prompt", prompt == null ? "" : prompt.trim());
        save();
        return publicOf(s);
    }

    public synchronized boolean delete(String id) {
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s != null && id.equals(s.optString("id", ""))) { removed = true; continue; }
            next.put(s);
        }
        if (removed) { skills = next; save(); }
        return removed;
    }

    /** 解析文本里的激活 token：形式为 @(零宽空格)技能名(零宽空格)。
     *  v1.1.20 #1：改为精确匹配被点选的激活 token，不再用 text.contains("@名") 模糊匹配。
     *  这同时解决两类失效——纯数字技能名（"1"、"2"）被互相误判，以及
     *  「模型说没找到技能但风格已变」（旧版注入靠模糊命中、上游看不到明确指令）。
     *  手打的 @1 没有零宽标记 → 不激活。 */
    public synchronized String promptFor(String text) {
        if (text == null || text.length() == 0) return "";
        char zw = '\u200b';
        java.util.ArrayList<String> prompts = new java.util.ArrayList<String>();
        java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '@' && i + 1 < text.length() && text.charAt(i + 1) == zw) {
                int end = text.indexOf(zw, i + 2);
                if (end > i + 1) {
                    String name = text.substring(i + 2, end);
                    String p = promptOf(name);
                    if (p != null && seen.indexOf(name) < 0) { seen.add(name); prompts.add(p); }
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < prompts.size(); k++) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(prompts.get(k));
        }
        return sb.toString();
    }

    /** v1.1.20 #1：按前端显式传来的「激活技能名」列表注入，与 token 解析等价，
     *  用于上游只看到干净的 prompt 文本（不含零宽字符）的场景。 */
    public synchronized String promptForNames(JSONArray names) {
        if (names == null || names.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i, "");
            if (name.length() == 0 || seen.indexOf(name) >= 0) continue;
            String p = promptOf(name);
            if (p == null) continue;
            seen.add(name);
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p);
        }
        return sb.toString();
    }

    /** 按精确技能名取提示词；找不到返回 null。 */
    private String promptOf(String name) {
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (name.equals(s.optString("name", ""))) return s.optString("prompt", "");
        }
        return null;
    }

    private static JSONObject publicOf(JSONObject s) throws JSONException {
        return new JSONObject()
                .put("id", s.optString("id", ""))
                .put("name", s.optString("name", ""))
                .put("note", s.optString("note", ""))
                .put("prompt", s.optString("prompt", ""));
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String text = new JSONObject().put("skills", skills).toString();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(text); w.close();
            if (file.exists() && !file.delete()) { /* ignore */ }
            if (!tmp.renameTo(file)) {
                Writer w2 = new OutputStreamWriter(new FileOutputStream(file), UTF8);
                w2.write(text); w2.close();
                tmp.delete();
            }
        } catch (Exception e) { /* 落盘失败不抛，避免聊天中断 */ }
    }

    private static byte[] readAll(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) raf.length()];
        raf.readFully(b);
        raf.close();
        return b;
    }
}
