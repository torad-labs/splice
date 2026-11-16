// NEW: gpt-speed (docs/specs/gpt-speed.md, decisions 1 to 3) — what a person typed, read as /fast or /ultrafast, and
// the words they are told about a tier. One place, so the menu's description and the reply to the command say the
// same thing about what a tier costs.
//
// Claude Code may hand the head's UserPromptSubmit hook either the command as typed (`/fast on`) or the skill's body
// with its arguments in place (`SPLICE_SPEED fast on`), so both read the same here. A prompt that only starts like one
// (`/fastidious`, `/ultrafastest`) is neither.
package splice.client.speed

import splice.core.model.ServiceTier
import splice.core.speed.Speed

/** The first word of the skill bodies the launch lays down: the skill's own text, as Claude Code expands it. */
internal const val SPEED_SENTINEL: String = "SPLICE_SPEED"

/** One /fast or /ultrafast: the tier it names, and what the person asked of it. */
public data class SpeedCommand(val speed: Speed, val ask: Ask) {
    /** Bare toggles, on and off set; [Unknown] is any other argument, answered with how the command is used. */
    public enum class Ask { TOGGLE, ON, OFF, UNKNOWN }

    /** The command's own name, `/fast` or `/ultrafast`. */
    public val name: String get() = "/${speed.word}"
}

/** Reads a prompt as a speed command, and words what a tier costs. */
public object SpeedCommands {
    private val shape = Regex("""^(?:/|$SPEED_SENTINEL\s+)(fast|ultrafast)(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)

    /** The command [prompt] is, or null for any other prompt. Everything after the name is its argument. */
    public fun parse(prompt: String): SpeedCommand? {
        val match = shape.matchEntire(prompt.trim()) ?: return null
        val speed = if (match.groupValues[1] == Speed.FAST.word) Speed.FAST else Speed.ULTRAFAST
        val ask = when (match.groupValues[2].trim().lowercase()) {
            "" -> SpeedCommand.Ask.TOGGLE
            "on" -> SpeedCommand.Ask.ON
            "off" -> SpeedCommand.Ask.OFF
            else -> SpeedCommand.Ask.UNKNOWN
        }
        return SpeedCommand(speed, ask)
    }

    /** What [tier] is and costs, in the catalog's words and OpenAI's published plan multiple:
     *  "Fast: 2x speed, increased usage. Uses a ChatGPT plan's limits at 2.5x." */
    public fun costOf(speed: Speed, tier: ServiceTier): String {
        val said = tier.description.trim()
        val what = when {
            said.isEmpty() -> "${speed.title}."
            said.last() in ".!?" -> "${speed.title}: $said"
            else -> "${speed.title}: $said."
        }
        return speed.planMultiple?.let { "$what Uses a ChatGPT plan's limits at $it." } ?: what
    }
}
