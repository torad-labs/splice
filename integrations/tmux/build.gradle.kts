plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // api: this module's whole public surface is core's session-terminal contract, implemented. A caller holds a
    // SessionTerminal and a SessionPane, never a type of this module's own, which is what makes it one deletion.
    api(project(":core"))
}
