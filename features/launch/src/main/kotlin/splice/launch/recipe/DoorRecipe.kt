// NEW: door-default (docs/specs/door-default.md, decision 2). The recipe of a launch of `claude` on the door, from what
// the door says it carries. Claude Code runs over the person's own Claude folder, so nothing is materialized and no
// head's resume or hub applies: the person's own arguments follow splice's as they are. Split from LaunchService, whose
// wrap and binary it reads.
package splice.launch.recipe

import splice.client.desktop.DoorOverlayMerge
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.launch.LaunchOutcome
import splice.sessions.registry.LaunchMarker
import java.nio.file.Path

internal class DoorRecipe(
    private val wrap: WrappedHead,
    /** Read per launch, as LaunchService reads it: the real binary behind a wrapped `claude`. */
    private val wrapState: WrapStateRead,
    private val claudeBinary: String,
) {
    /** The launch [door] carries, with the person's [extraArgs]. An inherited CLAUDE_CONFIG_DIR that is one of
     *  [headDirs] (a session started inside a head's) is taken out, and a root of the person's own is kept; [door]'s
     *  notice, what that head's own launch would say (head-catalog), is on a line of its own. */
    fun outcome(
        door: DoorLaunchSpec,
        extraArgs: List<String>,
        dangerouslySkipPermissions: Boolean,
        caller: LaunchCaller,
        headDirs: List<Path>,
    ): LaunchOutcome {
        val chosen = wrapState.claude()
        if (chosen is ClaudeToRun.Refused) return LaunchOutcome.Refused(chosen.reason)
        // custom-provider (cp-overlay): the person's own --settings are merged into the one overlay, never a second one.
        val merged = when (val m = DoorOverlayMerge(door.overlay, caller.cwd).merge(extraArgs)) {
            is DoorOverlayMerge.Merged.Refused -> return LaunchOutcome.Refused(m.reason)
            is DoorOverlayMerge.Merged.Ready -> m
        }
        val argv = buildList {
            add((chosen as? ClaudeToRun.Wrapped)?.path ?: claudeBinary)
            if (dangerouslySkipPermissions) add("--dangerously-skip-permissions")
            addAll(LaunchMarker.settingsArgs(merged.overlay))
            addAll(merged.args)
        }
        val config = wrap.ownHome().configRootUnsets(caller.inheritedConfigDir, headDirs)
        val danger = "dangerouslySkipPermissions engaged: $DANGER".takeIf { dangerouslySkipPermissions }
        val warning = listOfNotNull(danger, door.notice).takeIf { it.isNotEmpty() }?.joinToString("\n")
        // a variable the door sets (a profile's CLAUDE_CONFIG_DIR) is never also taken out
        val unset = (door.unset + config).distinct() - door.env.keys
        return LaunchOutcome.Ready(LaunchEnvironment(door.env, unset).recipe(argv, warning))
    }
}

/** What a launch with `--dangerously-skip-permissions` is told, after where it runs. */
internal const val DANGER = "Claude Code runs with --dangerously-skip-permissions (no permission prompts)."
