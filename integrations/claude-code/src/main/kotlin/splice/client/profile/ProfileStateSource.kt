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
    defaultRoot: Path,
    headDirs: List<Path>,
    folder: Path,
) {
    // A head sharing ~/.claude is using the person's own folder, not a copy.
    private val claude = root(home.resolve(UserHome.CLAUDE_DIR))
    private val protectedDirs = listOf(folder, defaultRoot, home.resolve(".gemini"))
    private val excludedRoots = (protectedDirs + headDirs.filterNot { root(it) == claude })
        .flatMap { listOf(it.toAbsolutePath().normalize(), root(it)) }.toSet()

    private val fallback = home.resolve(Keys.CLAUDE_JSON)

    /** The checked source, or null when the named file (or the fallback) is not safe to use. */
    fun of(profile: LaunchProfileConfig, inherited: String?): Path? {
        val named = profile.claudeJson
        if (named != null) return checked(UserHome.declared(named, home))
        val fromShell = inherited?.let { UserHome.declared(it, home) }?.resolve(Keys.CLAUDE_JSON)
        return checked(fromShell) ?: checked(fallback)
    }

    fun refusal(profile: LaunchProfileConfig): ProfileBuilt.Refused {
        val source = profile.claudeJson?.let { "claude_json '$it'" } ?: "default source '$fallback'"
        return ProfileBuilt.Refused("$source must resolve outside profile, head and ~/.gemini folders")
    }

    private fun checked(file: Path?): Path? {
        if (file == null || !file.isAbsolute) return null
        val entry = PathProbe.realEntry(file)
        val real = PathProbe.resolved(file)
        val target = real ?: PathProbe.throughLinks(file)
        if (entry == null || target == null) return null
        val forbidden = listOf(file.normalize(), entry, target).any(::excluded)
        // A missing component before '..' can make the literal path unreadable even if its projected target exists.
        return (real ?: file).takeUnless { forbidden }
    }

    private fun excluded(path: Path): Boolean =
        excludedRoots.any(path::startsWith) ||
            generateSequence(path.parent) { it.parent }.any { Files.exists(it.resolve(PROFILE_MARK)) }

    private fun root(path: Path): Path = PathProbe.throughLinks(path) ?: path.toAbsolutePath().normalize()
}
