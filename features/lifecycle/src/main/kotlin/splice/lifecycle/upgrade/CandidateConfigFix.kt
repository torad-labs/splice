// NEW: upgrade-compat (Nov 23, 2026). A release whose own `check-config` refuses the person's splice.toml used to end
// `splice upgrade` at a wall of findings, with nothing to do there but read them. On a terminal, the release's own fix
// session (`check-config --fix`, 0.7.0 and later) now runs where the person is, with its own findings and moves, after
// its own backup; the check is asked again, and the upgrade goes on only once it accepts the file. Off a terminal (the
// daemon's updater, a script) nothing is asked, and the refusal says where the fix session can run.
package splice.lifecycle.upgrade

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import java.nio.file.Path

/** `check-config --fix`, the release's own fix session over its findings and warnings, shipped in 0.7.0. */
private const val CONFIG_FIX_MINOR = 7

/** What a refusal off a terminal says to do, since the fix session needs one. */
private const val FIX_ON_A_TERMINAL =
    "run `splice upgrade` in a terminal, where the release's own fix session can repair splice.toml first"

internal class CandidateConfigFix(
    private val output: TerminalOutput,
    private val process: UpgradeProcess,
    private val java: String,
    /** A person at a terminal, who can answer the session. */
    private val terminal: Boolean,
) {
    /** The refusal of [version] at [jar], whose check refused splice.toml with [said], unless [repair] is asked, a
     *  person is at the terminal and the release's own fix session leaves a file its check accepts: null then. */
    fun refusal(jar: Path, version: String, said: String, repair: Boolean): Upgraded.Refused? {
        val answerable = repair && terminal
        if (!answerable || !offersFix(version)) return refused(version, said, "\n$FIX_ON_A_TERMINAL")
        val label = "config".padEnd(UPGRADE_PAD)
        output.line("  $YELLOW!$RESET $label $version would refuse your splice.toml:")
        said.trim().lines().forEach { output.line("      $it") }
        output.line("  ${"".padEnd(UPGRADE_PAD)}   its fix session can repair it now; the file is backed up first")
        process(listOf(java, "-jar", jar.toString(), "check-config", "--fix"), true)
        val again = process(listOf(java, "-jar", jar.toString(), "check-config"), false)
        if (again.code != 0) return refused(version, again.stdout.ifBlank { said }, "")
        output.line("  $GREEN✓$RESET $label $version accepts splice.toml now")
        return null
    }

    private fun refused(version: String, said: String, hint: String): Upgraded.Refused = Upgraded.Refused(
        "$version would refuse to boot on your splice.toml, so the running daemon was not touched:\n" +
            said.trim() + hint,
    )

    /** Whether [version] has `check-config --fix`; a version that does not read as one is taken as new enough. */
    private fun offersFix(version: String): Boolean {
        val parts = version.substringBefore('-').substringBefore('+').split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return major > 0 || minor >= CONFIG_FIX_MINOR
    }
}
