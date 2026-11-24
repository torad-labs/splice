// NEW: terminal launch profiles (issue #791, comment §4). The `[launch.profiles.<name>]` tables of splice.toml: a
// Claude folder of its own that `splice launch claude --profile <name>` rebuilds from the person's `~/.claude` before
// each launch, keeping only the hooks, plugins, CLAUDE.md blocks, skills, agents and commands the profile allows. A
// `--settings` overlay can't take any of these away, so the session runs over that folder instead (CLAUDE_CONFIG_DIR).
// The words are read as strings and checked by LaunchProfileChecks, so a bad value is one finding among all of them.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    /** Which hooks run: [KEEP_HOOKS_OWN] (the person's settings files' own, no plugin's), [KEEP_HOOKS_ALL] (those and
     *  the kept plugins' own) or [KEEP_HOOKS_NONE]. */
    @SerialName("keep_hooks") val keepHooks: String = KEEP_HOOKS_OWN,
    /** Hooks left out of the kept ones: an event's name drops every hook of that event, any other word every hook whose
     *  command holds it. */
    @SerialName("drop_hooks") val dropHooks: List<String> = emptyList(),
    /** The plugins the profile loads, by `name@marketplace` or by name; none when empty. */
    @SerialName("keep_plugins") val keepPlugins: List<String> = emptyList(),
    /** The marked blocks taken out of CLAUDE.md, by marker. */
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
}

/** [LaunchProfileConfig.keepHooks]: the hooks the person's settings files declare, and no plugin's. The default. */
internal const val KEEP_HOOKS_OWN: String = "own"

/** [LaunchProfileConfig.keepHooks]: the person's own hooks and the kept plugins' hooks. */
public const val KEEP_HOOKS_ALL: String = "all"

/** [LaunchProfileConfig.keepHooks]: no hooks at all but splice's own. */
public const val KEEP_HOOKS_NONE: String = "none"

// The home's mark at the start of a path, as a shell writes it.
private const val HOME_MARK = "~"
