// NEW: V4-160 — the pure rules a TeamStore write applies, moved out of TeamStore (function ceiling,
// 2026-09-18): what makes a team valid, what a re-saved slot keeps, and how a slot remembers every
// session it ever held. No file, no lock, no clock of its own: TeamStore calls these under its lock.
package splice.core.teams

import java.util.UUID

private const val ID_HEX_CHARS = 12

internal class TeamRules {

    /** [slot] as re-saved over [before]: a binding the upsert left out is kept, the history is kept,
     *  and the instructions stamp moves only when the instructions did. */
    fun carried(slot: TeamSlot, before: TeamSlot?, now: Long): TeamSlot {
        val instructionsMoved = before != null && before.instructions != slot.instructions
        return remembered(
            slot.copy(
                session = slot.session ?: before?.session,
                instructionsUpdatedAt = if (instructionsMoved) now else stampOf(before, slot),
                sessionsHistory = before?.sessionsHistory ?: slot.sessionsHistory,
            ),
        )
    }

    /** The slot with its current session appended to its history, once. */
    fun remembered(slot: TeamSlot): TeamSlot {
        val session = slot.session
        if (session == null || session in slot.sessionsHistory) return slot
        return slot.copy(sessionsHistory = slot.sessionsHistory + session)
    }

    fun mintId(): String = "team-" + UUID.randomUUID().toString().replace("-", "").take(ID_HEX_CHARS)

    /** Refuses a team with no name, a slot with no id or head, or a repeated slot id. */
    fun validate(team: Team) {
        val ids = team.slots.map { it.id }
        val reason = when {
            team.name.isBlank() -> "a team needs a name"
            ids.any { it.isBlank() } -> "every slot needs an id"
            ids.toSet().size != ids.size -> "slot ids repeat in ${team.name}"
            team.slots.any { it.head.isBlank() } -> "every slot needs a head"
            else -> null
        }
        if (reason != null) throw TeamRefusal(reason)
    }

    private fun stampOf(before: TeamSlot?, slot: TeamSlot): Long? =
        before?.instructionsUpdatedAt ?: slot.instructionsUpdatedAt
}
