// LAN share (docs/specs/lan-share.md): the listener that offers this machine's heads to the person's other machines,
// and the client side that finds, joins and relays to them. app wires both; nothing else depends on this module.
plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integrations-http"))
    implementation(project(":integrations-upstream"))
    implementation(project(":features-turns"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host) {
        // As in features/turns: the test host drags in ktor-client-apache5, an engine no test here uses.
        exclude(group = "io.ktor", module = "ktor-client-apache5")
    }
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.platform.launcher)
}
