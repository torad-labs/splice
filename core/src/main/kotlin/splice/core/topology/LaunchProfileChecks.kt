// NEW: terminal launch profiles (issue #791, comment §4). Why a `[launch.profiles.<name>]` table cannot run, each
// finding at its dotted path, so boot refuses them all at once. The one that matters most: a profile's folder is
// rebuilt before every launch, so it may never be `~/.claude`, a folder inside it, or one that holds it (the home).
package splice.core.topology

import splice.core.config.StatePaths
import splice.core.config.UserHome
import java.nio.file.Path

internal class LaunchProfileChecks(
    private val topology: Topology,
    private val home: Path = UserHome.dir(),
    /** Where a profile with no `dir` lives: the same root the launch builds it under. */
    private val defaultRoot: Path = StatePaths(homeDir = home).launchProfilesDir,
) {
    private val claudeDir = home.resolve(UserHome.CLAUDE_DIR).normalize()

    fun findings(): List<TopologyFinding> {
        val profiles = topology.launch.profiles
        val each = profiles.flatMap { (name, profile) ->
            listOfNotNull(name(name), dir(name, profile), keepHooks(name, profile)) + entries(name, profile)
        }
        return each + shared(profiles)
    }

    private fun name(name: String): TopologyFinding? =
        TopologyFinding("launch.profiles.$name", PROFILE_NAME_RULE).takeUnless { profileName.matches(name) }

    /** A `dir` that is relative, or is, holds or sits inside `~/.claude`. */
    private fun dir(name: String, profile: LaunchProfileConfig): TopologyFinding? {
        val raw = profile.dir?.takeIf(String::isNotBlank)
        val folder = profile.folder(name, home, defaultRoot)
        val why = when {
            raw != null && !folder.isAbsolute -> "must be an absolute folder or start with ~/"
            folder == claudeDir -> "is ~/.claude itself, which a profile is rebuilt from and never written to"
            folder.startsWith(claudeDir) -> "is inside ~/.claude, which a profile is rebuilt from and never written to"
            claudeDir.startsWith(folder) -> "holds ~/.claude, which a profile is rebuilt from and never written to"
            else -> null
        }
        return why?.let { TopologyFinding("launch.profiles.$name.dir", "$it; name a folder of the profile's own") }
    }

    private fun keepHooks(name: String, profile: LaunchProfileConfig): TopologyFinding? =
        TopologyFinding(
            "launch.profiles.$name.keep_hooks",
            "must be \"$KEEP_HOOKS_OWN\" (your settings files' hooks, no plugin's), \"$KEEP_HOOKS_ALL\" (those and " +
                "the kept plugins' hooks) or \"$KEEP_HOOKS_NONE\"",
        ).takeUnless { profile.keepHooks in keepHooksWords }

    /** A blank entry in any list, a marker that can't sit on one line of an HTML comment, a name prefix with a `/`. */
    private fun entries(name: String, profile: LaunchProfileConfig): List<TopologyFinding> {
        val lists = mapOf(
            "drop_hooks" to profile.dropHooks,
            "keep_plugins" to profile.keepPlugins,
            "drop_blocks" to profile.dropBlocks,
            "drop_names" to profile.dropNames,
        )
        val blank = lists.filterValues { list -> list.any(String::isBlank) }.keys
            .map { TopologyFinding("launch.profiles.$name.$it", "has a blank entry; remove it") }
        val marker = profile.dropBlocks.filter { it.contains('\n') || it.contains(COMMENT_END) }
            .map { TopologyFinding("launch.profiles.$name.drop_blocks", MARKER_RULE) }.distinct()
        val prefix = profile.dropNames.filter { it.contains('/') }
            .map { TopologyFinding("launch.profiles.$name.drop_names", PREFIX_RULE) }.distinct()
        return blank + marker + prefix
    }

    /** Two profiles over one folder would rebuild each other's. */
    private fun shared(profiles: Map<String, LaunchProfileConfig>): List<TopologyFinding> =
        profiles.entries.groupBy { (name, profile) -> profile.folder(name, home, defaultRoot) }
            .values.filter { it.size > 1 }
            .flatMap { same -> same.map { it.key } }
            .map { TopologyFinding("launch.profiles.$it.dir", "is another profile's folder too; give each its own") }
}

private val keepHooksWords = setOf(KEEP_HOOKS_OWN, KEEP_HOOKS_ALL, KEEP_HOOKS_NONE)

// A profile's name is a folder's name when it has no `dir`, and a word on the command line.
private val profileName = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

private const val PROFILE_NAME_RULE =
    "must be a letter or digit, then up to 63 letters, digits, - or _, since it names a folder and a launch's --profile"

private const val MARKER_RULE = "each marker must fit on one line and hold no -->"

private const val PREFIX_RULE = "each prefix is the start of a name, so it holds no /"

private const val COMMENT_END = "-->"
