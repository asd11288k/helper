# -*- coding: utf-8 -*-
# v1.1.26 红测试：
#  #1 提示词顺序改为 全局指南→操作手册→智能体设定→用户设定→思考等级→本轮技能
#  #2 输入栏四控件统一 44px（像素级断言，防再返工）
#  #3 两个提示词查看器：单一拼装收口 + 按真实顺序分段还原 + 预览补智能体设定
#  #4 思考等级传参关闭时，提示词里如实标注，不再误显示「开启状态高」
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

def body(src, sig):
    """取某个方法定义体（从签名到配对右花括号）。"""
    i = src.find(sig)
    if i < 0: return ''
    j = src.find('{', i)
    if j < 0: return ''
    d, k = 0, j
    while k < len(src):
        if src[k] == '{': d += 1
        elif src[k] == '}':
            d -= 1
            if d == 0: return src[j:k + 1]
        k += 1
    return src[j:]

PREV = body(GW, 'private Response promptPreview(')
CALL = body(GW, 'private String callUpstream(')
_ri = GW.find('String model, String skillPrompt, JSONArray images) {')
ROUTE = GW[_ri:_ri+2600] if _ri > 0 else ''
_ci = GW.rfind('private Response chatWithAgent(')
CWA = GW[_ci:_ci+4200] if _ci > 0 else ''
REC = body(GW, 'private void recordPrompt(')

print('[1] #1/#3 后端：唯一拼装收口 buildUpstream')
ok('存在 buildUpstream 收口方法', 'String buildUpstream(' in GW)
BU = body(GW, 'private String buildUpstream(')
ok('buildUpstream 里全局指南段先于 setting',
   BU and BU.find('effGuide') >= 0 and BU.find('setting') > BU.find('effGuide'))
ok('buildUpstream 里 setting 先于 profileHint（用户设定）',
   BU and BU.find('setting') >= 0 and BU.find('profileHint') > BU.find('setting'))
ok('buildUpstream 里 profileHint 先于 effortHint（思考等级）',
   BU and BU.find('profileHint') >= 0 and BU.find('effortHint') > BU.find('profileHint'))
ok('buildUpstream 里 effortHint 先于技能段',
   BU and BU.find('effortHint') >= 0 and BU.find('SKILL_LEAD') > BU.find('effortHint'))
ok('constantHints 已拆为 profileHint + effortHint',
   'private String profileHint(' in GW and 'private String effortHint(' in GW)
ok('聊天真实链路 callUpstream 走 buildUpstream', 'buildUpstream(' in CALL)
ok('非 loop 链路 routeChat 走 buildUpstream', 'buildUpstream(' in ROUTE)
ok('留档链路走 buildUpstream（不再自拼 recGuide）',
   'buildUpstream(' in CWA and 'recGuide' not in CWA)
ok('三处旧自拼串已清除', GW.count('globalGuide() + constantHints()') == 0)
ok('传给上游客户端时 setting 不再二次前置',
   CALL.count('setting') == 0 or 'upstream, images, extra' in CALL)

print('[2] #3 后端：B 预览按真实顺序完整还原')
ok('预览不再自拼 globalGuide+constantHints', 'globalGuide() + constantHints()' not in PREV)
ok('预览走 buildUpstream', 'buildUpstream(' in PREV)
ok('预览返回 messages 数组（真实发送序列）', 'put("messages"' in PREV)
ok('预览返回 segments 分段（带顺序）', 'put("segments"' in PREV)
ok('预览接收虚拟 prompt/skills/channelId/sessionId',
   all(k in PREV for k in ['prompt', 'skills', 'channelId', 'sessionId']))
ok('预览不写会话消息（不调 appendMessage）', 'appendMessage' not in PREV)
ok('预览不写留档（不调 recordPrompt）', 'recordPrompt(' not in PREV)
ok('留档条目含 segments', 'put("segments"' in REC)

