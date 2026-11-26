plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    implementation(libs.pty4j)
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.awaitility)
}
