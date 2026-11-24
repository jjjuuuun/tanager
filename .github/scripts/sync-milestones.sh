#!/usr/bin/env bash
# .github/milestones.json → GitHub 마일스톤 동기화 (만들기·제목/설명 갱신. 닫기·삭제는 하지 않는다)
# 사용: .github/scripts/sync-milestones.sh [--dry-run]     (gh 로그인 + 원격 저장소 필요)
# CI 에서는 .github/workflows/milestones.yml 이 GITHUB_TOKEN 으로 실행한다.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
file=".github/milestones.json"
dry=false; [[ "${1:-}" == "--dry-run" ]] && dry=true
repo="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

existing="$(gh api --paginate "repos/$repo/milestones?state=all&per_page=100" | jq -s 'add // []')"

jq -c '.milestones[]' "$file" | while read -r m; do
  key="$(jq -r .key <<<"$m")"
  title="$(jq -r .title <<<"$m")"
  desc="$(jq -r '
    "범위: \(.scope)\n완료 조건: \(.done)"
    + (if .release then "\n릴리스: \(.release)" else "" end)
    + (if (.requirements|length) > 0 then "\n요구사항: \(.requirements|join(", "))" else "" end)
    + "\n출처: docs/plans/02-SDD.html §13.1 · .github/milestones.json"' <<<"$m")"
  # 제목이 "<key> " 로 시작하는 기존 마일스톤을 같은 것으로 본다 (제목 문구를 바꿔도 이어짐)
  number="$(jq -r --arg k "$key" '[.[] | select(.title == $k or (.title | startswith($k + " ")))][0].number // empty' <<<"$existing")"

  if [[ -z "$number" ]]; then
    echo "생성  $title"
    $dry || gh api -X POST "repos/$repo/milestones" -f title="$title" -f description="$desc" >/dev/null
  else
    cur="$(jq -c --argjson n "$number" '.[] | select(.number == $n) | {title, description}' <<<"$existing")"
    if [[ "$cur" == "$(jq -cn --arg t "$title" --arg d "$desc" '{title:$t, description:$d}')" ]]; then
      echo "유지  $title (#$number)"
    else
      echo "갱신  $title (#$number)"
      $dry || gh api -X PATCH "repos/$repo/milestones/$number" -f title="$title" -f description="$desc" >/dev/null
    fi
  fi
done
