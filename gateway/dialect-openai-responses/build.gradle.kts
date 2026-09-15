plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-spi"))
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(project(":provider-spi"))
}
