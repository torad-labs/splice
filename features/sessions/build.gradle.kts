plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    api(project(":core"))
    api(project(":integrations-http"))
    // `splice sessions` reads splice.toml's head ports to name each session's head; never materializes it.
    implementation(project(":integrations-topology"))
}
