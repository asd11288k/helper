# 小助手

一个放在手机里的 AI 聊天小工具，自己做着玩的。

WebView 前端 + 进程内本地网关，聊天记录、智能体、渠道密钥都存在 App 自己的私有目录里，不经过任何服务器。

## 能做什么

- 多智能体对话，每个是独立会话，聊天自动保存
- 内置一个免配置的模型，开箱即用
- 也能自己接渠道，做了一些适配
- 支持上传图片和文件
- 支持简易搜索

## 下载

Releases 下载区有最新 APK。允许「安装未知来源应用」后直接装，Android 5.0 及以上。

装好打开就能用内置模型聊天

## 自己构建

不依赖 Android Studio / Gradle，用 apt 装的命令行工具链即可（Ubuntu 24）：

```bash
apt install -y --no-install-recommends \
  aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign
# JDK 17 提供 javac / keytool

cd android-app
bash build.sh        # → dist/helper.apk
```

首次构建会自动生成一个自签 `keystore.jks`（仅供本地分发）。

## 测试

- `android-app/tests/`：纯 JVM 单元测试与回归（编译需 `libs/json.jar`，它只用于单测，APK 用系统自带 org.json）
- `test_ui.js`：前端行为测试，`node test_ui.js`

## 说明

个人自用项目，没有质量保证，接口/数据格式可能随时变。
