// NEW: adversarial v5 histories preserve unowned replay, late results, and surviving local anchors.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeAnchorCapture
import splice.upstream.codemode.CodeModeResult

internal class CodeModeAnchoredIntegrityTest : CodeModeBridgeTestSupport() {
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val user = element("""{"role":"user","content":"same"}""")
    private val next = element("""{"role":"user","content":"next"}""")
    private val native = element("""{"type":"reasoning","id":"root","encrypted_content":"original"}""")

    @Test
    fun `a no-callback child remains after the newer user message it answered`() {
        val parent = record("parent", listOf(user), "parent-call")
        val canonicalParent = listOf(user, parent.outer, codec.customOutput(parent))
        val child = record("child", canonicalParent + next, null, listOf(parent))
        child.nativeParent = parent
        val rewritten = rewrite(canonicalParent + next, listOf(parent, child))
        val logical = projected(rewritten).logicalItems
        assertTrue(logical.indexOf(next) < logical.indexOf(child.outer))
    }

    @Test
    fun `an earlier duplicate can disappear while the owned callback and its anchor survive`() {
        val record = record("newer", listOf(user, user), "call")
        val rewritten = rewrite(listOf(user) + callback("call"), listOf(record))
        assertFalse("function_call" in rewritten)
        assertTrue("newer" in rewritten)
    }

    @Test
    fun `identical native bytes at an unrelated later position stay in the client history`() {
        val record = record("native", listOf(native, user), "call")
        val rewritten = rewrite(listOf(native, user) + callback("call") + next + native, listOf(record))
        assertEquals(2, projected(rewritten).replayItems.sumOf { it.items.count { item -> item == native } })
    }

    @Test
    fun `edited native payload omits its record instead of mixing old and new copies`() {
        val record = record("native", listOf(native, user), "call")
        val edited = element("""{"type":"reasoning","id":"root","encrypted_content":"edited"}""")
        val input = listOf(edited, user) + callback("call")
        val result = CodexCodeModeHistory(Json).canonicalize(body(input), listOf(record))
        assertEquals(1, result.omitted.size)
        assertEquals(projected(body(input)), projected(checkNotNull(result.bodyJson)))
    }

    @Test
    fun `a child whose parent is excluded still restores its inherited native payload`() {
        val parent = record("parent", listOf(native, user), "parent-call")
        val baseline = listOf(native, user, parent.outer, codec.customOutput(parent), next)
        val child = record("child", baseline, "child-call", listOf(parent)).copy(nativeSegments = emptyList()).also {
            it.replayAnchors = CodeModeAnchorCapture.inputBoundary(body(baseline), listOf(parent), codec)?.replayAnchors
            it.nativeParent = parent
            it.nativeBaseId = parent.id
        }
        val rewritten = rewrite(listOf(user) + callback("parent-call") + next + callback("child-call"), listOf(child))
        assertEquals(1, projected(rewritten).replayItems.sumOf { it.items.count { item -> item == native } })
    }

    @Test
    fun `an ambiguous forged callback does not erase either client call`() {
        val record = record("owned", listOf(user), "call")
        val forged = element("""{"type":"function_call","call_id":"call","name":"Other","arguments":"{}"}""")
        val input = listOf(user, forged) + callback("call")
        val result = CodexCodeModeHistory(Json).canonicalize(body(input), listOf(record))
        assertEquals(1, result.omitted.size)
        assertEquals(projected(body(input)), projected(checkNotNull(result.bodyJson)))
    }

    @Test
    fun `a late unaccepted callback keeps its call and result as ordinary history`() {
        val record = record("interrupted", listOf(user), null)
        record.pending += CodeModePending("runtime", "late", "Read", JsonObject(emptyMap()), true)
        val rewritten = rewrite(listOf(user) + callback("late"), listOf(record))
        assertTrue("function_call" in rewritten)
        assertTrue("function_call_output" in rewritten)
        assertTrue("late" in rewritten)
    }

    private fun record(
        id: String,
        baseline: List<JsonElement>,
        callback: String?,
        completed: List<CodeModeRecord> = emptyList(),
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
            continuityReplay = emptyList(),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            callback?.let {
                record.accepted.accept(mapOf(it to CodeModeResult(it, "result")), emptyMap())
                record.issued += CodeModeIssuedStep(
                    "request",
                    listOf(CodeModePending("runtime", it, "Read", JsonObject(emptyMap()), true)),
                )
            }
        }
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        element("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        element("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun rewrite(input: List<JsonElement>, records: List<CodeModeRecord>): String =
        checkNotNull(CodexCodeModeHistory(Json).canonicalize(body(input), records).bodyJson)

    private fun projected(body: String) = codec.projection.project(checkNotNull(codec.root(body)).second)
    private fun body(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()
    private fun element(text: String): JsonElement = Json.parseToJsonElement(text)
}
