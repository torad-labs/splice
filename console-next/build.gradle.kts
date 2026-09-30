// :console-next — the replacement operator console (V4-444), a Bun/Vite workspace with no Kotlin, built beside
// :console and gated the same way: typecheck, bundle, lint and the vitest suite are tasks of this module, and
// `gateOfRecord` also runs its real-daemon browser suite. After parity, :app reads this module's
// bundle output for the shipped dashboard.
plugins {
    base
}

/** The bundle vite writes: one self-contained HTML file (vite-plugin-singlefile). */
val bundle: RegularFile = layout.projectDirectory.file("dist/index.html")

/** `name` on this machine's PATH, or null — resolved at execution time, never assumed. */
fun onPath(name: String): File? =
    (System.getenv("PATH") ?: "").split(File.pathSeparator)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }

/** The inputs every Bun-side task reads: the sources, the tests, the config files, and the ROOT bun.lock
 *  because the workspace's dependencies resolve there. */
fun Exec.consoleInputs(vararg trees: String) {
    inputs.files(trees.map { fileTree(it) })
        .withPropertyName("consoleNextTrees")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file("index.html"),
        layout.projectDirectory.file("vite.config.ts"),
        layout.projectDirectory.file("tsconfig.json"),
        layout.projectDirectory.file("eslint.config.mjs"),
        layout.projectDirectory.file("package.json"),
        rootProject.layout.projectDirectory.file("bun.lock"),
    ).withPropertyName("consoleNextConfig").withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = projectDir
    doFirst {
        // FAIL BY NAME, never a silent skip.
        check(onPath("bunx") != null) {
            "bun is not on PATH, so the console cannot be built or checked. Install Bun 1.4.2 (the " +
                "packageManager pin in package.json; .github/CONTRIBUTING.md) and run `bun install " +
                "--frozen-lockfile` at the repository root."
        }
        check(rootProject.file("node_modules").isDirectory) {
            "node_modules is missing at the repository root — run `bun install --frozen-lockfile` " +
                "before building the console."
        }
    }
}

val typecheck = tasks.register<Exec>("typecheck") {
    group = "verification"
    description = "tsc --noEmit over the replacement console's sources, unit tests and browser suite."
    consoleInputs("src", "tests", "e2e")
    outputs.upToDateWhen { true }
    commandLine("bunx", "tsc", "--noEmit")
}

val consoleBundle = tasks.register<Exec>("bundle") {
    group = "build"
    description = "Builds the replacement console bundle (dist/index.html) with `bunx --bun vite build`."
    dependsOn(typecheck)
    consoleInputs("src")
    outputs.file(bundle)
    commandLine("bunx", "--bun", "vite", "build")
}

tasks.register<Exec>("lint") {
    group = "verification"
    description = "eslint over src, tests and e2e: the production layers and network boundary are lint-enforced."
    consoleInputs("src", "tests", "e2e")
    outputs.upToDateWhen { true }
    commandLine("bunx", "eslint", "src", "tests", "e2e")
}
tasks.register<Exec>("test") {
    group = "verification"
    description = "vitest run."
    consoleInputs("src", "tests")
    // Never up-to-date: the coverage wall reads the daemon's Kotlin sources, the CLI verb table, the knob
    // enum and FEATURES.md through git, which no input tree here can name.
    outputs.upToDateWhen { false }
    commandLine("bunx", "vitest", "run")
}

tasks.register<Exec>("e2e") {
    group = "verification"
    description = "Playwright over the built console and isolated real daemon, including a throwing-page canary."
    dependsOn(consoleBundle, "lint", ":app:shadowJar")
    consoleInputs("e2e")
    inputs.file(bundle)
    inputs.dir(rootProject.file("console/e2e"))
    inputs.file(rootProject.file("app/build/libs/app-all.jar"))
    outputs.upToDateWhen { false }
    // Judge the shipped HTML, even when a developer shell selects a local build.
    environment.remove("CONSOLE_E2E_HTML")
    environment("CONSOLE_E2E_BUNDLE", "jar")
    environment("CONSOLE_E2E_JAR", rootProject.file("app/build/libs/app-all.jar").absolutePath)
    commandLine("bun", "e2e/run.ts")
}

// `check` reaches the bundle so a bundle that stops building is red before the switch, not at it.
tasks.named("assemble") { dependsOn(consoleBundle) }
tasks.named("check") { dependsOn(consoleBundle) }
