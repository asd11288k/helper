# -*- coding: utf-8 -*-
# v1.1.19 红测试：技能表单改为「#skillsPage 内 flex:1 覆盖卡片」——
# 保留覆盖式（列表再多也不会无限往下、表单内部滚动），但顶栏(←返回/技能)不被遮挡。
import re, sys

import os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))) + '/'
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
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

print('[1] #1 技能表单：flex:1 覆盖卡片（保留覆盖式优点，不盖顶栏）')
sk = block('#skForm')
ok('#skForm 不再是 fixed 全屏层（不会盖住顶栏）',
   'position: fixed' not in sk)
ok('#skForm 采用 flex:1 + 内部滚动（占顶栏以下、长列表不无限往下）',
   'flex: 1' in sk and 'overflow-y: auto' in sk)
ok('#skForm .card 全宽、无 max-width 居中留白',
   not re.search(r'#skForm \.card\s*\{[^}]*max-width', HTML))
ok('skForm 在 skillsBody 之后、仍在 skillsPage 内（兄弟节点）',
   HTML.find('id="skillsPage"') < HTML.find('id="skillsBody"') < HTML.find('id="skForm"') < HTML.find('id="toolsPage"'))
ok('外层 #skForm 包裹内层 .card（覆盖式卡片观感）',
   re.search(r'<div id="skForm">\s*<div class="card">', HTML.replace('\n', ' ')) is not None)

print('[2] JS 互斥切换：开表单隐藏列表区、关表单恢复列表区')
ok('openSkillForm 打开表单时隐藏 #skillsBody',
   re.search(r"function openSkillForm[\s\S]*?\$\('skillsBody'\)\.style\.display = 'none'", HTML) is not None)
ok('closeSkillForm 收起表单时恢复 #skillsBody',
   re.search(r"function closeSkillForm[\s\S]*?\$\('skillsBody'\)\.style\.display = ''", HTML) is not None)
ok('openSkills 进入技能页确保 #skillsBody 可见（防御遗留）',
   re.search(r"function openSkills[\s\S]*?\$\('skillsBody'\)\.style\.display = ''", HTML) is not None)
ok('顶栏 #skillsBar 为 flex-shrink:0（覆盖卡片不挤压返回栏）',
   '#skillsBar' in HTML and 'flex-shrink: 0' in block('#userBar, #skillsBar'))

print('[3] 原 UI 未被破坏（仅必要改动）')
ok('技能表单字段齐全',
   all(x in HTML for x in ['id="skName"','id="skNote"','id="skPrompt"','id="skSaveBtn"','id="skCancelBtn"']))
ok('渠道表单仍是 body 级单卡片浮层（上一版修复未受影响）',
   '<div class="card" id="chForm">' in HTML)
ok('历史耗时重建逻辑仍在（1.1.18 #3 未受影响）',
   'm.elapsedMs > 0' in HTML)
ok('kbd-open 规则保留', 'body.kbd-open #settingsTabs' in HTML)

print('[4] 版本对齐 1.1.19 / code 12')
ok('Manifest versionName=1.1.19', 'android:versionName="1.1.19"' in MAN)
ok('Manifest versionCode=12', 'android:versionCode="12"' in MAN)
ok('关于页版本 1.1.19', 'id="aboutVersion">1.1.19<' in HTML)

print('\n' + ('全部通过 ✅ %d 项' % pass_n if fail_n == 0 else '%d 项失败 ❌' % fail_n))
sys.exit(0 if fail_n == 0 else 1)