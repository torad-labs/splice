plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-upstream"))
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(project(":integrations-upstream"))
}
