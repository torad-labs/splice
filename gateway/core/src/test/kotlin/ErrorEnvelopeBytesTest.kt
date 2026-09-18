// NEW: V4-102 (arch-audit, 2026-09-18) — the WIRE-BYTES pin for the envelope.
//
// The row consolidated four hand-written error envelopes into one builder in core. The argument for
// that being safe is that the builder reproduces the shape exactly — but an argument about the
// source is not a measurement of the bytes, and the oracle replay re-pins on bytes. So this pins
// the serialized STRING, not the object: a field order change, a dropped key, or a nested-vs-sibling
// move for `usage` all compile cleanly and would sail past any structural assertion.
//
// THE EXPECTED STRINGS ARE AUTHORED HERE BY HAND, never produced by calling the builder twice. A
// test that renders with the code under test and compares against the same code agrees with itself.
//
// WHY IT MATTERS BEYOND THIS ROW: this envelope is what our own fail-fast body claims to be, and
// `UpstreamFailureClassifier` reads `error.message` out of it. A shape drift would not fail loudly —
// it would make our own synthesized failure unreadable to the classifier that classifies it, which
// is the exact defect V4-59 was written to catch.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.wire.ErrorEnvelope

class ErrorEnvelopeBytesTest {

    @Test
    fun `the envelope serializes to exactly the bytes the four hand-written sites produced`() {
        // The shape all four sites built: type=error with a nested error object carrying type and
        // message. Key ORDER is part of the assertion because it is part of the bytes.
        assertEquals(
            """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""",
            ErrorEnvelope.of("rate_limit_error", "slow down").toString(),
        )
        assertEquals(
            """{"type":"error","error":{"type":"overloaded_error","message":"retry"}}""",
            ErrorEnvelope.of("overloaded_error", "retry").toString(),
        )
    }

    @Test
    fun `usage rides as a SIBLING of error, and is omitted entirely when absent`() {
        // Both halves matter. Nested inside `error` would be a plausible-looking refactor that
        // changes the wire contract silently; emitted as a JSON null when absent would change the
        // bytes for every caller that never had one.
        val usage: JsonObject = buildJsonObject { put("input_tokens", 7) }
        assertEquals(
            """{"type":"error","error":{"type":"api_error","message":"m"},"usage":{"input_tokens":7}}""",
            ErrorEnvelope.of("api_error", "m", usage).toString(),
        )
        assertEquals(
            """{"type":"error","error":{"type":"api_error","message":"m"}}""",
            ErrorEnvelope.of("api_error", "m").toString(),
        )
    }

    @Test
    fun `the classifier can still read our own synthesized body`() {
        // Why the shape matters beyond formatting: splice's own fail-fast body is THIS envelope, and
        // UpstreamFailureClassifier reads `error.message` out of whatever it is handed. Asserted at
        // the byte level here so a drift cannot make our synthesized failure unreadable to the
        // classifier that classifies it. The classifier's own suite covers the read itself.
        val rendered = ErrorEnvelope.of("rate_limit_error", "balance exhausted").toString()
        assertEquals(true, rendered.contains(""""message":"balance exhausted""""))
        assertEquals(true, rendered.contains(""""type":"rate_limit_error""""))
    }
}
