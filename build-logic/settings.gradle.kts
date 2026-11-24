// 공통 빌드 설정(convention plugin)을 담는 포함 빌드. 버전은 루트 카탈로그를 같이 쓴다.
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"

// 리스크 R8: 저장소가 /mnt/c 에 있어 WSL 과 Windows 의 Gradle 이 같은 작업 트리를 쓴다.
// WSL 안에서는 빌드 산출물을 build/wsl/ 로 보내 Windows 쪽 build/ 와 섞이지 않게 한다(Windows 경로는 그대로).
if (System.getenv("WSL_DISTRO_NAME") != null) {
    gradle.beforeProject { layout.buildDirectory = layout.projectDirectory.dir("build/wsl") }
}
