// NEW: door-default (docs/specs/door-default.md, decision 2). The `claude` command's launch while it is on the door:
// the daemon that holds the door says what the launch sets, takes out and lays over the person's own settings, and
// LaunchService builds the recipe from it the way it builds every other: the real `claude` binary while `claude` is
// wrapped, the person's own arguments after splice's. No head is launched and nothing is materialized: the session
// runs over the person's own Claude folder. Decision 9: on its head, the launch adds the door's picker rows after
// Claude Code's own models (headPicker).
package splice.launch.recipe

import splice.client.desktop.DoorPicker
import splice.launch.LaunchSpec

/** What a launch of `claude` on the door carries. [overlay] is the `--settings` JSON laid over the person's own
 *  settings for this run. */
public data class DoorLaunchSpec(
    val env: Map<String, String>,
    val unset: List<String>,
    val overlay: String,
)

/** The door's part of a launch of `claude`. Assigned by the daemon before routes serve requests. */
public interface ClaudeDoor {
    /** What a launch of `claude` carries while it is on the door, or null while it is on its head. */
    public suspend fun launch(): DoorLaunchSpec?

    /** Whether `claude` is on the door now, which is what a wrapped `claude` launches when no head is keyed `claude`. */
    public fun onDoor(): Boolean

    /** Decision 9: the rows a launch of `claude` on its head adds to `/model` after Claude Code's own models, the models
     *  of the signed-in accounts; null on the door and with no account signed in. */
    public suspend fun headPicker(): DoorPicker? = null

    /** Whether splice.toml has a head `claude` runs on, which carries the door's models (decision 9). */
    public fun headHere(): Boolean = false
}

/** The door as it is now: the daemon assigns it after the routes that read it are built. */
public fun interface DoorNow {
    public operator fun invoke(): ClaudeDoor?
}

/** What the launch of another provider's own command says before Claude Code starts (docs/specs/door-default.md,
 *  decision 7): the command is deprecated, and what to type in `claude` to run this head's model. `claude` runs every
 *  head's models already where [door] says it has a head here, or that it is on the door while `claude` is [linked]
 *  to splice; anywhere else the notice says that `splice claude door` comes first. Heads still work, and no removal
 *  date is set. */
public class HeadsDeprecated(private val door: ClaudeDoor?, private val linked: Boolean) {
    /** The notice of a launch of [spec]'s head. */
    public fun said(spec: LaunchSpec): String {
        val models = spec.models
        val selector = models.discoveryPrefix + models.pinnedModel
        val pick = if (models.pinnedModel.isEmpty()) "/model" else "/model $selector"
        val runsHeads = door != null && (door.headHere() || (linked && door.onDoor()))
        val first = if (runsHeads) "" else "after `splice claude door`, "
        return "this command is deprecated, and `claude` runs this head's models: ${first}type `$pick` in `claude`"
    }
}
