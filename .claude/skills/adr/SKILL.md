---
name: adr
description: 되돌리기 어려운 기술 결정(라이브러리 선택, 저장 형식, 모듈 경계, 플랫폼 전략)을 docs/adr 에 Architecture Decision Record 로 남긴다.
argument-hint: "<결정 제목>"
---
# /adr $ARGUMENTS

1. `docs/adr/` 의 마지막 번호 + 1 로 `NNNN-<kebab-slug>.md` 를 만든다.
2. 아래 형식을 그대로 쓴다.

```markdown
# NNNN. <제목>

- 상태: 제안 | 채택 | 대체됨(→ NNNN) | 폐기
- 날짜: YYYY-MM-DD
- 관련: 이슈 #, SDD 절

## 맥락
<왜 결정이 필요한가. 제약.>

## 선택지
1. <A> — 장점 / 단점
2. <B> — 장점 / 단점

## 결정
<무엇을 골랐고 왜>

## 결과
<좋아지는 것, 감수하는 것, 후속 작업>
```

3. 기존 결정을 뒤집으면 이전 ADR 상태를 `대체됨(→ NNNN)` 으로 고친다.
4. 커밋은 `docs(docs): ADR-NNNN <제목>` 으로 따로 한다(사용자가 요청할 때).
