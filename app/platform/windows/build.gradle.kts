plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    // ShellCatalogContract(application testFixtures)를 상속한다
    testImplementation(testFixtures(project(":application")))
}
