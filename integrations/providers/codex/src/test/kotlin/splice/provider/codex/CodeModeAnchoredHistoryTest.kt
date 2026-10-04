// NEW: a newer script places by its own callbacks even when an earlier canonical record is gone.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.InputDigest
import splice.upstream.codemode.CodeModeStep

internal class CodeModeAnchoredHistoryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `removing an earlier record does not prevent a newer callback rewrite`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    steps("first"),
                    steps("second"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val initial = """{"role":"user","content":"start"}"""
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(body(initial), first) { outerOutcome("outer-1") }
        val firstId = first.tools.single().id
        val firstHistory = "$initial,${callback(firstId, "A")}"
        manager.interceptor(turn(firstId, "A"), disableParallel = false)
            .intercept(body(firstHistory), RecordingSink()) { completedOutcome() }
        val secondHistory = "$firstHistory," + """{"role":"user","content":"second"}"""
        val second = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(secondHistory), second) { outerOutcome("outer-2") }
        val secondId = second.tools.single().id
        val history = body("$secondHistory,${callback(secondId, "B")}")
        manager.interceptor(turn(secondId, "B"), disableParallel = false)
            .intercept(history, RecordingSink()) { completedOutcome() }

        val newer = Json.decodeFromJsonElement<CodeModeRecordSnapshot>(stateFiles.records().last()).restore()
        val rewrite = CodexCodeModeHistory(Json).canonicalize(history, listOf(newer))
        assertEquals(emptyList<CodeModeOmission>(), rewrite.omitted)
        val rewritten = checkNotNull(rewrite.bodyJson)
        assertTrue(firstId in rewritten, "the removed record's callbacks remain ordinary history")
        assertFalse(secondId in rewritten, "the newer record's own callbacks still canonicalize")
        assertTrue("outer-2" in rewritten)
        assertFalse("outer-1" in rewritten)
    }

    @Test
    fun `new records use v5 anchors without whole-history digests`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("done")))))
        var posts = 0
        val input = body("""{"role":"user","content":"start"}""")
        manager.interceptor(turn(), disableParallel = false).intercept(input, RecordingSink()) {
            if (posts++ == 0) outerOutcome() else completedOutcome()
        }
        val stored = stateFiles.records().single()
        assertEquals(5, stored.getValue("metadataVersion").jsonPrimitive.content.toInt())
        assertTrue(stored["baselineInputDigest"]?.jsonPrimitive?.content.orEmpty().isEmpty())
        assertTrue(stored["baselineLogicalDigest"]?.jsonPrimitive?.content.orEmpty().isEmpty())
    }

    @Test
    fun `v4 records retain digest replay compatibility while v5 records are created`() = runTest {
        val manager = bridge(ScriptedRuntime(steps("legacy")))
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, first) { outerOutcome() }
        val id = first.tools.single().id
        val history = requestWithResult(id, "A")
        manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(history, RecordingSink()) { completedOutcome() }
        val saved = Json.decodeFromJsonElement<CodeModeRecordSnapshot>(stateFiles.records().single())
        val legacy = saved.copy(
            metadataVersion = CODE_MODE_LEGACY_METADATA_VERSION,
            baselineInputDigest = InputDigest.hex("[]"),
            baselineLogicalDigest = InputDigest.hex("[]"),
        ).restore()
        val rewritten = CodexCodeModeHistory(Json).canonicalize(history, listOf(legacy))
        assertEquals(emptyList<CodeModeOmission>(), rewritten.omitted)
        assertTrue("outer-call" in checkNotNull(rewritten.bodyJson))
        assertFalse(id in checkNotNull(rewritten.bodyJson))
    }

    private fun steps(id: String): ArrayDeque<CodeModeStep> = ArrayDeque(
        listOf(CodeModeStep.Calls(listOf(call(id, "Read"))), CodeModeStep.Completed(id)),
    )

    private fun body(items: String): String = """{"input":[{"role":"developer","content":"s"},$items]}"""

    private fun callback(id: String, output: String): String =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"$output"}"""
}
