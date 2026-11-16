# -*- coding: utf-8 -*-
# v1.1.18 红测试：三件事写成静态断言（先 FAIL 再修，最终全绿）。
import re, sys

ROOT = '/data/user/0/com.ai.assistance.operit/files/workspace/doubao/'
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
STORE = open(ROOT + 'android-app/src/com/doubao/app/Store.java', encoding='utf-8').read()
GW = open(ROOT + 'android-app/src/com/doubao/app/LocalGateway.java', encoding='utf-8').read()
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()

pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond:
        pass_n += 1; print('  ok  ' + name)
    else:
        fail_n += 1; print('  FAIL ' + name)

def block(sel):
    m = re.search(re.escape(sel) + r'\s*\{([^}]*)\}', HTML)
    return m.group(1) if m else ''

print('[1] #1/#2 技能表单回归内联卡片（不覆盖返回栏 + 修复留白）')
sk = block('#skForm')
ok('#skForm 为纯内联（display:none，无 fixed/inset 覆盖层）',
   'display: none' in sk and 'position: fixed' not in sk and 'inset' not in sk)
ok('#skForm .card 不再用 max-width:460 居中（留白根因已消除）',
   not re.search(r'#skForm \.card\s*\{[^}]*max-width', HTML))
ok('技能表单 DOM 在 #skillsBody 内（返回栏在上、天然不被遮）',
   HTML.find('id="skillsBody"') < HTML.find('id="skForm"') < HTML.find('id="toolsPage"'))
ok('openSkillForm 以 display:block 展开内联卡片',
   "function openSkillForm" in HTML and "($('skForm').style.display = 'block')" in HTML.replace(' ', ' ').replace('  ', ' ') or re.search(r"\$\('skForm'\)\.style\.display = 'block'", HTML) is not None)

print('[2] #2 渠道表单恢复原「单卡片全屏浮层」，仅搬 body 级消除键盘露缝')
cf = block('#chForm')
ok('chForm 恢复为单卡片 class="card" id="chForm"（原弹出层外观）',
   '<div class="card" id="chForm">' in HTML)
ok('chForm 仍是 fixed inset:0 全屏浮层（外观零变化的那一层）',
   'position: fixed' in cf and 'inset: 0' in cf and 'var(--bg)' in cf)
ok('chForm padding 沿用 18px 14px 原值（未被改成 12px 整页观感）',
   re.search(r'padding:\s*18px 14px', cf) is not None)
ok('chForm DOM 已搬到 body 级（在 skillsPage 之后、脱离 settingsBody）',
   HTML.find('id="settingsPage"') < HTML.find('id="settingsBody"') < HTML.find('id="skillsPage"') < HTML.find('id="chForm"'))
ok('modelPicker 层级(85)仍高于 body 级 chForm(80)，拉模型浮层不被盖',
   re.search(r'#modelPicker\s*\{[^}]*z-index:\s*85', HTML) is not None)
ok('渠道表单字段齐全',
   all(x in HTML for x in ['id="chName"','id="chProvider"','id="chBaseUrl"','id="chApiKey"','id="chModel"','id="pullModelsBtn"','id="chSaveBtn"','id="modelCandidates"','id="chHint"']))

print('[3] #3 切换会话后历史附带时间不失效（耗时持久化 + 历史重建）')
ok('Store 提供带 elapsedMs 的 appendMessage 重载并落库',
   'long elapsedMs' in STORE and 'm.put("elapsedMs"' in STORE)
ok('后端计时并在成功回复时持久化耗时',
   'long t0 = System.currentTimeMillis()' in GW and 'persisted, elapsedMs' in GW)
ok('前端历史渲染按 elapsedMs 重建「回复耗时」msg-meta',
   'm.elapsedMs > 0' in HTML and "meta.className = 'msg-meta'" in HTML and '回复耗时 ' in HTML)
ok('历史重建受 timingMode 控制（与实时一致，关则不显示）',
   re.search(r'if \(timingMode && m\.role', HTML) is not None)

print('[4] 原 UI 未被破坏（仅必要改动）')
ok('kbd-open 规则保留', 'body.kbd-open #settingsTabs' in HTML)
ok('技能表单字段齐全',
   all(x in HTML for x in ['id="skName"','id="skNote"','id="skPrompt"']))

print('[5] 版本对齐 1.1.18 / code 11')
ok('Manifest versionName=1.1.18', 'android:versionName="1.1.18"' in MAN)
ok('Manifest versionCode=11', 'android:versionCode="11"' in MAN)
ok('关于页版本 1.1.18', 'id="aboutVersion">1.1.18<' in HTML)
ok('后端 /about 版本 1.1.18', '"version", "1.1.18"' in GW)

print('\n' + ('全部通过 ✅ %d 项' % pass_n if fail_n == 0 else '%d 项失败 ❌' % fail_n))
sys.exit(0 if fail_n == 0 else 1)
