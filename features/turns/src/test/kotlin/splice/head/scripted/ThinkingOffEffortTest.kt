// NEW: thinking off never costs a turn its reasoning (Marcos, 2026-10-27: "thinking being off should never be a
// thing"). A Claude Code request whose thinking is disabled reaches the provider with the reasoning any other request
// gets, through a real head: on a Responses head the head's own effort and summary (splice authors a Responses head's
// effort, Marcos 2026-10-28), on a Chat head the effort it asked for. Every assertion reads the request the provider
// received.
package splice.head.scripted

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The effort and summary the scripted Responses head is configured with (ReasoningSettings in HeadProviders.kt). */
private const val HEAD_EFFORT = "high"
private const val HEAD_SUMMARY = "detailed"

class ThinkingOffEffortTest {
    @Test
    fun `a Responses head sends its own effort and summary when thinking is off`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { responsesAnswer("answered") })
            val head = responses()

            // low differs from the effort the head is configured with, so the head's own effort is what rides.
            for (asked in listOf("high", "low")) {
                val answer = head.client.send(thinkingOff(asked))

                val reasoning = sentTo("/responses")["reasoning"] as? JsonObject
                val seen = "asked $asked, sent reasoning $reasoning: ${answer.describe()}"
                assertEquals(HEAD_EFFORT, reasoning?.get("effort")?.jsonPrimitive?.content, seen)
                assertEquals(HEAD_SUMMARY, reasoning?.get("summary")?.jsonPrimitive?.content, seen)
            }
        }
    }

    @Test
    fun `a Chat head sends the asked effort when thinking is off`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })

            val answer = chat().client.send(thinkingOff("high"))

            val sent = sentTo("/chat/completions")
            val seen = "sent $sent: ${answer.describe()}"
            assertEquals("high", sent["reasoning_effort"]?.jsonPrimitive?.content, seen)
            assertEquals("high", (sent["reasoning"] as? JsonObject)?.get("effort")?.jsonPrimitive?.content, seen)
        }
    }

    /** Claude Code's request with its thinking off and [effort] asked for in output_config. */
    private fun thinkingOff(effort: String): ClientRequest = ClientRequest(
        extra = buildJsonObject {
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonObject("output_config") { put("effort", effort) }
        },
    )

    /** The body of the last request the provider received on [path]. */
    private fun ScriptedHeads.sentTo(path: String): JsonObject =
        Json.parseToJsonElement(provider.requests.last { it.path == path }.body).jsonObject
}
