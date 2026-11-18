// NEW: door-default. Claude Code's model tiers and the three variables it reads for each (the tier's model, the name
// its picker shows, and its description), in one place: a head's launch plants them (LaunchModelEnvironment) and
// clears the ones it does not plant (LaunchService), and a launch on the door clears them all (DoorSession).
package splice.client

/** Claude Code's model tiers, by the name its variables spell them with. */
public object ClaudeTiers {
    public val names: List<String> = listOf("OPUS", "SONNET", "HAIKU", "FABLE")

    /** The variable naming the model [tier] runs. */
    public fun model(tier: String): String = "ANTHROPIC_DEFAULT_${tier}_MODEL"

    /** The variable naming what the picker shows for [tier]. */
    public fun label(tier: String): String = "${model(tier)}_NAME"

    /** The variable naming [tier]'s description in the picker. */
    public fun description(tier: String): String = "${model(tier)}_DESCRIPTION"

    /** All three of [tier]'s variables. */
    public fun variables(tier: String): List<String> = listOf(model(tier), label(tier), description(tier))
}
