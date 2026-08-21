// NEW: doctor's PATH-membership probe. Split from DoctorInstallProbes
// (concentration, 2026-08-19) so that file leaves the HIGH band after
// neighbourhood floor drift. Same-package FQCN is unchanged.
package splice.app.cli

import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class DoctorPathCheck(private val probes: DoctorProbes) {
    fun check(binDir: Path, envReader: EnvReader): DoctorCheck {
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

    internal fun wrapperCheck(link: Path, command: String): DoctorCheck = when {
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

    internal fun binaryOnPath(name: String, envReader: EnvReader): Path? =
        envReader("PATH").orEmpty().split(':').asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { probes.safePath(it) }
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
}

private const val CHECK_WRAPPER = "wrapper"
