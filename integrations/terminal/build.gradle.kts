plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // implementation, not api: the public surface is this module's own widgets and seams; core's
    // colours and Cancellables are used inside it and named by no public signature.
    implementation(project(":core"))
}
