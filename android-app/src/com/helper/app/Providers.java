package com.helper.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 需求⑫(1)：API 提供商注册表。
 * protocol：openai（OpenAI 通用兼容）/ gemini（Google 原生）/ anthropic（Claude 原生）。
 * 通用兼容 = openai 协议；专用提供商按各家原生协议适配。
 * baseUrl 留空表示需用户填写（同一家可能有多个接入域名），否则用官方默认值。
 * 纯 JVM 类，不依赖 Android SDK。
 */
public final class Providers {
    private Providers() {}

    /** 通用兼容（OpenAI 协议，官方端点） */
    public static final String[][] GENERAL = {
        // id, 名称, 默认 Base URL, 默认模型
        {"openai", "OpenAI 通用", "https://api.openai.com/v1", "gpt-4o-mini"},
        {"gemini-openai", "Gemini 通用", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"},
        // Anthropic 官方无 /v1/chat/completions 兼容端点：Base URL 留空，由用户填 OpenAI 兼容中转
        {"anthropic-openai", "Anthropic 通用", "", "claude-sonnet-4-5"},
    };

    /** 专用提供商（按原生协议适配） */
    public static final String[][] SPECIAL = {
        // id, 名称, 默认 Base URL, 默认模型, 协议
        {"baidu", "百度（文心一言）", "https://aip.baidubce.com", "ernie-4.0-8k", "openai"},
        {"aliyun", "阿里云（通义千问）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "openai"},
        // v1.1.16：讯飞改用 MaaS 端点（实测 /v1/models 返回 spark-x2.6 / spark-x2.6-4b，
        // /v1 与 /v2 的 chat/completions 均可用；旧的 spark-api-open 域名对 MaaS key 不适用）。
        {"xfyun", "讯飞（星火认知）", "https://maas-api.cn-huabei-1.xf-yun.com/v2", "spark-x2.6", "openai"},
        {"zhipu", "智谱AI（ChatGLM）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "openai"},
        {"deepseek", "Deepseek", "https://api.deepseek.com/v1", "deepseek-chat", "openai"},
        {"doubao", "豆包（火山大模型）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k", "openai"},
        {"moonshot", "月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "openai"},
        {"siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "openai"},
        {"gemini", "Gemini（原生）", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", "gemini"},
        {"anthropic", "Anthropic（Claude）", "https://api.anthropic.com", "claude-sonnet-4-5", "anthropic"},
    };

    /** 前端选择器数据：{id,name,baseUrl,model,protocol,general} 全量列表。 */
    public static JSONArray list() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < GENERAL.length; i++) out.put(of(GENERAL[i], true));
        for (int i = 0; i < SPECIAL.length; i++) out.put(of(SPECIAL[i], false));
        return out;
    }

    private static JSONObject of(String[] row, boolean general) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", row[0]).put("name", row[1]).put("baseUrl", row[2])
             .put("model", row[3]).put("protocol", row.length > 4 ? row[4] : "openai")
             .put("general", general);
        } catch (JSONException ignored) {}
        return o;
    }

    /** 按 id 查协议；未知 id 视为 openai 兼容。 */
    public static String protocolOf(String id) {
        for (int i = 0; i < SPECIAL.length; i++) {
            if (SPECIAL[i][0].equals(id)) return SPECIAL[i].length > 4 ? SPECIAL[i][4] : "openai";
        }
        for (int i = 0; i < GENERAL.length; i++) {
            if (GENERAL[i][0].equals(id)) return "openai";
        }
        return "openai";
    }
}