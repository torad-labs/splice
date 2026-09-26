// V4-157: an EMPTY thinking block must never reach the upstream, and a signature must not save it.
//
// THE INCIDENT, 2026-09-18, a live operator session against api.anthropic.com. The whole request
// came back 400 invalid_request_error — `messages.903.content.0.thinking: each thinking block must
// contain thinking`, request_id req_011CfBPZe8HG2qTVWNVXBmZm. Index 903 is the point: the block sat
// deep in the replayed transcript Claude Code resends every turn, so EVERY retry resent it and no
// retry layer above this one could do anything about it. The turn was unrecoverable, the operator
// had to switch model mid-session, and compaction — which replays the same transcript — was
// impossible for the rest of it.
//
// THE FIXTURE IS THE INCIDENT SHAPE, not a hand-invented one: an assistant turn whose FIRST content
// block is a thinking block with blank text and a NON-EMPTY signature. `PassthroughMessageScrubber`
// used to require the signature to be EMPTY before it would drop a blank thinking block, so exactly
// this shape — blank AND signed — was judged content-bearing and rode upstream verbatim.
//
// BOTH HEADS, because the signature has two possible authors and the rule must not care which:
// a REAL upstream signature (the claude head, neutral quirks, which is what 400d) and splice's own
// `splice-synth-v1` (the kimi profile, whose `synthesizeSignatures` stamps an unsigned thinking
// block at close). Neither is a reason to send a block with no thinking in it.
//
// TWO CLASSES, ONE PER SIDE OF THE WIRE. [PassthroughEmptyThinkingTest] is the REQUEST side — the
// filter that closes the live defect, since it catches the shape whoever authored it.
// [PassthroughEmptyThinkingMintTest] is the RESPONSE side — splice must not MINT the shape in the
// first place, which is hardening rather than the fix: the incident came from an upstream block on
// a head with synthesis off, so the request side alone would have prevented it. Relying on a
// downstream filter to catch our own output is the shape that produced this defect, so both.
package splice.dialect.anthropic

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.upstream.sse.WireSink

class PassthroughEmptyThinkingTest {

    /** The claude head: a faithful passthrough, no allowlist, no signature synthesis — the head the
     *  operator was on when the 400 landed, so the drop cannot be an allowlist side effect. */
    private val claude = PassthroughQuirks(providerTag = "claude-splice")

    private val kimi = KimiProfileFixture().kimi("kimi")

    // A real Anthropic thinking signature is a long opaque base64 blob; its LENGTH is irrelevant and
    // its non-emptiness is the whole point, so the prefix is enough to be recognisably one.
    private val upstreamSignature = "ErUBCkYIBRgCIkDq0mSPLICEv4157"

    @Test
    fun `the incident shape never reaches the built request`() {
        val types = blockTypes(
            claude,
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"","signature":"$upstreamSignature"},
                {"type":"text","text":"Here is the plan."}
            ]}""",
        )

        assertEquals(listOf("text"), types, "the signed empty thinking block is what Anthropic 400s on")
    }

    /** The signature travels with the block, so proving the TYPE is gone is not enough — a rebuilt
     *  block that kept the signature under another type would pass the arm above. */
    @Test
    fun `no trace of the signed empty block rides upstream`() {
        val req = build(
            claude,
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"","signature":"$upstreamSignature"},
                {"type":"text","text":"Here is the plan."}
            ]}""",
        )

        assertFalse(req.toString().contains(upstreamSignature), req.toString())
    }

    /** splice's own synthesis is the other author of this shape: the kimi profile stamps
     *  `splice-synth-v1` on any thinking block that closed unsigned, an EMPTY one included, so the
     *  block we mint ourselves must fail the same rule as one an upstream sent us. */
    @Test
    fun `splice's own synthetic signature does not save an empty block either`() {
        val types = blockTypes(
            kimi,
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"","signature":"splice-synth-v1"},
                {"type":"text","text":"done"}
            ]}""",
        )

        assertEquals(listOf("text"), types, "a block splice signed itself is still a block with no thinking in it")
    }

    /** Anthropic's own rule is "must CONTAIN thinking", and whitespace contains none — which is why
     *  the pre-V4-157 scrubber already dropped this shape when it was UNSIGNED. Signing it changed
     *  nothing about what the upstream will accept. */
    @Test
    fun `whitespace-only thinking is empty whether or not it is signed`() {
        for (signature in listOf("", upstreamSignature)) {
            assertEquals(
                listOf("text"),
                blockTypes(
                    claude,
                    """{"role":"assistant","content":[
                        {"type":"thinking","thinking":" \n\t ","signature":"$signature"},
                        {"type":"text","text":"answer"}
                    ]}""",
                ),
                "signature='$signature'",
            )
        }
    }

    /** NEVER-BELOW-STATUS-QUO. Real reasoning rides VERBATIM, signature included — Anthropic
     *  verifies that signature on the way back, so a block this drop touched would be a worse defect
     *  than the one it fixes. */
    @Test
    fun `a signed thinking block that carries real reasoning is untouched`() {
        val block = blocks(
            claude,
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"weighing two routes","signature":"$upstreamSignature"}
            ]}""",
        ).single()

        assertEquals("thinking", block["type"]?.jsonPrimitive?.content)
        assertEquals("weighing two routes", block["thinking"]?.jsonPrimitive?.content)
        assertEquals(upstreamSignature, block["signature"]?.jsonPrimitive?.content)
    }

    /** The turn that carried NOTHING ELSE is the one the drop could make worse: an assistant message
     *  emptied to `content: []` is the V4-39 defect, a shape no backend here can act on. The
     *  substitute block that answers it is already in the scrubber and must cover this reason too. */
    @Test
    fun `an assistant turn that was only the signed empty block does not become an empty array`() {
        val content = blocks(
            claude,
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"","signature":"$upstreamSignature"}
            ]}""",
        )

        assertEquals(1, content.size, content.toString())
        assertEquals("text", content.single()["type"]?.jsonPrimitive?.content)
        assertTrue(
            content.single()["text"]!!.jsonPrimitive.content.contains("1 content block(s) omitted by claude-splice"),
            content.single().toString(),
        )
    }

    private fun build(quirks: PassthroughQuirks, message: String): JsonObject =
        PassthroughRequestBuilder(quirks).build(
            AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[$message]}"""),
            upstreamModel = "k3",
            originalModel = "claude-splice--k3[1m]",
            compact = false,
        ).req

    private fun blocks(quirks: PassthroughQuirks, message: String): List<JsonObject> =
        build(quirks, message)["messages"]!!.jsonArray.single().jsonObject["content"]!!
            .jsonArray.map { it.jsonObject }

    private fun blockTypes(quirks: PassthroughQuirks, message: String): List<String?> =
        blocks(quirks, message).map { it["type"]?.jsonPrimitive?.content }
}

