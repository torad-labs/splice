// NEW: terminal launch profiles (issue #791, comment §4). What the door answers for `splice launch claude --profile
// <name>`: the side-by-side launch, run over the profile's own Claude folder that was just rebuilt, or why it can't.
package splice.launch.recipe

/** A side-by-side launch in a `[launch.profiles.<name>]` folder. */
public sealed class ProfileLaunch {
    /** [spec] is the side-by-side launch with CLAUDE_CONFIG_DIR set to the profile's folder. */
    public data class Ready(val spec: DoorLaunchSpec) : ProfileLaunch()

    /** The launch does not start, for [reason], said as the shim prints it. */
    public data class Refused(val reason: String) : ProfileLaunch()
}
