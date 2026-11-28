# 0019. 도구함 공급자 SPI: 읽기 전용 · 비밀값 마스킹 · 경고로 실패 허용 · 지문 캐시

- 상태: 채택(#209, 2026-11-28)
- 날짜: 2026-11-28
- 관련: #209 · #204 · #205 · #206 · #208 · #220, FR-TK-01 · FR-TK-04 · FR-TK-07 · FR-TK-08 · FR-TK-10 · NFR-06 · NFR-07, SDD §7.4 · §8.1, ADR-0002 · 0013 · 0020

## 맥락
도구함(FR-TK-01)은 폴더 · 세션을 고르면 그 경로에서 쓸 수 있는 AI 도구 구성을 보여 준다. 지금 읽을 도구는 Claude Code 하나지만 Codex · Gemini CLI 같은 도구를 어댑터만 더해 붙일 수 있어야 한다(FR-TK-10). 도메인 카탈로그(`ToolkitItem` · `ToolkitView` · `ToolkitWarning`, #204), 환경의 홈과 앱이 읽을 경로(`HostFiles`, #205), 설정 `toolkit.providers`(#206)는 이미 있다.

제약:
- 사용자 설정 파일을 **쓰면 안 된다**. MCP `env` · 헤더 · 토큰 같은 비밀값은 화면 · 로그 · 저장에 남으면 안 된다(FR-TK-07, NFR-06).
- 형식이 공개 사양이 아니고 도구 판마다 바뀐다. 파일 하나가 틀렸다고 패널 전체가 비면 안 된다.
- WSL 경로는 `\\wsl.localhost` 로 읽어 느리고 VM 을 깨운다. Windows 200ms · WSL 1초(NFR-07) 안에 보여야 하고 EDT · `StateActor` 를 막으면 안 된다.
- 선택 · 창 포커스 때마다 다시 읽는다(FR-TK-08). 바뀌지 않은 경로를 매번 다 읽으면 느리다.
- 대화 기록(FR-TK-09)은 따로 `ConversationSource`(ADR-0020)로 읽는다. 입력(환경의 홈 전체)과 결과(이력 · 대화)가 달라 합치지 않는다.

## 선택지
**확장 방법**
1. application 에 도구별 분기를 둔다 — 도구가 늘 때마다 application 을 고친다(ADR-0002 위반).
2. **port.out SPI `ToolkitProvider` + `ServiceLoader`** — 새 도구 = `app/adapter/toolkit/<도구>` 모듈 + 서비스 등록. `PlatformModule` 과 같은 방식이다.

**실패 표현**
1. 예외 — 공급자 하나의 버그가 조회 전체를 깬다.
2. **값으로** — 파일 하나는 그 항목 대신 `ToolkitWarning`(`Unreadable` · `Malformed`), 공급자 전체는 `Result.err(ToolkitError)` → 서비스가 `ProviderFailed` 경고로 바꾼다.

**다시 읽기**
1. 매번 다 읽는다 — 단순하나 WSL 경로에서 1초를 넘기 쉽다.
2. 파일 감시(WatchService) — `\\wsl.localhost` 에서는 알림이 오지 않는다.
3. **지문(stamp)** — 공급자가 지난 scan 에서 본 파일 · 디렉터리와 수정 시각(`ScanStamp`)을 돌려주고, 다음 조회 때 `stamp()` 로 시각만 다시 재어 같으면 지난 결과를 쓴다. 시각 읽기는 내용 읽기보다 훨씬 싸다.

## 결정
- **SPI**(port.out): `ToolkitProvider` — `id()` · `supports(env)` · `scan(ToolkitQuery)` → `Result<ToolkitScan, ToolkitError>` · `stamp(ToolkitQuery)`(기본 empty = 캐시하지 않음). `ToolkitQuery(path, home, host)` 의 `path` · `home` 은 그 환경의 경로, `host` 는 앱이 `java.nio` 로 읽을 경로로 바꾸는 함수(`HostFiles::hostPath`)다. 공급자는 OS 를 모른다.
- **읽기 전용**: 공급자는 파일을 쓰지 않는다. "파일 열기"는 `QueryToolkitUseCase.hostFile(source)` 가 준 경로를 UI 가 OS 편집기에 넘길 뿐이다.
- **비밀값**: 공급자가 읽는 즉시 `AttributeValue.Masked` 로 바꾼다. 원래 값은 도메인 · 경고 · 로그로 넘기지 않는다(`ToolkitWarning` 에는 파서 메시지도 담지 않는다, #204). `ToolkitError` 도 경로 · 내용 없이 종류만 갖는다.
- **실패 허용**: `scan` 은 블로킹이고 예외를 던지지 않는다. 서비스는 그래도 던진 예외 · 10초 시간 초과 · err 를 모두 그 공급자의 `ProviderFailed` 하나로 바꾸고 다른 공급자의 항목은 그대로 둔다. 홈을 모르면 `HomeUnknown` + 프로젝트 범위만(`home` empty).
- **SPI 오용 방지**: 공급자가 다른 공급자 id 의 항목을 돌려주면 서비스가 버리고 로그한다. ArchUnit 이 `ToolkitProvider` 구현을 `tanager.adapter.toolkit..` 안으로 가둔다.
- **유스케이스**(port.in) `QueryToolkitUseCase` — `query(path, Freshness)` → `CompletionStage<ToolkitView>` · `providers(env)`(칩) · `hostFile(source)`. 구현 `ToolkitQueryService`:
  - 설정 `toolkit.providers`(비면 모두) 중 `supports(path.env)` 인 공급자를 **등록 순**으로 부른다. 부를 공급자가 없으면 홈도 묻지 않는다(WSL 을 깨우지 않는다).
  - 홈 묻기 · 공급자마다의 scan 은 I/O 실행기(가상 스레드)에서 나란히 돈다. 시간 제한은 `Scheduler` 로 건다 — 테스트는 가짜 실행기 · 가짜 시계로 결정적이다.
  - 도구함은 저장 상태가 아니므로 `StateActor` 를 거치지 않는다. 캐시 · 진행 중 조회는 출력 포트 조율용 가변 상태라 `HistorySaver` 와 같이 단일 monitor 로 지켜 application.service 에 둔다(java-style 8, ADR-0013).
  - 캐시: (경로, 공급자) → scan. `REUSE_IF_UNCHANGED` 면 `stamp()` 가 지난 지문과 같을 때 재사용, `FORCE` 면 늘 다시 읽는다. 지문 없는 scan · 실패는 캐시하지 않는다. 64 경로 LRU.
  - 같은 경로의 조회가 진행 중이면 새로 읽지 않고 그 결과를 같이 받는다(`FORCE` 여도).
- **조립**: `app/bootstrap` 이 `ServiceLoader.load(ToolkitProvider.class)` 로 모두 찾는다. 등록이 없으면 빈 view.

## 결과
- 새 도구는 모듈 하나 + `META-INF/services/tanager.application.port.out.ToolkitProvider` + 픽스처 테스트로 붙는다. application · ui 는 바뀌지 않는다.
- 바뀌지 않은 경로는 시각 확인만으로 끝나 포커스 때마다 다시 읽어도 싸다. 대가로 수정 시각을 바꾸지 않는 변경(같은 초 안의 두 번 저장 등)은 놓칠 수 있다 — 수동 새로고침(`FORCE`)이 있다.
- 시간을 넘긴 공급자의 읽기는 멈추지 않고 결과만 버린다(블로킹 I/O 를 끊을 방법이 없다). 가상 스레드 하나가 그동안 남는다.
- 진행 중 조회를 같이 받으므로 `FORCE` 가 진행 중 `REUSE_IF_UNCHANGED` 에 묻힐 수 있다 — 같은 순간의 결과라 문제 삼지 않는다.
- 후속: Claude 공급자(`ClaudeToolkitProvider`), 도구함 패널 · 진행 표시 · 새로고침(UI).
- **ArchUnit 으로 고정(#220)**: `toolkitAdaptersDoNotWriteFiles`(`adapter.toolkit..` 은 `Files` 의 쓰기 · 생성 · 삭제 · 이동 · 복사 · 속성 변경, `File` 의 변경 메서드, `FileOutputStream` · `FileWriter` · `RandomAccessFile` · `FileChannel` · `AsynchronousFileChannel` · `FileSystemProvider`, 파일 이름을 받는 `PrintWriter` · `PrintStream` · `Formatter`, Jackson 의 `File` · `Path` 쓰기를 쓰지 않는다 — `FileChannel.open` · `newByteChannel` 은 여는 옵션을 정적으로 가릴 수 없어 통째로 막는다), `noNetworkAccess`(제품 코드 전체에서 `java.net.http` · `javax.net` · 소켓 · `URLConnection` · 소켓 채널 · `URL.openConnection`/`openStream`/`getContent` 금지, `java.net.URI` 허용 — NFR-06), `domainToolkitKnowsOnlyHistoryAndShared`, `ToolkitProvider` · `ConversationSource` 구현 위치. 리플렉션 · 동적 로딩으로 우회하는 코드는 잡지 못한다 — 리뷰 몫이다.
- **제3자 라이브러리 네트워크 확인(#220, 2026-11-28)**: 규칙은 `tanager..` 만 본다. 런타임 jar(FlatLaf 3.8 · JediTerm core/ui 3.76 · pty4j 0.14.0 · JNA 5.19.1 · Jackson 3.3.0 core/databind/yaml · SnakeYAML Engine 3.0.1 · slf4j 2.0.19 · kotlin-stdlib)의 클래스 상수에서 `java/net/Socket` · `ServerSocket` · `DatagramSocket` · `URLConnection` · `HttpURLConnection` · `java/net/http` · `javax/net` · `openConnection` 참조는 없다. `URL.openStream` 은 FlatLaf(`UIDefaultsLoader` 등 — 클래스패스의 테마 · 네이티브 리소스) · pty4j(`ExtractedNative` — 번들 네이티브 라이브러리) · Jackson(`createParser(URL)` API, 앱은 부르지 않음)에만 있고 모두 로컬 리소스다. JediTerm 은 사용자가 터미널의 링크를 누르면 `Desktop.browse` 로 OS 브라우저에 넘길 뿐 스스로 통신하지 않는다. 라이브러리를 올릴 때 같은 확인을 다시 한다.
