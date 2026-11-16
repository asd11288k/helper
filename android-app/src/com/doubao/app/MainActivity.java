package com.doubao.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * D 方案宿主：全屏 WebView + App 进程内本地网关。
 *
 * - assets/index.html 由 build.sh 从工作区复制，页面通过同源相对路径访问网关
 * - 网关只绑 127.0.0.1 随机端口，避免与网页版 8000 冲突
 * - 密钥存 App 私有目录，不进入 WebView DOM
 * - WebView 默认不弹 JS 对话框，这里把 confirm() 桥接到系统 AlertDialog
 */
public class MainActivity extends Activity {

    private WebView web;
    private LocalGateway gateway;
    private static final int REQ_IMAGE = 1001;
    private static final int REQ_FILE = 1002;
    private String pendingFileCallback = "";   // JS 回调函数名

    /** 系统栏配色：浅色白底深图标，深色深底浅图标。窗口铺满后由 App 自己绘制这两条区域。 */
    private void applyTheme(final String theme) {
        if (Build.VERSION.SDK_INT < 21) return;
        final boolean dark = "dark".equals(theme);
        runOnUiThread(new Runnable() {
            public void run() {
                Window w = getWindow();
                int bar = dark ? Color.parseColor("#1f2329") : Color.WHITE;
                w.setStatusBarColor(bar);
                w.setNavigationBarColor(bar);
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                if (!dark) {
                    if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags |= 0x10; // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                w.getDecorView().setSystemUiVisibility(flags);
            }
        });
    }

    /** WebView -> Java：主题同步 + 退出二次确认。 */
    private final class ThemeBridge {
        @JavascriptInterface
        public void setBars(final String theme) { applyTheme(theme); }

        /** 聊天页按返回键时由前端状态机调用；原生弹二次确认，确认才真正退出。 */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                public void run() { confirmExit(); }
            });
        }

        /** 需求⑩：前端 + 菜单选择图片（转 data URL 回传 JS）。 */
        @JavascriptInterface
        public void pickImage(final String callback) {
            runOnUiThread(new Runnable() {
                public void run() {
                    pendingFileCallback = callback;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    try { startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_IMAGE); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "无法打开选择器", Toast.LENGTH_SHORT).show(); }
                }
            });
        }

        /** 需求⑩：前端 + 菜单选择文件（文本文件读出内容回传 JS；超出 512KB 截断）。 */
        @JavascriptInterface
        public void pickFile(final String callback) {
            runOnUiThread(new Runnable() {
                public void run() {
                    pendingFileCallback = callback;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try { startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "无法打开选择器", Toast.LENGTH_SHORT).show(); }
                }
            });
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(MainActivity.this)
                .setMessage("确定退出小助手吗？")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { finish(); }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { d.dismiss(); }
                })
                .show();
    }

    /** 选择结果回传：图片按视觉载荷压缩；文本按 UTF-8 解码；二进制不再当文本乱码。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_IMAGE && requestCode != REQ_FILE) || web == null) return;
        final String cb = pendingFileCallback;
        pendingFileCallback = "";
        if (resultCode != RESULT_OK || data == null || data.getData() == null || cb.length() == 0) return;
        final Uri uri = data.getData();
        final int req = requestCode;
        new Thread(new Runnable() {
            public void run() {
                String jsArgs = "null";
                try {
                    String name = displayName(uri);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "";
                    byte[] raw = readAllBytes(getContentResolver().openInputStream(uri));
                    boolean image = req == REQ_IMAGE || mime.startsWith("image/")
                            || isPng(raw) || isJpeg(raw);
                    if (image) {
                        // 需求⑨：不再是「>512KB 直接丢弃」，而是等比压缩 + JPEG 质量递减
                        byte[] small = compressImage(raw);
                        String du = "data:image/jpeg;base64,"
                                + android.util.Base64.encodeToString(small, android.util.Base64.NO_WRAP);
                        jsArgs = new org.json.JSONObject()
                                .put("name", name).put("data", du).toString();
                    } else if (isBinary(raw)) {
                        // 需求⑩：PDF/压缩包等二进制不再 new String() 出乱码气泡
                        jsArgs = new org.json.JSONObject().put("name", name)
                                .put("error", "「" + name + "」是二进制文件（非文本），不能当文字发送；"
                                        + "图片请用「＋ → 图片」，其他文件请先转成 txt/md/csv/json。").toString();
                    } else {
                        String text = decodeText(raw);
                        boolean cut = text.length() > 200000;
                        if (cut) text = text.substring(0, 200000);
                        jsArgs = new org.json.JSONObject().put("name", name)
                                .put("text", text)
                                .put("note", cut ? "内容过长已截断到 200000 字符" : "").toString();
                    }
                } catch (Exception e) {
                    try {
                        jsArgs = new org.json.JSONObject()
                                .put("error", "读取失败：" + e.getMessage()).toString();
                    } catch (Exception ignored) { jsArgs = "null"; }
                }
                final String args = jsArgs;
                runOnUiThread(new Runnable() {
                    public void run() {
                        // 用 evaluateJavascript 而不是 loadUrl("javascript:...")：
                        // 后者把字符串当 URL，base64 里的 '+' 会被解成空格，图片直接损坏。
                        web.evaluateJavascript(
                                "(function(){try{" + cb + "(" + args + ");}catch(e){}})()", null);
                    }
                });
            }
        }, "picker").start();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        if (in == null) throw new Exception("无法打开文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > 12 * 1024 * 1024) break;   // 防御超大文件 OOM
        }
        in.close();
        return out.toByteArray();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && n.length() > 0) return n;
                }
            }
        } catch (Exception ignored) {
        } finally { if (c != null) try { c.close(); } catch (Exception ignored) {} }
        String p = uri.getLastPathSegment();
        return p == null || p.length() == 0 ? "附件" : p;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }
    private static boolean isJpeg(byte[] b) {
        return b.length > 4 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
    }

    /** 二进制判定：NUL 字节或 UTF-8 解码出现替换字符，都视为不可当文本。 */
    private static boolean isBinary(byte[] b) {
        int n = Math.min(b.length, 8192);
        for (int i = 0; i < n; i++) if (b[i] == 0) return true;
        try {
            String s = new String(b, "UTF-8");
            return s.indexOf('\ufffd') >= 0;
        } catch (Exception e) { return true; }
    }

    /** UTF-8 / UTF-16 BOM 兼容，去 BOM 后返回纯文本。 */
    private static String decodeText(byte[] b) throws Exception {
        if (b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf)
            return new String(b, 3, b.length - 3, "UTF-8");
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe)
            return new String(b, "UTF-16LE");
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff)
            return new String(b, "UTF-16BE");
        return new String(b, "UTF-8");
    }

    /** 等比缩到最长边 1024，JPEG 质量从 85 递减直到 ≤900KB；避免「图片过大」与坏图。 */
    private static byte[] compressImage(byte[] raw) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int w = bounds.outWidth > 0 ? bounds.outWidth : 1024;
        int h = bounds.outHeight > 0 ? bounds.outHeight : 1024;
        int sample = 1;
        while (w / sample > 2048 || h / sample > 2048) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
        if (bm == null) throw new Exception("图片解码失败（格式不支持或文件损坏）");
        float scale = Math.min(1f, 1024f / Math.max(bm.getWidth(), bm.getHeight()));
        if (scale < 1f) {
            Bitmap smaller = Bitmap.createScaledBitmap(bm,
                    Math.max(1, Math.round(bm.getWidth() * scale)),
                    Math.max(1, Math.round(bm.getHeight() * scale)), true);
            if (smaller != bm) { bm.recycle(); bm = smaller; }
        }
        int q = 85;
        byte[] out;
        do {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, q, bos);
            out = bos.toByteArray();
            q -= 10;
        } while (out.length > 900 * 1024 && q >= 35);
        bm.recycle();
        return out;
    }

    /** 启动时读一次已保存主题，避免深色用户每次冷启动闪一下白。 */
    private String savedTheme(File dir) {
        try {
            File f = new File(dir, "prefs.json");
            if (!f.exists()) return "light";
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b); raf.close();
            String t = new org.json.JSONObject(new String(b, "UTF-8")).optString("theme", "light");
            return "dark".equals(t) ? "dark" : "light";
        } catch (Exception e) {
            return "light";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File dir = getFilesDir();
        Settings settings = new Settings(new File(dir, "settings.json"));
        Store store = new Store(new File(dir, "agents.json"));
        Channels channels = new Channels(new File(dir, "channels.json"));
        Skills skills = new Skills(new File(dir, "skills.json"));
        Tools tools = new Tools(new File(dir, "toolfiles"));
        byte[] page;
        try {
            page = readAsset("index.html");
        } catch (Exception e) {
            Toast.makeText(this, "前端页面缺失: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            gateway = new LocalGateway(settings, store, page, channels, skills, tools);
            gateway.start();
        } catch (Exception e) {
            Toast.makeText(this, "本地服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        applyTheme(savedTheme(dir));
        web = new WebView(this);
        // 让 WebView 自己避开状态栏与底部手势区（旧 C 方案漏了这步，才出现顶栏重叠/底栏贴边）
        web.setFitsSystemWindows(true);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // 页面 fetch/localStorage 需要
        ws.setAllowFileAccess(false);           // 不经 file://，减少本地文件暴露
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        web.addJavascriptInterface(new ThemeBridge(), "AppBridge");
        web.setWebViewClient(new WebViewClient()); // 相对路径请求留在 WebView 内
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
        });
        web.loadUrl("http://127.0.0.1:" + gateway.port() + "/");
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            // 页面级返回由前端状态机处理：设置/技能页→聊天，聊天页→二次确认退出
            web.loadUrl("javascript:(function(){if(window.onHardwareBack)onHardwareBack();})()");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (gateway != null) gateway.stop();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}