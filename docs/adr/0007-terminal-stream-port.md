# 0007. 터미널 입출력 포트: PtyLauncher · TerminalStream 계약

- 상태: 채택(#43, 2026-11-25)
- 날짜: 2026-11-25
- 관련: SDD §4.2 스레드 모델 · §5.4 · §6.2 · §10.2, ADR-0001 · 0002 · 0003, FR-SES-01 · 04

## 맥락
M2 에서 셸을 실제로 띄운다. 터미널 출력은 화면(JediTerm), cwd 추적(`CwdTracker`, M3), 이후 다른 소비자가 함께 읽는다. 이 계약은 UI · application · 어댑터 · 플랫폼 모듈이 모두 기대는 것이어서 나중에 바꾸기 어렵다. 정할 것은 다섯 가지다.
1. 출력을 밀어 줄지(push), 소비자가 당겨 갈지(pull)
2. 콜백이 어느 스레드에서 오는지
3. 기동 명령(WSL `--cd`, PowerShell `-NoExit` 같은 것)을 어디서 만드는지
4. 종료를 어떻게 알리는지
5. 구독보다 먼저 나온 출력을 어떻게 다루는지

## 선택지
**출력 전달**
1. pull — `read(byte[])` 를 노출한다(JediTerm `TtyConnector` 와 같은 모양). 소비자가 둘 이상이면 누가 읽을지 다툰다.
2. **push — `subscribe(Consumer<byte[]>)`**. 세션마다 읽기 스레드 1개가 모든 구독자에게 차례로 넘긴다. UI 는 push 를 pull 로 바꾸는 작은 큐를 둔다.

**기동 명령 조립**
1. pty 어댑터가 `ShellProfile` 을 받아 직접 조립한다. 어댑터가 WSL · PowerShell 을 알게 되고, macOS 가 들어오면 어댑터를 고쳐야 한다.
2. **`PlatformModule.launchCommand(profile, cwd)` 가 `LaunchCommand(argv, workingDirectory, environment)` 를 만들고, `PtyLauncher` 는 그것만 실행한다.** 셸 통합 주입(M3)도 같은 자리에 들어간다.

**구독 전 출력**
1. 첫 구독 때 읽기를 시작한다. 두 번째 구독자는 첫 프롬프트를 놓친다.
2. 앞부분을 버퍼에 쌓아 두었다가 늦게 온 구독자에게 다시 준다. 크기 상한과 중복 처리가 복잡하다.
3. **명시적 `start()`**. 구독을 다 붙인 뒤 부르면 그 뒤로는 첫 바이트부터 놓치지 않는다.

## 결정
- `PtyLauncher.launch(LaunchCommand, TerminalSize) → Result<TerminalStream, LaunchError>`. 기동 명령 조립은 `PlatformModule.launchCommand` 가 맡는다. pty 어댑터는 OS 와 셸 종류를 모른다(macOS 에서도 그대로 쓴다).
- **push 구독과 명시적 `start()`.**
  - 콜백은 그 세션의 읽기 스레드에서 한 번에 하나씩, 바이트 순서대로 온다. EDT 에서는 오지 않는다.
  - 읽기 스레드는 세션당 데몬 **플랫폼** 스레드다. SDD §4.2 는 가상 스레드였지만 바꿨다.
    - 이유: Windows 의 pty4j 읽기는 JNA `ReadFile` 블로킹 호출이다. 네이티브 프레임에서 막히면 가상 스레드의 carrier 가 고정(pinning)되고 JDK 가 보충하지 않는다.
    - 그래서 세션 수가 코어 수에 이르면 다른 가상 스레드가 굶을 수 있다.
    - 세션은 많아야 수십 개라 플랫폼 스레드의 비용은 문제가 없다.
  - 조각(`byte[]`)은 구독자마다 새로 복사해 준다. 받은 쪽이 보관하거나 고쳐도 된다.
  - 구독자가 `RuntimeException` 을 던지면 그 구독자만 해제하고 경고를 남긴다. 출력 내용은 로그에 남기지 않는다. 다른 구독자와 읽기는 계속된다.
  - 구독자가 오래 막히면 그만큼 셸 출력도 멈춘다. 이것을 배압(back-pressure)으로 받아들인다.
- `onExit()` 는 `CompletionStage<Integer>` 다.
  - 정확히 한 번 완료되고, 예외로 끝나지 않는다.
  - 모든 조각이 모든 구독자에게 전달된 **뒤에** 완료된다(happens-before).
  - `close()` 로 끝나도 완료된다. 이때 종료 코드는 의미가 없다(Windows 는 1).
- `write` · `resize` · `close` 는 어느 스레드에서 불러도 된다. 끝난 뒤에 부르면 아무 일도 하지 않는다.
  - `close()` 는 기다리지 않고 바로 돌아온다. 정리는 따로 진행한다: 자식 프로세스 목록을 먼저 잡아 두고 → 루트를 종료하고 → 정해진 시간 안에 끝나지 않은 자식을 강제 종료한다.
- 바이트는 UTF-8 로 본다. ConPTY 는 콘솔 코드 페이지(949)와 상관없이 UTF-8 을 준다(M0, ADR-0001). 그래서 `chcp 65001` 을 쓰지 않는다.
- 환경 변수는 앱의 환경을 물려받고, 그 위에 `TERM=xterm-256color` 와 `LaunchCommand.environment` 를 덮어쓴다.

## 결과
- 소비자를 더할 때 포트를 바꾸지 않는다(구독만 하나 더 붙인다).
- UI 는 `TerminalStream` 을 JediTerm `TtyConnector` 로 감싼다. 이때 push → pull 큐가 필요하다(#45). UI 는 pty4j 를 모른다. ArchUnit 규칙 "`com.pty4j..` 는 `adapter.pty` 만 쓴다"로 강제한다.
- WSL 의 작업 디렉터리는 `--cd <리눅스 경로>` 로 넘긴다.
  - 경로가 없으면 기동 오류가 나지 않는다. wsl.exe 2.5 는 `chdir(<경로>) failed` 를 출력하고 `/` 에서 셸을 띄운다(종료 코드 0, 테스트 L3).
  - 그래서 미리 확인하는 일은 복원(M5)에서 꼭 필요하다(경로 변환 `paths()` 사용).
- Fake `ScriptedPtyLauncher` 와 `Pty4jLauncher` 는 같은 추상 계약 테스트 `PtyLauncherContract` 를 상속한다.
- 구현에서 확인한 것(#43, Windows 11 · pty4j 0.14 번들 ConPTY)
  - 크기: 초기 90×25 가 그대로 보이고, `resize(100, 30)` 뒤 PowerShell 이 100×30 을 본다(W3 · W4).
  - 프로세스 트리: cmd 아래 `ping` 을 띄운 상태에서 `close()` 하면 5초 안에 루트와 자손이 모두 끝난다(W7).
    - `close()` 는 자손 목록 잡기와 `destroy()` 까지만 동기로 하고 바로 돌아온다. 3초 대기와 남은 프로세스 강제 종료는 별도 스레드가 한다. 루트도 살아 있으면 강제 종료한다.
  - 한글: 코드 페이지를 바꾸지 않아도 UTF-8 로 온다(W8). 한글 · 공백 · `#` 가 든 작업 디렉터리와 WSL `--cd` 경로도 그대로 전달된다(W9 · L2).
  - **ConPTY 시작 지연**: 번들 OpenConsole 이 시작하자마자 DA1 질의(`ESC[c`)를 보낸다. 아무도 답하지 않으면 약 3초 동안 입력 처리와 종료가 멈춘다. 답하면 약 40ms 다.
    - 어댑터는 답하지 않는다(에뮬레이터의 일). JediTerm 이 답하는지 #46 에서 확인한다.
    - 테스트가 세션마다 3초쯤 걸리는 이유가 이것이다.
  - ConPTY 출력 앞에는 `ESC[1t ESC[c ESC[?1004h ESC[?9001h` 같은 제어 시퀀스가 붙는다. 소비자(CwdTracker 등)는 이를 무시해야 한다.
  - jlink 런타임에 java.logging 이 들어 있다. java.sql 이 transitive 로 끌어오고, slf4j-jdk14 가 그쪽으로 로그를 보낸다.
