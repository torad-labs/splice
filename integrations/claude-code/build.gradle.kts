plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    // Client mechanics stay on core; the transcript reader implements the narrow contract owned by
    // sessions without pulling the feature back toward Claude Code's on-disk format.
    implementation(project(":core"))
    api(project(":features-sessions"))
}
