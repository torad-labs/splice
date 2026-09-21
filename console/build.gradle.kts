// :console — the operator console, a Bun/Vite workspace with no Kotlin, as a Gradle module so the
// release packages what the build PRODUCES and never a checked-in bundle. Until the restructure
// `webui/dist/index.html` was committed and the gate compared a fresh build against it byte for
// byte; the bundle is now the output of `bundle` below, and :app's shadowJar and
// verifyReleaseCompliance read it through this task's output provider, so the dependency is
// carried by Gradle rather than by a comment. `bunx --bun vite build` is the plan's P13: Vite runs
// under Bun, the bundler does not change. The `base` plugin supplies the lifecycle tasks, so
// `:console:build` and `:console:check` both reach the bundle and `gradle clean check` builds it.
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

/** The inputs every Bun-side task reads: the sources, the tests, the config files, and the ROOT
 *  bun.lock because the workspace's dependencies resolve there. */
fun Exec.consoleInputs(vararg trees: String) {
    inputs.files(trees.map { fileTree(it) })
        .withPropertyName("consoleTrees")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file("index.html"),
        layout.projectDirectory.file("vite.config.ts"),
        layout.projectDirectory.file("tsconfig.json"),
        layout.projectDirectory.file("eslint.config.mjs"),
        layout.projectDirectory.file("package.json"),
        rootProject.layout.projectDirectory.file("bun.lock"),
    ).withPropertyName("consoleConfig").withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = projectDir
    doFirst {
        // FAIL BY NAME, never a silent skip: a release without a console is not a release.
        check(onPath("bunx") != null) {
            "bun is not on PATH, so the console cannot be built or checked. Install Bun 1.4.2 (the " +
                "packageManager pin in package.json; CONTRIBUTING.md) and run `bun install " +
                "--frozen-lockfile` at the repository root."
        }
        check(rootProject.file("node_modules").isDirectory) {
            "node_modules is missing at the repository root — run `bun install --frozen-lockfile` " +
                "before building the console."
        }
    }
}

// The workspace's own `build` script is `tsc --noEmit && vite build`; the Gradle build keeps BOTH
// halves (astra's PR 5 audit, webui/package.json:8): the bundle depends on the typecheck.
val consoleTypecheck = tasks.register<Exec>("typecheck") {
    group = "verification"
    description = "tsc --noEmit over the console's sources and tests."
    consoleInputs("src", "tests")
    outputs.upToDateWhen { true }
    commandLine("bunx", "tsc", "--noEmit")
}

val consoleBundle = tasks.register<Exec>("bundle") {
    group = "build"
    description = "Builds the console bundle (dist/index.html) with `bunx --bun vite build`."
    dependsOn(consoleTypecheck)
    consoleInputs("src")
    outputs.file(bundle)
    commandLine("bunx", "--bun", "vite", "build")
}

// The lint and the vitest suite as tasks too (`:console:lint`, `:console:test`), each with both
// directories as inputs. The gate ladder runs them as its own legs (checks/gate.sh), so they are
// NOT wired into `check` — the gradle tier would otherwise run each twice per gate.
tasks.register<Exec>("lint") {
    group = "verification"
    description = "eslint over src and tests — the FSD boundaries are lint-enforced."
    consoleInputs("src", "tests")
    outputs.upToDateWhen { true }
    commandLine("bunx", "eslint", "src", "tests")
}
tasks.register<Exec>("test") {
    group = "verification"
    description = "vitest run."
    consoleInputs("src", "tests")
    // Never up-to-date: the tests also read .dev/campaigns/web-console/CONTRACTS.md, FEATURES.md and
    // the base branch's token sheet through git (world.test.ts), which no input tree here can name.
    outputs.upToDateWhen { false }
    commandLine("bunx", "vitest", "run")
}

// `:console:build` (base's lifecycle) and `:console:check` both produce the bundle, so the
// root `gradle clean check` — the gate's gradle tier — typechecks and builds the console on
// every run.
tasks.named("assemble") { dependsOn(consoleBundle) }
tasks.named("check") { dependsOn(consoleBundle) }