/**
 * V4-157, the RESPONSE side: splice must not MINT the shape the class above filters.
 *
 * A provider may open a thinking block and close it having sent nothing — the Kimi behaviour CX-09
 * records — and `synthesizeSignatures` used to stamp that empty block exactly like a full one. A
 * signature is what makes Claude Code KEEP a thinking block, so the stamp was what carried the empty
 * block into the transcript, where it replayed every turn. Leaving it unsigned is the right end for
 * a block with nothing in it: Claude Code discards it.
 *
 * Its own class because [PassthroughStreamTranslatorTest] is at detekt's LargeClass ceiling and this
 * file's other class is about the request — the same one-class-per-subject idiom the translator test
 * file already follows (PassthroughStopReasonHonestyTest, PassthroughBlockEvictionTest).
 */
class PassthroughEmptyThinkingMintTest {

    private val kimi = KimiProfileFixture().kimi("kimi")

    @Test
    fun `a thinking block that received no thinking is never signed`() = runTest {
        val calls = drive(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_stop","index":0}""",
        )

        assertEquals(listOf("openThinking", "close", "closeAll"), calls, "nothing to sign, so no signature")
    }

    /** A block whose only delta was whitespace is the same block: `isNotBlank` is the threshold the
     *  prose channel already uses for its own flag, so the two agree on what "received thinking"
     *  means and the empty-turn honesty gate cannot disagree with the signature decision. */
    @Test
    fun `a thinking block that received only whitespace is never signed`() = runTest {
        val calls = drive(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":" \n "}}""",
            """{"type":"content_block_stop","index":0}""",
        )

        assertFalse(calls.any { it.startsWith("sig:") }, calls.toString())
    }

    /** NEVER-BELOW-STATUS-QUO, and the control without which "never sign anything" would satisfy
     *  both arms above: real reasoning on the synthesizing profile still gets its one signature. */
    @Test
    fun `a thinking block that received real reasoning is still signed exactly once`() = runTest {
        val calls = drive(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""",
            """{"type":"content_block_stop","index":0}""",
        )

        assertEquals(listOf("openThinking", "think:plan", "sig:splice-synth-v1", "close", "closeAll"), calls)
    }

    /** THE ARM THAT NAMES WHY THE FLAG IS PER-BLOCK. The turn-wide
     *  [splice.dialect.anthropic.PassthroughProseChannels.emittedThinking] latches once for the
     *  whole turn, so on a turn carrying one full thinking block and one empty one it cannot tell
     *  them apart — and it is the empty one that must not be signed. Exactly one signature, on the
     *  block that earned it. */
    @Test
    fun `a full block and an empty one in the same turn are judged separately`() = runTest {
        val calls = drive(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"weighed it"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_stop","index":1}""",
        )

        assertEquals(
            listOf(
                "openThinking",
                "think:weighed it",
                "sig:splice-synth-v1",
                "close",
                "openThinking",
                "close",
                "closeAll",
            ),
            calls,
            "one signature, on the block that earned it, and the second block closes unsigned",
        )
    }

    private suspend fun drive(vararg events: String): List<String> {
        val sink = Recorder()
        PassthroughStreamTranslator(
            PassthroughTurnContext({ false }, { null }, IDLE_CAP_MS, TOTAL_CAP_MS),
            kimi,
        ).driveTurn(events.map { Json.parseToJsonElement(it).jsonObject }.asFlow(), sink)
        return sink.calls
    }

    /** The transcript recorder the translator tests use, trimmed to the verbs a thinking block can
     *  drive — anything else arriving here would be a test that wandered off its subject. */
    private class Recorder : WireSink {
        val calls = mutableListOf<String>()
        private var n = 0
        override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
        override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
        override suspend fun openTool(id: String, name: String) = WireBlockIndex(n++).also { calls.add("openTool") }
        override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json") }
        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) { calls.add("sig:$signature") }
        override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
        override suspend fun closeAll() { calls.add("closeAll") }
        override suspend fun addTextBlock(text: String) { calls.add("addText") }
        override suspend fun addRedactedThinking(data: String) { calls.add("redacted") }
    }
}

// The watchdog tiers the translator tests construct their context with — no turn here runs long
// enough to reach either, so they are a shape the constructor needs rather than a threshold at test.
private const val IDLE_CAP_MS = 180_000L
private const val TOTAL_CAP_MS = 900_000L