print('[3] #4 思考等级传参关闭时如实标注')
EH = body(GW, 'private static String effortHint(String effort, boolean paramOn)')
EH0 = body(GW, 'private String effortHint()')
ok('effortHint 读取传参开关', 'prefsBoolean("reasoningParamOn"' in EH0)
ok('effortHint 文案区分已发送/未发送参数',
   ('已随请求' in EH and '未' in EH and '思考等级传参』已关闭' in EH) and 'reasoning_effort' in EH)
ok('等级值不再是唯一信息（同时给出档位与状态）', 'effortHint' in GW)

print('[4] #3 前端：两个查看器同构展示')
ok('预览页渲染 segments', 'd.segments' in HTML or '.segments' in HTML)
ok('历史页渲染同一段列表（复用渲染器）', 'renderPromptFlow' in HTML)
ok('旧的“系统提示词（全局指南+手册+用户设定+思考等级）”混排文案已删',
   '系统提示词（全局指南+手册+用户设定+思考等级）' not in HTML)
ok('预览请求带虚拟 prompt 与 skills',
   HTML.count("/prompt_preview") >= 1 and re.search(r"prompt_preview[\s\S]{0,400}skills", HTML) is not None)
ok('历史条目展示顺序与预览一致（同 renderPromptFlow）',
   HTML.count('renderPromptFlow(') >= 2)

print('[5] #2 输入栏四控件统一 44px（像素级）')
ok('#plusBtn 等高（同源 --ctlH）', re.search(r'#plusBtn\s*\{[^}]*height:\s*var\(--ctlH\)', HTML) is not None)
ok('#channelTrigger 等高（同源 --ctlH）', re.search(r'#channelTrigger\s*\{[^}]*height:\s*var\(--ctlH\)', HTML) is not None)
ok('#send 等高（同源 --ctlH）', re.search(r'#send\s*\{[^}]*height:\s*var\(--ctlH\)', HTML) is not None)
_send_blk = HTML[HTML.find('#send {'):HTML.find('#send {') + 260]
ok('#send 不再用 padding 11px 14px 撑高（仅检查 #send 规则块）',
   '#send {' in HTML and 'padding: 11px 14px' not in _send_blk)
ok('#inputWrap 显式定高（v1.1.26 真因：高度钉在框子而非 textarea）', re.search(r'#inputWrap\s*\{[^}]*height:\s*var\(--ctlH\)', HTML) is not None)
ok('行高用整数 24px（10+24+10=44 精确）', re.search(r'#input, #inputMirror\s*\{[^}]*line-height:\s*24px', HTML) is not None)
ok('输入框内边距 10px 13px', re.search(r'#input, #inputMirror\s*\{[^}]*padding:\s*10px 13px', HTML) is not None)
ok('自适应增高下限 44px 且上限 68px(2行) 保留',
   re.search(r'math\.max\(44,\s*math\.min\([^)]*68', HTML, re.I) is not None
   or 'Math.max(44, Math.min(' in HTML and ', 68)' in HTML)
ok('#row 底边对齐保持 flex-end', re.search(r'#row\s*\{[^}]*align-items:\s*flex-end', HTML) is not None)

print('[5b] 手册长度守卫（V1113/V1114 要求 globalGuide < 4000）')
_m = GW[GW.find('public static String manual()'):GW.find('public static String guideRules()')]
ok('手册源码串长度受控（<60000 chars of java）', len(_m) < 60000)
ok('手册含 v1.1.24 旧句已清除的自检（不留双版本测试段）',
   _m.count('关闭测试模式') <= 2)

print('[6] 版本与文案同步 1.1.45 / code 20')
ok('Manifest versionName 1.1.45', 'android:versionName="1.1.45"' in MAN)
ok('Manifest versionCode 19', 'android:versionCode="38"' in MAN)
ok('/about 1.1.45', '"1.1.45"' in GW)
ok('手册标题 v1.1.26', '操作手册 v1.1.45】' in GW)
ok('手册写明新顺序', '全局指南→操作手册→智能体设定→用户设定→思考等级' in GW)
ok('前端 aboutVersion 1.1.45', 'id="aboutVersion">1.1.45<' in HTML)

print('\n合计 %d 项：通过 %d，失败 %d' % (pass_n + fail_n, pass_n, fail_n))
sys.exit(1 if fail_n else 0)
