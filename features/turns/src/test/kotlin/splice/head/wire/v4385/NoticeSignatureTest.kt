package splice.head.wire.v4385

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.SpliceNotice
import splice.core.turn.Usage
import splice.head.wire.SseEmitterFactory

class NoticeSignatureTest {
    @Test
    fun `only splice's wait block carries its exact marker at close`() = runTest {
        val frames = mutableListOf<String>()
        val emitter = SseEmitterFactory().create(
            write = { frames += it },
            model = "claude-muse--m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_notice",
        )
        emitter.ensureStarted()
        val model = emitter.openThinking()
        emitter.thinkingDelta(model, "actual model thought")
        emitter.signatureDelta(model, "opaque-model-signature")
        emitter.closeBlock(model)
        emitter.progress { "[splice] holding this turn open." }
        emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())

        val signatures = frames.filter { it.contains("\"type\":\"signature_delta\"") }
        assertEquals(2, signatures.size, "the model and notice are signed once each: $frames")
        assertTrue(signatures.any { it.contains("opaque-model-signature") }, "model signature changed: $frames")
        assertTrue(signatures.any { it.contains(SpliceNotice.SIGNATURE) }, "splice notice has no marker: $frames")
        assertTrue(frames.any { it.contains("[splice] holding this turn open") }, "client lost the notice: $frames")
    }
}
