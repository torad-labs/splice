// What a turn held on a usage limit, an overload or credit says on its session's status line (docs/specs/harness.md,
// decision 8): what it met and the reset it waits for, in the words the line has said them in since slice 5.
package splice.head.hold.resources

import splice.head.turn.TurnState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class HoldWords(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val resetFormat = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT)

    /** `⏸ plan limit reached: waiting until Tue 14:05 or a reset`, spelled as the line's limit bars spell a reset. */
    fun of(held: TurnState.Held): String {
        val met = HeldCause.entries.firstOrNull { it.word == held.cause } ?: HeldCause.LIMIT
        val wait = held.until?.let { "until ${resetFormat.format(it.atZone(zone))}${met.orSooner}" } ?: met.unstated
        return "⏸ ${met.said}: waiting $wait"
    }
}

/** What a held turn met. [word] is the cause as the hold names it, [said] names it, [orSooner] is what can end the
 *  wait before the stated reset, and [unstated] what the turn waits for where no reset was stated. A cause these do
 *  not know reads as a plan limit. */
private enum class HeldCause(val word: String, val said: String, val orSooner: String, val unstated: String) {
    LIMIT("limit", "plan limit reached", " or a reset", "for a reset"),
    OVERLOAD("overload", "provider overloaded", "", "for it to answer"),
    CREDIT("credit", "out of credit", "", "for credit"),
}

/** why: the colour a limit's wait has been drawn in on the status line since slice 5. */
internal const val HOLD_COLOR = "yellow"
