// NEW: doctor's install-integrity section — jar, launch shim, per-command wrappers, and whether the
// bin dir is on PATH. Split from DoctorProbes.kt, which is the prerequisite-binary pipeline: the two
// sections share no state and no control flow, only the one-line PATH parser, so they were
// co-located for the function-count ceiling rather than because they belong together. Its semantic
// owner is InstallCommand (which writes every artifact checked here), and that class is itself at
// the 14-function ceiling — hence a collaborator of its own. :app is wall-exempt for println.
package splice.app.cli

import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.config.InstallPaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal const val FIX_RELINK = "splice install --all"
private const val CHECK_WRAPPER = "wrapper"

/** Doctor's install-integrity probes as a constructed collaborator (Kotlin style law, 2026-08-15:
 *  main sources carry no top-level functions). [probes] is injected for one thing only: the
 *  malformed-PATH-entry parser this section shares with the prerequisite pipeline, which stays with
 *  the pipeline. Every member keeps the old function's name. */
internal class DoctorInstallProbes(private val probes: DoctorProbes) {

    // The installed-shim marker is InstallCommand's fact (it writes the shim), so shimCheck asks
    // that verb rather than re-reading the file itself.
    private val installCommand = InstallCommand()

    internal fun installationChecks(topo: DoctorTopology, envReader: EnvReader): List<DoctorCheck> {
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

    private fun shimCheck(shim: Path, envReader: EnvReader): DoctorCheck {
        if (!Files.exists(shim)) {
            return DoctorCheck(
                "shim",
                CheckStatus.FAIL,
                "launch shim missing at $shim — every wrapper needs it",
                "./install.sh from a checkout, or re-run the release installer",
            )
        }
        val installed = installCommand.installedShimVersion(envReader)
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

    private fun pathCheck(binDir: Path, envReader: EnvReader): DoctorCheck {
        val onPath = envReader("PATH").orEmpty().split(':')
            .filter { it.isNotEmpty() }
            .mapNotNull { probes.safePath(it) }
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
}
