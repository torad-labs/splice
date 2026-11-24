// NEW: door-default (docs/specs/door-default.md, decision 2). The recipe of a launch of `claude` on the door, from what
// the door says it carries. Claude Code runs over the person's own Claude folder, so nothing is materialized and no
// head's resume or hub applies: the person's own arguments follow splice's as they are. Split from LaunchService, whose
// wrap and binary it reads. #791 (Nov 23, 2026): it plants what a head's launch plants from the same helpers (the
// client's timers and wall header from the heads' walls, the windows from the door's, NO_PROXY with the door's host),
// and says every value that is no secret again in the overlay's `env`, which a person's settings files cannot outrank.
package splice.launch.recipe

import splice.client.ClaudeArgv
import splice.client.desktop.DoorOverlayMerge
import splice.client.desktop.GATEWAY_BASE_URL
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.core.util.EnvReader
import splice.launch.LaunchOutcome
import splice.sessions.registry.LaunchMarker
import java.nio.file.Path

internal class DoorRecipe(
    private val wrap: WrappedHead,
    /** Read per launch, as LaunchService reads it: the real binary behind a wrapped `claude`. */
    private val wrapState: WrapStateRead,
    private val claudeBinary: String,
    envReader: EnvReader,
) {
    private val ambient = LaunchAmbient(envReader)

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
        val modelGiven = ClaudeArgv.namesModel(extraArgs)
        val held = held(door, modelGiven, caller.noProxy)
        val merged = when (val m = DoorOverlayMerge(door.overlay, caller.cwd, held).merge(extraArgs)) {
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
        val headers = ambient.customHeaders(door.wallMs, emptyList(), merged.headers)
        // what the overlay says empty, the process environment takes out
        val cleared = held.filterValues(String::isEmpty).keys
        val env = door.env - cleared + held.filterValues(String::isNotEmpty) + headers
        val unset = door.unset + config + cleared
        val danger = "dangerouslySkipPermissions engaged: $DANGER".takeIf { dangerouslySkipPermissions }
        val warning = listOfNotNull(danger, door.notice).takeIf { it.isNotEmpty() }?.joinToString("\n")
        return LaunchOutcome.Ready(LaunchEnvironment(env, (unset - env.keys).distinct()).recipe(argv, warning))
    }

    /** What [door]'s launch plants beside what it carries, each in the process environment and in the overlay's `env`:
     *  the client's timers for the longest wall of its providers and heads, the door's window as the context and
     *  compaction windows, and NO_PROXY with [noProxy], the caller's, and the door's host; and with [modelGiven], the
     *  person's `--model`, an empty starting model, which the process environment takes out. None is a secret. */
    private fun held(door: DoorLaunchSpec, modelGiven: Boolean, noProxy: List<String>): Map<String, String> =
        buildMap {
            door.wallMs?.let { putAll(LaunchClientTimers.of(it)) }
            putAll(LaunchModelEnvironment.gatewayWindows(door.window))
            putAll(ambient.noProxy(door.env[GATEWAY_BASE_URL], noProxy))
            if (modelGiven) put(STARTING_MODEL, "")
        }
}

/** Claude Code's variable for the model a session starts on, which its `--model` outranks. */
private const val STARTING_MODEL = "ANTHROPIC_MODEL"

/** What a launch with `--dangerously-skip-permissions` is told, after where it runs. */
internal const val DANGER = "Claude Code runs with --dangerously-skip-permissions (no permission prompts)."
