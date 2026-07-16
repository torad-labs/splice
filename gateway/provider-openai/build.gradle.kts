plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    implementation(project(":dialect-openai-responses"))
    implementation(project(":dialect-openai-chat"))
    testImplementation(libs.kotlinx.coroutines.test)
}
