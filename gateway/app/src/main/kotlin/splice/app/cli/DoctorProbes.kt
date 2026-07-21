// NEW: doctor's environment probes — prerequisite binaries and install-integrity checks.
// Split from DoctorCommand.kt (which owns sections, rendering, and the verdict) so each file
// stays under the function-count ceiling. :app is wall-exempt for println.
package splice.app.cli

import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.config.InstallPaths
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val FIX_RELINK = "splice install --all"
private const val CHECK_WRAPPER = "wrapper"
private const val FLAG_VERSION = "--version"

private data class BinarySpec(val name: String, val versionArgs: List<String>, val missing: DoctorCheck)

private val BINARIES = listOf(
    BinarySpec(
        "claude",
        listOf(FLAG_VERSION),
        DoctorCheck(
            "claude",
            CheckStatus.FAIL,
            "Claude Code not found on PATH — splice wraps it",
            "install it: https://docs.anthropic.com/en/docs/claude-code",
        ),
    ),
    BinarySpec(
        "node",
        listOf("-v"),
        DoctorCheck(
            "node",
            CheckStatus.FAIL,
            "not found on PATH — Claude Code's runtime (Node 24)",
            "install Node 24: https://nodejs.org",
        ),
    ),
    BinarySpec(
        "python3",
        listOf(FLAG_VERSION),
        DoctorCheck(
            "python3",
            CheckStatus.FAIL,
            "not found on PATH — the launch shim parses JSON with it",
            "install python3 with your package manager",
        ),
    ),
    BinarySpec(
        "curl",
        listOf(FLAG_VERSION),
        DoctorCheck(
            "curl",
            CheckStatus.FAIL,
            "not found on PATH — the launch shim's health checks need it",
            "install curl with your package manager",
        ),
    ),
    BinarySpec(
        "bash",
        listOf(FLAG_VERSION),
        DoctorCheck(
            "bash",
            CheckStatus.FAIL,
            "not found on PATH — the launch shim is a bash script",
            "install bash with your package manager",
        ),
    ),
)

internal fun prerequisiteChecks(envReader: (String) -> String?): List<DoctorCheck> {
    val java = DoctorCheck("java", CheckStatus.OK, System.getProperty("java.version") ?: "unknown")
    // claude (~1s) and gh (up to PROBE_SECONDS of network) dominate sequential wall time — run
    // every probe concurrently; runProbes preserves this list's order regardless of finish order.
    val tasks = BINARIES.map { spec -> Callable { binaryCheck(spec, envReader) } } + Callable { ghCheck(envReader) }
    return listOf(java) + runProbes(tasks)
}

private fun binaryCheck(spec: BinarySpec, envReader: (String) -> String?): DoctorCheck {
    val found = binaryOnPath(spec.name, envReader) ?: return spec.missing
    return DoctorCheck(spec.name, CheckStatus.OK, capturedVersion(listOf(found.toString()) + spec.versionArgs))
}

