// NEW: an ACTIVE owner can lose its source before resume or cell lookup without becoming invalid input.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.sse.WireSink
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CodeModePreAdvanceTearTest : CodeModeStatementStreamSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["before-resume", "before-cell"])
    @Timeout(20)
    fun `a selected ACTIVE record keeps its tear verdict when the cell disappears`(window: String) = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val initial = StepSink()
        val post = GatedPost(initial)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, initial, post)
            val first = initial.callback.await()
            val registry = managerField(manager, "registry") as CodexCodeModeRegistry
            val resume = managerField(manager, "resume") as CodexCodeModeResume
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val picked = CompletableDeferred<CodeModeRecord>()
            val release = CompletableDeferred<Unit>()
            val next = StepSink()
            val continuation = turn(first.id, "result-0")
            val tools = object : Set<String> by continuation.tools {
                override fun contains(element: String): Boolean {
                    if (window == "before-cell" && element == "Read") {
                        runBlocking { tear(post, picked.await()) }
                    }
                    return element in continuation.tools
                }
            }
            val request = async {
                val record = checkNotNull(registry.owner(key, "continuation", setOf(first.id), emptySet()))
                assertEquals(CodeModePhase.ACTIVE, record.phase)
                picked.complete(record)
                release.await()
                val context = CodeModeRunContext(
                    continuation.copy(tools = tools),
                    false,
                    key,
                    "continuation",
                    CodeModeRoundLink(next, upstreamPost(post)),
                )
                resume.active(record, context, codeModeBody(history(listOf(first))))
            }
            val record = picked.await()
            if (window == "before-resume") tear(post, record)
            release.complete(Unit)
            val outcome = withTimeout(5_000) { request.await() } as TurnOutcome.Failure
            assertEquals(FailureCause.UPSTREAM_CONN_RESET, outcome.cause)
            assertFalse(outcome.traits.deterministic)
            assertNull(outcome.partial)
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
            if (window == "before-cell") assertStampRemoved(manager, record)
            assertLostRetry(manager, runtime, post, first, record)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `the first driver step keeps a tear after successful attachment`() = runBlocking {
        val runtime = IncrementalRuntime()
        val clock = LookupClock()
        lateinit var manager: CodexCodeModeBridge
        val initial = StepSink()
        val post = GatedPost(initial)
        var attached: CodeModeRecord? = null
        val gated = object : CodeModeRuntime by runtime {
            override suspend fun startStreamingSession(
                sessionKey: String,
                source: CodeModeSource,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                val cell = runtime.startStreaming(source, tools, descriptions)
                val registry = managerField(manager, "registry") as CodexCodeModeRegistry
                val records = CodexCodeModeRegistry::class.java.getDeclaredField("records")
                    .apply { isAccessible = true }.get(registry) as List<*>
                val record = records.filterIsInstance<CodeModeRecord>().single()
                clock.beforeRead = {
                    assertEquals(CodeModePhase.ACTIVE, record.phase)
                    assertNotNull(registry.cell(record), "the driver attached the cell before this tear")
                    attached = record
                    runBlocking { tear(post, record) }
                }
                return cell
            }
        }
        manager = bridge(gated, clock = clock)
        try {
            val outcome = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, initial, post).turn() as TurnOutcome.Failure
            assertEquals(FailureCause.UPSTREAM_CONN_RESET, outcome.cause)
            assertFalse(outcome.traits.deterministic)
            assertNull(outcome.partial)
            assertFalse(initial.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertTrue(runtime.delivered.isEmpty(), "the disappeared cell must never advance")
            assertEquals(1, post.posts)
            assertStampRemoved(manager, checkNotNull(attached))
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `an IOException before source admission remains an internal failure`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val initial = StepSink()
        var posts = 0
        val post = object : RedirectableRoundPost {
            override suspend fun invoke(bodyJson: String): RoundResult = into(bodyJson, initial)

            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                posts++
                throw IOException("synthetic transport failure before script admission")
            }
        }
        try {
            val outcome = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, initial, post).turn() as TurnOutcome.Failure
            assertEquals(FailureCause.INTERNAL, outcome.cause)
            assertFalse(outcome.traits.deterministic)
            assertNull(outcome.partial)
            assertFalse(initial.callback.isCompleted)
            assertTrue(stateFiles.records().isEmpty(), "there was no script to admit or lose")
            assertEquals(0, runtime.starts)
            assertEquals(1, posts)
        } finally {
            manager.onHeadStop()
        }
    }

    private suspend fun assertLostRetry(
        manager: CodexCodeModeBridge,
        runtime: IncrementalRuntime,
        post: GatedPost,
        first: SeenTool,
        record: CodeModeRecord,
    ) {
        assertEquals(CodeModePhase.LOST, record.phase)
        val retry = StepSink()
        val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
            .intercept(history(listOf(first)), retry, post).turn()
        assertTrue(outcome is TurnOutcome.Success, "the second attempt must heal through lost-record recovery")
        assertEquals(CodeModePhase.COMPLETED, record.phase)
        assertFalse(retry.callback.isCompleted)
        assertTrue(post.continuation.contains("result-0"), "accepted results survive the retry")
        assertTrue(post.continuation.contains("source was not rerun"))
        assertEquals(1, runtime.starts, "the partially executed script must not restart")
        assertEquals(2, post.posts, "only the ordinary model continuation may post again")
        assertStampRemoved(manager, record)
    }

    private fun assertStampRemoved(manager: CodexCodeModeBridge, record: CodeModeRecord) {
        val machine = managerField(manager, "machine") as CodexCodeModeMachine
        val stamps = CodexCodeModeMachine::class.java.getDeclaredField("started")
            .apply { isAccessible = true }.get(machine) as Map<*, *>
        assertFalse(stamps.containsKey(record.id), "a torn missing cell must release its wall-time stamp")
    }

    /** The first clock read after attach is Machine's stamp, before its cell lookup. */
    private class LookupClock : Clock() {
        var beforeRead: (() -> Unit)? = null

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

        override fun instant(): Instant = Instant.ofEpochMilli(millis())

        override fun millis(): Long {
            beforeRead.also { beforeRead = null }?.invoke()
            return 1_000
        }
    }

    private suspend fun tear(post: GatedPost, record: CodeModeRecord) {
        post.tearAfterFirst = true
        post.gates[1].complete(Unit)
        withTimeout(5_000) { while (record.phase != CodeModePhase.LOST) yield() }
        assertTrue(post.stopped.isCompleted)
    }

    private fun managerField(manager: CodexCodeModeBridge, name: String): Any =
        CodexCodeModeBridge::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
}
