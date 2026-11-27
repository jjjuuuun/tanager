#!/usr/bin/env bash
# PR 의 CI 가 끝날 때까지 기다린다(#145). 멈추지 않게 모든 대기에 상한이 있다.
#   사용: .claude/scripts/pr-wait.sh <PR 번호> [최대 초, 기본 2400]
#   종료 코드: 0 = 모든 검사 통과 · 1 = 실패한 검사 있음 · 2 = PR 없음/인자 오류 · 3 = 시간 초과
#   출력: 마지막 줄들에 "검사이름 결과" 목록
set -uo pipefail

pr="${1:-}"
limit="${2:-2400}"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "PR 번호가 필요하다: $0 <번호> [최대 초]" >&2; exit 2; }
gh pr view "$pr" --json state >/dev/null 2>&1 || { echo "PR #$pr 이 없다" >&2; exit 2; }

deadline=$(( $(date +%s) + limit ))
while :; do
  out="$(gh pr checks "$pr" 2>/dev/null)"; code=$?
  # gh pr checks: 0 = 모두 통과, 1 = 실패 있음, 8 = 진행 중. 검사가 아직 하나도 없으면 1 과 함께 빈 출력이 온다
  if [[ $code -eq 0 ]]; then
    printf '%s\n' "$out" | awk -F'\t' '{print $1" "$2}'; exit 0
  fi
  if [[ $code -eq 1 && -n "$out" ]] && ! printf '%s\n' "$out" | awk -F'\t' '{print $2}' | grep -qE '^(pending|queued|in_progress)$'; then
    printf '%s\n' "$out" | awk -F'\t' '{print $1" "$2}'; exit 1
  fi
  if (( $(date +%s) >= deadline )); then
    echo "시간 초과(${limit}s) — PR #$pr 검사가 끝나지 않았다" >&2
    printf '%s\n' "$out" | awk -F'\t' '{print $1" "$2}'
    exit 3
  fi
  sleep 30
done
