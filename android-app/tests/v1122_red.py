# -*- coding: utf-8 -*-
# v1.1.22 红测试：锁定本轮 5 项需求（修正 v1.1.21 未真正解决的问题）。
# #1 输入框蓝框错位 + 与左右对齐：可见边框统一归 #inputWrap，textarea/mirror 无边框
# #2 加号按钮不再常驻蓝框：默认灰框，仅展开列表时 .on 亮蓝（与渠道按钮同款）
# #3 智能体重名提示改为表单下方（复用技能同款，去掉 alert）
# #4 技能注入加【用户已指定技能】引导包装，杜绝「未收到技能提示词/找不到技能」
# #5 用户设定显式开/关态注入：关闭时不暴露内容、要求不猜、如实说不知道
import re, sys
ROOT = '/data/user/0/com.ai.assistance.operit/files/workspace/doubao/'
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()
GW = open(ROOT + 'android-app/src/com/doubao/app/LocalGateway.java', encoding='utf-8').read()

pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond: pass_n += 1; print('  ok  ' + name)
    else: fail_n += 1; print('  FAIL ' + name)

def block(sel):
    m = re.search(re.escape(sel) + r'\s*\{([^}]*)\}', HTML)
    return m.group(1) if m else ''

print('[1] #1 输入框蓝框错位 + 与左右对齐（边框归容器，textarea/mirror 无边框）')
ok('#inputWrap 携带唯一可见边框 1px var(--line)',
   'border: 1px solid var(--line)' in block('#inputWrap'))
ok('#inputWrap:focus-within 整体变蓝',
   '#inputWrap:focus-within { border-color: var(--accent); }' in HTML)
ok('#inputMirror 无边框 + 透明背景（消除双层盒/灰角）',
   'overflow-y: auto; user-select: none; border: none;' in HTML
   and 'inset: 0; background: transparent; color: var(--text); pointer-events: none;' in HTML)
ok('#input textarea 无边框（不再有第二道可见框）',
   'border: none;' in block('#input'))
ok('旧的 mirror 透明同宽边框写法已移除',
   '同宽透明边框' not in HTML)
ok('#bar 左右内边距 16px 与聊天/顶栏对齐（#chat 也是 16px）',
   'padding: 10px 16px' in block('#bar') and 'padding: 16px' in block('#chat'))

print('[2] #2 加号按钮不常驻蓝框，仅展开时亮蓝')
pb = block('#plusBtn')
ok('plusBtn 默认 var(--line) 灰框',
   'border: 1px solid var(--line)' in pb)
ok('plusBtn 默认不再用 var(--accent) 常驻蓝框',
   'var(--accent)' not in pb)
ok('.plusBtn.on 展开态才变蓝',
   '#plusBtn.on { border-color: var(--accent); color: var(--accent); }' in HTML)
ok("openPlusMenu 置 $('plusBtn').className = 'on'",
   "$('plusBtn').className = 'on';" in HTML)
ok("closePlusMenu 清除 $('plusBtn').className = ''",
   "$('plusBtn').className = '';" in HTML)

print('[3] #3 智能体重名提示改为表单下方（与技能同款）')
ok('modal 内新增 #agFormTip 容器', 'id="agFormTip"' in HTML)
ok('#skFormTip 与 #agFormTip 共用同款样式',
   '#skFormTip, #agFormTip' in HTML)
ok('智能体重名提示文案「智能体名已存在，请换一个名字」',
   '智能体名已存在，请换一个名字' in HTML)
ok('旧的 alert「该智能体名称已存在」已移除',
   '该智能体名称已存在' not in HTML and "$('agFormTip').textContent = '智能体名已存在" in HTML)
ok('openModal/openEdit 打开时清空 agFormTip',
   HTML.count("$('agFormTip').textContent = '';") >= 2)

print('[4] #4 技能注入加引导包装（杜绝声称未收到技能/找不到技能）')
ok('网关注入【用户已指定技能】引导包装 >= 2 处（callUpstream + routeChat）',
   GW.count('用户已指定技能】用户在本条消息中选定了一个技能') >= 2)
ok('引导文案要求严格按技能指令完成、不得声称未收到/找不到',
   '严格按以下技能指令完成本次任务' in GW and '未收到技能提示词' in GW)
ok('guide 第1条改为指向【用户已指定技能】段落',
   '当本系统提示词下方出现【用户已指定技能】段落时' in GW)
ok('保留上游剥离零宽 stripZeroWidth（v1.1.21 成果不回归）',
   'static String stripZeroWidth' in GW)

print('[5] #5 用户设定显式开/关态注入')
ok('开启态【用户设定·开启】注入 + 说明是用户自己的设定',
   '【用户设定·开启】' in GW and '这是用户对自己背景的常驻设定' in GW)
ok('关闭态【用户设定·关闭】注入 + 不暴露内容',
   '【用户设定·关闭】' in GW and '你看不到任何具体内容' in GW)
ok('关闭态要求不猜测/如实说不知道',
   '不要猜测或编造' in GW and '如实回答' in GW)
ok('关闭态解释「为何之前知道现在不知道」的追问应答口径',
   '之前是因用户开启了用户设定你才读到' in GW)
ok('旧的单一【关于用户的常驻设定】写法已移除',
   '关于用户的常驻设定' not in GW)
ok('constantHints 读取 enabled 开关分支',
   'boolean on = p.optBoolean("enabled", true)' in GW and 'else if (!on)' in GW)

print('[v] 版本对齐 1.1.22 / code 15')
ok('Manifest versionName 1.1.22', 'android:versionName="1.1.22"' in MAN)
ok('Manifest versionCode 15', 'android:versionCode="15"' in MAN)
ok('aboutVersion 默认 1.1.22', 'id="aboutVersion">1.1.22<' in HTML)

print()
print('v1122 红测试：%d 通过, %d 失败' % (pass_n, fail_n))
sys.exit(1 if fail_n else 0)
