// NEW: the reasoning of a script's own round reaches the upstream body once, whichever path carried it.
// A round's reasoning can arrive by two paths: the ordinary builder puts the cached envelopes in the client's
// history before the call (ResponsesInputTools.appendToolUse), and the script's record stores the same
// envelopes as its continuity replay. The rewrite must emit each distinct item once at the script's slot.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.state.native.CodeModeAnchorCapture
import splice.upstream.codemode.CodeModeResult

internal class CodeModeReasoningArrivesOnceTest : CodeModeBridgeTestSupport() {
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val history = listOf(
        element("""{"role":"developer","content":"s"}"""),
        element("""{"role":"user","content":"Fix it."}"""),
    )
    private val round = listOf(reasoning("rs-a"), reasoning("rs-b"), reasoning("rs-c"))

    @Test
    fun `a round's reasoning in the client's history and in the record is emitted once before the callback`() {
        val client = history + round + callback("call-0")

        assertEquals(round, reasoningIn(rewrite(client, record(history))))
    }

    @Test
    fun `a round's reasoning in the client's history and in the record is emitted once past a context message`() {
        val client = history + round + ResponsesContextMessage.item("synthetic context") + callback("call-0")

        assertEquals(round, reasoningIn(rewrite(client, record(history))))
    }

    @Test
    fun `a record with no copy in the client's history still emits its reasoning once`() {
        assertEquals(round, reasoningIn(rewrite(history + callback("call-0"), record(history))))
    }

    private fun reasoningIn(items: List<JsonElement>): List<JsonElement> =
        items.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }

    private fun rewrite(input: List<JsonElement>, record: CodeModeRecord): List<JsonElement> {
        val result = CodexCodeModeHistory(Json).canonicalize(body(input), listOf(record))
        assertEquals(emptyList<String>(), result.omitted.map { it.reason }, "the record places")
        return checkNotNull(codec.root(checkNotNull(result.bodyJson))).second
    }

    private fun record(baseline: List<JsonElement>): CodeModeRecord {
        val boundary = checkNotNull(CodeModeAnchorCapture.inputBoundary(body(baseline), emptyList(), codec))
        return CodeModeRecord(
            id = "script-0",
            key = "key",
            phase = CodeModePhase.COMPLETED,
            origin = CodeModeOrigin(
                outer = outer("script-0").raw,
                outerCallId = "script-0",
                source = "source",
                baseline = CodeModeBaseline(
                    inputCount = boundary.fullCount,
                    inputDigest = "",
                    logicalCount = boundary.logicalCount,
                    logicalDigest = "",
                    metadataVersion = CODE_MODE_METADATA_VERSION,
                ),
            ),
            progress = CodeModeProgress(output = "done", updatedAt = 1_000, lastDigest = "request"),
            carry = CodeModeNativeContinuity(
                segments = boundary.nativeSegments,
                continuity = emptyList(),
                replay = listOf(CodeModeNativeSegment(0, round)),
            ),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            record.accepted.accept(mapOf("call-0" to CodeModeResult("call-0", "result")), emptyMap())
            record.issued += CodeModeIssuedStep(
                "request",
                listOf(CodeModePending("runtime", "call-0", "Read", JsonObject(emptyMap()), true)),
            )
        }
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        element("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        element("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun reasoning(id: String) =
        element("""{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"e"}""")

    private fun body(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()
    private fun element(text: String): JsonElement = Json.parseToJsonElement(text)
}
