# 0021. 시작 시간: JDK 25 AOT 캐시를 빌드 때 만들고 설치 뒤 jar 시각을 되돌린다

- 상태: 채택(#221)
- 날짜: 2026-11-28
- 관련: #221, NFR-02, SDD §11 · §12, ADR-0001 · ADR-0002

## 맥락
NFR-02 는 콜드 스타트 후 첫 창 표시 2초 이내(AppCDS 사용)다. SDD §11 은 "AppCDS 아카이브를 빌드 시 생성해 포함"이라 했지만 빌드에 CDS 설정이 없었고, jpackage 가 만든 런타임(jlink)에는 JDK 기본 CDS 아카이브(`classes.jsa`)도 없어 **CDS 가 전혀 꺼져 있었다**. 시작 시간을 재는 로그도 없었다.

- 시작 로그(#221): JVM 시작(`ProcessHandle` 시작 시각 — `java.management` 없이) → main → state.json 읽기 끝 → 셸 탐지 끝 → 첫 창(`windowOpened`) → 첫 셸 첫 바이트. 예: `startup: main=142ms state=182ms shells=235ms window=839ms firstByte=1149ms`. 값은 모두 JVM 시작부터의 누적이다.
- 훈련 실행: `Tanager.exe --startup-training`(숨은 인수)은 첫 창 · 첫 셸 첫 바이트까지 간 뒤 창에 닫기 요청을 보내 사용자가 X 를 누른 것과 같은 길로 끝난다. 셸 탐지 · 기동 · 종료 길을 실제로 탄다.

**JDK 25 에서 확인한 것**(Temurin 25.0.5, `java -XX:+PrintFlagsFinal` 에 `AOTCache` · `AOTCacheOutput` · `AOTConfiguration` · `AOTMode` · `ArchiveClassesAtExit` · `SharedArchiveFile` · `AutoCreateSharedArchive` 가 있다)
- `-XX:AOTCacheOutput`(한 번에 만들기)은 끝날 때 `runtime/bin/java` 로 자식 JVM 을 띄워 캐시를 조립한다. jpackage 기본 jlink 옵션 `--strip-native-commands` 가 그 `java` 를 지워 실패한다. jlink `--generate-cds-archive` 도 같은 이유로 실패한다.
- `-XX:AOTCache` 와 `-XX:AOTCacheOutput` 은 같이 받지 않는다("Only one of AOTCache or AOTCacheOutput can be specified"). `AOTMode=record` 도 `AOTCache` 가 있으면 거절하고, `-XX:AOTCache=`(빈 값)도 거절한다. 그래서 cfg 에 캐시 경로가 있으면 훈련 실행이 뜨지 않는다.
- 캐시가 없거나 맞지 않으면 `AOTMode=auto`(기본)는 stdout 에 `[error][aot]` 세 줄을 쓰고 캐시 없이 계속 돈다. Tanager.exe 는 콘솔이 없는 GUI 프로그램이라 사용자에게는 보이지 않는다.
- JVM 은 클래스패스 jar 의 **수정 시각을 100ns 단위로** 캐시를 만들 때와 비교해 다르면 캐시를 버린다(초 단위가 같아도 버렸다). 이미지 폴더를 옮기는 것은 괜찮다(시각을 지켜 복사하면 쓰인다). 런타임 `lib/modules` 는 시각을 보지 않는다.
- MSI · zip 은 파일 시각을 **현지 시간 2초 단위**로 담는다. CI(UTC 06:27)에서 만든 MSI 를 이 PC(KST)에서 `msiexec /a` 로 풀면 jar 시각이 `06:27:22 +0900`(실제보다 9시간 앞, 초 아래 0)이 된다. 빌드 때 만든 캐시는 설치한 앱에서 늘 버려진다.
- `-XX:+AutoCreateSharedArchive`(동적 CDS 자동 생성)는 JDK 판이 다를 때만 다시 만들고, jar 시각이 어긋난 아카이브는 다시 만들지 않았다 — 스스로 고쳐지지 않는다. 또 jlink 가 만든 기본 아카이브는 `--enable-native-access` 없이 만들어져 "optimized module handling" 이 꺼진다(직접 `-Xshare:dump --enable-native-access=ALL-UNNAMED` 로 다시 만들어야 했다).

**측정**(이 PC, Windows 11, 다른 빌드가 같이 도는 중 · CPU 37%, 임시 APPDATA · 기억한 셸 cmd, 네 길을 번갈아 7번씩, 단위 ms, 중앙값)

| 길 | main | window | firstByte |
|---|---|---|---|
| 지금(CDS 없음) | 162 | 1583 | 2076 |
| (a) AppCDS 동적 아카이브(`ArchiveClassesAtExit` → `SharedArchiveFile`, 기본 아카이브를 같은 옵션으로 다시 만듦) | 120 | 1049 | 1390 |
| (b) AOT 캐시(`AOTCacheOutput` → `AOTCache`) | 180 | **983** | **1292** |

앞선 두 번의 측정(5번씩, 부하 다름)에서도 (a) · (b) 가 지금보다 400~600ms 빨랐고 둘 사이 차이는 부하에 따라 뒤집혔다. jlink 기본 아카이브만 넣은 경우(`--generate-cds-archive`)는 지금과 차이가 없었다(1674). 재부팅 직후 콜드 측정은 하지 못했다(아래 결과).

## 선택지
1. **(a) AppCDS 동적 아카이브** — 오래된 기능, `AutoCreateSharedArchive` 가 있다 / 기본 아카이브(14MB)를 같은 실행 옵션으로 따로 만들어야 하고, 자동 생성도 jar 시각 어긋남은 고치지 않는다. 합쳐 약 44MB.
2. **(b) JDK 25 AOT 캐시** — 파일 하나(약 47MB, 압축 약 12MB), 기본 아카이브가 필요 없고 클래스 링크 · 메서드 프로파일(JEP 514 · 515)까지 담는다. 이 PC 에서 가장 빨랐다 / jar 시각 문제는 (a) 와 같다.
3. 설치한 PC 에서 처음 실행할 때 만들기 — 시간대 문제가 없다 / 첫 실행이 훈련 실행이 되어 끝날 때 수 초 걸리고, cfg 의 실행 옵션은 고정이라 "없을 때만 만들기"를 JVM 옵션으로 표현할 수 없다.

## 결정
**(b) AOT 캐시를 빌드 때 만들고, 앱이 설치 뒤 밀린 jar 시각을 되돌린다.**

- `:bootstrap:jpackageImage`(Windows)가 app-image 를 만든 뒤 `Tanager.exe --startup-training` 을 임시 `APPDATA`(기억한 셸 `cmd`)와 `JAVA_TOOL_OPTIONS=-XX:AOTCacheOutput=<app>/tanager.aot` 로 한 번 띄운다(약 5초). 사용자의 `%APPDATA%\Tanager` 는 건드리지 않고, 3분 안에 끝나지 않으면 자손 프로세스까지 끝낸다.
- 캐시를 만들었을 때만 `app/Tanager.cfg` 의 `[JavaOptions]` 에 `-XX:AOTCache=$APPDIR/tanager.aot` 와 `-Dtanager.aot.stamps=$APPDIR/tanager.aot.stamps` 를 넣는다. 이슈는 `--java-options` 를 제안했지만 그러면 훈련 실행이 뜨지 않는다.
- jlink 옵션에서 `--strip-native-commands` 만 빼고(캐시 조립에 `runtime/bin/java` 가 필요), 훈련 뒤 `runtime/bin/*.exe` 를 지워 jpackage 기본 이미지와 같게 둔다. 런타임 모듈(`runtime-modules.txt`)은 그대로다.
- 빌드는 jar 마다 `<sha256> <크기> <수정 시각> <이름>` 을 `tanager.aot.stamps` 에 적는다. 앱은 시작 로그를 남긴 뒤 가상 스레드에서 **내용이 같고 시각만 밀린** jar 의 수정 시각을 되돌린다(`ClasspathStamps`). MSI · zip 으로 설치한 앱은 첫 실행만 캐시 없이 뜨고 다음부터 캐시를 쓴다. 내용이 다르면 되돌리지 않는다 — 캐시가 다른 클래스를 내놓으면 안 된다.
- 훈련 실패(데스크톱 없음 등)는 경고만 하고 캐시 없는 이미지를 남긴다. 끄기: `-PstartupTraining=false`. macOS 는 아직 훈련하지 않는다(jpackage 레이아웃이 다르다 — macOS 지원 때).
- CI package-smoke 는 MSI 를 `msiexec /a` 로 풀어 두 번 띄워, 2번째에 `-Xlog:aot` 에 "Using AOT-linked classes: true" 가 있는지 본다(`.github/scripts/check-startup-cache.ps1`). 캐시 없음 · 데스크톱 없음은 경고만 한다.

## 결과
- 이 PC 의 따뜻한 시작에서 첫 창이 약 1.6초 → 약 1.0초(목표 2초 안). 캐시를 지운 이미지도 정상으로 뜬다(느릴 뿐).
- app-image 가 99MB → 146MB, MSI · zip 은 약 12MB 늘어난다.
- 앱이 자기 설치 폴더의 jar 수정 시각을 바꾼다. 사용자 단위 MSI(`%LOCALAPPDATA%\Tanager`)와 압축을 푼 폴더는 쓸 수 있다. 쓸 수 없는 곳에 두면 로그만 남고 캐시 없이 돈다. MSI 는 수정 시각이 만든 시각보다 늦은 파일을 "사용자가 고친 파일"로 보는데, 되돌린 시각은 빌드 때라 업그레이드 · 복구가 그대로 덮어쓴다.
- 캐시는 JDK 판 · jar 내용과 묶인다. 의존성 · 코드가 바뀌면 빌드마다 다시 만든다(jpackageImage 가 늘 다시 훈련한다).
- 감수: 재부팅 직후 콜드 측정(이슈 인수 조건)은 이 작업에서 하지 못했다 — 디스크에서 47MB 캐시를 읽는 비용이 콜드에서 얼마나 되는지는 수동 확인 항목으로 남긴다. 첫 창 전의 나머지 시간(FlatLaf · 글꼴 등록 · 외관 읽기의 reg.exe 등)은 이 결정 밖이다.
