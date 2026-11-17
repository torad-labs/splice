// NEW: door-default (docs/specs/door-default.md, decision 2). The `claude` command's launch while it is on the door:
// the daemon that holds the door says what the launch sets, takes out and lays over the person's own settings, and
// LaunchService builds the recipe from it the way it builds every other: the real `claude` binary while `claude` is
// wrapped, the person's own arguments after splice's. No head is launched and nothing is materialized: the session
// runs over the person's own Claude folder.
package splice.launch.recipe

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
}

/** The door as it is now: the daemon assigns it after the routes that read it are built. */
public fun interface DoorNow {
    public operator fun invoke(): ClaudeDoor?
}

/** What a head's launch, the head lane, `splice status` and `splice doctor` say of heads (docs/specs/door-default.md,
 *  decision 7): they still work, and no removal date is set. */
public const val HEADS_DEPRECATED: String =
    "head commands are deprecated, and `claude` on splice's door replaces them: `splice claude door` puts it there"
