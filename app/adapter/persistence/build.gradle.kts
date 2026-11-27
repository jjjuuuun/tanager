plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    implementation(libs.jackson.databind)

    // ConfigRepositoryContract(application testFixtures)를 상속한다
    testImplementation(testFixtures(project(":application")))
    // 설정 파일 감시(WatchService)를 실제 파일로 기다린다
    testImplementation(libs.awaitility)
}
