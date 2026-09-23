// NEW: V4-131, FEATURES.md 4.13 "Role instructions" — the standing text splice adds to the system
// prompt of every session bound to a team slot: which team, which role, who the lead is and how to
// reach them, then the operator's own instructions for the slot.
//
// PER SESSION AND HOT, beside the per-head layers that are boot-only by design (SystemPromptLayers,
// HeadServerFactory): the text is resolved from TeamStore on every turn, so a binding or an edit
// applies on the session's next turn with no restart.
//
// APPENDED, NEVER REPLACING. TurnPreparation places this text after whatever the head's own layers
// produced, in APPEND mode even on a head whose system_prompt_mode is replace: a slot adds to a
// head's prompt, it never takes the head's place.
//
// THE COLD-CACHE TURN. The text sits in the request prefix, so the turn after it changes cannot hit
// the prompt cache. That is the price of editing a live session's role, and the row pays it visibly:
// [changed] answers true exactly once per change per session, and TurnPreparation writes it to the
// turn's perf row as [SLOT_PROMPT_CHANGED]. The key is declared here, not in PerfKeys, because this is
// the only writer and PerfKeys was outside this row's fence; the perf row writes every counter by name
// (PerfStats), so the key needs no registration to reach it.
//
// DETERMINISTIC. The same bindings produce the same bytes, so an unchanged team never breaks a cache:
// teams by created time then id (TeamStore.bindingsOf), one block per bound slot, nothing
// time-dependent in the text.
package splice.sessions.prompt

import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore

/** The perf-row counter set to 1 on the turn whose slot text differs from the session's last turn. */
public const val SLOT_PROMPT_CHANGED: String = "slot_prompt_changed"

/** How many sessions the resolver remembers the last applied text of. A forgotten session's next
 *  turn reads as unchanged, never as a spurious cold-cache mark. */
private const val REMEMBERED_SESSIONS = 4096

/** A session id to the address its peers SendMessage to, when the registry knows it. */
public fun interface SessionAddress {
    public operator fun invoke(session: String): String?
}

/** The slot text for one turn, and the label the turn's meta carries for where it came from. */
public data class SlotPrompt(val text: String, val source: String)

public class SlotInstructions(
    private val store: TeamStore,
    private val address: SessionAddress = SessionAddress { null },
) {
    private val lastApplied = LinkedHashMap<String, Int>()

    /** The text for [session]'s turn, or null when it is bound in no live (unarchived) team. */
    public fun forSession(session: String?): SlotPrompt? {
        val bound = session?.let(store::bindingsOf)?.filterNot { (team, _) -> team.archived }.orEmpty()
        if (bound.isEmpty()) return null
        return SlotPrompt(
            text = bound.joinToString("\n\n") { (team, slot) -> block(team, slot) },
            source = bound.joinToString("+") { (team, slot) -> "slot:${team.id}/${slot.id}" },
        )
    }

    /** True when [prompt] differs from the text [session]'s previous turn carried. The first turn a
     *  session is seen with a slot text counts as a change only if it previously ran without one,
     *  which the resolver cannot know across a restart, so a restart never marks a turn. */
    public fun changed(session: String, prompt: SlotPrompt?): Boolean = synchronized(lastApplied) {
        val now = prompt?.text?.hashCode() ?: 0
        val before = lastApplied.remove(session)
        lastApplied[session] = now
        if (lastApplied.size > REMEMBERED_SESSIONS) lastApplied.remove(lastApplied.keys.first())
        before != null && before != now
    }

    private fun block(team: Team, slot: TeamSlot): String = buildString {
        append("[splice team \"").append(team.name).append("\", slot ").append(slot.id).append("]\n")
        append("Your role: ").append(slot.role).append('.')
        append(' ').append(leadLine(team, slot))
        if (team.goal.isNotBlank()) append("\nTeam goal: ").append(team.goal)
        slot.instructions?.let { append("\n\n").append(it) }
    }

    private fun leadLine(team: Team, slot: TeamSlot): String {
        if (slot.lead) return "You are the team's lead."
        val lead = team.slots.firstOrNull { it.lead } ?: return "The team has no lead slot."
        val reach = lead.session?.let { session -> address(session) ?: "session $session" }
            ?: "not bound to a session yet"
        return "The lead is the ${lead.role} slot (${lead.id}); reach it at $reach."
    }
}
