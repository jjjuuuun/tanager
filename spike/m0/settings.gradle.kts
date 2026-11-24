// M0 스파이크 — 병합하지 않는 실험 프로젝트 (이슈 #10). 결론은 docs/adr/0001 · 0003 에 남긴다.
plugins {
    // Java 25 toolchain 이 없으면 자동으로 내려받는다(설치 없이 빌드 가능한지 확인 — 확인 항목 e)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tanager-spike-m0"
