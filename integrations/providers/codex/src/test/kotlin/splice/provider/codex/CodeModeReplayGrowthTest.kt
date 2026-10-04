// NEW: a code-mode conversation's upstream body stays proportional to the client's. On
// 2026-10-04 one claudex session's posts went 5.4, 10.2, 19.6 and 37.9 MB from a 0.75 MB client body:
// its persisted records held one reasoning item 1, 2, 4 ... 1024 times at the same slot. Each script's
// record is captured from the body splice posted, and a capture whose slots no longer line up with its
// predecessor's is a root holding the whole native history, the reasoning the earlier records emit as
// their own continuity included. Every owner re-emitted its copy at the shared slot, so each post
// carried the reasoning twice as often as the one before, and the next root captured that.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeAnchorCapture
import splice.upstream.codemode.CodeModeResult

private const val SCRIPTS = 6

internal class CodeModeReplayGrowthTest : CodeModeBridgeTestSupport() {
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val history = listOf(
        element("""{"role":"developer","content":"s"}"""),
        element("""{"role":"user","content":"Fix it."}"""),
    )

    @Test
    fun `scripts captured from splice's own posts keep each reasoning item once`() {
        var client = history + callback("call-0")
        val records = mutableListOf(record("script-0", history, "call-0", emptyList()))
        val posted = mutableListOf<String>()
        repeat(SCRIPTS) { n ->
            val post = rewrite(client, records)
            posted += post
            // The next script starts in that post, so its record is captured from it. Its slots no longer
            // line up with its predecessor's, so it is a root: it holds the whole native history.
            records += record("script-${n + 1}", items(post), "call-${n + 1}", records.toList())
            client = client + callback("call-${n + 1}")
        }
        val reasoning = posted.map { post ->
            items(post).filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        }
        val counts = reasoning.map { it.size }
        reasoning.forEachIndexed { n, items ->
            assertEquals(items.distinct(), items, "post $n carries each reasoning item once; per post: $counts")
            assertEquals(n + 1, items.size, "post $n carries one reasoning item per script; per post: $counts")
        }
        val growth = posted.zipWithNext { a, b -> b.length - a.length }
        assertTrue(growth.distinct().size == 1, "each script adds the same bytes to the post: $growth")
    }

    /** A completed script started on [baseline]: one client callback, and its reasoning before the call. */
    private fun record(
        id: String,
        baseline: List<JsonElement>,
        callback: String,
        completed: List<CodeModeRecord>,
    ): CodeModeRecord {
        val boundary = checkNotNull(CodeModeAnchorCapture.inputBoundary(body(baseline), completed, codec))
        return CodeModeRecord(
            id = id,
            key = "key",
            outer = outer(id).raw,
            outerCallId = id,
            source = "source",
            phase = CodeModePhase.COMPLETED,
            output = "done",
            updatedAt = 1_000,
            lastDigest = "request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = "",
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = "",
            nativeSegments = boundary.nativeSegments,
            continuity = emptyList(),
            continuityReplay = listOf(CodeModeNativeSegment(0, listOf(reasoning("rs-$id")))),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            record.accepted.accept(mapOf(callback to CodeModeResult(callback, "result")), emptyMap())
            record.issued += CodeModeIssuedStep(
                "request",
                listOf(CodeModePending("runtime", callback, "Read", JsonObject(emptyMap()), true)),
            )
        }
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        element("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        element("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun reasoning(id: String) =
        element("""{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"e"}""")

    private fun rewrite(input: List<JsonElement>, records: List<CodeModeRecord>): String {
        val result = CodexCodeModeHistory(Json).canonicalize(body(input), records)
        assertEquals(emptyList<String>(), result.omitted.map { it.reason }, "every record places")
        return checkNotNull(result.bodyJson)
    }

    private fun items(body: String): List<JsonElement> = checkNotNull(codec.root(body)).second
    private fun body(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()
    private fun element(text: String): JsonElement = Json.parseToJsonElement(text)
}
