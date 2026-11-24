// NEW: door-default (docs/specs/door-default.md, decision 2). The `claude` command's launch while it is on the door:
// the daemon that holds the door says what the launch sets, takes out and lays over the person's own settings, and
// LaunchService builds the recipe from it the way it builds every other: the real `claude` binary while `claude` is
// wrapped, the person's own arguments after splice's. No head is launched and nothing is materialized: the session
// runs over the person's own Claude folder. head-catalog: on its head the launch adds no rows any more (decision 9's
// picker is retired), and every head's command starts on the same gateway (gateway).
package splice.launch.recipe

import splice.launch.LaunchSpec

/** What a launch of `claude` on the door carries. [overlay] is the `--settings` JSON laid over the person's own
 *  settings for this run, its `env` holding every value of [env] but the key, and what [unset] takes out spelled
 *  empty (DoorSession.settingsEnv). [window] is the one window the door declares. */
public data class DoorLaunchSpec(
    val env: Map<String, String>,
    val unset: List<String>,
    val overlay: String,
    val window: Long,
    /** head-catalog: what a head's own launch says, for a `claude` started in that head's own Claude folder. */
    val notice: String? = null,
    /** #791: the client timeout the session must outlive, the longest wall of the heads its turns can run on; null
     *  while no head here has one, and then no timer is planted. */
    val wallMs: Long? = null,
)

/** The door's part of a launch of `claude`. Assigned by the daemon before routes serve requests. */
public interface ClaudeDoor {
    /** What a launch of `claude` carries while it is on the door, or null while it is on its head. */
    public suspend fun launch(): DoorLaunchSpec?

    /** Side-by-side launch of Claude Code on the door (`splice launch claude` / `claude-splice`), always on the door
     *  and leaving the user's `~/.claude` files untouched. */
    public suspend fun launchSideBySide(): DoorLaunchSpec? = launch()

    /** custom-provider (cp-command): a launch of the door command [name] (`[door.commands.<name>]`), on the door
     *  whatever lane `claude` is on; null when [name] is no door command. */
    public suspend fun launchCommand(name: String): DoorLaunchSpec? = null

    /** Whether `claude` is on the door now, which is what a wrapped `claude` launches when no head is keyed `claude`. */
    public fun onDoor(): Boolean

    /** head-catalog: the Splice gateway the command of [head] (its key) starts its session on, in Claude Code's
     *  gateway mode, with the model it starts on; null before the daemon has opened it. */
    public suspend fun gateway(head: String): GatewayAt? = null

    /** Whether splice.toml has a head `claude` runs on, which carries the door's models (decision 9). */
    public fun headHere(): Boolean = false
}

/** Where a session in Claude Code's gateway mode is pointed: the Splice gateway's [base] address, the terminal's
 *  [key], the [window] every count the gateway reports to it is a share of, and the id its head's [model] is listed by
 *  (null while the head is not in the gateway). */
public data class GatewayAt(val base: String, val key: String, val window: Long, val model: String?)

/** The door as it is now: the daemon assigns it after the routes that read it are built. */
public fun interface DoorNow {
    public operator fun invoke(): ClaudeDoor?
}

/** What a launch of a custom head says before Claude Code starts, on a line of its own (docs/specs/door-default.md,
 *  decision 7, as head-catalog changed it on Nov 19, 2026): custom heads (a head's own command and its own Claude
 *  folder) are deprecated and will be removed in a coming release, and what to type instead: `claude`, then
 *  `/model` with the head's own selector, the one its launch starts on (bare `/model` for a head that pins none of the
 *  gateway's, `claude` on its head). Where `claude` is not on the Splice gateway (the head lane), `splice claude door`
 *  comes first. The head still works in this release. */
internal class HeadsDeprecated {
    /** The line for [command], whose session starts on [selector], while `claude` is [onGateway] or not. */
    fun said(command: String, selector: String?, onGateway: Boolean): String {
        val pick = selector?.let { "/model $it" } ?: "/model"
        val run = if (onGateway) "run `claude`" else "run `splice claude door`, then `claude`,"
        return "`$command` is a custom head, and custom heads are deprecated and will be removed in a coming " +
            "release: $run and type `$pick`"
    }

    /** The line for a launch of [spec]'s head by its [command], whose session starts on [gateway]'s model when it
     *  starts on the Splice gateway, while `claude` is on the gateway when [door] says so. */
    fun said(command: String, spec: LaunchSpec, gateway: GatewayAt?, door: ClaudeDoor?): String {
        val selector = gateway?.model ?: spec.models.pinnedSelector?.takeIf { spec.startsOnGateway }
        return said(command, selector, door?.onDoor() == true)
    }
}
