// NEW: doctor's prerequisite-binary pipeline — PATH resolution, the concurrent probe run,
// and a verdict per binary. The probe table lives in DoctorBinaryTable.kt so this file is
// not billed for a constant catalogue (concentration HIGH, 2026-08-19). Split from
// DoctorCommand.kt (which owns sections, rendering, and the verdict) so each file stays
// under the function-count ceiling; the install-integrity section that shared this file
// for the same reason now lives in DoctorInstallProbes.kt, which reaches back only for
// [safePath]. :app is wall-exempt for println.
package splice.app.cli

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Doctor's prerequisite probes as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). DoctorCommand builds one and asks it for the
 *  prerequisites section; every member keeps the old function's name. */
internal class DoctorProbes {

    private val path = DoctorPathCheck(this)
    private val install = DoctorInstallProbes(this)

    internal fun prerequisiteChecks(envReader: EnvReader): List<DoctorCheck> {
        val java = DoctorCheck("java", CheckStatus.OK, System.getProperty("java.version") ?: "unknown")
        // claude (~1s) and gh (up to PROBE_SECONDS of network) dominate sequential wall time — run
        // every probe concurrently; runProbes preserves this list's order regardless of finish order.
        val tasks = BINARIES.map { spec -> Callable { binaryCheck(spec, envReader) } } +
            Callable { install.ghCheck(envReader) }
        return listOf(java) + runProbes(tasks)
    }

    private fun binaryCheck(spec: BinarySpec, envReader: EnvReader): DoctorCheck {
        val found = path.binaryOnPath(spec.name, envReader) ?: return DoctorCheck(
            spec.name,
            CheckStatus.FAIL,
            spec.missingDetail,
            spec.fix,
        )
        return DoctorCheck(spec.name, CheckStatus.OK, install.capturedVersion(listOf(found.toString()) + spec.versionArgs))
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

    // PATH can carry a malformed entry (garbage bytes under a non-UTF-8 jnu.encoding); Paths.get()
    // throws InvalidPathException (an IllegalArgumentException) on those — skip the entry, don't
    // let it collapse the whole PATH scan. `internal` because DoctorInstallProbes' pathCheck splits
    // PATH the same way and must skip the same entries — one parser, two readers.
    internal fun safePath(raw: String): Path? = Cancellables.runCatchingCancellable { Paths.get(raw) }.getOrNull()
}

internal const val PROBE_SECONDS = 4L
private const val PROBE_POOL_SIZE = 4
private const val OVERALL_BOUND_SECONDS = PROBE_SECONDS * 3
private const val FIX_REDOCTOR = "re-run: splice doctor"
