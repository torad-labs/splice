plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // API: `splice upgrade`'s public surface names core's TerminalOutput and EnvReader.
    api(project(":core"))
    api(libs.ktor.server.core)
    // The upgrade verb asks the local daemon for its in-flight turns and version, and resolves the
    // control port and supervisor unit the daemon itself resolves.
    implementation(project(":integrations-daemon-client"))
    // Where install put the jar and the launch shim: launch's InstallLayout, the one both installers
    // agree on, so an upgrade repoints the files `splice install` wrote.
    implementation(project(":features-launch"))
    implementation(libs.kotlinx.serialization.json)
}
