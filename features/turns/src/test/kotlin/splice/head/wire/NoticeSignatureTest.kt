package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.QuietTurn
import splice.core.turn.SpliceNotice
import splice.core.turn.Usage
import splice.head.testVoice

class NoticeSignatureTest {
    @Test
    fun `only splice's wait block carries its exact marker at close`() = runTest {
        val frames = mutableListOf<String>()
        val emitter = SseEmitterFactory().create(
            write = { frames += it },
            model = "claude-muse--m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            streaming = StreamWiring(messageId = "msg_notice"),
            voice = testVoice,
        )
        emitter.ensureStarted()
        val model = emitter.openThinking()
        emitter.thinkingDelta(model, "actual model thought")
        emitter.signatureDelta(model, "opaque-model-signature")
        emitter.closeBlock(model)
        val quiet = testVoice.quiet(QuietTurn(30_000, answering = false, again = false))
        emitter.progress { quiet }
        emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())

        val signatures = frames.filter { it.contains("\"type\":\"signature_delta\"") }
        assertEquals(2, signatures.size, "the model and notice are signed once each: $frames")
        assertTrue(signatures.any { it.contains("opaque-model-signature") }, "model signature changed: $frames")
        assertTrue(signatures.any { it.contains(SpliceNotice.SIGNATURE) }, "splice notice has no marker: $frames")
        assertTrue(frames.any { it.contains(quiet.text) }, "client lost the notice: $frames")
    }
}
