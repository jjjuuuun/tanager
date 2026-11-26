#!/usr/bin/env bash
# PreToolUse(Bash: git * / gh *) — Git 전략을 강제한다.
#   exit 2               = 차단 (사유는 stderr 로 Claude 에 전달)
#   permissionDecision   = "ask" 를 stdout JSON 으로 내면 allow 규칙과 상관없이 사용자 승인을 받는다
# 역할 분담(사용자 결정 2026-11-25 · ADR-0005 개정): 사용자 몫은 dev → main 릴리스 PR 병합 하나.
#           그 밖(작업 PR squash · 역병합 · hotfix 병합 · 태그 · Release · PR 닫기)은 Claude.
#           되돌리기 어려운 것(강제 푸시 · 보호 브랜치 직접 변경 · 원격 태그 삭제 등)은 계속 막거나 승인 창.
# 보호 브랜치: main(릴리스 전용) · dev(통합). 둘 다 직접 커밋·푸시 금지.
set -euo pipefail

raw="$(jq -r '.tool_input.command // ""')"
# git 전역 옵션(-C <경로>, -c <키=값>)을 지워 'git -C x push' 도 'git push' 로 검사한다
cmd="$(printf '%s' "$raw" | sed -E "s/git([[:space:]]+-[Cc][[:space:]]+(\"[^\"]*\"|'[^']*'|[^[:space:]]+))+/git/g")"
branch="$(git -C "${CLAUDE_PROJECT_DIR:-.}" symbolic-ref --short HEAD 2>/dev/null || echo "")"

block() { echo "[guard-git] 차단: $1" >&2; exit 2; }
ask() {
  jq -n --arg r "[guard-git] 승인 필요: $1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"ask",permissionDecisionReason:$r}}'
  exit 0
}

# ── 차단 ─────────────────────────────────────────────────────────────
# 1) 훅 우회 금지
[[ "$cmd" =~ (^|[[:space:]])--no-verify([[:space:]]|$) ]] && block "--no-verify 로 git 훅을 우회할 수 없습니다."
# core.hooksPath 변경 차단 — 실제 git 명령 구간(git config … / git -c core.hooksPath=…)만 본다(커밋 메시지 본문 오탐 방지)
hp="$(printf '%s\n' "$raw" | sed -E 's/(&&|\|\||;|\|)/\n/g' \
      | grep -iE '^[[:space:]]*git[[:space:]].*core\.hookspath' \
      | grep -iE '([[:space:]]config[[:space:]]|-c[[:space:]]*core\.hookspath)' || true)"
if [[ -n "$hp" ]] && ! grep -qiE 'hookspath[[:space:]=]+\.githooks/?([[:space:]]|$)' <<<"$hp"; then
  block "core.hooksPath 를 바꿔 git 훅을 우회할 수 없습니다(허용 값: .githooks)."
fi

# 2) 강제 푸시·main 푸시 금지 (작업 브랜치의 --force-with-lease 는 허용: rebase 후 갱신용)
#    한 명령에 여러 단계가 묶여 있어도(&&, ;, |, 줄바꿈) git push 부분만 검사한다
push="$(printf '%s\n' "$cmd" | sed -E 's/(&&|\|\||;|\|)/\n/g' | grep -E 'git[[:space:]]+push' || true)"
if [[ -n "$push" ]]; then
  [[ "$push" =~ (^|[[:space:]])(-f|--force)([[:space:]]|$) ]] && block "강제 푸시는 금지입니다. 작업 브랜치라면 --force-with-lease 를 쓰세요."
  [[ "$push" =~ (^|[[:space:]])\+ ]] && block "+refspec 강제 푸시는 금지입니다."
  [[ "$push" =~ (^|[[:space:]])--(mirror|all|prune|delete)([[:space:]]|$) ]] && block "--mirror · --all · --prune · --delete 푸시는 금지입니다."
  [[ "$push" =~ (^|[[:space:]:/])(main|dev)([[:space:]]|$) ]] && block "main · dev 로 직접 푸시할 수 없습니다. PR 로 병합하세요."
  [[ ( "$branch" == "main" || "$branch" == "dev" ) && ! "$push" =~ (refs/tags/|--tags|--follow-tags|[[:space:]:]v[0-9]+\.[0-9]+) ]] && block "현재 브랜치가 $branch 입니다. 작업 브랜치에서 푸시하세요."
fi

# 3) main · dev 직접 커밋·병합 금지
if [[ "$cmd" =~ git[[:space:]]+(commit|merge|cherry-pick|revert|am)([[:space:]]|$) && ( "$branch" == "main" || "$branch" == "dev" ) ]]; then
  block "$branch 에 직접 커밋/병합할 수 없습니다. 'git switch dev && git pull --ff-only && git switch -c <type>/<이슈번호>-<slug>' 후 작업하세요."
fi

# 4) PR 병합: dev → main 릴리스 PR 은 사용자만. Claude 는 PR 의 head · base 를 조회해 다음만 병합한다.
#    작업 PR(→ dev, head ≠ main) = --squash · 역병합(main → dev) = --merge · hotfix/* → main = --merge
#    gh pr 로 시작하는 구간만 본다(이슈·PR 본문 같은 텍스트 오탐 방지)
prseg="$(printf '%s\n' "$cmd" | sed -E 's/(&&|\|\||;|\|)/\n/g' | grep -E '^[[:space:]]*gh[[:space:]]+pr[[:space:]]+' || true)"
[[ "$prseg" =~ gh[[:space:]]+pr[[:space:]]+review && "$prseg" =~ (^|[[:space:]])(--approve|-a)([[:space:]]|$) ]] && block "자기 PR 은 승인할 수 없습니다. 'gh pr review <번호> --comment' 를 쓰세요."
while IFS= read -r m; do
  [[ "$m" =~ gh[[:space:]]+pr[[:space:]]+merge ]] || continue
  [[ "$m" =~ (^|[[:space:]])(--rebase|-r|--admin|--auto|--disable-auto|--subject|-t|--body|-b|--body-file|-F)([[:space:]=]|$) ]] \
    && block "rebase · 관리자 우회 · 자동 병합 · 메시지 지정 병합은 쓰지 않습니다."
  [[ "$m" =~ gh[[:space:]]+pr[[:space:]]+merge[[:space:]]+([0-9]+)([[:space:]]|$) ]] || block "병합할 PR 번호를 merge 바로 뒤에 숫자로 쓰세요."
  num="${BASH_REMATCH[1]}"
  repo=""; [[ "$m" =~ (--repo|-R)[[:space:]=]+([^[:space:]]+) ]] && repo="${BASH_REMATCH[2]}"
  refs="$(cd "${CLAUDE_PROJECT_DIR:-.}" && gh pr view "$num" ${repo:+--repo "$repo"} --json headRefName,baseRefName -q '.headRefName+" "+.baseRefName' 2>/dev/null || true)"
  [[ "$refs" =~ ^([^[:space:]]+)[[:space:]]([^[:space:]]+)$ ]] || block "PR #$num 의 head · base 를 확인할 수 없습니다."
  head="${BASH_REMATCH[1]}"; base="${BASH_REMATCH[2]}"
  [[ "$base" == "main" && "$head" == "dev" ]] && block "릴리스 PR(dev → main)은 사용자가 병합합니다."
  if [[ "$m" =~ (^|[[:space:]])(--squash|-s)([[:space:]]|$) ]]; then
    [[ "$base" == "dev" && "$head" != "main" ]] || block "squash 는 dev 로 가는 작업 PR 에만 씁니다(PR #$num: $head → $base)."
  elif [[ "$m" =~ (^|[[:space:]])(--merge|-m)([[:space:]]|$) ]]; then
    [[ ( "$head" == "main" && "$base" == "dev" ) || ( "$base" == "main" && "$head" == hotfix/* ) ]] \
      || block "merge commit 은 역병합(main → dev)과 hotfix/* → main 에만 씁니다(PR #$num: $head → $base)."
  else
    block "병합 방법을 쓰세요: 작업 PR 은 --squash, 역병합 · hotfix 는 --merge."
  fi
done <<<"$prseg"
[[ "$cmd" =~ gh[[:space:]]+api && "$cmd" =~ pulls/[0-9]+/merge ]] && block "API 로 PR 을 병합할 수 없습니다."
[[ "$cmd" =~ gh[[:space:]]+api && "$cmd" =~ (pulls|issues)/[0-9]+ && "$cmd" =~ state=closed ]] && block "API 로 PR 을 닫을 수 없습니다."
[[ "$cmd" =~ gh[[:space:]]+api[[:space:]]+graphql && "$cmd" =~ (mergePullRequest|closePullRequest|enablePullRequestAutoMerge|approve) ]] && block "GraphQL 로 PR 을 병합/닫기/승인할 수 없습니다."

# ── 승인 필요 (되돌리기 어려운 것만) ─────────────────────────────────
# 태그 생성 · 푸시와 gh release create/edit/upload 는 Claude 가 바로 한다(사용자 결정 2026-11-25).
# 원격 태그 삭제는 이미 받아 간 사람이 있을 수 있어서, 여러 태그 한꺼번에 푸시는 archive/* 같은 태그까지 나가서 승인 창을 띄운다.
if [[ -n "$push" ]] && [[ "$push" =~ [[:space:]]:(refs/tags/)?[^[:space:]] ]]; then
  ask "원격 ref 삭제입니다. 태그라면 그 GitHub Release 가 없는지(아무도 받지 않았는지) 확인하세요."
fi
if [[ -n "$push" ]] && [[ "$push" =~ (^|[[:space:]])(--tags|--follow-tags)([[:space:]]|$) ]]; then
  ask "모든 태그를 푸시합니다. 보통은 'git push origin vX.Y.Z' 로 하나만 푸시합니다."
fi

exit 0
