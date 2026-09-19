// NEW: V4-165 — the slot count is read from llama-server's own /props, beside the OpenAI base.
package campaign.v4165

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.spi.LocalHttpReply
import splice.spi.local.LlamaServerSlots

class LlamaServerSlotsTest {

    @Test
    fun `total_slots is read from the server root, not from under v1`() {
        val asked = mutableListOf<String>()
        val slots = LlamaServerSlots("http://127.0.0.1:8099/v1") { _, url, _ ->
            asked += url
            LocalHttpReply(200, """{"total_slots":4,"default_generation_settings":{"n_ctx":262144}}""")
        }

        assertEquals(4, slots.read())
        assertEquals(listOf("http://127.0.0.1:8099/props"), asked)
    }

    // Every "no" is null, and null sends the turn unpinned — today's behaviour, never a failure.
    @Test
    fun `a server that is down, refusing, or silent on slots gives no count`() {
        assertNull(LlamaServerSlots("http://127.0.0.1:8099/v1") { _, _, _ -> null }.read())
        assertNull(LlamaServerSlots("http://127.0.0.1:8099/v1") { _, _, _ -> LocalHttpReply(404, "") }.read())
        assertNull(LlamaServerSlots("http://127.0.0.1:8099/v1") { _, _, _ -> LocalHttpReply(200, "not json") }.read())
        assertNull(LlamaServerSlots("http://127.0.0.1:8099/v1") { _, _, _ -> LocalHttpReply(200, "{}") }.read())
    }
}
