// The scenarios whose stream ends badly: an in-stream failure, a frame past the cap, a stream that just stops.
package splice.head

/** Past the head's one-mebibyte frame cap by a byte. */
private const val OVERSIZED_FRAME_CHARS = 1024 * 1024 + 1
private const val PARTIAL_ANSWER = "partial answer"

internal class MockFailureScenarios(private val wire: MockSseWire) {
    private val e = wire.events

    /** Plays [scenario] when it is a failing one, and says whether it was. */
    fun play(scenario: String): Boolean {
        when (scenario) {
            "overload_sse", "overflow_sse" -> inStreamRefusal(scenario)
            "overflow_after_content" -> overflowAfterContent()
            "oversized_sse" -> oversizedFrame()
            "oversized_after_content" -> oversizedAfterContent()
            "truncated" -> truncated()
            "zero_event_auth", "zero_event_overflow", "zero_event_empty" -> zeroEvents(scenario)
            else -> return terminal(scenario)
        }
        return true
    }

    private fun terminal(scenario: String): Boolean {
        when (scenario) {
            "failed" -> wire.sse(e.failed("server_error", "boom upstream"))
            "empty_completed" -> wire.sse(e.completed("empty", 0, 0))
            // A complete handshake followed by EOF, with no model content or HTTP framing race.
            "truncated_before_content" -> wire.sse("""{"type":"response.created","response":{"id":"incomplete"}}""")
            else -> return false
        }
        return true
    }

    /** The capacity signal in its IN-STREAM shape — the 2026-09-01 20:56 compaction death — and the context
     *  overflow in the same shape. */
    private fun inStreamRefusal(scenario: String) {
        if (scenario == "overload_sse") {
            wire.sse(e.failed("server_is_overloaded", "The engine is currently overloaded, please try again later"))
        } else {
            wire.sse(
                e.failed(
                    "invalid_request_error",
                    "Your input exceeds the context window of this model. Please reduce the length.",
                ),
            )
        }
    }

    private fun overflowAfterContent() {
        partialAnswer()
        wire.sse(e.failed("invalid_request_error", "Your input exceeds the context window of this model."))
    }

    private fun partialAnswer() {
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta(PARTIAL_ANSWER))
    }

    private fun oversizedFrame() {
        wire.write("data: ")
        wire.write("x".repeat(OVERSIZED_FRAME_CHARS))
    }

    // An oversized frame AFTER content has reached the client: the tear can no longer be re-issued.
    private fun oversizedAfterContent() {
        partialAnswer()
        oversizedFrame()
    }

    private fun truncated() {
        partialAnswer()
        // no response.completed
    }

    private fun zeroEvents(scenario: String) {
        when (scenario) {
            "zero_event_auth" ->
                wire.write(
                    "<html><body>401 Unauthorized: your session token has expired, please sign in again.</body></html>",
                )
            // The provider's own JSON error in place of the stream, which the head's reader reads (failure-handling
            // slice 3): only such a body names a cause, and any other is a broken path.
            "zero_event_overflow" -> wire.write(
                """{"error":{"type":"invalid_request_error","code":"context_length_exceeded",""" +
                    """"message":"Your input exceeds the context window of this model."}}""",
            )
            // deliberately nothing written — a true stall, not a diagnosable auth-shaped body
            else -> Unit
        }
    }
}
