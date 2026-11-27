#!/usr/bin/env bash
# 打一个能被手机「检查更新」拉到的发布包。
#
# 用法：
#   tools/hermes-bridge/release.sh            # 构建 + 校验签名 + 打印待发文件
#   tools/hermes-bridge/release.sh --publish  # 顺带 gh release create 发到 fork
#
# 两个关键约束（改之前先看 App 里的实现）：
#   1. 附件名要对得上 Releases.apkPreference()：arm64 设备的包必须以 `-arm64.apk` 结尾，
#      全架构包不能带任何 ABI 后缀。所以产物要改名成 mika-arm64.apk / mika-universal.apk。
#      （Gradle 出的是 app-arm64-v8a-release.apk，直接发上去是匹配不上的。）
#   2. 下载地址只接受 `${MikaLinks.RELEASES}/download/` 下的 HTTPS 附件 —— 所以必须发在
#      我们 fork 的 release 里，才能通过 ApkIntegrity.requireOfficialUrl()。
#
# 签名必须每次一致（~/.android/mika-bridge.jks），否则手机上会报「签名不一致」。

set -euo pipefail
cd "$(dirname "$0")/../.."

PUBLISH=0
[ "${1:-}" = "--publish" ] && PUBLISH=1

VERSION=$(grep -m1 'versionName = ' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
TAG="v$VERSION"
DIST=dist
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export GRADLE_USER_HOME=$PWD/.gradle-home

echo "版本 $VERSION（tag $TAG）"
./gradlew :app:assembleRelease --console=plain

A64=app/build/outputs/apk/release/app-arm64-v8a-release.apk
UNI=app/build/outputs/apk/release/app-universal-release.apk
for f in "$A64" "$UNI"; do
  [ -f "$f" ] || { echo "❌ 缺产物：$f（splits 只在任务名含 release/perf 时生效）"; exit 1; }
done

APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)
echo
echo "签名核对（两个包都必须是同一张证书）："
for f in "$A64" "$UNI"; do
  printf '  %-24s ' "$(basename "$f")"
  "$APKSIGNER" verify --print-certs "$f" | grep -m1 "SHA-256" || { echo "❌ 未签名"; exit 1; }
done

mkdir -p "$DIST"
cp "$A64" "$DIST/mika-arm64.apk"
cp "$UNI" "$DIST/mika-universal.apk"
echo
ls -lh "$DIST"/*.apk

if [ "$PUBLISH" = "1" ]; then
  if gh release view "$TAG" --repo mokasoul/Mika >/dev/null 2>&1; then
    echo "❌ $TAG 已存在。先在版本号上 +1（versionCode 与 versionName 同步改）再发。"
    exit 1
  fi
  NOTES=${NOTES:-"壳模式构建：电脑可直接调用手机工具（/tools），手机模型不参与。"}
  gh release create "$TAG" "$DIST/mika-arm64.apk" "$DIST/mika-universal.apk" \
    --repo mokasoul/Mika --title "$TAG" --notes "$NOTES"
  echo
  echo "已发布：https://github.com/mokasoul/Mika/releases/tag/$TAG"
  echo "手机上打开 Mika › 关于 › 检查更新 应能看到 $VERSION"
else
  echo
  echo "没发。要发就加 --publish。"
fi
