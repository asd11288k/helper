package com.doubao.app;

/**
 * v1.1.16：上游地址归一化。
 *
 * 用户经常直接粘贴官方文档里的「完整端点」（如 https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions），
 * 也常只填到根（如 https://api.deepseek.com 或 .../v1）。旧版无脑拼 baseUrl + "/chat/completions"，
 * 完整端点会变成 /v2/chat/completions/chat/completions → 上游 404，这是 v1.1.15 讯飞报「接口地址不对」的真因。
 *
 * 这里统一成两种能力：
 *  - chatUrl(base)：得到可直接 POST 的对话地址（已是完整端点就原样用，否则补 /chat/completions）
 *  - modelsUrl(base)：得到模型列表地址（完整端点则替换末段为 /models，否则补 /models）
 * 纯 JVM 静态工具，不依赖 Android SDK，也不做任何域名特判（不猜厂商、不硬编码型号）。
 */
public final class Upstream {
    private Upstream() {}

    private static String trimSlash(String s) {
        String b = s == null ? "" : s.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** 是否已经是完整对话端点（用户粘了官方文档里的全长地址）。 */
    public static boolean isChatEndpoint(String base) {
        String b = trimSlash(base).toLowerCase();
        return b.endsWith("/chat/completions") || b.endsWith("/completions");
    }

    /** 去掉末尾的完整端点，回到「根 + 版本段」，供其它路径复用。 */
    private static String stripEndpoint(String base) {
        String b = trimSlash(base);
        String low = b.toLowerCase();
        if (low.endsWith("/chat/completions")) {
            return b.substring(0, b.length() - "/chat/completions".length());
        }
        if (low.endsWith("/completions")) {
            return b.substring(0, b.length() - "/completions".length());
        }
        return b;
    }

    /** 对话 POST 地址：完整端点原样用；否则补 /chat/completions。 */
    public static String chatUrl(String base) {
        String b = trimSlash(base);
        if (b.length() == 0) return b;
        if (isChatEndpoint(b)) return b;
        return b + "/chat/completions";
    }

    /** 模型列表地址：完整端点则把末段换成 /models；否则补 /models。 */
    public static String modelsUrl(String base) {
        String b = trimSlash(base);
        if (b.length() == 0) return b;
        return stripEndpoint(b) + "/models";
    }
}