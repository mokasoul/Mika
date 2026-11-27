#!/usr/bin/env bash
# 把上游 wangzan101/Mika 的更新合进我们的 fork，并检查我们那几处改动还能不能干净应用。
#
# 用法：
#   scripts/upstream-sync.sh            # 只检查，不动当前分支
#   scripts/upstream-sync.sh --apply    # 检查通过就真的 rebase 到 upstream/main
#
# 我们的改动只集中在少数几处（见 docs/HERMES-BRIDGE.md 的改动清单），
# 而且绝大多数是"新增文件 + 新增行"，所以正常情况 rebase 一遍就过。

set -euo pipefail
cd "$(dirname "$0")/.."

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

git fetch --quiet upstream
BRANCH=$(git rev-parse --abbrev-ref HEAD)
BASE=$(git merge-base HEAD upstream/main)
OURS=$(git log --oneline "$BASE"..HEAD | wc -l | tr -d ' ')
UPSTREAM=$(git log --oneline HEAD..upstream/main | wc -l | tr -d ' ')

echo "当前分支：$BRANCH"
echo "我们独有 commit：$OURS"
echo "上游新 commit：$UPSTREAM"
if [ "$UPSTREAM" = "0" ]; then
  echo "上游没有新提交，不用同步。"
  exit 0
fi

echo
echo "上游新提交："
git log --oneline --max-count=20 HEAD..upstream/main | sed 's/^/  /'

# 用临时 worktree 试算，不碰当前工作区
WT=$(mktemp -d "${TMPDIR:-/tmp}/mika-sync.XXXXXX")
PATCHES="$WT/patches"
mkdir -p "$PATCHES"
git format-patch --quiet "$BASE"..HEAD -o "$PATCHES" >/dev/null

echo
echo "在临时工作区试算 rebase ..."
git worktree add --detach --quiet "$WT/tree" upstream/main
CLEAN=1
if ! ( cd "$WT/tree" && git am --3way --quiet "$PATCHES"/*.patch >/dev/null 2>&1 ); then
  CLEAN=0
fi

if [ "$CLEAN" = "1" ]; then
  echo "✅ 干净应用，没有任何冲突。"
  ( cd "$WT/tree" && git diff --stat upstream/main HEAD | tail -20 | sed 's/^/  /' )
else
  echo "❌ 有冲突，需要人工处理。冲突文件："
  ( cd "$WT/tree" && git status --porcelain | grep -E '^(UU|AA|DU|UD)' | sed 's/^/  /' ) || true
  echo
  echo "补丁已存到：$PATCHES"
  echo "处理办法：在那个临时工作区里手工解决冲突，或者按 docs/HERMES-BRIDGE.md 的改动清单重新打一遍。"
fi

git worktree remove --force "$WT/tree" >/dev/null 2>&1 || true
rm -rf "$WT"

if [ "$APPLY" = "1" ] && [ "$CLEAN" = "1" ]; then
  echo
  echo "正把 $BRANCH rebase 到 upstream/main ..."
  git rebase upstream/main
  echo "✅ 完成。现在可以重新构建：scripts/build.sh"
elif [ "$APPLY" = "1" ]; then
  echo "有冲突，没有执行 rebase（--apply 只在干净时生效）。"
  exit 1
fi
