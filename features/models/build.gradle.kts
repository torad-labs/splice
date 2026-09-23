plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
}
