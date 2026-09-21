plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // test-only: drive the suspend perf helpers; production :core stays framework-free
    testImplementation(libs.kotlinx.coroutines.test)
}
