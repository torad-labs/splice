// NEW: terminal launch profiles (issue #791, comment §4). The `[launch.profiles.<name>]` tables of splice.toml: a
// Claude folder of its own that `splice launch claude --profile <name>` rebuilds from the person's `~/.claude` before
// each launch, keeping only the hooks, plugins, CLAUDE.md blocks, skills, agents and commands the profile allows. A
// `--settings` overlay can't take any of these away, so the session runs over that folder instead (CLAUDE_CONFIG_DIR).
// The words are read as strings and checked by LaunchProfileChecks below, so a bad value is one finding among all of
// them. Above all, a profile's folder is rebuilt before every launch, so it may never be `~/.claude`, a folder inside
// it, or one that holds it (the home).
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.config.HeadConfigDirs
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.topology.profile.GEMINI_FOLDER
import splice.core.topology.profile.PROFILE_STATE_PATH_RULE
import splice.core.topology.profile.ProfileStatePaths
import splice.core.util.PathProbe
import java.nio.file.Path
import java.nio.file.Paths

/** `[launch]`: how `splice launch claude` starts Claude Code, today its named profiles. */
@Serializable
public data class LaunchConfig(
    /** `[launch.profiles.<name>]`, by name. */
    val profiles: Map<String, LaunchProfileConfig> = emptyMap(),
)

/** One `[launch.profiles.<name>]`. */
@Serializable
public data class LaunchProfileConfig(
    /** The profile's own Claude folder; `<splice root>/launch-profiles/<name>` when unset. Never `~/.claude`. */
    val dir: String? = null,
    /** The `.claude.json` the profile's own is seeded from (MCP servers, projects, onboarding), read and never
     *  written. Unset: the launching shell's `$CLAUDE_CONFIG_DIR/.claude.json`, where Claude Code keeps it while that
     *  names a Claude folder of the person's own, else `~/.claude.json`. */
    @SerialName("claude_json") val claudeJson: String? = null,
    /** Which hooks run: [KEEP_HOOKS_OWN] (the person's settings files' own, no plugin's), [KEEP_HOOKS_ALL] (those and
     *  the kept plugins' own) or [KEEP_HOOKS_NONE]. */
    @SerialName("keep_hooks") val keepHooks: String = KEEP_HOOKS_OWN,
    /** Hooks left out of the kept ones: an event's name drops every hook of that event, any other word every hook whose
     *  command holds it. */
    @SerialName("drop_hooks") val dropHooks: List<String> = emptyList(),
    /** The plugins the profile loads, by `name@marketplace` or by name; none when empty. */
    @SerialName("keep_plugins") val keepPlugins: List<String> = emptyList(),
    /** Comment, `# BEGIN`, or XML-style blocks taken out of CLAUDE.md, by single-line marker without `<` or `>`. */
    @SerialName("drop_blocks") val dropBlocks: List<String> = emptyList(),
    /** Skills, agents and commands whose names start with one of these are left out. */
    @SerialName("drop_names") val dropNames: List<String> = emptyList(),
) {
    /** The folder profile [name] runs in: `dir` with a leading `~` read as [home], or [name] under [defaultRoot] when
     *  `dir` is unset; normalized, so `..` can't walk it anywhere its text does not say. */
    public fun folder(name: String, home: Path, defaultRoot: Path): Path {
        val raw = dir?.takeIf(String::isNotBlank) ?: return defaultRoot.resolve(name).normalize()
        val expanded = when {
            raw == HOME_MARK -> home.toString()
            raw.startsWith("$HOME_MARK/") -> home.toString() + raw.substring(1)
            else -> raw
        }
        return Paths.get(expanded).normalize()
    }

    /** Why [folder] can never be a profile's: it is, sits inside or holds a folder splice never writes, `~/.claude`
     *  (which a profile is rebuilt from) or `~/.gemini` (Google's tools' own), each compared through every link on the
     *  way; null when it may be one. */
    public fun refusal(folder: Path, home: Path): String? {
        val where = PathProbe.real(folder)
        val kept = KEPT_OUT.firstOrNull { name ->
            val root = PathProbe.real(home.resolve(name))
            where.startsWith(root) || root.startsWith(where)
        }
        return kept?.let { "is ~/$it, inside it or holds it, and a profile never writes there" }
    }
}

/** [LaunchProfileConfig.keepHooks]: the hooks the person's settings files declare, and no plugin's. The default. */
internal const val KEEP_HOOKS_OWN: String = "own"

/** [LaunchProfileConfig.keepHooks]: the person's own hooks and the kept plugins' hooks. */
public const val KEEP_HOOKS_ALL: String = "all"

/** [LaunchProfileConfig.keepHooks]: no hooks at all but splice's own. */
public const val KEEP_HOOKS_NONE: String = "none"

// The home's mark at the start of a path, as a shell writes it.
private const val HOME_MARK = "~"

/** Why a `[launch.profiles.<name>]` table cannot run, each finding at its dotted path, so boot refuses them all at
 *  once. */
