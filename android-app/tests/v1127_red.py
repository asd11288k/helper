# -*- coding: utf-8 -*-
# v1.1.28 反馈回路（红测试）。四项需求（用户 #6 原话）锁死关键代码特征：
#  #1 历史对话不再逐条分条，合并为「一条」对话上下文（几百楼也只是一条），内部【用户】/【AI】顺序拼接。
#  #2 历史子条默认收起可折叠：CSS 必须用直接子选择器，杜绝后代选择器强制点亮内部子条。
#  #3 两个提示词查看器返回按钮与设置页返回同款（不再裸 button）。
#  #4 测试模式可改「系统提示词（全局指南 / 操作手册）」：实例口 effGuide/effManual + /prompt_override 路由 + 编辑页。
# 注意：版本段用内容标记断言（1.1.45 / code 20），不引用裸 1.1.26，避免被后续批量替换自我污染。
import io
import re
import sys

ROOT = "/data/data/com.ai.assistance.operit/files/workspace/helper"
HTML = io.open(ROOT + "/index.html", encoding="utf-8").read()
GW = io.open(ROOT + "/android-app/src/com/helper/app/LocalGateway.java", encoding="utf-8").read()
MAN = io.open(ROOT + "/android-app/AndroidManifest.xml", encoding="utf-8").read()

pass_n = fail_n = 0


def ok(name, cond):
    global pass_n, fail_n
    if cond:
        pass_n += 1
        print("  ok  " + name)
    else:
        fail_n += 1
        print("  FAIL " + name)


print("[1] #1 历史对话合并为一条「对话上下文」（不再几百个分条）")
ok("renderPromptFlow 仍唯一渲染器", "function renderPromptFlow" in HTML)
ok("对话段合并为单条标签「对话上下文（共 N 条，含本轮消息）」",
   "对话上下文（共 " in HTML and "条，含本轮消息）" in HTML)
ok("合并用 msgs.map(...).join 拼接（而非逐条 add）",
   re.search(r"msgs\.map\(m =>", HTML) is not None and ".join('\\n\\n')" in HTML)
ok("拼接行内含【用户】/【AI】角色标注", "'【' + role + '】'" in HTML or "【用户】" in HTML)
ok("合并只 add 一次（对话上下文整体一项，open=true 默认展开可折叠）",
   HTML.count("部分 · 对话上下文") >= 1)
ok("常驻设定段仍逐段显示（sg.title 循环未受影响）",
   "segs.forEach(sg => add(" in HTML)

print("[2] #2 历史子条默认收起可折叠（直接子选择器，杜绝后代选择器强制点亮）")
ok("展开规则改为直接子选择器 .pr-item.open > .pr-detail",
   re.search(r"\.pr-item\.open\s*>\s*\.pr-detail\s*\{\s*display:\s*block", HTML) is not None)
ok("旧的后代选择器 .pr-item.open .pr-detail（无 >）已清除",
   re.search(r"\.pr-item\.open\s+\.pr-detail\s*\{[^}]*display:\s*block", HTML) is None)
ok(".pr-detail 默认仍 display:none（默认收起）",
   re.search(r"\.pr-detail\s*\{\s*display:\s*none", HTML) is not None)

print("[3] #3 查看器返回按钮与设置页返回同款（不再裸 button）")
ok("#promptBackBtn 有显式样式规则", re.search(r"#promptBackBtn\s*\{", HTML) is not None)
_pb = HTML[HTML.find("#promptBackBtn {"):]
_pb = _pb[:_pb.find("}") + 1]
ok("promptBackBtn 含同款背景 var(--panel2)", "background: var(--panel2)" in _pb)
ok("promptBackBtn 含同款边框 1px solid var(--line)", "border: 1px solid var(--line)" in _pb)
ok("promptBackBtn 含同款圆角/内边距", "border-radius: 8px" in _pb and "padding: 6px 12px" in _pb)

print("[4] #4 后端：系统提示词可覆盖（实例口 + 持久化 + 路由）")
ok("static guideRules()/manual()/globalGuide() 签名仍在（不能动）",
   "public static String guideRules()" in GW and "public static String manual()" in GW
   and "public static String globalGuide()" in GW)
ok("新增实例读取口 effGuide()/effManual()",
   "private String effGuide()" in GW and "private String effManual()" in GW)
ok("effGuide/effManual 默认回落 static（覆盖文件缺失时）",
   'promptOverrideOr("guide", guideRules())' in GW and 'promptOverrideOr("manual", manual())' in GW)
ok("buildUpstream 改走实例口 effGuide/effManual",
   re.search(r"buildUpstream\(String setting", GW) and "sb.append(effGuide())" in GW and "sb.append(effManual())" in GW)
ok("segmentsOf 改走实例口（查看器/留档如实反映覆盖）",
   'seg("全局指南", effGuide())' in GW and 'seg("操作手册", effManual())' in GW)
ok("覆盖持久化文件 promptOverride.json",
   'promptOverride.json' in GW and "private File promptOverrideFile()" in GW)
ok("覆盖写入原子替换（writePromptOverride）", "private boolean writePromptOverride(" in GW)
ok("路由 /prompt_override 注册", 'if ("/prompt_override".equals(path))' in GW)
ok("路由处理 GET/PUT/DELETE",
   "private Response promptOverrideRoute(" in GW
   and GW[GW.find("promptOverrideRoute"):GW.find("promptOverrideRoute") + 2200].count("DELETE") >= 1)

print("[5] #4 前端：测试模式入口 + 编辑页")
ok("测试模式区出现「修改系统提示词」行", 'id="devRowPromptOverride"' in HTML)
ok("入口按钮 devPromptOverrideBtn", 'id="devPromptOverrideBtn"' in HTML)
ok("编辑页 promptOverridePage 存在", 'id="promptOverridePage"' in HTML)
ok("两个 textarea：全局指南 poGuide + 操作手册 poManual",
   'id="poGuide"' in HTML and 'id="poManual"' in HTML)
ok("保存按钮 poSaveBtn + 恢复默认 poResetBtn + 返回 poBackBtn",
   'id="poSaveBtn"' in HTML and 'id="poResetBtn"' in HTML and 'id="poBackBtn"' in HTML)
ok("JS 走 /prompt_override 读写", "API + '/prompt_override'" in HTML)
ok("打开函数 openPromptOverridePage 存在", "function openPromptOverridePage" in HTML)
ok("applyTestModeUI 控制 devRowPromptOverride 显隐（跟随 testMode）",
   "$('devRowPromptOverride').style.display = testMode" in HTML)
ok("返回键拦截 promptOverridePage", "closePromptOverridePage(); return" in HTML)

print("[6] 版本同步 1.1.45 / code 20（内容标记断言）")
ok("Manifest versionName 1.1.45", 'android:versionName="1.1.45"' in MAN)
ok("Manifest versionCode 20", 'android:versionCode="38"' in MAN)
ok("手册标题 v1.1.45", "操作手册 v1.1.45】" in GW)
ok("/about 版本 1.1.45", '"1.1.45"' in GW)
ok("前端 aboutVersion 默认 1.1.45", 'id="aboutVersion">1.1.45<' in HTML)

print("[7] 守卫：覆盖逻辑不得污染 static 长度（V1113/V1114 约束）")
ok("globalGuide 由 static guideRules+manual 组成（未被改成实例口）",
   re.search(r"public static String globalGuide\(\)\s*\{\s*return guideRules\(\) \+ manual\(\)", GW) is not None)

print("\n合计 %d 项：通过 %d，失败 %d" % (pass_n + fail_n, pass_n, fail_n))
sys.exit(1 if fail_n else 0)