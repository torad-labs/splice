// NEW: doctor's PATH-membership probe. Split from DoctorInstallProbes
// (concentration, 2026-08-19) so that file leaves the HIGH band after
// neighbourhood floor drift. Same-package FQCN is unchanged.
package splice.diagnostics.doctor

import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
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
                "$binDir is not on PATH, so installed commands won't resolve",
                "add to your shell rc: ${rcLine(binDir, envReader)}",
            )
        }
    }

    // V4-220 item 4: the one fix meant for pasting must survive the report's redaction. Expanded, the
    // line read `export PATH="~/.local/bin:$PATH"` after DoctorRedaction, which takes everything up to
    // the quote as ONE path token, finds no allowed prefix for `~/.local/bin:$PATH`, and masks it —
    // the console showed `export PATH="<redacted:path>"`. `$HOME` survives redaction when set.
    // Without it a default JVM and Node both fall back to the passwd home; resolve that at paste time
    // instead of printing an absolute home path that the report masks to a quoted, inert `~`.
    // The test-only UserHome.within override cannot cross into a shell command.
    private fun rcLine(binDir: Path, envReader: EnvReader): String {
        val home = UserHome.dir(envReader)
        return if (binDir.startsWith(home) && binDir != home) {
            val shellHome = if (UserHome.environmentHome(envReader) != null) {
                "\$HOME"
            } else {
                "\$(node -p 'require(\"node:os\").userInfo().homedir')"
            }
            "export PATH=\"$shellHome/${home.relativize(binDir)}:\$PATH\""
        } else {
            "export PATH=\"$binDir\":\"\$PATH\""
        }
    }

    internal fun wrapperCheck(link: Path, command: String): DoctorCheck {
        // DR-69: the entry stat is the probe — NoSuch through a traversable parent is genuinely
        // not-linked; any other failure is a PRESENT-but-unreadable bin, a different diagnosis.
        val entryStat = Cancellables
            .runCatchingCancellable { Files.getLastModifiedTime(link, NOFOLLOW_LINKS) }
            .exceptionOrNull()
        return when {
            entryStat is java.nio.file.NoSuchFileException ->
                DoctorCheck(
                    CHECK_WRAPPER,
                    CheckStatus.FAIL,
                    "'$command' is not linked",
                    FIX_RELINK,
                    DoctorFix.INSTALL_ALL,
                    fixKind = FixKind.COMMAND,
                )
            entryStat != null ->
                DoctorCheck(
                    CHECK_WRAPPER,
                    CheckStatus.FAIL,
                    "'$command' at $link is unreadable (${SafeFailureText.render(entryStat)}), not missing",
                    "fix access to $link and its parents, then re-run doctor",
                )
            !Files.isSymbolicLink(link) ->
                DoctorCheck(
                    CHECK_WRAPPER,
                    CheckStatus.WARN,
                    "'$command' exists at $link but is not a splice-managed symlink",
                    "move the foreign file aside, then: $FIX_RELINK",
                )
            !Files.exists(link) ->
                DoctorCheck(
                    CHECK_WRAPPER,
                    CheckStatus.FAIL,
                    "'$command' is a dangling symlink (target gone)",
                    FIX_RELINK,
                    DoctorFix.INSTALL_ALL,
                    fixKind = FixKind.COMMAND,
                )
            else -> DoctorCheck(CHECK_WRAPPER, CheckStatus.OK, "'$command' → ${Files.readSymbolicLink(link)}")
        }
    }

    /** A name in [binDir] that resolves to OUR launch shim but that no head (nor `splice`) claims.
     *  `install --all` links the topology's commands and never prunes one whose name left it — a
     *  renamed or removed head's command survives, launching a head the daemon cannot route. Only a
     *  symlink resolving to [shim] is judged: a user's file of the same name is never ours to name.
     *  A bin dir that cannot be listed is its own row: the scan did not run, which is not "none". */
    internal fun orphanWrappers(binDir: Path, shim: Path, commands: Set<String>): List<DoctorCheck> {
        // ast-grep-ignore: kt-no-silent-result-collapse -- a shim that does not resolve is the shim row's own diagnosis (missing, dangling or unreadable, each with its remedy), and no link can resolve to it
        val target = Cancellables.runCatchingCancellable { shim.toRealPath() }.getOrNull() ?: return emptyList()
        val entries = Cancellables.runCatchingCancellable { Files.list(binDir).use { it.toList() } }
            .getOrElse { failure ->
                return listOf(
                    DoctorCheck(
                        CHECK_WRAPPER,
                        CheckStatus.WARN,
                        "$binDir could not be listed (${SafeFailureText.render(failure)}), so " +
                            "commands left behind by a renamed or removed head were not checked",
                        "fix access to $binDir, then re-run doctor",
                    ),
                )
            }
        return entries
            .filter { Files.isSymbolicLink(it) && it.fileName.toString() !in commands }
            // ast-grep-ignore: kt-no-silent-result-collapse -- a link that does not resolve cannot resolve to our shim, the one fact this filter asks
            .filter { link -> Cancellables.runCatchingCancellable { link.toRealPath() }.getOrNull() == target }
            .sortedBy { it.fileName.toString() }
            .map { link ->
                DoctorCheck(
                    CHECK_WRAPPER,
                    CheckStatus.WARN,
                    "'${link.fileName}' → $shim names no head in the topology (left by a renamed or removed " +
                        "head; or give that command a head again in the topology)",
                    "rm $link",
                    fixKind = FixKind.COMMAND,
                )
            }
    }

    internal fun binaryOnPath(name: String, envReader: EnvReader): Path? =
        envReader("PATH").orEmpty().split(':').asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { probes.safePath(it) }
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
}

private const val CHECK_WRAPPER = "wrapper"
