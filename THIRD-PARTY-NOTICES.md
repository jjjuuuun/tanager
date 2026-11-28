# 제3자 고지 (Third-Party Notices)

Tanager(MIT, `LICENSE.txt`)는 아래 제3자 구성 요소를 포함해 배포한다. 각 구성 요소는 표시한 라이선스(SPDX ID)를 따른다.
라이선스 전문과 각 구성 요소의 NOTICE 는 `licenses/` 폴더에 있다 — 저장소에서는 루트의 `licenses/`, 실행 파일 폴더(app-image · zip)와 MSI 설치 폴더에서는 `Tanager.exe` 옆의 `licenses/` 다.
전문 파일은 출처의 원문을 그대로 옮겼다(줄바꿈만 LF 로 맞추고, 끝 줄바꿈이 없던 파일에는 하나를 더했다).

이 문서에 적힌 `licenses/…` 파일과 jar 이름은 `:bootstrap:verifyAppImage` 가 실행 파일 폴더와 대조한다. 표에 있는 파일이 이미지에 없거나, 이미지의 제3자 jar · `licenses/` 파일이 표에 없으면 빌드가 실패한다. 의존성을 바꾸면 이 문서를 같은 PR 에서 고친다.

## 글꼴

### D2Coding 1.4.0 (Regular · Bold)
- 라이선스: `OFL-1.1` (SIL Open Font License 1.1) — 전문 `licenses/D2Coding-OFL.txt`
- 저작권: Copyright (c) 2015, NAVER Corporation (https://www.navercorp.com), with Reserved Font Names D2Coding, D2Coding-Bold.
- 출처: https://github.com/naver/d2-coding-font (릴리스 `VER1.4.0`, `D2Coding/` 표준 빌드 TTF 두 개를 수정 없이 포함)
- 번들 위치: 소스 `app/ui/src/main/resources/fonts/`(`tanager-ui` jar 안 `fonts/`), 라이선스 전문 `fonts/OFL.txt`.
  실행 파일 폴더와 MSI 설치 폴더에는 빌드가 이 파일을 `licenses/D2Coding-OFL.txt` 로 복사해 넣는다(원본은 저장소에 하나, ADR-0010).
- 무결성: `fonts/SHA256SUMS`, 결정 기록 `docs/adr/0010-soft-theme-tokens-and-d2coding-bundle.md`.

## 라이브러리 (실행 클래스패스)

| 구성 요소 | jar | 라이선스(SPDX) | 전문 · NOTICE |
|---|---|---|---|
| FlatLaf 3.8 (`com.formdev:flatlaf`) | `flatlaf-3.8.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` |
| JediTerm 3.76 (`org.jetbrains.jediterm`) | `jediterm-core-3.76.jar` · `jediterm-ui-3.76.jar` | `LGPL-3.0-only OR Apache-2.0` → **Apache-2.0 선택** | `licenses/Apache-2.0-jediterm.txt` · `licenses/NOTICE-jVT220.txt` |
| pty4j 0.14.0 (`org.jetbrains.pty4j:pty4j`) | `pty4j-0.14.0.jar` | `EPL-1.0`. 일부 코드는 JPty 유래 `Apache-2.0`. 번들 네이티브: winpty `MIT`, ConPTY `MIT` | `licenses/EPL-1.0.txt` · `licenses/NOTICE-pty4j.txt` · `licenses/Apache-2.0.txt` · `licenses/MIT-winpty.txt` · `licenses/MIT-microsoft-terminal.txt` · `licenses/NOTICE-microsoft-terminal.txt` |
| JNA 5.19.1 (`net.java.dev.jna`) | `jna-5.19.1.jar` · `jna-platform-5.19.1.jar` | `Apache-2.0 OR LGPL-2.1-or-later` → **Apache-2.0 선택**. 번들 네이티브의 libffi `MIT` | `licenses/Apache-2.0.txt` · `licenses/MIT-libffi.txt` |
| Jackson core 3.3.0 (`tools.jackson.core:jackson-core`) | `jackson-core-3.3.0.jar` | `Apache-2.0`. 포함 코드: FastDoubleParser `MIT`(그 안에 `BSL-1.0` · `MIT` · `BSD-2-Clause`), jsoniter-scala `MIT`, xjb `Apache-2.0` | `licenses/Apache-2.0.txt` · `licenses/NOTICE-jackson-core.txt` · `licenses/MIT-FastDoubleParser.txt` · `licenses/FastDoubleParser-ThirdParty.txt` · `licenses/MIT-jsoniter-scala.txt` · `licenses/Apache-2.0-xjb.txt` |
| Jackson databind 3.3.0 (`tools.jackson.core:jackson-databind`) | `jackson-databind-3.3.0.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` · `licenses/NOTICE-jackson-databind.txt` |
| Jackson dataformat YAML 3.3.0 (`tools.jackson.dataformat:jackson-dataformat-yaml`) | `jackson-dataformat-yaml-3.3.0.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` · `licenses/NOTICE-jackson-dataformat-yaml.txt` |
| SnakeYAML Engine 3.0.1 (`org.snakeyaml:snakeyaml-engine`) | `snakeyaml-engine-3.0.1.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` |
| Jackson annotations 2.23 (`com.fasterxml.jackson.core:jackson-annotations`) | `jackson-annotations-2.23.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` · `licenses/NOTICE-jackson-annotations.txt` |
| SLF4J 2.0.19 (`org.slf4j`) | `slf4j-api-2.0.19.jar` · `slf4j-jdk14-2.0.19.jar` | `MIT` | `licenses/MIT-slf4j.txt` |
| Kotlin 표준 라이브러리 2.4.20 (`org.jetbrains.kotlin:kotlin-stdlib`) | `kotlin-stdlib-2.4.20.jar` | `Apache-2.0`. 포함 코드: ThreeTen backport 유래 `BSD-3-Clause`, Boost 유래 `BSL-1.0`, GWT · Guava 유래 `Apache-2.0` | `licenses/Apache-2.0.txt` · `licenses/NOTICE-kotlin.txt` · `licenses/BSD-3-Clause-threetenbp.txt` · `licenses/BSL-1.0-boost.txt` |
| JetBrains Java Annotations 26.1.0 (`org.jetbrains:annotations`) | `annotations-26.1.0.jar` | `Apache-2.0` | `licenses/Apache-2.0.txt` |

`licenses/Apache-2.0.txt` 는 Apache License 2.0 표준 전문이다(jackson-core jar 의 `META-INF/LICENSE` 에서 가져왔고 apache.org 의 `LICENSE-2.0.txt` · Kotlin 저장소 `license/LICENSE.txt` 와 같다).
Tanager 는 위 jar 를 수정하지 않고 그대로 함께 배포한다.

### 이중 라이선스 선택
- **JediTerm** 은 LGPL-3.0 과 Apache-2.0 이중 라이선스다(저장소 README "Licenses", Maven POM 에는 LGPL 3.0 만 적혀 있다). Tanager 는 **Apache-2.0** 을 고른다. JediTerm 의 Apache-2.0 전문 끝에는 "Runtime Library Exception" 이 붙어 있어 표준 전문과 따로 둔다(`licenses/Apache-2.0-jediterm.txt`).
- **JNA** 는 `Apache-2.0 OR LGPL-2.1-or-later` 다(jar `META-INF/LICENSE`). Tanager 는 **Apache-2.0** 을 고른다. jar 의 `META-INF/AL2.0` 은 표준 전문에서 끝의 APPENDIX 만 빠진 같은 조항이다.

### 저작권과 출처
각 줄 끝의 괄호는 확인한 곳이다. "jar" 는 배포되는 jar 파일 안, "저장소" 는 프로젝트 공식 GitHub 저장소다.

- **FlatLaf** — Copyright (C) 2019-2026 FormDev Software. All rights reserved. (jar `META-INF/MANIFEST.MF` 의 `Implementation-Copyright`). 소스 헤더는 Copyright 2019 FormDev Software GmbH (저장소 `JFormDesigner/FlatLaf` 태그 `3.8`). 전문: jar `META-INF/LICENSE`(표준 전문과 같음).
- **JediTerm** — Copyright 2000-2012 JetBrains s.r.o. (저장소 `JetBrains/jediterm` 소스 헤더, 예 `core/src/com/jediterm/terminal/util/Pair.java`. jar · POM 에는 저작권 줄이 없다). 전문: 저장소 `LICENSE-APACHE-2.0.txt`(태그가 없어 기본 브랜치, 마지막 변경 커밋 `081b5194`).
  - 문자 집합 지정 · 매핑 구현은 jVT220(`Apache-2.0`)을 바탕으로 한다(저장소 README) — (C) Copyright 2012 - J.W. Janssen <j.w.janssen@lxtreme.nl>. NOTICE: 저장소 `jawi/jVT220` 의 `NOTICE`.
- **pty4j** — jar 에 라이선스 파일이 없다(POM: Eclipse Public License 1.0). 전문 · NOTICE: 저장소 `JetBrains/pty4j` 의 `LICENSE` · `NOTICE`(태그가 없어 0.14.0 버전 커밋 `7dd1378d16` 기준, 기본 브랜치와 같음).
  - **소스 제공(EPL-1.0 §3)**: Tanager 에 들어간 pty4j 0.14.0 의 소스는 https://github.com/JetBrains/pty4j (커밋 `7dd1378d16`)에서 받을 수 있다. Tanager 는 pty4j 를 수정하지 않았다.
  - Copyright 2000-2012 JetBrains s.r.o. (소스 헤더 `src/com/pty4j/util/Pair.java`)
  - Copyright (c) 2002, 2010 QNX Software Systems and others. — Eclipse elt 에서 온 파일(소스 헤더 `src/com/pty4j/unix/Pty.java` · `native/openpty.c`. 연도는 파일마다 다르다: `native/exec_pty.c` 2004, 2010 · `WinPTYInputStream.java` 2000, 2011)
  - Copyright (c) 2012 - J.W. Janssen — JPty(`Apache-2.0`) 유래 부분(저장소 `NOTICE`)
  - 번들 `winpty.dll` · `winpty-agent.exe`: winpty — Copyright (c) 2011-2016 Ryan Prichard (저장소 `rprichard/winpty` 의 `LICENSE`, JetBrains 포크 `JetBrains/winpty` 와 같음)
  - 번들 `conpty.dll` · `OpenConsole.exe`: Windows Terminal ConPTY v1.24.11911.0 — Copyright (c) Microsoft Corporation. All rights reserved. (저장소 `microsoft/terminal` 태그 `v1.24.11911.0` 의 `LICENSE` · `NOTICE.md`. 버전은 pty4j 커밋 "update bundled ConPTY to v1.24.11911.0")
  - 번들 `win-helper.dll` · `cyglaunch.exe` · `libpty.*` · `pty4j-unix-spawn-helper` 는 pty4j 자체 코드(`EPL-1.0`)
- **JNA** — Copyright (c) 2007-2015 Timothy Wall, All Rights Reserved (저장소 `java-native-access/jna` 태그 `5.19.1` 소스 헤더 `src/com/sun/jna/Native.java`). 네이티브 `native/dispatch.c` 에는 Copyright (c) 1998 Sun Microsystems, Inc. · Copyright (c) 2007-2013 Timothy Wall · Copyright (c) 2007 Wayne Meissner. jar 의 `META-INF/LICENSE` 는 이중 라이선스 안내뿐이고 저작권 줄이 없다.
  - 네이티브 `jnidispatch` 에 정적 링크된 libffi — libffi - Copyright (c) 1996-2022 Anthony Green, Red Hat, Inc and others. (저장소 태그 `5.19.1` 의 `native/libffi/LICENSE`. 5.19.1 이후 저장소에 생긴 `NOTICE` 와 같은 내용)
- **Jackson core · databind · annotations · dataformat YAML** — Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi) (각 jar `META-INF/NOTICE`). 전문 · NOTICE 는 모두 jar `META-INF/` 에서 가져왔다. dataformat YAML 의 jar `META-INF/LICENSE` 는 Apache-2.0 안내문뿐이라 전문은 표준 전문을 쓴다.
  - jackson-core 에 포함된 FastDoubleParser — Copyright (c) 2024 Werner Randelshofer, Switzerland. (jar `META-INF/FastDoubleParser-LICENSE`. NOTICE 에는 © 2023). 그 안의 fast_double_parser(Copyright (c) 2022 Daniel Lemire, `BSL-1.0`) · fast_float(Copyright (c) 2021 The fast_float authors, `MIT`) · bigint(Copyright 2022 Tim Buktu, `BSD-2-Clause`) 는 jar `META-INF/FastDoubleParser-ThirdParty-LICENSE`
  - jsoniter-scala — Copyright (c) 2017-2026 Andriy Plokhotnyuk, and respective contributors (jar `META-INF/jsoniter-scala-LICENSE`)
  - xjb — Copyright 2026 xjb714 and contributors (jar `META-INF/xjb-LICENSE`)
