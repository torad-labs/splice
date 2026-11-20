// NEW: the convention plugin that makes a mode's module (modes/README.md). A mode is one folder, modes/<name>, whose
// build.gradle.kts is the one line `plugins { id("splice.mode") }`; settings.gradle.kts includes it as :modes-<name>
// because the folder holds a mode.toml, and :app depends on it by the same listing.
//
// The mode's own files ride its jar as resources under a path of its own, splice-modes/<name>/..., so the shadow jar
// never merges two modes' files, and the catalog (:modes-api) reads each mode from there. The manifest's handler
// becomes the mode's java.util.ServiceLoader entry. Nothing here checks the manifest: a broken one still builds, and
// the catalog names what is wrong, in the mode's own tests and at boot.
import splice.modes.ModeServiceFiles

plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    "api"(project(":modes-api"))
}

val modeName: String = project.projectDir.name
val modeFolder = layout.projectDirectory

val modeFiles = tasks.register<Sync>("modeFiles") {
    description = "Carries the mode's manifest, prompt, tools and resources under splice-modes/$modeName."
    from(modeFolder) {
        include("mode.toml", "prompt.md", "tools/**", "resources/**")
        into("splice-modes/$modeName")
    }
    into(layout.buildDirectory.dir("generated/splice-mode/files"))
}

val modeService = tasks.register<ModeServiceFiles>("modeService") {
    description = "Writes the mode's ServiceLoader entry from its manifest's handler, and its line in the mode index."
    manifest.set(modeFolder.file("mode.toml"))
    folder.set(modeName)
    output.set(layout.buildDirectory.dir("generated/splice-mode/service"))
}

the<SourceSetContainer>().named("main") {
    resources.srcDir(modeFiles)
    resources.srcDir(modeService)
}
