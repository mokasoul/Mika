#!/usr/bin/env bash
# 构建 Mika（壳模式）APK。
#
# 本机特殊处理：
#   - Maven Central 出口 IP 被封 → GRADLE_USER_HOME 指向项目内 .gradle-home，那里有一份
#     只换镜像、不塞项目级仓库的 init.gradle（见 .gradle-home/init.gradle 注释）。
#   - 需要 JDK 17 toolchain（brew install openjdk@17），路径写在 .gradle-home/gradle.properties。

set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export GRADLE_USER_HOME=$PWD/.gradle-home

if [ ! -f local.properties ]; then
  echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
fi

./gradlew :app:assembleDebug --console=plain "$@"

APK=$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
if [ -n "$APK" ]; then
  echo
  echo "APK: $APK ($(stat -f%z "$APK") 字节)"
fi
