// the perf row joins Claude Code's transcript by the id the client actually received.
// Both terminal shapes mint that id before writing any content; a pass-through translator uses
// the gateway emitter as well, so the upstream's own id is not the one to record.
package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.Usage

class TerminalResponseIdTest {
    @Test
    fun `streaming terminal exposes exactly its message_start id`() = runTest {
        val frames = mutableListOf<String>()
        val emitter = SseEmitterFactory().create(
            write = { frames += it },
            model = "claude-e2e--m",
            usagePayload = { buildJsonObject { } },
            messageId = "msg_stream_42",
        )
        emitter.ensureStarted()
        val opener = frames.first { it.startsWith("event: message_start") }
            .lineSequence().first { it.startsWith("data:") }.removePrefix("data:").trim()
        val onWire = Json.parseToJsonElement(opener).jsonObject.getValue("message").jsonObject
            .getValue("id").jsonPrimitive.content

        assertEquals("msg_stream_42", emitter.responseMessageId)
        assertEquals(onWire, emitter.responseMessageId)
    }

    @Test
    fun `collected terminal exposes exactly its reply body's id`() = runTest {
        val terminal = CollectingTerminal(
            model = "claude-e2e--m",
            usagePayload = { buildJsonObject { } },
            messageId = "msg_collect_43",
        )
        terminal.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())

        assertEquals("msg_collect_43", terminal.responseMessageId)
        assertEquals(terminal.responseBody().getValue("id").jsonPrimitive.content, terminal.responseMessageId)
    }
}