// A small fixed pool bounds concurrency; invokeAll's own timeout is a last-resort safety net on
// top of each probe's internal waitFor (a probe that somehow ignores its own bound still can't
// hang doctor forever). A future left incomplete at the bound is cancelled, never thrown past here.
private fun runProbes(tasks: List<Callable<DoctorCheck>>): List<DoctorCheck> {
    val pool = Executors.newFixedThreadPool(minOf(tasks.size, PROBE_POOL_SIZE))
    return try {
        pool.invokeAll(tasks, OVERALL_BOUND_SECONDS, TimeUnit.SECONDS).map { future ->
            try {
                future.get()
            } catch (e: CancellationException) {
                DoctorCheck("probe", CheckStatus.FAIL, "probe did not complete in time (${e.message})", FIX_REDOCTOR)
            } catch (e: ExecutionException) {
                DoctorCheck("probe", CheckStatus.FAIL, "probe crashed: ${e.cause?.message}", FIX_REDOCTOR)
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

// gh matters only when installing from a GitHub Release (attestation verification); an
// unauthenticated gh aborts that install — catch it here, before it costs a download.
private fun ghCheck(envReader: (String) -> String?): DoctorCheck {
    val gh = binaryOnPath("gh", envReader)
        ?: return DoctorCheck("gh", CheckStatus.INFO, "not installed (only needed to verify release-mode installs)")
    val authed = runCatchingCancellable {
        val process = ProcessBuilder(gh.toString(), "auth", "status")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    }.getOrDefault(false)
    return if (authed) {
        DoctorCheck("gh", CheckStatus.OK, "${capturedVersion(listOf(gh.toString(), FLAG_VERSION))}, authenticated")
    } else {
        DoctorCheck(
            "gh",
            CheckStatus.WARN,
            "installed but not authenticated — release installs will abort",
            "gh auth login",
        )
    }
}

private fun binaryOnPath(name: String, envReader: (String) -> String?): Path? =
    envReader("PATH").orEmpty().split(':').asSequence()
        .filter { it.isNotEmpty() }
        .mapNotNull { safePath(it) }
        .map { it.resolve(name) }
        .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }

// PATH can carry a malformed entry (garbage bytes under a non-UTF-8 jnu.encoding); Paths.get()
// throws InvalidPathException (an IllegalArgumentException) on those — skip the entry, don't
// let it collapse the whole PATH scan.
private fun safePath(raw: String): Path? = runCatchingCancellable { Paths.get(raw) }.getOrNull()

// waitFor() runs BEFORE any read: a probed binary that blocks on its inherited stdin (or just
// hangs) must not deadlock doctor waiting on output that will never come. Only after a clean or
// forced exit do we read — the output is tiny --version text, far below the pipe buffer, so a
// post-exit read cannot deadlock.
private fun capturedVersion(command: List<String>): String = runCatchingCancellable {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        "probe timed out"
    } else {
        val line = process.inputStream.bufferedReader().use { it.readLine() ?: "" }
        // First line only, capped — `curl --version` alone would flood the row with its feature list.
        line.trim().let { if (it.length > VERSION_MAX_CHARS) it.take(VERSION_MAX_CHARS) + "…" else it }
            .ifEmpty { "present" }
    }
}.getOrDefault("present (version probe failed)")

internal fun installationChecks(topo: DoctorTopology, envReader: (String) -> String?): List<DoctorCheck> {
    val paths = InstallPaths(envReader = envReader)
    val topology = (topo as? DoctorTopology.Parsed)?.topology
    val commands = topology?.heads?.map { (k, h) -> h.claude.command ?: k }.orEmpty() + "splice"
    return listOf(jarCheck(), shimCheck(paths.shareDir.resolve("splice-launch"), envReader)) +
        commands.map { wrapperCheck(paths.binDir.resolve(it), it) } +
        pathCheck(paths.binDir, envReader)
}

private fun jarCheck(): DoctorCheck {
    val jar = AdminSupport.selfJar()
    return if (jar == null) {
        DoctorCheck("jar", CheckStatus.INFO, "running from classes (dev build), $GATEWAY_VERSION")
    } else {
        DoctorCheck("jar", CheckStatus.OK, "$GATEWAY_VERSION ($jar)")
    }
}

private fun shimCheck(shim: Path, envReader: (String) -> String?): DoctorCheck {
    if (!Files.exists(shim)) {
        return DoctorCheck(
            "shim",
            CheckStatus.FAIL,
            "launch shim missing at $shim — every wrapper needs it",
            "./install.sh from a checkout, or re-run the release installer",
        )
    }
    val installed = installedShimVersion(envReader)
    return if (installed == SHIM_VERSION) {
        DoctorCheck("shim", CheckStatus.OK, "current ($SHIM_VERSION)")
    } else {
        DoctorCheck(
            "shim",
            CheckStatus.WARN,
            "stale (installed=${installed ?: "<unmarked>"}, expected=$SHIM_VERSION)",
            "$FIX_RELINK   (or ./install.sh)",
        )
    }
}

private fun wrapperCheck(link: Path, command: String): DoctorCheck = when {
    !Files.exists(link, NOFOLLOW_LINKS) ->
        DoctorCheck(CHECK_WRAPPER, CheckStatus.FAIL, "'$command' is not linked", FIX_RELINK)
    !Files.isSymbolicLink(link) ->
        DoctorCheck(
            CHECK_WRAPPER,
            CheckStatus.WARN,
            "'$command' exists at $link but is not a splice-managed symlink",
            "move the foreign file aside, then: $FIX_RELINK",
        )
    !Files.exists(link) ->
        DoctorCheck(CHECK_WRAPPER, CheckStatus.FAIL, "'$command' is a dangling symlink (target gone)", FIX_RELINK)
    else -> DoctorCheck(CHECK_WRAPPER, CheckStatus.OK, "'$command' → ${Files.readSymbolicLink(link)}")
}

private fun pathCheck(binDir: Path, envReader: (String) -> String?): DoctorCheck {
    val onPath = envReader("PATH").orEmpty().split(':')
        .filter { it.isNotEmpty() }
        .mapNotNull { safePath(it) }
        .any { it == binDir }
    return if (onPath) {
        DoctorCheck("PATH", CheckStatus.OK, "$binDir is on PATH")
    } else {
        DoctorCheck(
            "PATH",
            CheckStatus.FAIL,
            "$binDir is not on PATH — installed commands won't resolve",
            "add to your shell rc: export PATH=\"$binDir:\$PATH\"",
        )
    }
}

internal const val PROBE_SECONDS = 4L
private const val PROBE_POOL_SIZE = 4
private const val OVERALL_BOUND_SECONDS = PROBE_SECONDS * 3
private const val FIX_REDOCTOR = "re-run: splice doctor"
private const val VERSION_MAX_CHARS = 48
