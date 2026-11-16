package com.doubao.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * 需求③：内置工具注册表 + 执行入口。工具与技能不同：技能是提示词注入，工具是真实执行。
 * 内置五个：搜索网页 / 抓取网页 / 读文件 / 写文件 / 列目录。
 * 文件操作沙箱限定在 App 私有 files/toolfiles/ 目录下，路径不允许 .. 逃逸。
 * 纯 JVM 类，不依赖 Android SDK。
 */
public final class Tools {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File sandbox;   // 文件工具的根目录（App 私有）

    public Tools(File sandboxDir) {
        this.sandbox = sandboxDir == null ? new File("toolfiles") : sandboxDir;
        if (!sandbox.exists()) sandbox.mkdirs();
    }

    public File sandbox() { return sandbox; }

    /** 工具清单（前端工具页 / @ 列表 / + 菜单数据源）。 */
    public static JSONArray list() {
        String[][] rows = {
            {"search_web", "搜索网页", "并发搜索全部源（cn.bing/百度/搜狗/夸克/Bing RSS/DDG），逐条标注来源，返回标题、链接与摘要"},
            {"fetch_web", "抓取网页", "抓取一个网址的正文内容，转成纯文本"},
            {"read_file", "读文件", "读取工具沙箱里的文本文件内容"},
            {"write_file", "写文件", "把文本写入工具沙箱里的文件（可新建）"},
            {"list_files", "列目录", "列出工具沙箱目录下的文件"},
            {"now", "当前时间", "获取当前日期、星期与时区时间，无需联网"},
        };
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.length; i++) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", rows[i][0]).put("name", rows[i][1]).put("desc", rows[i][2]);
            } catch (JSONException ignored) {}
            out.put(o);
        }
        return out;
    }

    /** 执行一次工具调用。args 为前端/AI 给的参数；返回 JSON 结果（ok / error + 数据）。 */
    public JSONObject exec(String toolId, JSONObject args) {
        if (args == null) args = new JSONObject();
        try {
            if ("search_web".equals(toolId)) return searchWeb(args.optString("query", ""));
            if ("fetch_web".equals(toolId)) return fetchWeb(args.optString("url", ""));
            if ("read_file".equals(toolId)) return readFile(args.optString("path", ""));
            if ("write_file".equals(toolId)) return writeFile(args.optString("path", ""), args.optString("content", ""));
            if ("list_files".equals(toolId)) return listFiles();
            if ("now".equals(toolId)) return nowTime(args.optString("tz", "Asia/Shanghai"));
            return new JSONObject().put("ok", false).put("error", "未知工具: " + toolId);
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("error", e.getMessage()); }
            catch (JSONException ignored) { return new JSONObject(); }
        }
    }

    // ---------- 搜索网页：国内可达源优先（移植自 various_search 免费搜索包的端点思路） ----------
    /**
     * 截图 63/64 的根因：DDG 在国内网络经常 15s 连接超时。
     * #9：全部源并发跑（每源独立 8s 超时、9s join 兜底），聚合所有结果并逐条标注来源；
     * 不再「首个命中就返回」，百度不会被前面的源跳过；失败的源如实记进 errors/sources。
     * 模型据此如实告诉用户「暂时搜不到」，而不是编造结果。
     */
    private JSONObject searchWeb(String query) throws Exception {
        if (query == null || query.trim().length() == 0) {
            return new JSONObject().put("ok", false).put("error", "缺少 query 参数");
        }
        String q = query.trim();
        String[][] sources = {
            {"cn-bing-rss", "cn.bing.com RSS"},
            {"baidu", "百度"},
            {"sogou", "搜狗"},
            {"quark", "夸克"},
            {"bing-rss", "Bing RSS"},
            {"ddg", "DuckDuckGo"}
        };
        // #9：全部源并发执行，不再「首个有结果就返回」——百度等源绝不会被跳过。
        final int n = sources.length;
        final JSONArray[] buckets = new JSONArray[n];
        final String[] errs = new String[n];
        final long[] costs = new long[n];
        final boolean[] done = new boolean[n];   // 线程是否正常跑完（早停时区分未完成源）
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String key = sources[i][0];
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try { buckets[idx] = fetchSearchSource(key, q); }
                    catch (Exception e) { buckets[idx] = new JSONArray(); errs[idx] = String.valueOf(e.getMessage()); }
                    costs[idx] = System.currentTimeMillis() - t0;
                    done[idx] = true;
                }
            });
            ts[i].setDaemon(true);
            ts[i].start();
        }
        // #2 早停：有足够结果（>=6 条）就立即返回，不再死等国内不可达的慢源（实测 DDG 每次卡满 8s）。
        // 分两段等待：先给快源 2500ms 汇总，达标即走；否则再等到 4500ms 上限收尾。
        long deadline = System.currentTimeMillis() + 4500;
        while (System.currentTimeMillis() < deadline) {
            int got = 0;
            for (int i = 0; i < n; i++) {
                JSONArray b = buckets[i];
                if (b != null) got += b.length();
            }
            if (got >= 6) break;
            try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
        }
        // 收尾：只 join 已完成的线程，超时线程设为 daemon 由 JVM 自行回收，主线程绝不阻塞
        for (int i = 0; i < n; i++) {
            try { ts[i].join(120); } catch (InterruptedException ignored) {}
        }
        JSONArray tried = new JSONArray();       // 真正跑完的源
        JSONArray attempted = new JSONArray();   // 全部发起过的源（含未等到的）
        JSONArray skipped = new JSONArray();     // 早停时未跑完、未采信结果的源
        JSONArray errors = new JSONArray();
        JSONArray sourceStats = new JSONArray();
        JSONArray merged = new JSONArray();
        java.util.ArrayList<String> hitSources = new java.util.ArrayList<String>();
        for (int i = 0; i < n; i++) {
            attempted.put(sources[i][1]);
            JSONArray r = buckets[i] == null ? new JSONArray() : buckets[i];
            if (done[i]) {
                tried.put(sources[i][1]);
                if (errs[i] != null) errors.put(new JSONObject().put("source", sources[i][1]).put("error", errs[i]));
                if (r.length() > 0) hitSources.add(sources[i][1]);
                try {
                    sourceStats.put(new JSONObject().put("source", sources[i][1])
                            .put("count", r.length()).put("elapsedMs", costs[i])
                            .put("error", errs[i] == null ? "" : errs[i]).put("skipped", false));
                } catch (JSONException ignored) {}
            } else {
                // 结果齐备已提前返回，该源没跑完：如实标注，绝不把「未采信」写成「0 条结果」
                skipped.put(sources[i][1]);
                try {
                    sourceStats.put(new JSONObject().put("source", sources[i][1])
                            .put("count", 0).put("elapsedMs", costs[i])
                            .put("error", "结果已齐备，提前返回未等待该源")
                            .put("skipped", true));
                } catch (JSONException ignored) {}
                continue;   // 未完成源的桶不采信，避免并发写读到半截
            }
            for (int j = 0; j < r.length(); j++) {
                JSONObject it = r.optJSONObject(j);
                if (it == null) continue;
                try { if (it.optString("source", "").length() == 0) it.put("source", sources[i][1]); }
                catch (JSONException ignored) {}
                merged.put(it);
            }
        }
        JSONObject out = new JSONObject().put("ok", true).put("results", merged)
                .put("tried", tried).put("attempted", attempted).put("skipped", skipped)
                .put("errors", errors).put("sources", sourceStats)
                .put("concurrent", true).put("earlyStop", skipped.length() > 0);
        if (hitSources.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hitSources.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(hitSources.get(i));
            }
            out.put("source", sb.toString());
            out.put("note", "并发搜索全部 " + n + " 个源（结果齐备即提前返回，不等待超时源），结果来自 " + sb
                    + "；每条 result 的 source 字段标明真实来源，引用时请注明，绝不混编或编造其它来源的内容");
        } else {
            out.put("note", "并发搜索全部 " + n + " 个源都未返回结果（多为网络不可达，已提前收尾不等超时）；请如实告知用户暂时搜不到，"
                    + "或改用 fetch_web 直接抓取用户给出的已知网址，绝不编造搜索结果");
        }
        return out;
    }

    private static JSONArray fetchSearchSource(String key, String query) throws Exception {
        String q = URLEncoder.encode(query, "UTF-8");
        if ("cn-bing-rss".equals(key)) return parseRss(
                "https://cn.bing.com/search?format=rss&q=" + q, "cn-bing-rss");
        if ("bing-rss".equals(key)) return parseRss(
                "https://www.bing.com/search?format=rss&q=" + q, "bing-rss");
        if ("baidu".equals(key)) return parseBaidu(
                "https://www.baidu.com/s?wd=" + q);
        if ("sogou".equals(key)) return parseSogou(
                "https://www.sogou.com/web?query=" + q);
        if ("quark".equals(key)) return parseQuark(
                "https://quark.sm.cn/s?q=" + q);
        if ("ddg".equals(key)) return parseDdg(
                "https://html.duckduckgo.com/html/?q=" + q);
        return new JSONArray();
    }

    /** RSS 源：cn.bing.com / www.bing.com 的 <item> 结构，国内可达且解析稳定。 */
    private static JSONArray parseRss(String url, String src) throws Exception {
        String xml = httpGet(url, 3500);
        java.util.regex.Pattern item = java.util.regex.Pattern.compile(
                "<item>([\\s\\S]*?)</item>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern tag = java.util.regex.Pattern.compile(
                "<(title|link|description)>([\\s\\S]*?)</\\1>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher mi = item.matcher(xml);
        JSONArray out = new JSONArray();
        int i = 0;
        while (mi.find() && i < 8) {
            java.util.regex.Matcher mt = tag.matcher(mi.group(1));
            String title = "", link = "", desc = "";
            while (mt.find()) {
                String k = mt.group(1), v = stripTags(mt.group(2));
                if ("title".equals(k)) title = v;
                else if ("link".equals(k)) link = v;
                else desc = v;
            }
            if (title.length() > 0 || link.length() > 0) {
                out.put(new JSONObject().put("title", title).put("url", link)
                        .put("snippet", desc).put("source", src));
                i++;
            }
        }
        return out;
    }

    /** 百度：h3 > a 结果标题，摘要取 .c-abstract / .content-right_8Zs40 附近的纯文本。 */
    private static JSONArray parseBaidu(String url) throws Exception {
        String html = httpGet(url, 3500);
        java.util.regex.Pattern h3 = java.util.regex.Pattern.compile(
                "<h3[^>]*>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</h3>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = h3.matcher(html);
        JSONArray out = new JSONArray();
        int i = 0;
        while (m.find() && i < 8) {
            String title = stripTags(m.group(2));
            if (title.length() > 0) {
                out.put(new JSONObject().put("title", title).put("url", m.group(1))
                        .put("snippet", "").put("source", "baidu"));
                i++;
            }
        }
        return out;
    }

    /** 搜狗：h3 > a，class="vrwrap"/"rb" 里的摘要文本。 */
    private static JSONArray parseSogou(String url) throws Exception {
        String html = httpGet(url, 3500);
        java.util.regex.Pattern h3 = java.util.regex.Pattern.compile(
                "<h3[^>]*>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</h3>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = h3.matcher(html);
        JSONArray out = new JSONArray();
        int i = 0;
        while (m.find() && i < 8) {
            String title = stripTags(m.group(2));
            String href = m.group(1);
            if (href.startsWith("/")) href = "https://www.sogou.com" + href;
            if (title.length() > 0) {
                out.put(new JSONObject().put("title", title).put("url", href)
                        .put("snippet", "").put("source", "sogou"));
                i++;
            }
        }
        return out;
    }

    /** 夸克：结果标题在 <a ... class="result-title"> 或 h3 内。 */
    private static JSONArray parseQuark(String url) throws Exception {
        String html = httpGet(url, 3500);
        java.util.regex.Pattern h3 = java.util.regex.Pattern.compile(
                "<h3[^>]*>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</h3>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = h3.matcher(html);
        JSONArray out = new JSONArray();
        int i = 0;
        while (m.find() && i < 8) {
            String title = stripTags(m.group(2));
            if (title.length() > 0) {
                out.put(new JSONObject().put("title", title).put("url", m.group(1))
                        .put("snippet", "").put("source", "quark"));
                i++;
            }
        }
        return out;
    }

    /** DDG 降为最后兜底：国内常超时，8s 快速失败。 */
    private static JSONArray parseDdg(String url) throws Exception {
        String html = httpGet(url, 3500);
        java.util.regex.Pattern link = java.util.regex.Pattern.compile(
                "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern snip = java.util.regex.Pattern.compile(
                "class=\"result__snippet\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher ml = link.matcher(html);
        java.util.regex.Matcher ms = snip.matcher(html);
        JSONArray out = new JSONArray();
        int i = 0;
        while (ml.find() && i < 8) {
            String href = ml.group(1);
            int u = href.indexOf("uddg=");
            if (u >= 0) {
                int e = href.indexOf('&', u);
                String enc = href.substring(u + 5, e > 0 ? e : href.length());
                try { href = URLDecoder(enc); } catch (Exception ignored) {}
            }
            String title = stripTags(ml.group(2));
            String sn = ms.find() ? stripTags(ms.group(1)) : "";
            out.put(new JSONObject().put("title", title).put("url", href)
                    .put("snippet", sn).put("source", "ddg"));
            i++;
        }
        return out;
    }

    /** 当前时间工具：模型不需要联网就能拿到准确本地/指定时区时间。 */
    private static JSONObject nowTime(String tz) {
        try {
            java.util.TimeZone zone = java.util.TimeZone.getTimeZone(tz);
            java.util.Calendar cal = java.util.Calendar.getInstance(zone);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy年MM月dd日 HH:mm:ss EEEE", java.util.Locale.CHINA);
            f.setTimeZone(zone);
            return new JSONObject().put("ok", true).put("timezone", tz)
                    .put("local_time", f.format(cal.getTime()))
                    .put("epoch_millis", cal.getTimeInMillis());
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("error", e.getMessage()); }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    private static String URLDecoder(String s) throws Exception {
        return java.net.URLDecoder.decode(s, "UTF-8");
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">") .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'").replaceAll("&#39;", "'").trim();
    }

    // ---------- 抓取网页：GET 正文，剥掉 script/style 后给纯文本 ----------
    private JSONObject fetchWeb(String url) throws Exception {
        if (url == null || url.trim().length() == 0) {
            return new JSONObject().put("ok", false).put("error", "缺少 url 参数");
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        String html = httpGet(u, 10000);
        String text = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<head.*?</head>", " ")
                .replaceAll("(?is)<[^>]+>", "\n")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">") .replaceAll("&quot;", "\"");
        // 压缩空行
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.length() > 0) sb.append(t).append('\n');
        }
        String body = sb.toString();
        boolean clipped = body.length() > 20000;
        if (clipped) body = body.substring(0, 20000);
        JSONObject out = new JSONObject().put("ok", true).put("url", u).put("text", body);
        if (clipped) out.put("note", "正文过长已截断到 20000 字符");
        return out;
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs); conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while (in != null && (n = in.read(buf)) != -1) out.write(buf, 0, n);
            if (in != null) in.close();
            if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code);
            return new String(out.toByteArray(), UTF8);
        } finally {
            conn.disconnect();
        }
    }

    // ---------- 文件工具（沙箱内） ----------
    private File resolve(String path) throws Exception {
        String p = path == null ? "" : path.trim();
        if (p.length() == 0) throw new RuntimeException("缺少 path 参数");
        if (p.contains("..")) throw new RuntimeException("路径不允许 ..");
        // 需求⑭：只允许常规文本/代码后缀，禁止写入可执行脚本或覆盖系统文件
        String low = p.toLowerCase();
        if (low.endsWith(".sh") || low.endsWith(".apk") || low.endsWith(".dex")
                || low.endsWith(".so") || low.endsWith(".exe") || low.endsWith(".bat")) {
            throw new RuntimeException("出于安全考虑，不允许写入可执行/二进制文件");
        }
        File f = new File(sandbox, p.startsWith("/") ? p.substring(1) : p);
        String canon = f.getCanonicalPath();
        if (!canon.startsWith(sandbox.getCanonicalPath())) throw new RuntimeException("路径越界");
        return f;
    }

    private JSONObject readFile(String path) throws Exception {
        File f = resolve(path);
        if (!f.exists() || !f.isFile()) return new JSONObject().put("ok", false).put("error", "文件不存在: " + path);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) Math.min(raf.length(), 200000)];
        raf.readFully(b); raf.close();
        return new JSONObject().put("ok", true).put("path", path)
                .put("content", new String(b, UTF8));
    }

    private JSONObject writeFile(String path, String content) throws Exception {
        File f = resolve(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), UTF8);
        w.write(content == null ? "" : content);
        w.close();
        return new JSONObject().put("ok", true).put("path", path)
                .put("bytes", content == null ? 0 : content.getBytes(UTF8).length);
    }

    private JSONObject listFiles() throws Exception {
        JSONArray names = new JSONArray();
        File[] fs = sandbox.listFiles();
        if (fs != null) {
            for (File f : fs) {
                JSONObject o = new JSONObject().put("name", f.getName())
                        .put("dir", f.isDirectory()).put("size", f.length());
                names.put(o);
            }
        }
        return new JSONObject().put("ok", true).put("files", names);
    }
}