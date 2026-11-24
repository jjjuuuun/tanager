---
paths:
  - "**/*.java"
---
# Java 코드 작성 규칙

기준: JDK 25(LTS), preview 기능 금지(`--enable-preview` 쓰지 않음). 포맷은 Spotless(palantir-java-format) 가 결정 — 손으로 맞추지 말고 `./gradlew spotlessApply`.

## 큰 원칙: Functional Core, Imperative Shell
- **domain / application 의 계산** → 함수형: 불변 `record`, `sealed interface`, `switch` 패턴 매칭, 부수효과 없는 메서드.
- **경계(adapter, platform, ui)** → 객체지향: 포트 구현, 생명주기·자원 관리, SOLID.
- 상태를 바꾸는 메서드는 새 값을 반환한다 (`LayoutNode split(...)` → 새 트리). 세터 금지.

## 구체 규칙
1. 값 객체·이벤트·DTO 는 `record`. compact constructor 에서 불변식 검증.
2. 닫힌 계층은 `sealed` + `permits` 생략 가능한 같은 파일 내 선언. `switch` 는 `default` 없이 전 케이스 처리(컴파일러가 누락 검출).
3. `null` 반환·인자 금지. 없을 수 있음 → `Optional<T>`, 실패 가능 → `Result<T, E>` (`dev.tanager.domain.shared.Result`).
4. 도메인 오류는 예외가 아니라 `sealed interface XxxError` 값. 예외는 진짜 예외(I/O 실패, 버그)에만.
5. 컬렉션은 `List.copyOf` / `Map.copyOf` 로 방어 복사, 외부에는 불변 뷰만.
6. 상속보다 합성. 추상 클래스 대신 인터페이스 + default 메서드 최소화.
7. 클래스는 기본 `final`. 패키지 전용(package-private)을 기본 가시성으로.
8. 가변 상태는 adapter 와 `StateActor` 안에만. `static` 가변 필드 금지.
9. 스트림은 읽기 쉬울 때만. 3단계 넘는 체인·부수효과 있는 `forEach` 대신 for 문.
10. 이름: 유스케이스 `XxxUseCase`(port.in), 출력 포트 `XxxPort` 대신 역할명(`HistoryRepository`, `PtyLauncher`), 구현체는 기술명 접두(`JsonHistoryRepository`, `Pty4jLauncher`).
11. 주석은 "왜"만. 공개 포트·도메인 타입에는 Javadoc 한 줄 이상.
12. 로깅: `System.Logger`. 경로 외 사용자 입력(명령어 내용)은 로그·저장 금지.
