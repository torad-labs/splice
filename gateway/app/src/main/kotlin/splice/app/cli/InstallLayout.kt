// NEW: the install path wrappers — localBin / shareDir / launchShimPath. Kept as
// named methods (not inlined into install()) so install.sh and `splice install`
// always agree on where wrappers and the shim land. Split from InstallCommand.kt
// (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.core.config.InstallPaths
import splice.core.util.EnvReader
import java.nio.file.Path

internal class InstallLayout {
    // SPLICE_BIN_DIR / SPLICE_SHARE_DIR honored via core/config (System.getenv is walled there).
    fun localBin(env: EnvReader): Path = InstallPaths(envReader = env).binDir

    fun shareDir(env: EnvReader): Path = InstallPaths(envReader = env).shareDir

    fun launchShimPath(env: EnvReader): Path = shareDir(env).resolve("splice-launch")

    /** DR-169: the containment law for a wrapper command name, or null when [command] would not
     *  land directly inside [bin].
     *
     *  A command is `head.claude.command` or the head key — an unsanitized TOML string — and
     *  `bin.resolve` honours whatever it holds: a leading `../` normalizes OUT of bin, an absolute
     *  value discards bin entirely, and `a/b` lands in a subdirectory. Install CREATES a symlink at
     *  that path and uninstall DELETES one, so the pair reached anywhere the user can write.
     *  Nothing upstream constrained it: requireReplaceableLink only asks whether the entry is a
     *  symlink, never where it is, and DR-67, DR-74 and DR-84 all govern the claim rather than the
     *  location. Uninstall's specific-arg path is looser still — it falls back to the operator-typed
     *  string verbatim when the topology is unreadable, by DR-101's design.
     *
     *  Null rather than a throw because uninstall iterates several commands and has to report and
     *  continue; install turns the null into its usual loud failure. Both verbs go through this one
     *  predicate deliberately — a rule enforced only on install would leave uninstall deleting paths
     *  install now refuses to create. */
    fun wrapperLinkOrNull(bin: Path, command: String): Path? =
        bin.resolve(command).takeIf { link ->
            command.isNotEmpty() && link.normalize().parent == bin.normalize()
        }
}