- **SnakeYAML Engine** — Copyright (c) 2018, SnakeYAML (Maven Central 의 3.0.1 소스 jar(sources) 소스 헤더, 예 `org/snakeyaml/engine/v2/api/Load.java`). jar 에는 라이선스 · NOTICE 파일이 없다(POM: Apache License, Version 2.0). 전문: 저장소 `bitbucket.org/snakeyaml/snakeyaml-engine` 태그 `snakeyaml-engine-3.0.1` 의 `LICENSE.txt` — 표준 전문에서 끝의 APPENDIX 만 빠진 같은 조항이다. 저장소에 NOTICE 파일은 없다.
- **SLF4J** — Copyright (c) 2004-2023 QOS.ch (두 jar `META-INF/LICENSE.txt`, 내용이 같음)
- **Kotlin 표준 라이브러리** — Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors. (저장소 `JetBrains/kotlin` 태그 `v2.4.20` 의 `license/COPYRIGHT.txt`. jar 에는 라이선스 파일이 없다). NOTICE: 같은 태그의 `license/NOTICE.txt`. 표준 라이브러리에 들어간 제3자 코드(같은 태그 `license/README.md`):
  - `kotlin/collections` — GWT 유래, (C) 2007-08 Google Inc., `Apache-2.0`
  - `kotlin/time` — ThreeTen backport 유래, Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos, `BSD-3-Clause` (`license/third_party/threetenbp_license.txt`)
  - `kotlin/util/UnsignedJVM.kt` — Guava 유래, (C) 2011 The Guava Authors, `Apache-2.0`
  - `kotlin/util/MathJVM.kt` — Boost 유래, Copyright Eric Ford & Hubert Holin 2001, `BSL-1.0` (`license/third_party/boost_LICENSE.txt`)
