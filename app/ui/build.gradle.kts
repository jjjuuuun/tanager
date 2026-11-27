plugins { id("tanager.java-conventions") }

dependencies {
    // ScriptedTerminalStream 등 포트 Fake(#46 터미널 브리지 테스트)
    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.awaitility)
    implementation(project(":application"))
    implementation(libs.flatlaf)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
}

// 테마 · 글꼴 테스트는 화면 없이 돈다(CI 리눅스 · WSL). FlatLaf getDefaults() 와 Font.createFont 는 headless 에서도 동작한다
// 화면 글자는 표시 언어로 고른다(#136). 테스트 결과가 PC · CI 의 언어에 따라 달라지지 않게 한국어로 고정하고, 영어는 Messages.of 로 따로 검사한다
tasks.withType<Test>().configureEach {
    jvmArgs("-Djava.awt.headless=true", "-Duser.language=ko", "-Duser.country=KR")
}
