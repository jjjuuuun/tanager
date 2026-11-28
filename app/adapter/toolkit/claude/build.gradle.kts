plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}
