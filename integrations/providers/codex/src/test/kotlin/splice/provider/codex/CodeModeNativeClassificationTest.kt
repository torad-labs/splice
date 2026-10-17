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
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeNativeReplay
import splice.upstream.RoundBody

internal class CodeModeNativeClassificationTest {
    @Test
    fun `an appended declared callback does not erase the preceding native capture`() {
        val projection = ResponsesCodeModeProjection()
        val prefix = nativePrefix()
        val callback = declaredCallback()
        val before = projection.project(JsonArray(prefix))
        val after = projection.project(JsonArray(prefix + callback))
        assertEquals(prefix, projection.rebuild(after).take(prefix.size), "the raw prefix is byte-unchanged")
        assertEquals(3, before.nativeSegments.sumOf { it.items.size })
        assertEquals(0, after.nativeSegments.sumOf { it.items.size })
        assertEquals(before.replayItems.flatMap { it.items }, after.replayItems.flatMap { it.items })
        assertEquals(
            0,
            checkNotNull(CodexCodeModeHistory(Json).anchoredBoundary(body(prefix + callback), emptyList()))
                .nativeSegments.sumOf { it.items.size },
        )
        val restored = restoreWithCallback(prefix, callback)
        assertNull(restored.error, "the production placement map includes callback-scoped replay")
        val restoredRoot = Json.parseToJsonElement(checkNotNull(restored.bodyJson)).jsonObject
        val restoredInput = restoredRoot.getValue("input").jsonArray
        assertEquals(prefix + callback, restoredInput.toList())
    }

    private fun nativePrefix(): List<JsonElement> = listOf(
        item("""{"role":"user","content":"synthetic request"}"""),
        item("""{"type":"reasoning","id":"synthetic-reason","encrypted_content":"synthetic"}"""),
        searchCall(),
        searchOutput(),
    )

    private fun searchCall(): JsonElement = item(
        """{
            "type":"tool_search_call",
            "call_id":"ts_decl_371632585da0c0f317b97580",
            "execution":"client",
            "arguments":"{}"
        }""",
    )

    private fun searchOutput(): JsonElement = item(
        """{
            "type":"tool_search_output",
            "call_id":"ts_decl_371632585da0c0f317b97580",
            "execution":"client",
            "tools":[{"type":"function","name":"mcp__benchmark__read_shard"}]
        }""",
    )

    private fun declaredCallback(): JsonElement = callback("synthetic-callback")

    private fun callback(id: String): JsonElement = item(
        """{
            "type":"function_call",
            "call_id":"$id",
            "name":"mcp__benchmark__read_shard",
            "arguments":"{}"
        }""",
    )

    private fun restoreWithCallback(prefix: List<JsonElement>, callback: JsonElement): CodeModeRewrite {
        val history = CodexCodeModeHistory(Json)
        val boundary = checkNotNull(history.anchoredBoundary(body(prefix), emptyList()))
        val owner = item(
            """{"type":"custom_tool_call","call_id":"synthetic-owner","name":"exec","input":"return 'synthetic';"}""",
        ).jsonObject
        val record = CodeModeRecord(
            id = "synthetic-owner",
            key = "synthetic-conversation",
            phase = CodeModePhase.ACTIVE,
            origin = CodeModeOrigin(
                outer = owner,
                outerCallId = "synthetic-owner",
                source = "return 'synthetic';",
                baseline = CodeModeBaseline(
                    inputCount = boundary.fullCount,
                    inputDigest = boundary.fullDigest,
                    logicalCount = boundary.logicalCount,
                    logicalDigest = boundary.logicalDigest,
                    metadataVersion = CODE_MODE_METADATA_VERSION,
                ),
            ),
            progress = CodeModeProgress(updatedAt = 0, lastDigest = "synthetic-request"),
            carry = CodeModeNativeContinuity(boundary.nativeSegments, emptyList(), emptyList()),
        ).also { it.replayAnchors = boundary.replayAnchors }
        return history.restoreBaseline(body(prefix + callback), record)
    }

    @Test
    fun `callback declaration stays attached to retained callback after script insertion`() {
        val projection = ResponsesCodeModeProjection()
        val raw = JsonArray(
            listOf(
                item("""{"role":"user","content":"request"}"""),
                searchCall(),
                searchOutput(),
                callback("callback"),
            ),
        )
        val input = projection.project(raw)
        assertEquals("callback", input.replayItems.single().callbackId)

        // Canonical output is inserted at the same old boundary as the callback. The generic
        // offset points before this pair; callback affinity must point just before function_call.
        val codec = CodexCodeModeHistoryCodec(Json)
        val index = CodeModeHistoryIndex(input.logicalItems, codec)
        val remapped = CodeModeNativeReplay(codec, input, index, emptyList())
            .rewrite(emptyList(), intArrayOf(0, 1, 4), emptyMap())
        val logical = listOf(
            input.logicalItems[0],
            item("""{"type":"custom_tool_call","call_id":"exec","name":"exec","input":"return 1;"}"""),
            item("""{"type":"custom_tool_call_output","call_id":"exec","output":"1"}"""),
            input.logicalItems[1],
        )
        val rebuilt = projection.rebuild(ResponsesCodeModeInput(logical, remapped))
        val reprojected = projection.project(rebuilt)
        assertEquals(0, reprojected.nativeSegments.sumOf { it.items.size })
        assertEquals("callback", reprojected.replayItems.single().callbackId)
    }

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
