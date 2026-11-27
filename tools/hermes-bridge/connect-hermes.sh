#!/usr/bin/env bash
# 把 Mac 和手机上的 Mika 接起来，并用工具级入口（/tools）配好 Hermes 的 MCP server。
#
# 用法：
#   tools/hermes-bridge/connect-hermes.sh           # USB：打开电脑连接、端口转发、打印 token 和配置片段
#   tools/hermes-bridge/connect-hermes.sh --write   # 顺带把 token 写进 Hermes 的 config.yaml（会先备份）
#
# 前置：手机插着 USB、adb devices 能看到设备、Mika 已装并打开过一次、
#       Mika 里「连接电脑」已开启（USB 或局域网任一）。

set -euo pipefail
cd "$(dirname "$0")/.."

LOCAL_PORT=${LOCAL_PORT:-18765}
INFO=/sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
WRITE=0
[ "${1:-}" = "--write" ] && WRITE=1

if ! adb devices | grep -qE '\sdevice$'; then
  echo "❌ adb 看不到设备。插线或无线调试连上再来。"
  exit 1
fi

echo "1) 打开手机的电脑连接（USB 模式）..."
adb shell sh "$(dirname "$INFO")/connect-computer.sh" | sed 's/^/   /'

echo "2) 端口转发 tcp:$LOCAL_PORT -> 手机 8765..."
adb forward "tcp:$LOCAL_PORT" tcp:8765

echo "3) 读 token..."
TOKEN=$(adb shell cat "$INFO" | tr -d '\r' | sed -n 's/.*"token": *"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "❌ 没读到 token。确认 Mika 已打开过一次，且电脑连接已开启。"
  exit 1
fi

echo "4) 自检（tools/list）..."
RESP=$(curl -s -m 60 "http://127.0.0.1:$LOCAL_PORT/tools" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')
echo "$RESP" | head -c 600; echo

case "$RESP" in
  *'"tools"'*) echo "✅ 工具面已就绪。" ;;
  *) echo "⚠️ 没拿到工具列表；第一次请求会触发装配，可能要 30-60 秒，稍等重跑一次。";;
esac

cat <<EOF

Hermes 配置片段（注意 URL 末尾是 /tools，不是 /mcp）：

mcp_servers:
  mika-bridge:
    type: http
    url: http://127.0.0.1:$LOCAL_PORT/tools
    headers:
      Authorization: Bearer $TOKEN

token：$TOKEN
（手机重启或关掉电脑连接后 token 会变，重跑这个脚本即可。）

EOF

if [ "$WRITE" = "1" ]; then
  CONF=~/.hermes/profiles/ops/config.yaml
  [ -f "$CONF" ] || { echo "❌ 找不到 $CONF"; exit 1; }
  cp "$CONF" "$CONF.bak.$(date +%Y%m%d%H%M%S)"
  if command -v yq >/dev/null 2>&1; then
    MIKA_URL="http://127.0.0.1:$LOCAL_PORT/tools" MIKA_TOKEN="$TOKEN" \
      yq -i '.mcp_servers["mika-bridge"].type="http" | .mcp_servers["mika-bridge"].url=env(MIKA_URL) | .mcp_servers["mika-bridge"].headers.Authorization=("Bearer " + env(MIKA_TOKEN))' "$CONF"
    echo "✅ 已写入 $CONF（备份在同目录 .bak.*）"
  else
    echo "⚠️ 没装 yq，不自动改配置。手动把上面的片段贴进去。"
  fi
fi
