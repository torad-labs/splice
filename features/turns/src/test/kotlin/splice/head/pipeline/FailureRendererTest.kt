// NEW: FailureRenderer is the one place a failure becomes client-facing text: a huge non-JSON vendor body
// cannot render whole into the transcript, and no relayed vendor sentence reaches the client with an em dash
// (each is spoken as a clause break). The classifier upstream of it keeps reading the raw text, where the dash
// is a clause boundary that decides whether "try again" is an invitation.
package splice.head.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier

class FailureRendererTest {

    private val renderer = FailureRenderer()

    @Test
    fun `a huge non-JSON vendor body is capped at the error snippet`() {
        // A space makes the body unparseable as JSON (a bare x-string would parse as a JsonLiteral
        // under the lenient parser), so this rides the non-JSON pass-through.
        val huge = "x ".repeat(10_000)

        val sentence = renderer.sentence(huge)

        assertEquals(200, sentence.length, sentence)
        assertEquals(huge.take(200), sentence)
    }

    @Test
    fun `a short non-JSON body rides through untouched`() {
        assertEquals("plain prose", renderer.sentence("plain prose"))
    }

    @Test
    fun `a blank non-JSON body is described, not echoed`() {
        assertEquals(
            "the upstream returned an error that could not be read",
            renderer.sentence("   "),
        )
    }

    @Test
    fun `relayed prose reaches the client with its em dashes spoken as clause breaks`() {
        assertEquals(
            "Rate limit reached; please slow down",
            renderer.sentence("Rate limit reached — please slow down"),
        )
        assertEquals("capacity; retry soon", renderer.sentence("capacity—retry soon"))
    }

    @Test
    fun `a lifted field is spoken the same way, however the vendor spelled the dash`() {
        assertEquals(
            "Quota exhausted; upgrade your plan",
            renderer.sentence("""{"detail":"Quota exhausted — upgrade your plan"}"""),
        )
        // The JSON escape, decoded by the parse: the dash reaches the lifted field as the character.
        assertEquals(
            "Overloaded; retry soon",
            renderer.sentence("""{"error":{"message":"Overloaded — retry soon"}}"""),
        )
        assertEquals("Busy; wait", renderer.sentence("\"Busy — wait\""))
    }

    @Test
    fun `the classifier still reads the raw dash as the clause boundary it is`() {
        val classified =
            UpstreamFailureClassifier.classify(FailureSource.SSE, "You cannot send this — try again in a minute")
        val unbroken =
            UpstreamFailureClassifier.classify(FailureSource.SSE, "You cannot send this try again in a minute")

        assertTrue(classified.transient, "the dash ends the negated clause, so the invitation stands")
        assertFalse(unbroken.transient, "without the boundary the negation reaches the invitation")
        assertTrue('—' in classified.message, "classification carries the vendor's own text")
        assertEquals("You cannot send this; try again in a minute", renderer.sentence(classified.message))
    }
}
