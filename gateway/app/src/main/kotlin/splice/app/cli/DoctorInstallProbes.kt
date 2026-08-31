// NEW: doctor's install-integrity section — jar, launch shim, per-command wrappers, and whether the
// bin dir is on PATH. Split from DoctorProbes.kt, which is the prerequisite-binary pipeline: the two
// sections share no state and no control flow, only the one-line PATH parser, so they were
// co-located for the function-count ceiling rather than because they belong together. Its semantic
// owner is InstallCommand (which writes every artifact checked here), and that class is itself at
// the 14-function ceiling — hence a collaborator of its own. :app is wall-exempt for println.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.InstallPaths
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal const val FIX_RELINK = "splice install --all"

/** Doctor's install-integrity probes as a constructed collaborator (Kotlin style law, 2026-08-15:
 *  main sources carry no top-level functions). [probes] is injected for one thing only: the
 *  malformed-PATH-entry parser this section shares with the prerequisite pipeline, which stays with
 *  the pipeline. Every member keeps the old function's name. */
internal class DoctorInstallProbes(private val probes: DoctorProbes) {

    // The installed-shim marker is InstallCommand's fact (it writes the shim), so shimCheck asks
    // that verb rather than re-reading the file itself.
    private val installCommand = InstallCommand()
    private val path = DoctorPathCheck(probes)

    internal fun installationChecks(topo: DoctorTopology, envReader: EnvReader): List<DoctorCheck> {
        val paths = InstallPaths(envReader = envReader)
        val topology = (topo as? DoctorTopology.Parsed)?.topology
        val commands = topology?.heads?.map { (k, h) -> h.claude.command ?: k }.orEmpty() + "splice"
        return listOf(jarCheck(), shimCheck(paths.shareDir.resolve("splice-launch"), envReader)) +
            commands.map { path.wrapperCheck(paths.binDir.resolve(it), it) } +
            path.check(paths.binDir, envReader)
    }

    private fun jarCheck(): DoctorCheck {
        val version = TopologyLoader.gatewayVersion()
        val jar = AdminSupport.selfJar()
        return if (jar == null) {
            DoctorCheck("jar", CheckStatus.INFO, "running from classes (dev build), $version")
        } else {
            DoctorCheck("jar", CheckStatus.OK, "$version ($jar)")
        }
    }

    private fun shimCheck(shim: Path, envReader: EnvReader): DoctorCheck {
        // DR-69: only proven absence is "missing"; a present shim behind denied access is a
        // different (and fixable-without-reinstall) diagnosis. installedShimVersion now throws
        // on indeterminate access, classified here instead of surfacing as check-crashed.
        val statFailure = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(shim) }.exceptionOrNull()
        if (statFailure != null) {
            val genuinelyAbsent = statFailure is java.nio.file.NoSuchFileException &&
                !Files.exists(shim, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            return if (genuinelyAbsent) {
                DoctorCheck(
                    "shim",
                    CheckStatus.FAIL,
                    "launch shim missing at $shim — every wrapper needs it",
                    "./install.sh from a checkout, or re-run the release installer",
                )
            } else {
                DoctorCheck(
                    "shim",
                    CheckStatus.FAIL,
                    "launch shim at $shim is unreadable (${SafeFailureText.render(statFailure)}) — not missing",
                    "fix access to $shim and its parents, then re-run doctor",
                )
            }
        }
        val expected = TopologyLoader.shimVersion()
        val installed = Cancellables.runCatchingCancellable { installCommand.installedShimVersion(envReader) }
            .getOrElse { failure ->
                return DoctorCheck(
                    "shim",
                    CheckStatus.FAIL,
                    "launch shim at $shim is unreadable (${SafeFailureText.render(failure)}) — not missing",
                    "fix access to $shim and its parents, then re-run doctor",
                )
            }
        return if (installed == expected) {
            DoctorCheck("shim", CheckStatus.OK, "current ($expected)")
        } else {
            DoctorCheck(
                "shim",
                CheckStatus.WARN,
                "stale (installed=${installed ?: "<unmarked>"}, expected=$expected)",
                "$FIX_RELINK   (or ./install.sh)",
            )
        }
    }

    // gh matters only when installing from a GitHub Release (attestation verification); an
    // unauthenticated gh aborts that install — catch it here, before it costs a download.
    internal fun ghCheck(envReader: EnvReader): DoctorCheck {
        val gh = path.binaryOnPath("gh", envReader)
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

    // waitFor() runs BEFORE any read: a probed binary that blocks on its inherited stdin (or just
    // hangs) must not deadlock doctor waiting on output that will never come. Only after a clean or
    // forced exit do we read — the output is tiny --version text, far below the pipe buffer, so a
    // post-exit read cannot deadlock.
    internal fun capturedVersion(command: List<String>): String = Cancellables.runCatchingCancellable {
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

private const val VERSION_MAX_CHARS = 48
