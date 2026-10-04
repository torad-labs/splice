package splice.provider.codex

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSourceCommit
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CodeModeSourceCertificationTest : CodeModeStatementStreamSupport() {
    @RepeatedTest(50)
    @Timeout(20)
    fun `incomplete terminal repetition cohort`() =
        `item-complete suffix waits for response certification and every ending releases it`("incomplete")

    @ParameterizedTest
    @ValueSource(strings = ["valid", "incomplete", "tear", "stall", "head-stop", "client-cancel"])
    @Timeout(20)
    fun `item-complete suffix waits for response certification and every ending releases it`(
        ending: String,
    ) = runBlocking {
        val runtime = CertificationRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        if (ending == "incomplete") post.terminalProblem = "incomplete"
        val sourcePost = EndingPost(post, ending)
        var request: Deferred<TurnOutcome>? = null
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, sourcePost)
            val first = sink.callback.await()
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(5_000) { post.itemDone.await() }
            val next = StepSink()
            val callback = async {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), next, sourcePost)
            }
            request = callback
            withTimeout(5_000) { while (runtime.reads.get() < 2 && !callback.isCompleted) kotlinx.coroutines.yield() }
            assertFalse(
                next.callback.isCompleted,
                "an item-complete suffix cannot issue Edit before response certification",
            )
            when (ending) {
                "head-stop" -> manager.onHeadStop()
                "client-cancel" -> callback.cancel()
                else -> post.complete.complete(Unit)
            }
            if (ending == "client-cancel") {
                withTimeout(5_000) { callback.join() }
                assertTrue(callback.isCancelled)
            } else {
                val outcome = withTimeout(5_000) { callback.await() }
                assertEnding(ending, outcome, next)
            }
            assertEquals(1, runtime.starts)
        } finally {
            post.complete.complete(Unit)
            manager.onHeadStop()
            request?.cancelAndJoin()
        }
    }
    private suspend fun assertEnding(ending: String, outcome: TurnOutcome, next: StepSink) {
        if (ending == "valid") {
            assertTrue(outcome is TurnOutcome.Success && outcome.hasToolUse, outcome.toString())
            assertEquals("Edit", next.callback.await().name)
        } else {
            assertFalse(next.callback.isCompleted, "a rejected or stopped source never issues Edit")
        }
    }
}

private class EndingPost(
    private val post: RedirectableRoundPost,
    private val ending: String,
) : RedirectableRoundPost by post {
    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
        val outcome = post.into(bodyJson, sink)
        return when (ending) {
            "tear" -> throw IOException("synthetic transport tear after item completion")
            "stall" -> TurnOutcome.Failure(
                "synthetic stalled response after item completion",
                cause = FailureCause.UPSTREAM_STALLED,
                phase = FailurePhase.MID_OUTPUT,
            )
            else -> outcome
        }
    }
}

private class CertificationRuntime : CodeModeRuntime {
    var starts = 0
    val reads = AtomicInteger()

    override suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = error("streaming source required")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        starts++
        reads.incrementAndGet()
        val first = source.read()
        return object : CodeModeCell {
            private var initial: CodeModeSourcePart? = first
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                val part = initial.also { initial = null } ?: run {
                    reads.incrementAndGet()
                    source.read()
                }
                val text = when (part) {
                    is CodeModeSourcePart.Delta -> part.text
                    is CodeModeSourcePart.Complete -> part.text
                    is CodeModeSourcePart.Failed -> throw IOException(part.error)
                }
                return if (text.isEmpty()) {
                    CodeModeStep.Completed("done")
                } else {
                    val name = if ("Edit" in text) "Edit" else "Read"
                    CodeModeStep.Calls(listOf(CodeModeCall("runtime-${reads.get()}", name, JsonObject(emptyMap()))))
                }
            }
            override fun close() = Unit
        }
    }

    override fun close() = Unit
}

class CodeModeSourceCursorCertificationTest {
    @Test
    fun `startup rejection disposes a concurrently returned cell`() {
        val source = CodeModeSourceBuffer()
        var closed = 0
        val cell = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                error("rejected startup must not advance")
            override fun close() {
                closed++
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                source.whileStarting {
                    source.fail("synthetic rejected source")
                    cell
                }
            }
        }
        assertEquals(1, closed, "a returned cell must not be orphaned when source rejection wins")
        assertTrue(source.startupRejected)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `certified source imposes no deadline on a healthy pending startup`() = runTest {
        val source = CodeModeSourceBuffer()
        source.complete("synthetic certified source")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var closed = 0
        val cell = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                error("startup only")
            override fun close() {
                closed++
            }
        }
        val boot = async {
            source.whileStarting {
                entered.complete(Unit)
                release.await()
                cell
            }
        }
        entered.await()
        advanceTimeBy(901_000)
        assertFalse(boot.isCompleted, "healthy startup must not acquire a blanket execution timeout")
        release.complete(Unit)
        assertTrue(boot.await() === cell)
        assertEquals(0, closed)
        assertFalse(source.startupRejected)
        cell.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "prefix"])
    fun `more source bytes cannot reopen a sealed item`(prefix: String) {
        val source = CodeModeSourceBuffer()
        source.publish(prefix)
        source.seal()
        assertThrows(IllegalStateException::class.java) { source.publish(prefix + "extra") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["fail", "complete"])
    fun `a cursor cannot return a pre-seal snapshot after its commit wait`(terminal: String) = runBlocking {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = CodeModeSourceBuffer(
            CodeModeSourceCommit {
                reached.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            },
        )
        source.publish("prefix")
        val cursor = source.view()
        val read = async(Dispatchers.Default) { cursor.read() }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            source.seal()
            if (terminal == "fail") source.fail("rejected") else source.complete("prefix-complete")
            release.countDown()
            val observed = withTimeout(5_000) { read.await() }
            if (terminal == "fail") {
                assertEquals(CodeModeSourcePart.Failed("rejected"), observed)
            } else {
                assertEquals(CodeModeSourcePart.Complete("prefix-complete"), observed)
            }
        } finally {
            release.countDown()
            read.cancelAndJoin()
        }
    }
}
