pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Java 25 toolchain 이 없으면 자동으로 내려받는다(M0 확인)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // JediTerm 은 Maven Central 에 없다(ADR-0001)
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

rootProject.name = "tanager"

// 의존 방향: domain ← application ← (adapter-* · platform-* · ui-swing) ← app   (ADR-0002, ArchUnit 이 검사)
include(
    "tanager-domain",
    "tanager-application",
    "tanager-adapter-pty",
    "tanager-adapter-persistence",
    "tanager-adapter-toolkit-claude",
    "tanager-platform-windows",
    "tanager-platform-macos",
    "tanager-ui-swing",
    "tanager-app",
    "tanager-architecture-test",
)

// 리스크 R8: 저장소가 /mnt/c 에 있어 WSL 과 Windows 의 Gradle 이 같은 작업 트리를 쓴다.
// WSL 안에서는 빌드 산출물을 build/wsl/ 로 보내 Windows 쪽 build/ 와 섞이지 않게 한다(Windows 경로는 그대로).
if (System.getenv("WSL_DISTRO_NAME") != null) {
    gradle.beforeProject { layout.buildDirectory = layout.projectDirectory.dir("build/wsl") }
}
