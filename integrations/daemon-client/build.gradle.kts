plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // API, not implementation: the public surface names core's Topology, EnvReader and TerminalOutput.
    api(project(":core"))
    // DaemonSettings resolves the control port from splice.toml the way the daemon does.
    implementation(project(":integrations-topology"))
}
