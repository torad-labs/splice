// NEW: V4-165 — the slot count is read from llama-server's own /props, beside the OpenAI base.
// V4-166: every "no" carries why, for the one log line that says slot affinity is paused.
package splice.upstream.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.transport.LocalHttpReply

class LlamaServerSlotsTest {

    @Test
    fun `total_slots is read from the server root, not from under v1`() {
        val asked = mutableListOf<String>()
        val slots = LlamaServerSlots("http://127.0.0.1:8099/v1") { _, url, _ ->
            asked += url
            LocalHttpReply(200, """{"total_slots":4,"default_generation_settings":{"n_ctx":262144}}""")
        }

        assertEquals(SlotsReading.Count(4), slots.read())
        assertEquals(listOf("http://127.0.0.1:8099/props"), asked)
    }

    // Every "no" sends the turn unpinned — today's behaviour, never a failure — and says why: a
    // router-mode server (200 without total_slots), a non-llama runtime (404), a keyed /props (401).
    @Test
    fun `a server that is down, refusing, or silent on slots says why it gave no count`() {
        fun read(reply: LocalHttpReply?) = LlamaServerSlots("http://127.0.0.1:8099/v1") { _, _, _ -> reply }.read()

        assertEquals(SlotsReading.Unreadable("http://127.0.0.1:8099/props is unreachable"), read(null))
        assertEquals(
            SlotsReading.Unreadable("http://127.0.0.1:8099/props answered HTTP 401 without a slot count"),
            read(LocalHttpReply(401, """{"error":{"code":401,"message":"Invalid API Key"}}""")),
        )
        assertEquals(
            SlotsReading.Unreadable("http://127.0.0.1:8099/props answered HTTP 404 without a slot count"),
            read(LocalHttpReply(404, "")),
        )
        assertEquals(
            SlotsReading.Unreadable("http://127.0.0.1:8099/props answered HTTP 200 without a slot count"),
            read(LocalHttpReply(200, "not json")),
        )
        assertEquals(
            SlotsReading.Unreadable("http://127.0.0.1:8099/props answered HTTP 200 without a slot count"),
            read(LocalHttpReply(200, "{}")),
        )
    }
}
