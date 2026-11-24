#!/usr/bin/env bash
# PreToolUse(Bash: git * / gh *) — Git 전략을 강제한다.
#   exit 2               = 차단 (사유는 stderr 로 Claude 에 전달)
#   permissionDecision   = "ask" 를 stdout JSON 으로 내면 allow 규칙과 상관없이 사용자 승인을 받는다
# 역할 분담: 이슈·브랜치·커밋·푸시·PR(작업→dev, 릴리스 dev→main)·리뷰는 Claude, 병합·PR 닫기는 사용자, 릴리스 태그는 사용자 승인 후 Claude.
# 보호 브랜치: main(릴리스 전용) · dev(통합). 둘 다 직접 커밋·푸시 금지.
set -euo pipefail

cmd="$(jq -r '.tool_input.command // ""')"
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

# 2) 강제 푸시·main 푸시 금지 (작업 브랜치의 --force-with-lease 는 허용: rebase 후 갱신용)
if [[ "$cmd" =~ git[[:space:]]+push ]]; then
  [[ "$cmd" =~ (^|[[:space:]])(-f|--force)([[:space:]]|$) ]] && block "강제 푸시는 금지입니다. 작업 브랜치라면 --force-with-lease 를 쓰세요."
  [[ "$cmd" =~ (^|[[:space:]])\+ ]] && block "+refspec 강제 푸시는 금지입니다."
  [[ "$cmd" =~ (^|[[:space:]:])(main|dev)([[:space:]]|$) ]] && block "main · dev 로 직접 푸시할 수 없습니다. PR 로 병합하세요."
  [[ ( "$branch" == "main" || "$branch" == "dev" ) && ! "$cmd" =~ (refs/tags/|--tags|--follow-tags|[[:space:]:]v[0-9]+\.[0-9]+) ]] && block "현재 브랜치가 $branch 입니다. 작업 브랜치에서 푸시하세요."
fi

# 3) main · dev 직접 커밋·병합 금지
if [[ "$cmd" =~ git[[:space:]]+(commit|merge|cherry-pick) && ( "$branch" == "main" || "$branch" == "dev" ) ]]; then
  block "$branch 에 직접 커밋/병합할 수 없습니다. 'git switch dev && git pull --ff-only && git switch -c <type>/<이슈번호>-<slug>' 후 작업하세요."
fi

# 4) PR 병합·닫기·승인은 사용자만 (Claude 는 리뷰를 코멘트로만 남긴다)
[[ "$cmd" =~ gh[[:space:]]+pr[[:space:]]+(merge|close) ]] && block "PR 병합/닫기는 사용자가 결정합니다. 리뷰 코멘트까지만 남기세요."
[[ "$cmd" =~ gh[[:space:]]+pr[[:space:]]+review && "$cmd" =~ (^|[[:space:]])(--approve|-a)([[:space:]]|$) ]] && block "PR 승인은 사용자가 합니다. 'gh pr review <번호> --comment' 를 쓰세요."
[[ "$cmd" =~ gh[[:space:]]+api && "$cmd" =~ pulls/[0-9]+/merge ]] && block "API 로 PR 을 병합할 수 없습니다."

# ── 승인 필요 (릴리스) ────────────────────────────────────────────────
if [[ "$cmd" =~ git[[:space:]]+tag([[:space:]]|$) && ! "$cmd" =~ git[[:space:]]+tag([[:space:]]+(-l|--list)([[:space:]]|$)|[[:space:]]*$) ]]; then
  ask "릴리스 태그 생성/삭제입니다. 태그 이름과 대상 커밋을 확인하세요."
fi
if [[ "$cmd" =~ git[[:space:]]+push ]] && [[ "$cmd" =~ (^|[[:space:]])(--tags|--follow-tags)([[:space:]]|$) || "$cmd" =~ refs/tags/ || "$cmd" =~ [[:space:]:]v[0-9]+\.[0-9]+ ]]; then
  ask "태그 푸시는 release 워크플로(패키징·GitHub Release)를 실행합니다."
fi
[[ "$cmd" =~ gh[[:space:]]+release[[:space:]]+(create|edit|upload) ]] && ask "GitHub Release 를 직접 바꿉니다. 보통은 태그 푸시로 워크플로가 만듭니다."

exit 0
