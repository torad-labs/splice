plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // :client -> :core ONLY (restructure plan §2.3, and the module law's `":client" to setOf(":core")`
    // row that makes any other edge a configuration-time build error). kotlinx-serialization arrives
    // through :core's `api(libs.kotlinx.serialization.json)` — the client reads Claude Code's own
    // JSON state, which is the same vocabulary :core already exports.
    implementation(project(":core"))
}
