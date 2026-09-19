// NEW: V4-165 — what slot affinity puts on the wire: id_slot, llama-server's own slot override,
// and nothing at all for a head that did not opt in (every other head's bytes are unchanged).
package campaign.v4165

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.chat.BuiltChatRequest
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder
import splice.dialect.chat.SlotAffinity

private const val TURN = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""

class IdSlotRequestTest {

    private fun build(affinity: SlotAffinity?): BuiltChatRequest =
        ChatRequestBuilder(ChatQuirks(providerTag = "bonsai"), affinity = affinity).build(
            AnthropicParse.parseAnthropicBody(TURN).typed,
            upstreamModel = "bonsai",
            originalModel = "claude-bonsai--bonsai",
            compact = false,
            sessionId = "s1",
        )

    // Mutant: drop idSlot from the assembler's DTO. The lease is taken but the server never hears
    // it, and slots are chosen by similarity again.
    @Test
    fun `an opted-in head sends the leased slot as id_slot`() {
        val built = build(SlotAffinity { 4 })

        assertEquals(built.lease?.slot, built.req["id_slot"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a head without slot affinity sends no id_slot and holds no lease`() {
        val built = build(null)

        assertNull(built.req["id_slot"])
        assertNull(built.lease)
    }

    @Test
    fun `an unknown slot count sends the turn unpinned`() {
        val built = build(SlotAffinity { null })

        assertNull(built.req["id_slot"])
        assertNull(built.lease)
    }
}
