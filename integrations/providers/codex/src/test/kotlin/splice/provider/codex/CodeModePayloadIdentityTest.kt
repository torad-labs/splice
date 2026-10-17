// NEW: payload tokens preserve collision rejection, JSON equality, and slot-local replay occurrence policy.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.state.CodeModeNativeChain
import splice.upstream.RoundBody

internal class CodeModePayloadIdentityTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"synthetic first"}""")
    private val last = item("""{"role":"user","content":"synthetic last"}""")

    @Test
    fun `equal hash edited native payloads remain rejected`() {
        val expected = native("Aa")
        val edited = native("BB")
        assertEquals(expected.hashCode(), edited.hashCode(), "the fixture must collide")
        val record = record(listOf(first, expected, last))
        val result = history.restoreBaseline(body(listOf(first, edited, last, record.origin.outer)), record)
        assertEquals("code-mode native discovery history was edited: payload", result.error)
    }

    @Test
    fun `reordered equal fields retain the original canonical transport bytes`() {
        val expected = native("Aa")
        val reordered = item("""{"encrypted_content":"Aa","id":"synthetic-native","type":"reasoning"}""")
        assertEquals(expected, reordered)
        val record = record(listOf(first, expected, last))
        val result = history.restoreBaseline(body(listOf(first, reordered, last, record.origin.outer)), record)
        assertNull(result.error)
        assertArrayEquals(
            body(listOf(first, expected, last, record.origin.outer)).round.bytes(),
            checkNotNull(result.body).round.bytes(),
        )
    }

    @Test
    fun `emission keeps collisions distinct and equal copies only once at their own slot`() {
        val first = native("Aa")
        val collision = native("BB")
        val copy = item("""{"encrypted_content":"Aa","id":"synthetic-native","type":"reasoning"}""")
        val replay = CodeModeNativeChain.emittedReplay(
            listOf(
                ResponsesCodeModeReplay(1, null, listOf(first, collision, copy)),
                ResponsesCodeModeReplay(1, null, listOf(copy)),
                ResponsesCodeModeReplay(3, null, listOf(copy)),
            ),
        )
        assertEquals(listOf(1, 3), replay.map { it.logicalOffset })
        assertEquals(listOf(first, collision), replay[0].items)
        assertSame(first, replay[0].items[0])
        assertEquals(listOf(copy), replay[1].items)
        assertSame(copy, replay[1].items[0])
    }

    private fun record(items: List<JsonElement>): CodeModeRecord {
        val baseline = checkNotNull(history.anchoredBoundary(body(items), emptyList()))
        return CodeModeRecord(
            id = "synthetic-record",
            key = "synthetic-conversation",
            phase = CodeModePhase.ACTIVE,
            origin = CodeModeOrigin(
                outer = item(
                    """{"type":"custom_tool_call","call_id":"synthetic-call","name":"exec","input":"return 1"}""",
                ) as JsonObject,
                outerCallId = "synthetic-call",
                source = "return 1",
                baseline = CodeModeBaseline(
                    inputCount = baseline.fullCount,
                    inputDigest = baseline.fullDigest,
                    logicalCount = baseline.logicalCount,
                    logicalDigest = baseline.logicalDigest,
                    metadataVersion = CODE_MODE_METADATA_VERSION,
                ),
            ),
            progress = CodeModeProgress(updatedAt = 0, lastDigest = "synthetic-request"),
            carry = CodeModeNativeContinuity(baseline.nativeSegments, emptyList(), emptyList()),
        ).also { it.replayAnchors = baseline.replayAnchors }
    }

    private fun native(content: String): JsonElement =
        item("""{"type":"reasoning","id":"synthetic-native","encrypted_content":"$content"}""")

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)
}