- **JetBrains Java Annotations** — Copyright 2000-2021 JetBrains s.r.o. (저장소 `JetBrains/java-annotations` 태그 `26.1.0` 소스 헤더 `NotNull.java`. 저장소 `LICENSE.txt` 의 APPENDIX 에는 Copyright 2000-2016 JetBrains s.r.o.). jar 에는 라이선스 파일이 없다.

## 실행 환경

실행 파일 폴더에는 jlink 로 줄인 Java 런타임(Eclipse Temurin/OpenJDK 25)이 들어간다. 라이선스: `GPL-2.0-only WITH Classpath-exception-2.0`.
런타임의 라이선스 전문과 고지는 실행 파일 폴더의 `runtime/legal/`(모듈별 폴더)에 jlink 가 넣은 그대로 있다.
**소스 제공(GPLv2 §3)**: 들어간 런타임의 정확한 버전은 실행 파일 폴더의 `runtime/release`(`JAVA_VERSION`)에 있고, 그 버전의 소스는 Eclipse Adoptium 의 https://github.com/adoptium/jdk25u (같은 버전 태그 `jdk-25.0.x+y`)에서 받을 수 있다. Tanager 는 런타임을 수정하지 않고 jlink 로 모듈만 골라 넣는다. `:bootstrap:verifyAppImage` 가 이 폴더가 있는지 검사한다.
