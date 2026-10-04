// NEW: V4-131 — SlotInstructions resolves the standing text a bound session's system prompt gains:
// which team, which role, who leads and how to reach them, the goal, then the operator's own words.
// The text is exact because it sits in the cached prompt prefix; [changed] marks the one cold-cache
// turn a change costs, and never a turn that did not change.
package splice.sessions.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import java.nio.file.Path

private const val LEAD = "c3c3c3c3-0000-4000-8000-000000000003"
private const val BUILDER = "d4d4d4d4-0000-4000-8000-000000000004"

class SlotInstructionsTest {

    @TempDir
    lateinit var dir: Path

    private fun bound(store: TeamStore, name: String = "atlas", instructions: String? = "run the gate"): String {
        val id = store.upsert(
            Team(
                name = name,
                goal = "ship v0.4.0",
                slots = listOf(
                    TeamSlot(id = "lead", role = "orchestrator", head = "claude", lead = true),
                    TeamSlot(id = "b1", role = "builder", head = "codex", instructions = instructions),
                ),
            ),
        ).id
        store.bind(id, mapOf("lead" to LEAD, "b1" to BUILDER))
        return id
    }

    @Test
    fun `a bound builder is told its role, the lead's address, the goal and its instructions`() {
        val store = TeamStore(dir.resolve("teams.json"))
        val id = bound(store)
        val slots = SlotInstructions(store, SessionAddress { if (it == LEAD) "uds:/run/lead.sock" else null })
        assertEquals(
            SlotPrompt(
                text = "[splice team \"atlas\", slot b1]\nYour role: builder. " +
                    "The lead is the orchestrator slot (lead); reach it at uds:/run/lead.sock.\n" +
                    "Team goal: ship v0.4.0\n\nrun the gate",
                source = "slot:$id/b1",
            ),
            slots.forSession(BUILDER),
        )
        assertEquals(
            "[splice team \"atlas\", slot lead]\nYour role: orchestrator. You are the team's lead.\n" +
                "Team goal: ship v0.4.0",
            slots.forSession(LEAD)?.text,
        )
    }

    @Test
    fun `an unknown lead address falls back to the session id, and a session in two teams gets both blocks`() {
        // A ticking clock, not the wall: two creates in one millisecond tie on createdAt, and the tie
        // falls to the random team ids, so the expected order held only half the time.
        var now = 1_789_725_600_000L
        val store = TeamStore(dir.resolve("teams.json"), clock = WallClock { now++ })
        val first = bound(store)
        val second = bound(store, name = "zephyr", instructions = null)
        val prompt = SlotInstructions(store).forSession(BUILDER)!!
        assertEquals("slot:$first/b1+slot:$second/b1", prompt.source)
        assertTrue(prompt.text.contains("reach it at session $LEAD."), prompt.text)
        assertTrue(prompt.text.contains("\n\n[splice team \"zephyr\", slot b1]"), prompt.text)
        assertTrue(prompt.text.endsWith("Team goal: ship v0.4.0"), "a slot with no instructions ends at the goal")
    }

    @Test
    fun `an unbound session, an archived team and a team with no lead say so`() {
        val store = TeamStore(dir.resolve("teams.json"))
        val id = bound(store)
        assertNull(SlotInstructions(store).forSession("someone-else"))
        assertNull(SlotInstructions(store).forSession(null))
        store.upsert(store.team(id)!!.copy(slots = store.team(id)!!.slots.map { it.copy(lead = false) }))
        assertTrue(SlotInstructions(store).forSession(BUILDER)!!.text.contains("The team has no lead slot."))
        store.archive(id)
        assertNull(SlotInstructions(store).forSession(BUILDER), "an archived team adds nothing")
    }

    @Test
    fun `changed is true once per change, never on the first sight or an unchanged turn`() {
        val slots = SlotInstructions(TeamStore(dir.resolve("teams.json")))
        val a = SlotPrompt("a", "slot:t/x")
        assertFalse(slots.changed(BUILDER, a), "first sight after a restart is not a change")
        assertFalse(slots.changed(BUILDER, a))
        assertTrue(slots.changed(BUILDER, SlotPrompt("b", "slot:t/x")))
        assertFalse(slots.changed(BUILDER, SlotPrompt("b", "slot:t/x")))
        assertTrue(slots.changed(BUILDER, null), "an unbind changes the prefix too")
        assertFalse(slots.changed(LEAD, null), "another session is its own history")
    }
}
