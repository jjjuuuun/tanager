---
name: tdd
description: 기능 하나를 TDD 사이클(설계 → RED → GREEN → REFACTOR → 리뷰)로 구현한다. 서브에이전트 architect, test-author, implementer, reviewer 를 순서대로 쓴다.
argument-hint: "[이슈번호] <기능 설명>"
---
# /tdd $ARGUMENTS

아래 순서를 건너뛰지 않는다. 각 단계 결과를 사용자에게 한 줄로 알린다.

0. **이슈·브랜치** — 이슈 번호가 없으면 `/issue` 로 먼저 만든다. 현재 브랜치가 `dev` 또는 `main` 이면 `git switch dev && git pull --ff-only` 후 `<type>/<번호>-<slug>` 브랜치를 만든다. 이미 릴리스된 버전의 긴급 수정이면 대신 `git switch main && git pull --ff-only` 후 `hotfix/<번호>-<slug>`. 이미 작업 브랜치면 그대로.
1. **설계** — 여러 모듈에 걸치거나 새 포트가 필요하면 `architect` 서브에이전트에 맡긴다. 단일 모듈의 작은 변경이면 직접 행위 목록(Given/When/Then)만 정리한다.
2. **RED** — `test-author` 서브에이전트에 행위 목록을 넘긴다. 실패 로그를 받아 "기능 없음" 때문에 실패하는지 확인한다.
3. **GREEN + REFACTOR** — `implementer` 서브에이전트에 실패 테스트 목록을 넘긴다. GREEN 커밋, (필요 시) REFACTOR 커밋이 생겼는지 `git log --oneline origin/<base>..HEAD` 로 확인한다 (base = 작업 브랜치는 `dev`, hotfix 는 `main`).
4. **리뷰** — `reviewer` 서브에이전트로 diff 를 검토한다. "차단" 항목이 있으면 2 또는 3 으로 돌아간다.
5. **PR** — 이슈의 인수 조건이 모두 GREEN 이면 `/pr` 로 이어간다(푸시 → Draft PR → CI → PR 에 리뷰 코멘트 → Ready). 병합 여부는 사용자가 정한다.
6. **보고** — PR URL, 커밋 목록, 권고(병합 권장/보류 권장), 남은 지적, 다음 행위 후보.
