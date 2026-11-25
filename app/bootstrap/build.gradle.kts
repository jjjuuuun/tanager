plugins { id("tanager.app-conventions") }

// 조립 루트(composition root): 유일하게 모든 모듈을 안다 (ADR-0002)
dependencies {
    implementation(project(":application"))
    implementation(project(":ui"))
    runtimeOnly(project(":adapter:pty"))
    runtimeOnly(project(":adapter:persistence"))
    runtimeOnly(project(":adapter:toolkit:claude"))
    runtimeOnly(project(":platform:windows"))
    runtimeOnly(project(":platform:macos"))
}

application {
    mainClass = "dev.tanager.bootstrap.TanagerApp"
}
