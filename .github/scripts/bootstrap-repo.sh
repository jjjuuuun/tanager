#!/usr/bin/env bash
# 저장소 최초 1회 설정 — 사용자가 직접 실행한다 (Claude 는 --no-verify 를 쓸 수 없으므로 실행 불가).
#
#   1) 빈 시작 커밋을 main 에 만들고 dev 를 그 위에 만든다 (훅 우회는 이 스크립트에서만)
#   2) main · dev 를 origin 에 올리고 기본 브랜치를 main 으로 둔다(작업 PR 은 --base dev 로 연다)
#   3) 병합 방식 · 브랜치 규칙(ruleset) · 라벨 · 마일스톤을 설정한다
#   4) 작업 중이던 파일은 커밋하지 않고 그대로 둔다 → 이후 Claude 가 이슈 · chore/<번호>-bootstrap 브랜치 · dev 로 PR
#
# 사용:  .github/scripts/bootstrap-repo.sh <owner/repo>            # 처음
#        .github/scripts/bootstrap-repo.sh <owner/repo> --with-checks   # M1 이후: 필수 CI 검사 추가
# 전제:  gh 로그인, GitHub 에 빈 저장소가 있음. 비공개 저장소의 ruleset 은 GitHub Pro 이상에서만 동작한다
#        (안 되면 경고만 내고 넘어간다 — 로컬 git 훅과 .claude/hooks/guard-git.sh 는 그대로 지킨다).
set -euo pipefail

repo="${1:?사용: bootstrap-repo.sh <owner/repo> [--with-checks]}"
# https://github.com/owner/repo(.git) · git@github.com:owner/repo.git 도 받는다
repo="${repo#https://github.com/}"; repo="${repo#http://github.com/}"; repo="${repo#git@github.com:}"
repo="${repo%/}"; repo="${repo%.git}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "✖ 저장소 형식 오류: $1 → owner/repo" >&2; exit 1; }
with_checks=false; [[ "${2:-}" == "--with-checks" ]] && with_checks=true
cd "$(git rev-parse --show-toplevel)"
say() { printf '\n▶ %s\n' "$1"; }

# ── 1. 시작 커밋 · dev ────────────────────────────────────────────
if ! git rev-parse --verify -q main >/dev/null; then
  say "main 에 빈 시작 커밋 생성"
  git symbolic-ref HEAD refs/heads/main
  git commit --allow-empty --no-verify -m "chore(build): 저장소 시작"
fi
git rev-parse --verify -q dev >/dev/null || { say "dev 브랜치 생성"; git branch dev main; }

# ── 2. 원격 · 기본 브랜치 ─────────────────────────────────────────
url="https://github.com/$repo.git"
if ! git remote get-url origin >/dev/null 2>&1; then
  git remote add origin "$url"
elif [[ "$(git remote get-url origin)" != "$url" ]]; then
  say "origin 주소 수정: $(git remote get-url origin) → $url"
  git remote set-url origin "$url"
fi
say "main · dev 푸시"
git push --no-verify -u origin main dev

say "저장소 설정: 기본 브랜치 main · squash(작업→dev) + merge commit(릴리스·역병합) · rebase merge 끔"
gh api -X PATCH "repos/$repo" \
  -f default_branch=main \
  -F allow_squash_merge=true -F allow_merge_commit=true -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true \
  -f squash_merge_commit_title=PR_TITLE -f squash_merge_commit_message=PR_BODY \
  -f merge_commit_title=PR_TITLE -f merge_commit_message=PR_BODY >/dev/null

# ── 3. 브랜치 규칙 (ruleset) ─────────────────────────────────────
checks='[]'
if $with_checks; then
  checks='[{"context":"1. lint (spotless)"},{"context":"2a. test core (domain · app · ArchUnit)"},
           {"context":"2b. test windows (ConPTY · PowerShell · 통합)"},{"context":"pr-lint"}]'
fi
ruleset() {  # $1 이름  $2 브랜치  $3 허용 병합 방식(JSON 배열)
  local body
  body="$(jq -n --arg n "$1" --arg ref "refs/heads/$2" --argjson m "$3" --argjson c "$checks" '{
    name: $n, target: "branch", enforcement: "active",
    conditions: { ref_name: { include: [$ref], exclude: [] } },
    rules: ([ {type: "deletion"}, {type: "non_fast_forward"},
              {type: "pull_request", parameters: {
                 required_approving_review_count: 0, dismiss_stale_reviews_on_push: false,
                 require_code_owner_review: false, require_last_push_approval: false,
                 required_review_thread_resolution: false, allowed_merge_methods: $m } } ]
            + (if ($c | length) > 0 then [ {type: "required_status_checks", parameters: {
                 strict_required_status_checks_policy: false, required_status_checks: $c } } ] else [] end))
  }')"
  local id
  id="$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$1\") | .id" 2>/dev/null || true)"
  if [[ -n "$id" ]]; then
    gh api -X PUT "repos/$repo/rulesets/$id" --input - <<<"$body" >/dev/null && echo "  갱신: $1"
  else
    gh api -X POST "repos/$repo/rulesets" --input - <<<"$body" >/dev/null && echo "  생성: $1"
  fi
}
say "브랜치 규칙$($with_checks && echo ' (+ 필수 CI 검사)')"
ruleset "main (릴리스 전용)" main '["merge"]' \
  || echo "  ⚠ ruleset 을 만들 수 없습니다(비공개 저장소 + 무료 플랜?). 로컬 훅과 Claude 훅만으로 보호합니다."
ruleset "dev (통합)" dev '["squash","merge"]' || true

# ── 4. 라벨 · 마일스톤 ───────────────────────────────────────────
say "라벨"
for pair in "type:feat=2563eb" "type:fix=b45309" "release=6d28d9"; do
  gh label create "${pair%%=*}" --repo "$repo" --color "${pair##*=}" --force >/dev/null && echo "  ${pair%%=*}"
done
say "마일스톤"
GITHUB_REPOSITORY="$repo" .github/scripts/sync-milestones.sh

say "완료. 다음: Claude 에게 '부트스트랩 이슈와 PR 을 만들어 줘' (chore/<번호>-bootstrap → dev)"
