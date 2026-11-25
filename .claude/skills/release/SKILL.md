---
name: release
description: 마일스톤이 끝났을 때 dev → main 릴리스 PR(chore(release): vX.Y.Z)을 열고, 그 마일스톤의 PR·리뷰를 모아 사용자가 한 번에 확인하게 한다. 사용자가 병합하면 승인을 받아 main 에 태그를 붙이고 release 워크플로와 마일스톤 닫기까지 마무리한다.
argument-hint: "[vX.Y.Z]"
---
# /release $ARGUMENTS

역할: PR 준비·리뷰·태그 실행은 Claude. **main 병합은 사용자**, **태그 생성·푸시는 사용자 승인 후**(`guard-git.sh` 가 승인 창을 띄운다).
작업 PR 은 Claude 가 dev 에 병합해 왔으므로, **이 릴리스 PR 이 사용자가 그 마일스톤 전체를 확인하는 유일한 지점**이다. 본문만 읽고 판단할 수 있게 쓴다.

## A. 준비 확인 (하나라도 실패하면 멈추고 보고)
1. 버전: 인자가 있으면 그것, 없으면 `.github/milestones.json` 에서 열린 마일스톤의 `release`. 형식 `vX.Y.Z[-pre]`. 이미 있는 태그면 중단(`git tag -l`).
2. 마일스톤의 열린 이슈 0개: `gh api "repos/{owner}/{repo}/milestones?state=open" --jq '.[] | {title, open_issues}'`. 남아 있으면 목록을 보여 주고 멈춘다.
3. `git fetch origin` 후 dev 최신 커밋의 CI 가 green: `gh run list --branch dev --workflow ci --limit 1`.
4. hotfix 역병합 누락 확인: `git rev-list --no-merges origin/dev..origin/main` 이 비어 있어야 한다(릴리스 merge commit 은 dev 에 없는 게 정상이라 `--no-merges` 로 뺀다). 커밋이 나오면 역병합 PR 을 먼저 연다(`/pr` D).

## B. 변경 내역
5. `git cliff origin/main..origin/dev --tag <버전> --strip all` (없으면 `git log --oneline --no-merges origin/main..origin/dev`).
6. 버전이 맞는지 SemVer 로 검사: `!`/`BREAKING CHANGE` → MAJOR(1.0 전에는 MINOR), `feat` → MINOR, `fix`/`perf` → PATCH. 다르면 사용자에게 알리고 제안 버전을 같이 적는다.
7. WSL 통합 테스트(CI 에서 안 도는 `@Tag("wsl")`): `cmd.exe /c gradlew.bat :tanager-platform-windows:test` 결과를 기록한다(M1 이후).
7-1. 포함된 PR 모으기: `gh pr list --base dev --state merged --search 'milestone:"<마일스톤>"' --json number,title,url,mergedAt` → PR 마다 `gh api repos/{owner}/{repo}/pulls/<n>/reviews` 에서 `## Claude 리뷰` 로 시작하는 마지막 코멘트의 **권고 줄**과 **남은 지적** 항목을 뽑는다. 사용자가 손으로 확인해야 하는 항목(UI · 한글 입력 · 설치 등)은 따로 모은다.

## C. 릴리스 PR (dev → main)
8. `gh pr create --base main --head dev --title "chore(release): <버전>" --label release --milestone "<마일스톤>" --body-file <파일>` — 본문:
   ```
   > 병합 방법: **Create a merge commit** (squash 금지 — dev 와 main 의 이력을 이어 두기 위해)

   ## 릴리스 <버전> — <마일스톤 제목>
   ### 포함된 PR            | PR | 제목 | Claude 권고 | 남은 지적 | — 7-1 결과, 링크 포함
   ### 변경 내역            (B-5 결과, 그룹별)
   ### 호환성               BREAKING 여부 · schemaVersion 변경 · 키 바인딩 변경
   ### 검사                 CI 링크 · WSL 통합 테스트 결과 · 패키지 스모크
   ### 사용자가 직접 확인할 것   7-1 에서 모은 수동 확인 항목 + 설치 파일 실행 · 주요 시나리오 (체크박스로)
   ### 병합 후 (Claude 가 진행)  태그 → release 워크플로 → 마일스톤 닫기
   🤖 Generated with [Claude Code](https://claude.com/claude-code)
   ```
9. CI 대기 → `gh pr review <번호> --comment` 로 릴리스 리뷰(권고: 릴리스 권장/보류 권장, 근거) → 멈추고 PR URL 보고.

## D. 사용자가 병합한 뒤
10. `git fetch origin && git switch main && git pull --ff-only`
11. `git tag -a <버전> -m "<버전> — <마일스톤 제목>"` → **승인 창** → `git push origin <버전>` → **승인 창**
12. `gh run watch` 로 release 워크플로를 본다. 실패하면 원인을 보고(태그 삭제·재태그는 사용자 결정).
    - 일시적 실패(네트워크 등)면 `gh run rerun <id> --failed`.
    - 워크플로 자체가 틀렸으면 `hotfix/<n>-<slug>`(main 에서 분기) PR 로 고친다. 병합은 사용자.
    - 병합 후 사용자에게 고르게 한다.
      - A: 태그 옮기기. Release 가 아직 없을 때만 고를 수 있다. `git push origin :refs/tags/<버전>`(승인 창) → `git tag -d <버전>` → `git tag -a <버전> -m "…" <hotfix 병합 커밋>` → `git push origin <버전>`(승인 창). v0.1.0 은 A 로 했다(2026-11-25).
      - B: 다음 패치 버전으로 릴리스.
    - 어느 쪽이든 끝나면 역병합 PR 을 연다(`/pr` D).
13. 마일스톤 닫기: `gh api -X PATCH "repos/{owner}/{repo}/milestones/<번호>" -f state=closed`
14. `git switch dev && git pull --ff-only` 로 돌아와 다음 마일스톤을 알린다.

## E. hotfix 패치 릴리스
hotfix PR 이 main 에 병합됐으면 C 를 건너뛰고 D 의 10~13 을 패치 버전으로 한 뒤, `/pr` D 의 역병합 PR 을 연다.
