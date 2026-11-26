plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    implementation(libs.jackson.databind)

    // ConfigRepositoryContract(application testFixtures)를 상속한다
    testImplementation(testFixtures(project(":application")))
}
