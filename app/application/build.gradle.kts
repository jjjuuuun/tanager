plugins {
    id("tanager.coverage")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))

    // 포트의 Fake 와 계약 테스트(어댑터 모듈이 상속한다) — testing.md
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj)
    testFixturesImplementation(libs.awaitility)
    testImplementation(libs.awaitility)
}
