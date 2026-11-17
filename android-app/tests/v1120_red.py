# -*- coding: utf-8 -*-
# v1.1.20 红测试：锁定本轮 7 项需求。
# #1 技能失效根治（后端只认激活 token/显式名单，删除 contains 模糊匹配）
# #2 atList 自动消失（空白/发送/Esc/选中 四处收起）
# #3 高亮重构（输入框镜像层 + 历史气泡 token 高亮 + 激活名单随请求发送）
# #4 两处注释修改
# #5 用户页保存提示改「已保存」+ 常驻开关上移
# #6 拉取模型错乱修复（以文本框 names 为唯一真相）
# #7 渠道表单键盘透层加固（openChannelForm 隐藏下层 settingsPage）
import re, sys
import os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))) + '/'
HTML = open(ROOT + 'index.html', encoding='utf-8').read()
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()
SK = open(ROOT + 'android-app/src/com/helper/app/Skills.java', encoding='utf-8').read()
GW = open(ROOT + 'android-app/src/com/helper/app/LocalGateway.java', encoding='utf-8').read()

ZW = '\u200b'
pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond:
        pass_n += 1; print('  ok  ' + name)
    else:
        fail_n += 1; print('  FAIL ' + name)

print('[1] #1 技能失效根治：后端精确注入')
ok('Skills 新增 promptForNames（显式名单注入）',
   'public synchronized String promptForNames' in SK)
ok('Skills 新增 promptOf 精确取名',
   'private String promptOf' in SK)
ok('Skills promptFor 改为按激活 token(@\\u200b名\\u200b)解析',
   'char zw' in SK and 'text.indexOf(zw, i + 2)' in SK)
ok('Skills 删除旧 text.contains("@name") 模糊匹配',
   'String hit = "@" + name' not in SK and '!text.contains(hit)' not in SK)
ok('网关读取显式 skills 名单并优先注入',
   'activeSkillNames' in GW and 'promptForNames(activeSkillNames)' in GW)

print('[2] #2 atList 自动消失：四处收起')
ok('点空白外部监听 ensureAtOutside + atOutsideListener',
   'function ensureAtOutside' in HTML and 'atOutsideListener' in HTML)
ok('发送时收起列表 closeAtList',
   re.search(r'busy = true;\s*closeAtList\(\);', HTML) is not None)
ok('Esc 键收起列表',
   "e.key === 'Escape') { closeAtList();" in HTML)
ok('选中列表项后收起列表',
   'v1.1.20 #2：选中后收起列表' in HTML)
ok('发送按钮「取消」逻辑未被破坏（三行赋值仍在 closeAtList 之后）',
   re.search(r"busy = true;\s*closeAtList\(\);[^\n]*\n[^\n]*sendBtn\.textContent = '取消'", HTML) is not None)

print('[3] #3 高亮重构：镜像层 + 历史气泡 + 激活名单发送')
ok('输入框镜像层 DOM #inputMirror', 'id="inputMirror"' in HTML and 'id="inputWrap"' in HTML)
ok('镜像层黄色高亮 CSS #inputMirror .hl', '#inputMirror .hl' in HTML)
ok('textarea 正文透明、placeholder 可见',
   '-webkit-text-fill-color: transparent' in HTML and
   re.search(r'#input::placeholder[^}]*-webkit-text-fill-color: var\(--sub\)', HTML) is not None)
ok('renderInputMirror 渲染函数', 'function renderInputMirror' in HTML)
ok('setBubbleText 渲染气泡 token 高亮', 'function setBubbleText' in HTML)
ok('addMsg 走 setBubbleText（历史气泡重建同样高亮）',
   'setBubbleText(d, text);' in HTML)
# JS 源码里用字面转义 '\u200b'（运行时由引擎解释为零宽），所以匹配字面反斜杠形式
ok('点列表项插入激活 token(@\\u200b名\\u200b)',
   "const token = '@\\u200b' + x.name + '\\u200b';" in HTML)
ok('send 提取 activeSkills 并随 body 发送',
   'const activeSkills = []' in HTML and 'skills: activeSkills' in HTML)
ok('复制/编辑剥离零宽标记', 'raw.replace(/' + '\\' + 'u200b/g' in HTML or "replace(/\\u200b/g" in HTML)

print('[4] #4 两处注释修改')
ok('技能页注释去掉分号后半句',
   '在聊天框输入 @技能名 即可引用。' in HTML and '不会显示在对话里' not in HTML)
ok('主题注释去掉「避免上下露黑边」', '避免上下露黑边' not in HTML)

print('[5] #5 用户页')
ok('保存提示改「已保存」',
   "r.ok ? '已保存' : '保存失败'" in HTML and '之后的对话会带上这份设定' not in HTML)
# 常驻提示词开关应在 textarea 上方
ok('常驻提示词开关在 userSetting textarea 之前',
   HTML.find('id="userSetting"') > 0 and HTML.find('常驻提示词') < HTML.find('id="userSetting"'))

print('[6] #6 拉取模型错乱修复')
ok('saveChannel 以 names 为唯一真相', 'const models = names;' in HTML)
ok('删除候选并集 slice+push 写法', 'const models = candidateModels.slice();' not in HTML)

print('[7] #7 渠道表单键盘透层加固')
ok('openChannelForm 隐藏下层 settingsPage visibility',
   re.search(r'function openChannelForm[\s\S]*?settingsPageEl\.style\.visibility = \'hidden\'', HTML) is not None)
ok('closeChannelForm 恢复 settingsPage visibility',
   re.search(r'function closeChannelForm[\s\S]*?settingsPageEl\.style\.visibility = \'\'', HTML) is not None)

print('[v] 版本对齐 1.1.20 / code 13')
ok('Manifest versionName 1.1.20', 'android:versionName="1.1.20"' in MAN)
ok('Manifest versionCode 13', 'android:versionCode="13"' in MAN)
ok('aboutVersion 默认 1.1.20', 'id="aboutVersion">1.1.20<' in HTML)

print()
print('v1120 红测试：%d 通过, %d 失败' % (pass_n, fail_n))
sys.exit(1 if fail_n else 0)
