#!/bin/bash
# 豆包问答 APK 构建脚本（arm64 Ubuntu + apt 原生 Android 工具链，无需 Android Studio / Gradle / 官方 SDK）
#
# 依赖（Ubuntu 24.04）：
#   apt install -y --no-install-recommends aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign
#   openjdk-17-jdk-headless（javac / keytool）
# JVM 单测另需 libs/json.jar（构建 APK 本身不需要，Android 平台自带 org.json）
#
# 用法：bash build.sh   → 产物 dist/helper.apk
set -u
cd "$(dirname "$0")" || exit 1

AJAR=${AJAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-/usr/lib/android-sdk/build-tools/debian/dx}
OUT=build
DIST=dist
PKG=com.helper.app

for c in aapt javac keytool zipalign apksigner; do
  command -v "$c" >/dev/null 2>&1 || { echo "缺少命令: $c"; exit 1; }
done
[ -f "$AJAR" ] || { echo "缺少 android.jar: $AJAR"; exit 1; }
[ -x "$DX" ] || { echo "缺少 dx: $DX"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/bin" "$DIST" "$OUT/assets"
# D 方案：复用工作区已验收的网页版，打进 assets 供 WebView 加载
SRC_HTML=../index.html
[ -f "$SRC_HTML" ] || { echo "缺少前端页面: $SRC_HTML"; exit 1; }
cp "$SRC_HTML" "$OUT/assets/index.html" || exit 1

step() { printf '%-12s' "$1"; }
fail() { echo "FAIL ($1)"; tail -8 "$2.log" 2>/dev/null; exit 1; }

# 1) 资源 + R.java（不引用 R 也要做这步，让 resources.arsc 生成）
step "aapt res"; aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$AJAR" > "$OUT/aapt_res.log" 2>&1 || fail aapt "$OUT/aapt_res"
echo OK

# 2) 编译 Java（Android 自带 org.json，classpath 只放 android.jar）
step "javac"; find src -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$AJAR" -cp "$AJAR" -d "$OUT/obj" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1 || fail javac "$OUT/javac"
echo OK

# 3) class -> dex（老 dx，不做 lambda desugar，故源码全用匿名内部类）
step "dx"; "$DX" --dex --output="$OUT/bin/classes.dex" "$OUT/obj" > "$OUT/dx.log" 2>&1 || fail dx "$OUT/dx"
echo OK

# 4) 组包（先出未签名 APK，再把 dex 加进去）
step "aapt package"; aapt package -f -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$AJAR" -F "$OUT/bin/raw.apk" > "$OUT/aapt_pkg.log" 2>&1 || fail aapt "$OUT/aapt_pkg"
ROOT=$(pwd)
(cd "$ROOT/$OUT/bin" && aapt add raw.apk classes.dex > "$ROOT/$OUT/add.log" 2>&1) || fail aapt "$OUT/add"
echo OK

# 5) 对齐 + 签名（keystore 不存在则自动生成，密码仅用于本地自签）
step "zipalign"; zipalign -f 4 "$OUT/bin/raw.apk" "$OUT/bin/aligned.apk" > "$OUT/zipalign.log" 2>&1 || fail zipalign "$OUT/zipalign"
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -alias doubao -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass doubao123 -keypass doubao123 -dname "CN=Doubao Local" > "$OUT/key.log" 2>&1 || fail keytool "$OUT/key"
fi
step "apksigner"; apksigner sign --ks keystore.jks --ks-pass pass:doubao123 --key-pass pass:doubao123 \
  --out "$DIST/helper.apk" "$OUT/bin/aligned.apk" > "$OUT/sign.log" 2>&1 || fail sign "$OUT/sign"
echo OK

step "verify"; apksigner verify --verbose "$DIST/helper.apk" > "$OUT/verify.log" 2>&1 || fail verify "$OUT/verify"
V1=$(grep -c 'v1 scheme.*true' "$OUT/verify.log"); V2=$(grep -c 'v2 scheme.*true' "$OUT/verify.log")
echo "OK (v1=$V1 v2=$V2)"
echo "DEX=$(unzip -l "$DIST/helper.apk" | grep classes.dex | awk '{print $1}') bytes"
echo "APK=$DIST/helper.apk  $(stat -c%s "$DIST/helper.apk") bytes"
echo "-- badging --"; aapt dump badging "$DIST/helper.apk" | head -3