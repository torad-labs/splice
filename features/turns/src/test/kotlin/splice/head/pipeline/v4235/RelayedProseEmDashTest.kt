// NEW: V4-235 — no string the gateway writes into a frame carries an em dash, including the one kind no
// ast-grep wall can see: prose splice relays from an upstream. FailureRenderer is the one place a
// failure becomes client-facing text, and it passed a vendor's sentence through untouched, dashes and
// all. It now speaks each em dash as a clause break ("; "). The classifier is upstream of it and keeps
// reading the raw text, where the dash is a clause boundary that decides whether "try again" is an
// invitation.
package splice.head.pipeline.v4235

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.head.pipeline.FailureRenderer
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier

class RelayedProseEmDashTest {

    private val renderer = FailureRenderer()

    @Test
    fun `relayed prose reaches the client with its em dashes spoken as clause breaks - V4-235`() {
        assertEquals(
            "Rate limit reached; please slow down",
            renderer.sentence("Rate limit reached — please slow down"),
        )
        assertEquals("capacity; retry soon", renderer.sentence("capacity—retry soon"))
    }

    @Test
    fun `a lifted field is spoken the same way, however the vendor spelled the dash - V4-235`() {
        assertEquals(
            "Quota exhausted; upgrade your plan",
            renderer.sentence("""{"detail":"Quota exhausted — upgrade your plan"}"""),
        )
        // The JSON escape, decoded by the parse: the dash reaches the lifted field as the character.
        assertEquals(
            "Overloaded; retry soon",
            renderer.sentence("""{"error":{"message":"Overloaded \u2014 retry soon"}}"""),
        )
        assertEquals("Busy; wait", renderer.sentence("\"Busy — wait\""))
    }

    @Test
    fun `the classifier still reads the raw dash as the clause boundary it is - V4-235`() {
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
