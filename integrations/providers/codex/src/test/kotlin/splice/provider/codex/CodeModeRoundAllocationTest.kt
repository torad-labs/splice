// NEW: one code-mode round's allocation on a history of about 1 MB, per stage and whole, with the history
// unchanged and with a completed record rewriting it. Measured before deciding the interceptor contract.
package splice.provider.codex

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.util.JsonWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeStep
import java.lang.management.ManagementFactory

private const val WARMUPS = 6
private const val PREFIX_ITEMS = 420
private const val START = """{"role":"user","content":"start"}"""

internal class CodeModeRoundAllocationTest : CodeModeBridgeTestSupport() {
    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private inline fun measure(name: String, reporter: TestReporter, action: () -> Unit): Long {
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(WARMUPS) { action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        action()
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        reporter.publishEntry("codemode_${name}_allocated_bytes", allocated.toString())
        return allocated
    }

    /** About 1 MB of ordinary history ahead of everything a script touches, so every request in the
     *  sequence shares it and the record's anchors sit after it. Synthetic text only. */
    private val prefix: String = (0 until PREFIX_ITEMS).joinToString(",") { index ->
        val role = if (index % 2 == 0) "user" else "assistant"
        """{"role":"$role","content":"${"Synthetic history line $index for allocation. ".repeat(50)}"}"""
    }

    private fun body(items: String): String =
        """{"model":"gpt-6-astra","stream":true,"input":[{"role":"developer","content":"s"},$prefix,$items]}"""

    private fun callback(id: String, output: String): String =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"$output"}"""

    private val plain: String = body(START)

    @Test
    fun `each stage of a code-mode round, on a 1 MB history`(reporter: TestReporter) = runTest {
        val size = JsonWire.byteSize(plain)
        reporter.publishEntry("codemode_body_bytes", size.toString())
        assertTrue(size in 900_000L..1_300_000L, "fixture is $size bytes")
        val wire = CodexCodeModeWire(Json) {}
        val tree = Json.parseToJsonElement(plain).jsonObject
        val identity = CodeModeTurnIdentity()
        measure("render_tree_to_text", reporter) { val _ = JsonWire.string(tree) }
        measure("identity_digest", reporter) { val _ = identity.digest(plain) }
        measure("canonicalize_unchanged", reporter) { val _ = wire.canonicalize(plain, emptyList()) }
        measure("callback_ids", reporter) { val _ = wire.callbackIds(plain) }
        measure("upstream", reporter) { val _ = wire.upstream(plain) }
        // What RequestBody.bytes keeps for the body, the way each case reaches the transport.
        measure("transport_text_bytes", reporter) { val _ = RoundBody.Text(plain).bytes() }
        measure("transport_tree_bytes", reporter) { val _ = RoundBody.Tree(tree).bytes() }
    }

    /** No record, so canonicalize keeps the original text. */
    @Test
    fun `a whole round whose history is unchanged`(reporter: TestReporter) = runTest {
        val idle = bridge(ScriptedRuntime(ArrayDeque()))
        try {
            measure("round_unchanged", reporter) {
                val _ = idle.interceptor(turn(), disableParallel = false)
                    .intercept(plain, RecordingSink()) { completedOutcome() }
            }
        } finally {
            idle.onHeadStop()
        }
    }

    /** A completed script's record replaces its client callbacks in the request that follows it. */
    @Test
    fun `a whole round whose history a completed record rewrites`(reporter: TestReporter) = runTest {
        val script = ArrayDeque(
            listOf(CodeModeStep.Calls(listOf(call("first", "Read"))), CodeModeStep.Completed("first")),
        )
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script))))
        try {
            val next = completeOneScript(manager)
            val wire = CodexCodeModeWire(Json) {}
            val rewritten = checkNotNull(wire.canonicalize(next, completed(manager)).bodyJson)
            assertTrue(rewritten != next, "the fixture must exercise a rewrite")
            measure("canonicalize_rewritten", reporter) { val _ = wire.canonicalize(next, completed(manager)) }
            measure("round_rewritten", reporter) {
                val _ = manager.interceptor(turn(), disableParallel = false)
                    .intercept(next, RecordingSink()) { completedOutcome() }
            }
        } finally {
            manager.onHeadStop()
        }
    }

    /** Runs one script to completion, and returns the request that follows it. */
    private suspend fun completeOneScript(manager: CodexCodeModeBridge): String {
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(START), first) { outerOutcome("outer-1") }
        val id = first.tools.single().id
        val done = "$START,${callback(id, "A")}"
        manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(body(done), RecordingSink()) { completedOutcome() }
        return body("$done," + """{"role":"user","content":"next"}""")
    }

    /** The conversation's completed records, as CodexCodeModeTurn reads them at the start of a round. */
    private fun completed(manager: CodexCodeModeBridge): List<CodeModeRecord> {
        val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val key = stateFiles.records().first().getValue("key").jsonPrimitive.content
        return (field.get(manager) as CodexCodeModeRegistry).completed(key)
    }
}
