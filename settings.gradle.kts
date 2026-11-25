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

// 코드 모듈은 모두 app/ 아래에 있다(ADR-0008). 폴더 이름에 '-' 를 쓰지 않는다.
// 의존 방향: domain ← application ← (adapter · platform · ui) ← bootstrap   (ADR-0002, ArchUnit 이 검사)
val modules = listOf(
    ":domain",
    ":application",
    ":adapter:pty",
    ":adapter:persistence",
    ":adapter:toolkit:claude",
    ":platform:windows",
    ":platform:macos",
    ":ui",
    ":bootstrap",
    ":architecture",
)
for (path in modules) {
    include(path)
    project(path).projectDir = file("app/" + path.removePrefix(":").replace(':', '/'))
}
// 묶음용 상위 프로젝트(:adapter · :adapter:toolkit · :platform)도 app/ 아래 폴더를 가리키게 한다
for (group in listOf(":adapter", ":adapter:toolkit", ":platform")) {
    project(group).projectDir = file("app/" + group.removePrefix(":").replace(':', '/'))
}

// 리스크 R8: 저장소가 /mnt/c 에 있어 WSL 과 Windows 의 Gradle 이 같은 작업 트리를 쓴다.
// WSL 안에서는 빌드 산출물을 build/wsl/ 로 보내 Windows 쪽 build/ 와 섞이지 않게 한다(Windows 경로는 그대로).
if (System.getenv("WSL_DISTRO_NAME") != null) {
    gradle.beforeProject { layout.buildDirectory = layout.projectDirectory.dir("build/wsl") }
}
