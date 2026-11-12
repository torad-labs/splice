// NEW: Oct 10, 2026 — a received message is read through its envelope and its sender, from the tag when the client
// writes one and from the envelope when it does not.
package splice.client.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PeerEnvelopeTest {
    private val envelope = PeerEnvelope()

    private fun record(json: String) = Json.parseToJsonElement(json).jsonObject

    private val wrapped = "Another Claude session sent a message:\n" +
        "<cross-session-message from=\"uds:/run/x.sock\" from-name=\"splice-lead\" from-mode=\"bypass\">\n" +
        "Push is done.\n\nSecond paragraph.\n</cross-session-message>\n\nThis came from another Claude session."

    @Test
    fun `a tagged record names its sender and carries the body without the envelope`() {
        val tagged = record("""{"type":"user","origin":{"kind":"peer","name":"splice-lead","body":"Push is done."}}""")
        assertEquals(ReceivedMessage("splice-lead", "Push is done."), envelope.read(tagged, wrapped))
    }

    @Test
    fun `a record with no tag is read from its envelope, the sender named by its name before its address`() {
        val untagged = record("""{"type":"user"}""")
        val whole = ReceivedMessage("splice-lead", "Push is done.\n\nSecond paragraph.")
        assertEquals(whole, envelope.read(untagged, wrapped))
        val onlyAddress = "<cross-session-message from=\"uds:/run/x.sock\">\nhi\n</cross-session-message>"
        assertEquals(ReceivedMessage("uds:/run/x.sock", "hi"), envelope.read(untagged, onlyAddress))
    }

    @Test
    fun `the person's own words and other origins are not received messages`() {
        assertNull(envelope.read(record("""{"type":"user","origin":{"kind":"human"}}"""), "continue"))
        val notice = record("""{"type":"user","origin":{"kind":"task-notification"}}""")
        assertNull(envelope.read(notice, "<task-notification>"))
        assertNull(envelope.read(record("""{"type":"user"}"""), null))
    }
}
