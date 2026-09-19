// NEW: V4-165 — a conversation keeps its llama-server slot, and a new one never takes a slot that
// is serving someone. llama-server's own choice (prompt similarity against the NEW prompt, empty
// slots skipped) hands a new session an idle conversation's slot, and the displaced conversation
// re-prefills from zero; these cells pin the allocator that decides id_slot instead.
package campaign.v4165

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.dialect.chat.SlotAffinity

class SlotAffinityTest {

    private fun affinity(slots: Int?) = SlotAffinity { slots }

    private fun messages(vararg roleText: Pair<String, String>): JsonArray =
        Json.parseToJsonElement(
            roleText.joinToString(",", "[", "]") { (role, text) -> """{"role":"$role","content":"$text"}""" },
        ).jsonArray

    // Mutant: pick() ignores the owner and always takes the LRU free slot. The conversation's
    // second turn lands on another slot, which is the re-prefill this row exists to stop.
    @Test
    fun `a conversation keeps its slot across turns`() {
        val a = affinity(4)
        val first = a.lease("conv-a")!!
        first.end()
        a.lease("conv-b")!!.end()

        assertEquals(first.slot, a.lease("conv-a")!!.slot)
    }

    // Mutant: drop the inFlight filter. A new conversation is handed a slot that is mid-turn, and
    // llama-server makes it wait for that whole turn (a pinned slot is waited on, never swapped).
    @Test
    fun `a new conversation never takes a slot with a turn in flight`() {
        val a = affinity(2)
        val busy = a.lease("conv-a")!!

        val other = a.lease("conv-b")!!
        assertNotEquals(busy.slot, other.slot)
        assertNull(a.lease("conv-c"), "every slot busy: the turn goes out unpinned, as before this row")
    }

    @Test
    fun `the least recently used free slot is the one reassigned`() {
        val a = affinity(2)
        val old = a.lease("conv-a")!!.also { it.end() }
        val recent = a.lease("conv-b")!!.also { it.end() }

        assertEquals(old.slot, a.lease("conv-c")!!.slot)
        assertEquals(recent.slot, a.lease("conv-b")!!.slot, "the recent owner kept its slot")
    }

    // Mutant: SlotLease.end without its once-guard. The double end frees a count the slot never
    // had and a second concurrent turn's slot reads as idle.
    @Test
    fun `a lease ends once however often it is ended`() {
        val a = affinity(1)
        val held = a.lease("conv-a")!!
        a.lease("conv-a")!! // the same conversation may queue on its own slot
        held.end()
        held.end()

        assertNull(a.lease("conv-b"), "the second turn of conv-a is still in flight")
    }

    @Test
    fun `until the runtime answers, turns go out unpinned, and the first answer is kept`() {
        var answer: Int? = null
        val a = SlotAffinity { answer }
        assertNull(a.lease("conv-a"))

        answer = 2
        val leased = a.lease("conv-a")
        answer = null
        assertEquals(leased?.slot, a.lease("conv-a")?.slot)
    }

    // Mutant: key on the session alone. The subagent — same session, different opening — shares
    // the main conversation's key, queues behind it on one slot and evicts it on every call.
    @Test
    fun `the conversation is its session plus its opening, never the session alone`() {
        val a = affinity(4)
        val main = messages("system" to "claude code", "user" to "fix the bug")
        val laterTurn = messages(
            "system" to "claude code",
            "user" to "fix the bug",
            "assistant" to "done",
            "user" to "thanks",
        )
        val subagent = messages("system" to "you are a search agent", "user" to "find X")

        assertEquals(a.conversationOf("s1", main), a.conversationOf("s1", laterTurn))
        assertNotEquals(a.conversationOf("s1", main), a.conversationOf("s1", subagent))
        assertNotEquals(a.conversationOf("s1", main), a.conversationOf("s2", main))
    }
}
