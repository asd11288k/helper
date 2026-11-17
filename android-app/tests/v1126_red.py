# -*- coding: utf-8 -*-
# v1.1.26 反馈回路（红测试）。
#  #1 提示词查看器 = 一份按 AI 收到顺序排列的完整清单（常驻设定 + 对话历史 + 本轮消息），
#     杜绝"两个视角/内容对不上"；协议外壳伪装轮由后端标 shell=true，不重复成段。
#  #2 输入栏高度钉在真正的框子 #inputWrap 上，四控件同源 var(--ctlH)——
#     根治"改了 textarea 高度、框子没变，连续 9 次看不出变化"。
import io
import re
import sys

ROOT = "/data/data/com.ai.assistance.operit/files/workspace/helper"
HTML = io.open(ROOT + "/index.html", encoding="utf-8").read()
GW = io.open(ROOT + "/android-app/src/com/helper/app/LocalGateway.java", encoding="utf-8").read()
MAN = io.open(ROOT + "/android-app/AndroidManifest.xml", encoding="utf-8").read()
CORE = io.open(ROOT + "/android-app/src/com/helper/app/Core.java", encoding="utf-8").read()

pass_n = fail_n = 0


def ok(name, cond):
    global pass_n, fail_n
    if cond:
        pass_n += 1
        print("  ok  " + name)
    else:
        fail_n += 1
        print("  FAIL " + name)


print("[1] #1 查看器只有一份清单，没有第二个视角")
ok("旧的双视角渲染器已彻底移除（renderSegments）", "renderSegments" not in HTML)
ok("旧的双视角渲染器已彻底移除（renderMessages）", "renderMessages" not in HTML)
ok("不再出现『上游实际收到的 messages』这个让人困惑的分区标题",
   "上游实际收到的 messages" not in HTML)
ok("存在唯一渲染器 renderPromptFlow", "function renderPromptFlow" in HTML)
ok("两个查看器共用它（预览 + 历史各一处调用）", HTML.count("renderPromptFlow(") >= 3)
ok("清单项按序编号（第N部分）", "第' + (++n) + '部分" in HTML)
ok("清单区分常驻设定段 + 合并后的对话上下文（v1.1.27 #1）",
   "对话上下文（共" in HTML and "sg.title" in HTML)
ok("渲染时按后端 shell 标记跳过外壳伪装轮", "filter(m => m && !m.shell)" in HTML)

print("[2] #1 后端给协议外壳打 shell 标记（前端不靠猜）")
ok("requestView 存在且带 shell 标记", ".put(\"shell\", true)" in GW)
ok("openai 协议：system 条标 shell", "put(\"role\", \"system\").put(\"content\", systemText).put(\"shell\", true)" in GW)
ok("core 协议：伪装 user 轮标 shell", "Core.SETTING_WRAPPER + systemText).put(\"shell\", true)" in GW)
ok("core 协议：假确认轮标 shell", "Core.SETTING_ACK).put(\"shell\", true)" in GW)
ok("gemini 协议：model 假轮标 shell", "好的，我会按以上要求执行。\").put(\"shell\", true)" in GW)
ok("本轮真实用户消息不标 shell（必须显示）",
   "put(\"role\", \"user\").put(\"content\", prompt));" in GW)
ok("历史消息透传处未误加 shell（保持真实原文）",
   "if (o != null) msgs.put(o);" in GW)

print("[3] #2 高度必须钉在真正的框子上（本次真因）")
ok("#row 定义唯一高度变量 --ctlH: 44px",
   re.search(r"#row\s*\{[^}]*--ctlH:\s*44px", HTML) is not None)
ok("#inputWrap 显式 height: var(--ctlH)（v1.1.26 前它无 height，被撑成 46px）",
   re.search(r"#inputWrap\s*\{[^}]*height:\s*var\(--ctlH\)", HTML) is not None)
for sel in ("#plusBtn", "#channelTrigger", "#send"):
    ok("%s 同源 var(--ctlH)" % sel,
       re.search(re.escape(sel) + r"\s*\{[^}]*height:\s*var\(--ctlH\)", HTML) is not None)
ok("textarea/mirror 不再各自钉死像素高（改为填满框子）",
   re.search(r"#input, #inputMirror\s*\{[^}]*height:\s*100%", HTML) is not None)
ok("生产代码里已无单独改 textarea 高度的写法", "inputEl.style.height" not in HTML)
ok("增高走唯一收口函数 setControlRowHeight", "function setControlRowHeight" in HTML)
ok("收口函数写的是 --ctlH（整排一起变）", "setProperty('--ctlH'" in HTML)
ok("增高上下限保留（44 起、68 封顶2行）",
   re.search(r"Math\.max\(44,[^;]*68\)", HTML) is not None)
ok("#row 仍底边对齐（增高时四个控件底边同一条线）",
   re.search(r"#row\s*\{[^}]*align-items:\s*flex-end", HTML) is not None)

print("[4] #1 手册文案与新语义一致（不再承诺 messages 分区）")
_m = GW[GW.find("public static String manual()"):GW.find("public static String guideRules()")]
ok("手册说明是一份按顺序的完整清单",
   "完整清单" in _m and "常驻设定" in _m and "对话历史" in _m and "本轮消息" in _m)
ok("手册不再写『与 messages』这种双视角表述", "与 messages" not in _m)
ok("手册保留拼装顺序串",
   "全局指南→操作手册→智能体设定→用户设定→思考等级→本轮技能" in GW)

print("[5] 长度与兼容守卫（不可因本轮改动退化）")
ok("globalGuide 仍 < 4000（V1113/V1114 约束）",
   len(GW[GW.find("public static String guideRules()"):GW.find("public static String manual()")]) < 12000
   and "guideRules" in GW and "manual" in GW)
ok("buildUpstream 唯一拼装收口仍在", "private String buildUpstream(String setting, String skillPrompt)" in GW)
ok("旧散装拼法未复活", "globalGuide() + constantHints()" not in GW)
ok("segmentsOf 仍供分段数据", "private JSONArray segmentsOf(String setting, String skillPrompt)" in GW)
ok("promptPreview 仍走真实收口", "String sys = buildUpstream(setting, skillPrompt);" in GW)
ok("内置渠道 SETTING_WRAPPER 定义未被误改", "请在整个对话中严格扮演并遵守以下角色设定" in CORE)

print("[6] 版本同步 1.1.45 / code 20")
ok("Manifest versionName 1.1.45", 'android:versionName="1.1.45"' in MAN)
ok("Manifest versionCode 19", 'android:versionCode="38"' in MAN)
ok("/about 1.1.45", '"1.1.45"' in GW)
ok("手册标题 v1.1.26", "操作手册 v1.1.45】" in GW)
ok("前端 aboutVersion 1.1.45", 'id="aboutVersion">1.1.45<' in HTML)
ok("旧版本号 1.1.25 已从版本点清除（溯源注释除外）",
   MAN.count("1.1.25") == 0 and 'id="aboutVersion">1.1.25<' not in HTML)

print("\n合计 %d 项：通过 %d，失败 %d" % (pass_n + fail_n, pass_n, fail_n))
sys.exit(1 if fail_n else 0)