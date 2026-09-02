// DR-76 (fresh-eyes sweep): JsonNull IS a JsonPrimitive whose .content is the literal "null" —
// a Responses reasoning item carrying "encrypted_content": null minted an envelope holding the
// four bytes n-u-l-l as its ciphertext, and a null-carrying envelope decoded into a poisoned
// reasoning item replayed upstream. The JsonScalars read (the ResponsesReasoningReplay sibling
// idiom) makes JSON-null and absent the same no-envelope / dropped answer on BOTH paired sides.
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.util.LogSink
import java.util.Base64

class ReasoningReplayEnvelopeTest {

    @Test
    fun `a JSON-null encrypted_content mints no envelope - DR-76`() {
        val item = buildJsonObject {
            put("id", JsonPrimitive("rs_1"))
            put("encrypted_content", JsonNull)
        }
        assertNull(ReasoningReplay.encodeReasoningEnvelope(item))
    }

    @Test
    fun `a JSON-null id mints no envelope - DR-76`() {
        val item = buildJsonObject {
            put("id", JsonNull)
            put("encrypted_content", JsonPrimitive("ciphertext"))
        }
        assertNull(ReasoningReplay.encodeReasoningEnvelope(item))
    }

    @Test
    fun `an envelope carrying a JSON-null encrypted_content is dropped - DR-76`() {
        val envelope = """{"tag":"splice-reasoning","v":1,"item":{"id":"rs_1","encrypted_content":null}}"""
        val data = Base64.getEncoder().encodeToString(envelope.toByteArray(Charsets.UTF_8))
        assertNull(ReasoningReplay.decodeReasoningEnvelope(data, LogSink { }))
    }

    @Test
    fun `a real item still round-trips - DR-76 control`() {
        val item = buildJsonObject {
            put("id", JsonPrimitive("rs_1"))
            put("encrypted_content", JsonPrimitive("ciphertext"))
        }
        val encoded = ReasoningReplay.encodeReasoningEnvelope(item)
        assertNotNull(encoded)
        assertNotNull(ReasoningReplay.decodeReasoningEnvelope(encoded, LogSink { }))
    }
}
