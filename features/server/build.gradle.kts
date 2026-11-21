// Server mode (docs/specs/server-mode.md): the listener that makes this machine a team's front door, the per-person
// keys it answers, and what each person may hold at once. app wires it; nothing else depends on this module.
plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-http"))
    implementation(project(":features-share"))
    // a kept-alive turn's silences are waited out on the runtime's Waiter seam (ServerMode's constructor names it).
    implementation(project(":integrations-upstream"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // a telemetry relay (F8) stamps OTLP requests in protobuf field by field, with protobuf-java's own reader.
    implementation(libs.protobuf.java)
    // A test stands in for a head, whose contract (SharedHead) names features-launch's LaunchSpec.
    testImplementation(project(":features-launch"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.platform.launcher)
}
