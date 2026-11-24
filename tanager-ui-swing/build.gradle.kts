plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":tanager-application"))
    implementation(libs.flatlaf)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
}
