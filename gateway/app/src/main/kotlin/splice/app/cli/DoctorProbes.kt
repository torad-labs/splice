// NEW: doctor's prerequisite-binary pipeline — the probe table, PATH resolution, the concurrent
// probe run, and a verdict per binary. Split from DoctorCommand.kt (which owns sections, rendering,
// and the verdict) so each file stays under the function-count ceiling; the install-integrity
// section that shared this file for the same reason now lives in DoctorInstallProbes.kt, which
// reaches back only for [safePath]. :app is wall-exempt for println.
package splice.app.cli

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val FLAG_VERSION = "--version"

private data class BinarySpec(val name: String, val versionArgs: List<String>, val missing: DoctorCheck)

// FILE SCOPE ON PURPOSE: the probe table is a constant shared by every doctor run. As a class
// member each DoctorProbes instance would rebuild five DoctorCheck objects for no reason.
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

/** Doctor's prerequisite probes as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). DoctorCommand builds one and asks it for the
 *  prerequisites section; every member keeps the old function's name. */
internal class DoctorProbes {

    internal fun prerequisiteChecks(envReader: EnvReader): List<DoctorCheck> {
        val java = DoctorCheck("java", CheckStatus.OK, System.getProperty("java.version") ?: "unknown")
        // claude (~1s) and gh (up to PROBE_SECONDS of network) dominate sequential wall time — run
        // every probe concurrently; runProbes preserves this list's order regardless of finish order.
        val tasks = BINARIES.map { spec -> Callable { binaryCheck(spec, envReader) } } +
            Callable { ghCheck(envReader) }
        return listOf(java) + runProbes(tasks)
    }

    private fun binaryCheck(spec: BinarySpec, envReader: EnvReader): DoctorCheck {
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
                    DoctorCheck(
                        "probe",
                        CheckStatus.FAIL,
                        "probe did not complete in time (${e.message})",
                        FIX_REDOCTOR,
                    )
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
    private fun ghCheck(envReader: EnvReader): DoctorCheck {
        val gh = binaryOnPath("gh", envReader)
            ?: return DoctorCheck("gh", CheckStatus.INFO, "not installed (only needed to verify release-mode installs)")
        val authed = Cancellables.runCatchingCancellable {
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

    private fun binaryOnPath(name: String, envReader: EnvReader): Path? =
        envReader("PATH").orEmpty().split(':').asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { safePath(it) }
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }

    // PATH can carry a malformed entry (garbage bytes under a non-UTF-8 jnu.encoding); Paths.get()
    // throws InvalidPathException (an IllegalArgumentException) on those — skip the entry, don't
    // let it collapse the whole PATH scan. `internal` because DoctorInstallProbes' pathCheck splits
    // PATH the same way and must skip the same entries — one parser, two readers.
    internal fun safePath(raw: String): Path? = Cancellables.runCatchingCancellable { Paths.get(raw) }.getOrNull()

    // waitFor() runs BEFORE any read: a probed binary that blocks on its inherited stdin (or just
    // hangs) must not deadlock doctor waiting on output that will never come. Only after a clean or
    // forced exit do we read — the output is tiny --version text, far below the pipe buffer, so a
    // post-exit read cannot deadlock.
    private fun capturedVersion(command: List<String>): String = Cancellables.runCatchingCancellable {
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
}

internal const val PROBE_SECONDS = 4L
private const val PROBE_POOL_SIZE = 4
private const val OVERALL_BOUND_SECONDS = PROBE_SECONDS * 3
private const val FIX_REDOCTOR = "re-run: splice doctor"
private const val VERSION_MAX_CHARS = 48
