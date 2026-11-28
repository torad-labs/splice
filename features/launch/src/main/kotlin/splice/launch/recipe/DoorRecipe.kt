// NEW: door-default (docs/specs/door-default.md, decision 2). The recipe of a launch of `claude` on the door, from what
// the door says it carries. Claude Code runs over the person's own Claude folder, so nothing is materialized and no
// head's resume or hub applies: the person's own arguments follow splice's as they are. Split from LaunchService, whose
// wrap and binary it reads. #791 (Nov 23, 2026): it plants what a head's launch plants from the same helpers (the
// client's timers and wall header from the heads' walls, the windows from the door's, NO_PROXY with the door's host),
// and says every value that is no secret again in the overlay's `env`, which a person's settings files cannot outrank.
// modes-mcp (Nov 25, 2026): the servers the daemon answers itself are no head's, so they are named here as on a head's
// launch: before, only a head's launch named them, and `claude` on the door never had an mcp mode's tools. Their file
// goes in front of the person's words as one word (OwnMcpLaunch), so each of those keeps the meaning it had.
package splice.launch.recipe

import splice.client.ClaudeArgv
import splice.client.desktop.DoorOverlayMerge
import splice.client.desktop.GATEWAY_BASE_URL
import splice.client.mcp.OwnMcpLaunch
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.core.client.NO_HOLD_HEADER
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
    /** The MCP servers the daemon answers itself (the mcp modes that are on), read at each launch, so a mode switched
     *  on reaches the next one; null adds none. */
    private val notices: LaunchNoticesNow = LaunchNoticesNow { null },
    private val ownMcp: OwnMcpNow,
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
        val noHold = caller.ambient.noHold == true
        val modelGiven = ClaudeArgv.options(extraArgs).namesModel
        val held = held(door, modelGiven, caller.ambient)
        val merged = when (val m = DoorOverlayMerge(door.overlay, caller.cwd, held).merge(extraArgs)) {
            is DoorOverlayMerge.Merged.Refused -> return LaunchOutcome.Refused(m.reason)
            is DoorOverlayMerge.Merged.Ready -> m
        }
        val argv = buildList {
            add((chosen as? ClaudeToRun.Wrapped)?.path ?: claudeBinary)
            if (dangerouslySkipPermissions) add("--dangerously-skip-permissions")
            addAll(LaunchMarker.settingsArgs(merged.overlay))
            // the file of the daemon's own servers goes with splice's words, in front of the person's; the door runs no
            // head here, so a mode's every-head value decides, and its server is asked for with none
            addAll(ownMcp()?.added(emptyList(), "", OWN_MCP_CALLER_DOOR, merged.args).orEmpty())
            addAll(merged.args)
        }
        val config = wrap.ownHome().configRootUnsets(caller.inheritedConfigDir, headDirs)
        val wallMs = door.wallMs?.let { LaunchClientTimers.wallMs(it, noHold) }
        val ownHeaders = if (noHold) listOf("$NO_HOLD_HEADER: 1") else emptyList()
        val headers = ambient.customHeaders(
            wallMs,
            ownHeaders,
            merged.headers,
            stripNoHold = caller.ambient.noHold != null,
        )
        // what the overlay says empty, the process environment takes out
        val cleared = held.filterValues(String::isEmpty).keys
        val env = door.env - cleared + held.filterValues(String::isNotEmpty) + headers
        val unset = door.unset + config + cleared
        val danger = "dangerouslySkipPermissions engaged: $DANGER".takeIf { dangerouslySkipPermissions }
        val warning = listOfNotNull(danger, door.notice).takeIf { it.isNotEmpty() }?.joinToString("\n")
        val launch = LaunchEnvironment(env, (unset - env.keys).distinct())
        return LaunchOutcome.Ready(launch.recipe(argv, warning, caller, notices()))
    }

    /** What [door]'s launch plants beside what it carries, each in the process environment and in the overlay's `env`:
     *  the client's timers for the longest wall of its providers and heads, the door's window as the context and
     *  compaction windows, and NO_PROXY with [callerAmbient]'s entries and the door's host; and with [modelGiven], the
     *  person's `--model`, an empty starting model, which the process environment takes out. None is a secret. */
    private fun held(
        door: DoorLaunchSpec,
        modelGiven: Boolean,
        callerAmbient: LaunchCallerAmbient,
    ): Map<String, String> = buildMap {
        door.wallMs?.let { putAll(LaunchClientTimers.of(it, callerAmbient.noHold == true)) }
        putAll(LaunchModelEnvironment.gatewayWindows(door.window))
        putAll(ambient.noProxy(door.env[GATEWAY_BASE_URL], callerAmbient.noProxy))
        if (modelGiven) put(STARTING_MODEL, "")
    }
}

/** Claude Code's variable for the model a session starts on, which its `--model` outranks. */
private const val STARTING_MODEL = "ANTHROPIC_MODEL"

/** What a launch with `--dangerously-skip-permissions` is told, after where it runs. */
internal const val DANGER = "Claude Code runs with --dangerously-skip-permissions (no permission prompts)."

/** The servers the daemon answers itself as the launch service holds them now: assigned after the recipe is built. */
internal fun interface OwnMcpNow {
    operator fun invoke(): OwnMcpLaunch?
}

internal fun interface LaunchNoticesNow {
    operator fun invoke(): splice.launch.LaunchNotices?
}
