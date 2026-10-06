// NEW: append-only raw replay can change projection ownership without changing any captured payload.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.upstream.RoundBody

internal class CodeModeNativeClassificationTest {
    @Test
    fun `an appended declared callback does not erase the preceding native capture`() {
        val projection = ResponsesCodeModeProjection()
        val prefix = listOf(
            item("""{"role":"user","content":"synthetic request"}"""),
            item("""{"type":"reasoning","id":"synthetic-reason","encrypted_content":"synthetic"}"""),
            item("""{"type":"tool_search_call","call_id":"synthetic-search","arguments":"{}"}"""),
            item("""{"type":"tool_search_output","call_id":"synthetic-search","tools":[{"name":"Read"}]}"""),
        )
        val callback = item(
            """{"type":"function_call","call_id":"synthetic-callback","name":"Read","arguments":"{}"}""",
        )
        val before = projection.project(JsonArray(prefix))
        val after = projection.project(JsonArray(prefix + callback))
        assertEquals(prefix, projection.rebuild(after).take(prefix.size), "the raw prefix is byte-unchanged")
        assertEquals(3, before.nativeSegments.sumOf { it.items.size })
        assertEquals(0, after.nativeSegments.sumOf { it.items.size })
        assertEquals(before.replayItems.flatMap { it.items }, after.replayItems.flatMap { it.items })
        val history = CodexCodeModeHistory(Json)
        val baseline = body(prefix)
        val boundary = checkNotNull(history.anchoredBoundary(baseline, emptyList()))
        val record = CodeModeRecord(
            id = "synthetic-owner",
            key = "synthetic-conversation",
            outer = item(
                """{"type":"custom_tool_call","call_id":"synthetic-owner","name":"exec","input":"return 'synthetic';"}""",
            ).jsonObject,
            outerCallId = "synthetic-owner",
            source = "return 'synthetic';",
            phase = CodeModePhase.ACTIVE,
            updatedAt = 0,
            lastDigest = "synthetic-request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = boundary.nativeSegments,
            continuity = emptyList(),
            continuityReplay = emptyList(),
        ).also { it.replayAnchors = boundary.replayAnchors }
        val restored = history.restoreBaseline(body(prefix + callback), record)
        assertNull(restored.error, "the production placement map includes callback-scoped replay")
        val restoredRoot = Json.parseToJsonElement(checkNotNull(restored.bodyJson)).jsonObject
        val restoredInput = restoredRoot.getValue("input").jsonArray
        assertEquals(prefix + callback, restoredInput.toList())
    }

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
