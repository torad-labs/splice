plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-claude-code"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
