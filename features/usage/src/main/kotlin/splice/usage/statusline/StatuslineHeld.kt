// What a session's status line leads with while turns of it are held (docs/specs/harness.md, decisions 7 and 8):
// the latest message among them in its colour, how long ago it came, and how many turns are held when more than one.
// Whoever holds a turn wrote its message; this part only draws it.
package splice.usage.statusline

import splice.core.capability.CapabilityColors
import splice.core.head.HeldSaying
import splice.core.util.AgeText

internal class StatuslineHeld {
    /** `Reading the request · 4s ago`, or `… · 4s ago · 2 turns` with more than one turn held, for [held], the
     *  session's own held turns, at [nowMs] on the wall clock. Null when none of the session's turns is held. */
    fun segment(held: List<HeldSaying>, nowMs: Long): String? {
        val latest = held.maxByOrNull { it.saidAtEpochMs } ?: return null
        val count = if (held.size > 1) " · ${held.size} turns" else ""
        // The declaration check accepts only the table's names, so every message has its colour.
        val colour = CapabilityColors.escapes[latest.color].orEmpty()
        return "$colour${latest.message}$RESET$DIM · ${AgeText.ago(nowMs - latest.saidAtEpochMs)}$count$RESET"
    }
}

private const val RESET = "\u001B[0m"
private const val DIM = "\u001B[2m"
