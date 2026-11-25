plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":tanager-application"))
    implementation(libs.jackson.databind)
}
