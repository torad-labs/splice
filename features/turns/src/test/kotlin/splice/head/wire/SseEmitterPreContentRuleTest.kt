// NEW: split out of SseEmitterTest (failure-handling slice 2, 2026-10-26) when that class passed detekt's LargeClass
// ceiling: the pre-content wire-type rule's cells, as they were there.
package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.turn.ErrorType
import splice.head.testVoice

class SseEmitterPreContentRuleTest {

    private val emitters = SseEmitterFactory()

    // THE PRE-CONTENT WIRE-TYPE RULE, PINNED WHERE IT NOW RUNS.
    //
    // The rule used to be restated at four failure surfaces (TurnKnownEnd, TurnEnding, TurnConnEnd,
    // TurnPipeline); a fifth ending could be added without it, and TurnFinish had to snapshot perf
    // on every turn just to feed it. It lives here now, at the one place an error frame can be
    // written, so these cells are the seam's own proof rather than a policy mirrored from it. The
    // rule's TABLE (which types remap, which are exempt) is ClientWire's, pinned over every leaf by
    // ClientWireTodayPropertyTest; what these cells add is that the emitter actually CONSULTS it, on
    // the frame it writes, with where the stream stood.
    //
    // MUTATION PROOF (recorded in the ledger): replace `ClientWire.answer(cause, at).type` at
    // SseEmitter.emitError with `ClientWire.ownType(cause)` and the relabel cell below goes red BY
    // NAME while the exempt cells stay green — so this pins the RULE's application, not the
    // constant OVERLOADED. The lambda being a FUNCTION is separately load-bearing: make it a
    // snapshot at construction and the "content arrives first" cell goes red.
    private fun errorTypeIn(frames: List<String>): String =
        frames.single { it.startsWith("event: error") }

    @Test
    fun `the pre-content rule is applied at the frame - a pre-content api_error is wired as overloaded`() =
        runTest {
            val frames = mutableListOf<String>()
            val e = emitters.create(
                write = { frames.add(it) },
                model = "claude-codex--gpt-5.6-sol",
                usagePayload = { buildJsonObject { } },
                streaming = StreamWiring(contentReached = { false }, messageId = "msg_fixed"),
                voice = testVoice,
            )
            e.emitError(FailureCause.Provider.Unrecognized(clientError = false))

            assertTrue(
                errorTypeIn(frames).contains("\"type\":\"${ErrorType.OVERLOADED.wireName}\""),
                "nothing was read yet, so the wire type is the one Claude Code retries: $frames",
            )
        }

    @Test
    fun `the rule reads content LIVE - content arriving after construction closes the remap`() = runTest {
        // The emitter is built before the first byte, so the answer has to be a question asked at
        // the failure, not an answer recorded at the open. This is the cell that fails if the
        // lambda is collapsed into a Boolean.
        val frames = mutableListOf<String>()
        var reached = false
        val e = emitters.create(
            write = { frames.add(it) },
            model = "claude-codex--gpt-5.6-sol",
            usagePayload = { buildJsonObject { } },
            streaming = StreamWiring(contentReached = { reached }, messageId = "msg_fixed"),
            voice = testVoice,
        )
        e.openText()
        reached = true
        e.emitError(FailureCause.Provider.Unrecognized(clientError = false))

        assertTrue(
            errorTypeIn(frames).contains("\"type\":\"${ErrorType.API_ERROR.wireName}\""),
            "content already reached the client, so the type rides through: $frames",
        )
    }

    @Test
    fun `a permanent failure keeps its real type and is never advertised as transient`() = runTest {
        val frames = mutableListOf<String>()
        val e = emitters.create(
            write = { frames.add(it) },
            model = "claude-codex--gpt-5.6-sol",
            usagePayload = { buildJsonObject { } },
            streaming = StreamWiring(contentReached = { false }, messageId = "msg_fixed"),
            voice = testVoice,
        )
        e.emitError(FailureCause.Provider.Unrecognized(clientError = true))

        assertTrue(
            errorTypeIn(frames).contains("\"type\":\"${ErrorType.API_ERROR.wireName}\""),
            "a retry reproduces this verdict exactly, so it is not sold as retryable: $frames",
        )
    }

    @Test
    fun `a caller that cannot prove nothing was written takes the inert default`() = runTest {
        // SseEmitterFactory.create defaults contentReached to true: the local answer and the
        // compaction replay have no counter, and a path that cannot PROVE the client has read
        // nothing must not be handed a relabel it cannot justify. Its types stay as they were.
        val frames = mutableListOf<String>()
        val e = emitters.create(
            write = { frames.add(it) },
            model = "claude-codex--gpt-5.6-sol",
            usagePayload = { buildJsonObject { } },
            voice = testVoice,
        )
        e.emitError(FailureCause.Provider.Unrecognized(clientError = false))

        assertTrue(
            errorTypeIn(frames).contains("\"type\":\"${ErrorType.API_ERROR.wireName}\""),
            "the default is inert, not optimistic: $frames",
        )
    }
}
