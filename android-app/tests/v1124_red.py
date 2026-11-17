# -*- coding: utf-8 -*-
# v1.1.26 红测试：#1 提示词查看器A+B+测试模式三级开关  #2 @技能列表启动预载  #3 布局两处
import os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))) + '/'
GW = open(ROOT + 'android-app/src/com/helper/app/LocalGateway.java', encoding='utf-8').read()
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()

pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond: pass_n += 1; print('  ok  ' + name)
    else: fail_n += 1; print('  FAIL ' + name)

print('[1] 后端：B 预览 + A 留档 + prefs 三键')
ok('路由 /prompt_preview 存在', '"/prompt_preview".equals(path)' in GW)
ok('路由 /prompt_log 存在（GET+DELETE）', '"/prompt_log".equals(path)' in GW)
ok('prompt_preview 返回 system=guide+constantHints', 'promptPreview' in GW or ('"/prompt_preview"' in GW and 'globalGuide() + constantHints()' in GW))
ok('recordPrompt 方法存在', 'private void recordPrompt' in GW)
ok('留档受 promptHistoryOn 门控', 'promptHistoryOn' in GW)
ok('留档在统一入口 chatWithAgent 记一次(含sessionId)', 'recordPrompt(sessionId' in GW)
ok('落盘文件 prompt_log.jsonl', 'prompt_log.jsonl' in GW)
ok('prefs GET 返回 testMode', 'o.put("testMode", prefsBoolean("testMode", false))' in GW)
ok('prefs GET 返回 promptViewerOn', 'o.put("promptViewerOn", prefsBoolean("promptViewerOn", false))' in GW)
ok('prefs GET 返回 promptHistoryOn', 'o.put("promptHistoryOn", prefsBoolean("promptHistoryOn", false))' in GW)
ok('prefs PUT 支持 testMode', 'if (d.has("testMode"))' in GW)
# 真实 bug 修复：writePrefs 只写结构字段会抹掉 setExtraPref 的独立键
ok('writePrefs 保留扩展键 EXTRA_PREF_KEYS', 'EXTRA_PREF_KEYS' in GW)
ok('扩展键清单含五个 setExtraPref 键',
   all(k in GW.split('EXTRA_PREF_KEYS = {')[1].split('};')[0]
       for k in ['reasoningParamOn', 'showToolCalls', 'testMode', 'promptViewerOn', 'promptHistoryOn']))
ok('聊天入口 recordPrompt 在 rememberLastChannel 之后仍受门控',
   GW.index('rememberLastChannel(effectiveChannel') < GW.index('recordPrompt(sessionId'))

print('[2] 前端：加号菜单两入口（插件下方）')
i_plugin = HTML.find('id="pmPlugin"')
i_prompt = HTML.find('id="pmPrompt"')
i_hist = HTML.find('id="pmPromptHist"')
ok('pmPrompt 按钮存在', i_prompt > 0)
ok('pmPromptHist 按钮存在', i_hist > 0)
ok('两按钮排在 pmPlugin 之后', i_plugin > 0 and i_prompt > i_plugin and i_hist > i_prompt)
ok('默认隐藏(display:none)', 'id="pmPrompt" style="display:none"' in HTML and 'id="pmPromptHist" style="display:none"' in HTML)
ok('可见性=测试模式&&对应开关', 'testMode && promptViewerOn' in HTML and 'testMode && promptHistoryOn' in HTML)

print('[3] 前端：提示词页面')
ok('#promptPage 容器存在', 'id="promptPage"' in HTML)
ok('B：fetch /prompt_preview', '/prompt_preview' in HTML)
ok('A：fetch /prompt_log', "' + API + '/prompt_log'" in HTML or "API + '/prompt_log'" in HTML)
ok('A：清空日志走 DELETE', "method: 'DELETE'" in HTML and 'prompt_log' in HTML)
ok('返回键链处理 promptPage', "$('promptPage').style.display === 'flex'" in HTML)

print('[4] 前端：关于页三级解锁')
ok('#devSection 存在且默认隐藏', 'id="devSection" style="display:none"' in HTML)
ok('三个开关齐全', all(x in HTML for x in ['id="devModeSwitch"','id="devPromptViewerSwitch"','id="devPromptHistorySwitch"']))
ok('版本号点击计数(7次)', 'devTapCount' in HTML and re.search(r'devTapCount\s*(\+=\s*1|\+\+)', HTML) is not None)
ok('阈值 7', re.search(r'devTapCount\s*>=?\s*7', HTML) is not None)
ok('点满7次默认开启测试模式并落盘', "testMode = true" in HTML and re.search(r'if \(!testMode\) \{ testMode = true; putDevPref\(.testMode., true\); \}', HTML) is not None)
ok('关测试模式→三行消失+菜单按钮消失', re.search(r'testMode\s*\?[\s\S]{0,300}display', HTML) is not None)
ok('loadPrefs 读取三键', 'testMode = d.testMode === true' in HTML and 'promptViewerOn = d.promptViewerOn === true' in HTML and 'promptHistoryOn = d.promptHistoryOn === true' in HTML)

print('[5] 修复 #2：启动预载技能 + @列表节流刷新')
boot = HTML[HTML.rfind('checkHealth();'):]
ok('boot 调用 loadSkills()', 'loadSkills();' in boot)
ok('showAtList 节流刷新(3s)', 'lastSkillsFetch' in HTML)

print('[6] 修复 #3：布局')
ok('#input/#inputMirror 内边距 10px 13px(v1.1.26 起四控件高度改由 --ctlH 统一, 原 9px 已被取代)',
   'padding: 10px 13px;' in HTML)
m = re.search(r'#userBtn,\s*#skillBtn,\s*#toolBtn,\s*#pluginBtn\s*\{[^}]*margin:\s*6px 6px 0[^}]*\}', HTML)
ok('侧栏四按钮统一 margin(等距6px)', m is not None)
ok('旧的不等距规则已清除', '#skillBtn, #userBtn {' not in HTML and 'margin-top: 0; border: 1px solid var(--line); background: var(--panel);\n    color: var(--text); border-radius: 10px; padding: 9px 10px' not in HTML)

print('[7] 版本对齐 1.1.45 / code 19（版本段跟随当前清单）')
ok('Manifest versionName 1.1.45', 'android:versionName="1.1.45"' in MAN)
ok('Manifest versionCode 19', 'android:versionCode="38"' in MAN)
ok('/about 1.1.45', '"version", "1.1.45"' in GW)
ok('手册标题 v1.1.26', '操作手册 v1.1.45】' in GW)
ok('aboutVersion 默认 1.1.45', 'id="aboutVersion">1.1.45<' in HTML)

print()
print('v1124 红测试：%d 通过, %d 失败' % (pass_n, fail_n))
sys.exit(1 if fail_n else 0)