# -*- coding: utf-8 -*-
# v1.1.23 红测试：锁定「包名 doubao→helper 大改 + 仓库地址 + 版本」。
# applicationId: com.doubao.app -> com.helper.app（决定沙箱路径名）
# 产物名: doubao.apk -> helper.apk；仓库地址 xiao-zhushou -> helper；版本 1.1.23 / code 16
# 有意保留项（断言其存在）：Providers 豆包模型 id、keystore 内部凭证(别名/密码)。
import os, re, sys
ROOT = '/data/user/0/com.ai.assistance.operit/files/workspace/doubao/'
MAN = open(ROOT + 'android-app/AndroidManifest.xml', encoding='utf-8').read()
BUILD = open(ROOT + 'android-app/build.sh', encoding='utf-8').read()
GW = open(ROOT + 'android-app/src/com/helper/app/LocalGateway.java', encoding='utf-8').read()
HTML = open(ROOT + 'index.html', encoding='utf-8').read()

pass_n = fail_n = 0
def ok(name, cond):
    global pass_n, fail_n
    if cond: pass_n += 1; print('  ok  ' + name)
    else: fail_n += 1; print('  FAIL ' + name)

print('[1] applicationId 改名为 com.helper.app')
ok('Manifest package=com.helper.app', 'package="com.helper.app"' in MAN)
ok('源码目录已是 com/helper/app', os.path.isdir(ROOT + 'android-app/src/com/helper/app'))
ok('旧目录 com/doubao 已不存在', not os.path.isdir(ROOT + 'android-app/src/com/doubao'))
ok('build.sh PKG=com.helper.app', 'PKG=com.helper.app' in BUILD)
ok('全部 src 源码无 com.doubao.app 残留', 'com.doubao.app' not in GW)
ok('沙箱提示词路径已改 com.helper.app', '/data/data/com.helper.app/files/toolfiles' in GW)

print('[2] 产物文件名 doubao.apk -> helper.apk')
ok('build.sh 产物名 helper.apk', 'helper.apk' in BUILD and 'doubao.apk' not in BUILD)

print('[3] 仓库地址 xiao-zhushou -> helper')
ok('LocalGateway /about projectUrl=helper', 'github.com/asd11288k/helper' in GW and 'xiao-zhushou' not in GW)
ok('index.html 关于页地址=helper', 'github.com/asd11288k/helper' in HTML and 'xiao-zhushou' not in HTML)

print('[4] 版本对齐 1.1.23 / code 16')
ok('Manifest versionName 1.1.23', 'android:versionName="1.1.23"' in MAN)
ok('Manifest versionCode 16', 'android:versionCode="16"' in MAN)
ok('aboutVersion 默认 1.1.23', 'id="aboutVersion">1.1.23<' in HTML)
ok('手册标题 v1.1.23', '操作手册 v1.1.23】' in GW)

print('[5] 有意保留项不得被误改')
ok('Providers 仍含豆包模型 id doubao（第三方厂商，保留）', '"doubao"' in open(ROOT+'android-app/src/com/helper/app/Providers.java',encoding='utf-8').read())
ok('keystore 别名/密码仍为 doubao（签名凭证，保留）', '-alias doubao' in BUILD and 'doubao123' in BUILD)

print()
print('v1123 红测试：%d 通过, %d 失败' % (pass_n, fail_n))
sys.exit(1 if fail_n else 0)