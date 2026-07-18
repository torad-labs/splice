plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-anthropic-passthrough"))
    testImplementation(libs.kotlinx.coroutines.test)
}
