// NEW: launch profiles' two gaps (issue #791, gap 2). Which `.claude.json` a profile's own is seeded from: the one
// Claude Code would read for the person. A profile's `claude_json` names it; else the launching shell's
// CLAUDE_CONFIG_DIR, where Claude Code keeps it while that variable is set; else `~/.claude.json`. A shell inside a
// profile's session, or a head's, inherits that folder, whose `.claude.json` is splice's copy and not the person's, so
// such a folder is passed over. The file is only ever read.
package splice.client.profile

import splice.client.Keys
import splice.core.config.UserHome
import splice.core.topology.LaunchProfileConfig
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

internal class ProfileStateSource(
    private val home: Path,
    /** Where a profile with no `dir` lives. */
    defaultRoot: Path,
    /** Every head's Claude folder. */
    headDirs: List<Path>,
) {
    private val profilesRoot = PathProbe.real(defaultRoot)

    /** The heads' folders, through every link; `~/.claude` is the person's own even where a head shares it. */
    private val heads = PathProbe.real(home.resolve(UserHome.CLAUDE_DIR)).let { yours ->
        headDirs.map(PathProbe::real).filterNot { it == yours }.toSet()
    }

    /** The file [profile], rebuilt in [folder], is seeded from, for a launch whose shell held [inherited] as
     *  CLAUDE_CONFIG_DIR (null while it held none). */
    fun of(profile: LaunchProfileConfig, folder: Path, inherited: String?): Path {
        val named = profile.claudeJson?.takeIf(String::isNotBlank)?.let { UserHome.declared(it, home) }
        return named ?: personal(inherited, folder)?.resolve(Keys.CLAUDE_JSON) ?: home.resolve(Keys.CLAUDE_JSON)
    }

    /** [inherited] as a Claude folder of the person's own, through every link; null when it is unset, blank or
     *  relative, or is (or is inside) [folder], a profile's or a head's. */
    private fun personal(inherited: String?, folder: Path): Path? {
        val dir = inherited?.takeIf(String::isNotBlank)?.let { UserHome.declared(it, home) }
            ?.takeIf { it.isAbsolute }?.let(PathProbe::real)
        return dir?.takeUnless { it.startsWith(folder) || profiles(it) || it in heads }
    }

    /** Whether [dir] is a profile's folder: under the default root, or marked for one wherever its `dir` put it. */
    private fun profiles(dir: Path): Boolean = dir.startsWith(profilesRoot) || Files.exists(dir.resolve(PROFILE_MARK))
}
