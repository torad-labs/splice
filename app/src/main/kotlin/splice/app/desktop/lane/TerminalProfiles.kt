// NEW: terminal launch profiles (issue #791, comment §4). `splice launch claude --profile <name>`: the side-by-side
// launch on the door, run over the `[launch.profiles.<name>]` folder rebuilt from the person's `~/.claude` just before
// it starts (LaunchProfileBuild). The door's own values are the top layer: what it sets and takes out of the
// environment is kept out of the profile's settings, and CLAUDE_CONFIG_DIR names the profile's folder.
package splice.app.desktop.lane

import splice.client.profile.LaunchProfileBuild
import splice.client.profile.ProfileBuilt
import splice.core.mcp.McpUserRegistry
import splice.core.topology.LaunchProfileConfig
import splice.launch.recipe.DoorLaunchSpec
import splice.launch.recipe.ProfileLaunch
import java.nio.file.Path

internal class TerminalProfiles(
    private val profiles: Map<String, LaunchProfileConfig>,
    private val home: Path,
    /** Where a profile with no `dir` keeps its folder. */
    private val root: Path,
    /** Splice's MCP user registry, read at each launch. */
    private val registry: McpUserRegistry,
) {
    /** [side], the door's side-by-side launch, moved into profile [name]'s folder once it is rebuilt. */
    fun launch(name: String, side: DoorLaunchSpec): ProfileLaunch {
        val profile = profiles[name] ?: return ProfileLaunch.Refused(unknown(name))
        val owned = side.env.keys + side.unset
        val build = LaunchProfileBuild(home, root, registry.entries())
        return when (val built = build.build(name, profile, owned)) {
            is ProfileBuilt.Refused -> ProfileLaunch.Refused("splice: ${built.reason}")
            is ProfileBuilt.Ready -> ProfileLaunch.Ready(
                side.copy(
                    env = side.env + (PROFILE_CONFIG_DIR_ENV to built.folder.toString()),
                    unset = side.unset - PROFILE_CONFIG_DIR_ENV,
                    notice = (listOfNotNull(side.notice) + built.notes.map { "profile $name: $it" })
                        .takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ),
            )
        }
    }

    private fun unknown(name: String): String {
        val known = profiles.keys.sorted().joinToString(", ").ifEmpty { "none" }
        return "splice: no launch profile named '$name' in splice.toml (profiles: $known); add a " +
            "[launch.profiles.$name] table and restart splice, or name one of these"
    }
}

/** The variable that moves Claude Code's whole config root, and its `.claude.json` with it. */
private const val PROFILE_CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR"
