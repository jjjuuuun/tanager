#!/usr/bin/env bash
# test-author 서브에이전트 전용 PreToolUse(Edit|Write) — src/test/ 밖의 파일 수정을 막는다
set -euo pipefail

path="$(jq -r '.tool_input.file_path // ""')"
case "$path" in
  */src/test/*|*/src/testFixtures/*) exit 0 ;;
  *) echo "[tests-only] test-author 는 src/test/, src/testFixtures/ 만 수정할 수 있습니다: $path" >&2; exit 2 ;;
esac
