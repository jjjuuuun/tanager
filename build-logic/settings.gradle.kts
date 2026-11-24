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