internal class LaunchProfileChecks(
    private val topology: Topology,
    private val home: Path = UserHome.dir(),
    /** Where a profile with no `dir` lives: the root the launch builds it under, in the state `[daemon] state_dir`
     *  names when it names one. */
    private val defaultRoot: Path = (
        topology.daemon.stateDir?.takeIf(String::isNotBlank)?.let { UserHome.declared(it, home) }
            ?.let { StatePaths(baseOverride = it) } ?: StatePaths(homeDir = home)
        ).launchProfilesDir,
) {

    fun findings(): List<TopologyFinding> {
        val profiles = topology.launch.profiles
        val each = profiles.flatMap { (name, profile) ->
            listOfNotNull(name(name), dir(name, profile), keepHooks(name, profile)) + entries(name, profile)
        }
        return each + sourceFindings(profiles) + shared(profiles)
    }

    private fun name(name: String): TopologyFinding? =
        TopologyFinding("launch.profiles.$name", PROFILE_NAME_RULE).takeUnless { profileName.matches(name) }

    /** A `dir` that is relative, or is, holds or sits inside a folder no profile may be in. */
    private fun dir(name: String, profile: LaunchProfileConfig): TopologyFinding? {
        val raw = profile.dir?.takeIf(String::isNotBlank)
        val folder = profile.folder(name, home, defaultRoot)
        val why = if (raw != null && !folder.isAbsolute) RELATIVE_RULE else profile.refusal(folder, home)
        return why?.let { TopologyFinding("launch.profiles.$name.dir", "$it; name a folder of the profile's own") }
    }

    private fun sourceFindings(profiles: Map<String, LaunchProfileConfig>): List<TopologyFinding> {
        val named = profiles.filterValues { it.claudeJson != null }
        if (named.isEmpty()) return emptyList()
        val headDirs = topology.heads.map { (key, head) -> HeadConfigDirs.of(key, head.claude.configDir, home) }
        return named.mapNotNull { (name, profile) ->
            val folder = PathProbe.real(profile.folder(name, home, defaultRoot))
            val paths = ProfileStatePaths(home, defaultRoot, headDirs, folder)
            TopologyFinding("launch.profiles.$name.claude_json", "$PROFILE_STATE_PATH_RULE; $SOURCE_HINT")
                .takeIf { paths.named(requireNotNull(profile.claudeJson)) == null }
        }
    }

    private fun keepHooks(name: String, profile: LaunchProfileConfig): TopologyFinding? =
        TopologyFinding(
            "launch.profiles.$name.keep_hooks",
            "must be \"$KEEP_HOOKS_OWN\" (your settings files' hooks, no plugin's), \"$KEEP_HOOKS_ALL\" (those and " +
                "the kept plugins' hooks) or \"$KEEP_HOOKS_NONE\"",
        ).takeUnless { profile.keepHooks in keepHooksWords }

    /** A blank list entry, a multiline or angle-bracketed block marker, or a name prefix with a `/`. */
    private fun entries(name: String, profile: LaunchProfileConfig): List<TopologyFinding> {
        val lists = mapOf(
            "drop_hooks" to profile.dropHooks,
            "keep_plugins" to profile.keepPlugins,
            "drop_blocks" to profile.dropBlocks,
            "drop_names" to profile.dropNames,
        )
        val blank = lists.filterValues { list -> list.any(String::isBlank) }.keys
            .map { TopologyFinding("launch.profiles.$name.$it", "has a blank entry; remove it") }
        val marker = profile.dropBlocks.filter { marker -> marker.any { it in "\n<>" } }
            .map { TopologyFinding("launch.profiles.$name.drop_blocks", MARKER_RULE) }.distinct()
        val prefix = profile.dropNames.filter { it.contains('/') }
            .map { TopologyFinding("launch.profiles.$name.drop_names", PREFIX_RULE) }.distinct()
        return blank + marker + prefix
    }

    /** Two profiles over one folder would rebuild each other's. One folder is one folder however it is reached, a
     *  link included. */
    private fun shared(profiles: Map<String, LaunchProfileConfig>): List<TopologyFinding> =
        profiles.entries.groupBy { (name, profile) -> PathProbe.real(profile.folder(name, home, defaultRoot)) }
            .values.filter { it.size > 1 }
            .flatMap { same -> same.map { it.key } }
            .map { TopologyFinding("launch.profiles.$it.dir", "is another profile's folder too; give each its own") }
}

private val keepHooksWords = setOf(KEEP_HOOKS_OWN, KEEP_HOOKS_ALL, KEEP_HOOKS_NONE)

// A profile's name is a folder's name when it has no `dir`, and a word on the command line.
private val profileName = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

private const val PROFILE_NAME_RULE =
    "must be a letter or digit, then up to 63 letters, digits, - or _, since it names a folder and a launch's --profile"

private const val MARKER_RULE = "each marker must fit on one line and hold no < or >"

private const val PREFIX_RULE = "each prefix is the start of a name, so it holds no /"

private const val RELATIVE_RULE = "must be an absolute folder or start with ~/"

private const val SOURCE_HINT = "name the .claude.json your MCP servers and projects are read from, or remove it"

// The folders under the home a profile is never in, nor holds: Claude Code's own, and Google's tools'.
private val KEPT_OUT = listOf(UserHome.CLAUDE_DIR, GEMINI_FOLDER)
