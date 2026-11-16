// LAN share (docs/specs/lan-share.md): the listener that offers this machine's heads to the person's other machines,
// and the launch of a command another machine serves. app wires both; nothing else depends on this module.
plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-claude-code"))
    implementation(project(":integrations-http"))
    implementation(project(":features-launch"))
    // a relay that keeps its client alive waits on the runtime's Waiter seam (RelayClock's constructor names it).
    implementation(project(":integrations-upstream"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.platform.launcher)
}
