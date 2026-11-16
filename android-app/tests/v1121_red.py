# -*- coding: utf-8 -*-
# v1.1.21 红测试：锁定本轮 5 项需求。
# #1 输入框蓝色边框错位 + 宽度对齐（mirror 透明同宽边框、textarea 唯一可见边框、bar 16px）
# #2 创建智能体/技能重名拦截（后端 nameExists + 409）
# #3 技能失效根治（上游边界剥离零宽 + 改写 guide 第1条不再反问技能名）
# #4 加号按钮同款蓝色边框
# #5 重开渠道恢复上次全部已保存模型的高亮
import re, sys
ROOT = '/data/user/0/com.ai.assistance.operit/files/workspace/doubao/'
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()
SK = open(ROOT + 'android-app/src/com/helper/app/Skills.java', encoding='utf-8').read()
GW = open(ROOT + 'android-app/src/com/helper/app/LocalGateway.java', encoding='utf-8').read()
ST = open(ROOT + 'android-app/src/com/helper/app/Store.java', encoding='utf-8').read()

pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond: pass_n += 1; print('  ok  ' + name)
    else: fail_n += 1; print('  FAIL ' + name)

def block(sel):
    m = re.search(re.escape(sel) + r'\s*\{([^}]*)\}', HTML)
    return m.group(1) if m else ''

print('[1] #1 输入框边框错位 + 宽度对齐')
ok('#bar 左右内边距 16px（与聊天/顶栏对齐，不再过宽）',
   '#bar { flex-shrink: 0; background: var(--panel); border-top: 1px solid var(--line); padding: 10px 16px' in HTML)
ok('#input 唯一可见边框 1px var(--line)',
   'border: 1px solid var(--line)' in block('#input'))
ok('#inputMirror 透明同宽边框（不出第二道框、文字像素对齐）',
   'border: 1px solid transparent' in HTML)
ok('#plusMenu/#atList 锚点改为 16px 与行内容齐平',
   'left: 16px' in block('#plusMenu') and 'left: 16px' in block('#atList'))

print('[2] #2 重名拦截')
ok('Store.nameExists 存在', 'public boolean nameExists' in ST)
ok('Skills.nameExists 存在', 'public synchronized boolean nameExists' in SK)
ok('网关 4 处重名拦截 409（agent create/update + skill create/update）',
   GW.count('return err(409, "name exists")') == 4)
ok('前端智能体重名提示', '该智能体名称已存在' in HTML)
ok('前端技能重名提示', '技能名已存在' in HTML)

print('[3] #3 技能失效根治')
ok('上游边界剥离零宽 stripZeroWidth 定义', 'static String stripZeroWidth' in GW)
ok('chat 前清洗 prompt + hist', 'upPrompt = stripZeroWidth(prompt);' in GW and 'cleanHistForUpstream(hist0);' in GW)
ok('清洗发生在存储写入之后（存储仍保留 token 供高亮）',
   GW.find('store.appendMessage(id, sessionId, "user", prompt)') < GW.find('upPrompt = stripZeroWidth(prompt);'))
ok('上游三路都用 upPrompt（AgentLoop/routeChat/appendHist）',
   GW.count('upPrompt') >= 3)
ok('guide 第1条明确禁止反问未识别技能名',
   '技能提示词已由系统注入' in GW and '不要**反问' in GW)

print('[4] #4 加号按钮同款蓝色边框')
pb = block('#plusBtn')
ok('plusBtn 使用 accent 边框 + accent 图标色',
   'border: 1px solid var(--accent)' in pb and 'color: var(--accent)' in pb)

print('[5] #5 重开渠道恢复上次全部已保存模型高亮')
ok('openChannelForm 从 c.models 恢复 candidateModels',
   'v1.1.21 #5' in HTML and 'Array.isArray(c.models)' in HTML
   and re.search(r"candidateModels = \[\];[\s\S]{0,260}c\.models\.forEach", HTML) is not None)
ok('旧「只预选当前模型」写法已移除',
   'candidateModels = (c && c.model) ? [c.model] : []' not in HTML)

print('[v] 版本对齐 1.1.21 / code 14')
ok('Manifest versionName 1.1.21', 'android:versionName="1.1.21"' in MAN)
ok('Manifest versionCode 14', 'android:versionCode="14"' in MAN)
ok('aboutVersion 默认 1.1.21', 'id="aboutVersion">1.1.21<' in HTML)

print()
print('v1121 红测试：%d 通过, %d 失败' % (pass_n, fail_n))
sys.exit(1 if fail_n else 0)