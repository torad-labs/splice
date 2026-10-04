// NEW: one code-mode round's allocation on a history of about 1 MB, per stage and whole, with the history
// unchanged and with a completed record rewriting it, pinned so a second parse or a render shows up red.
package splice.provider.codex

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.turn.TurnOutcome
import splice.core.util.JsonWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundBodyPost
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.lang.management.ManagementFactory

private const val WARMUPS = 6
private const val PREFIX_ITEMS = 420
private const val START = """{"role":"user","content":"start"}"""

// Whole-round budgets in percent of the body (911 KB). Before the tree contract an unchanged text round allocated
// 595 percent and a rewritten one 1,247; a tree was rendered first (202 more). After it: 231 and 659 from text,
// 9 and 435 from a tree. Each budget sits under the after figure plus one more parse of the body (121 percent).
private const val UNCHANGED_TEXT_PERCENT = 260
private const val UNCHANGED_TREE_PERCENT = 50
private const val REWRITTEN_TEXT_PERCENT = 750
private const val REWRITTEN_TREE_PERCENT = 500

// The rewrite itself, on a tree: 429 percent after, 788 before (it parsed and rendered the body).
private const val REWRITE_STAGE_PERCENT = 500

// A stage reads the round's one parse. Parsing the body again costs about one body (the text parse, measured
// here), so a quarter of a body rejects a stage that parses and passes one that walks the parsed tree.
private const val STAGE_PERCENT = 25

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

    /** The turn's post as the head builds it: a tree goes on as a tree, so the post renders nothing. */
    private val discarding = object : InterceptedRoundPost, RoundBodyPost {
        override suspend fun invoke(bodyJson: String): TurnOutcome = completedOutcome()
        override suspend fun post(body: RoundBody): TurnOutcome = completedOutcome()
        override suspend fun postInto(body: RoundBody, sink: WireSink): TurnOutcome = completedOutcome()
    }

    private inline fun stage(name: String, reporter: TestReporter, budget: Long, action: () -> Unit) {
        val allocated = measure(name, reporter, action)
        assertTrue(allocated < budget, "$name allocated $allocated; a stage's budget is $budget")
    }

    @Test
    fun `each stage of a code-mode round, on a 1 MB history`(reporter: TestReporter) = runTest {
        val size = JsonWire.byteSize(plain)
        reporter.publishEntry("codemode_body_bytes", size.toString())
        assertTrue(size in 900_000L..1_300_000L, "fixture is $size bytes")
        val wire = CodexCodeModeWire(Json) {}
        val tree = Json.parseToJsonElement(plain).jsonObject
        val body = wire.body(RoundBody.Tree(tree))
        val identity = CodeModeTurnIdentity()
        val budget = size * STAGE_PERCENT / 100

        val parse = measure("parse_text", reporter) { val _ = wire.body(RoundBody.Text(plain)) }
        assertTrue(parse >= budget, "the stage budget must reject a parse; one allocated $parse")
        stage("identity_digest_tree", reporter, budget) { val _ = identity.digest(RoundBody.Tree(tree)) }
        stage("canonicalize_unchanged", reporter, budget) { val _ = wire.canonicalize(body, emptyList()) }
        stage("callback_ids", reporter, budget) { val _ = wire.callbackIds(body) }
        stage("upstream", reporter, budget) { val _ = wire.upstream(body) }

        // References: a text round's digest, the render the head used to do, and the bytes the transport keeps.
        measure("identity_digest_text", reporter) { val _ = identity.digest(plain) }
        measure("render_tree_to_text", reporter) { val _ = JsonWire.string(tree) }
        measure("transport_text_bytes", reporter) { val _ = RoundBody.Text(plain).bytes() }
        measure("transport_tree_bytes", reporter) { val _ = RoundBody.Tree(tree).bytes() }
    }

    /** No record, so canonicalize keeps the original body. The head hands code mode the tree it built;
     *  before the tree contract it rendered that tree to text first, and code mode parsed it four times. */
    @Test
    fun `a whole round whose history is unchanged`(reporter: TestReporter) = runTest {
        val idle = bridge(ScriptedRuntime(ArrayDeque()))
        val size = JsonWire.byteSize(plain)
        val tree = Json.parseToJsonElement(plain).jsonObject
        try {
            val text = measure("round_unchanged", reporter) {
                val _ = idle.interceptor(turn(), disableParallel = false).intercept(plain, RecordingSink(), discarding)
            }
            val asTree = measure("round_unchanged_tree", reporter) {
                val _ = treeEntry(idle).intercept(RoundBody.Tree(tree), RecordingSink(), discarding)
            }
            assertTrue(text < size * UNCHANGED_TEXT_PERCENT / 100, "unchanged text round: $text for $size bytes")
            assertTrue(asTree < size * UNCHANGED_TREE_PERCENT / 100, "unchanged tree round: $asTree for $size bytes")
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
            val size = JsonWire.byteSize(next)
            val tree = Json.parseToJsonElement(next).jsonObject
            val body = wire.body(RoundBody.Tree(tree))
            val records = completed(manager)
            val rewritten = checkNotNull(wire.canonicalize(body, records).bodyJson)
            assertTrue(rewritten != next, "the fixture must exercise a rewrite")
            stage("canonicalize_rewritten", reporter, size * REWRITE_STAGE_PERCENT / 100) {
                val _ = wire.canonicalize(body, records)
            }
            val text = measure("round_rewritten", reporter) {
                val _ = manager.interceptor(turn(), disableParallel = false)
                    .intercept(next, RecordingSink(), discarding)
            }
            val asTree = measure("round_rewritten_tree", reporter) {
                val _ = treeEntry(manager).intercept(RoundBody.Tree(tree), RecordingSink(), discarding)
            }
            assertTrue(text < size * REWRITTEN_TEXT_PERCENT / 100, "rewritten text round: $text for $size bytes")
            assertTrue(asTree < size * REWRITTEN_TREE_PERCENT / 100, "rewritten tree round: $asTree for $size bytes")
        } finally {
            manager.onHeadStop()
        }
    }

    /** The bridge's interceptor through the entry the head uses for a round it holds as a tree. */
    private fun treeEntry(manager: CodexCodeModeBridge): RoundBodyInterceptor =
        manager.interceptor(turn(), disableParallel = false) as RoundBodyInterceptor

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
