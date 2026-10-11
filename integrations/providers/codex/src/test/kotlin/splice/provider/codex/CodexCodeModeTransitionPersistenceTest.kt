// NEW: failed post-runtime persistence retries captured transitions, never worker execution.
package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

class CodexCodeModeTransitionPersistenceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `worker failure starts with safe cause and counts accepted results`() = runTest {
        var failureText = "worker pool exhausted\nPRIVATE_TOOL_RESULT"
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell = object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                    error(failureText)
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        val outcome = bridge(runtime).interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome()) }.turn()
        assertTrue(outcome is TurnOutcome.Failure)
        val message = (outcome as TurnOutcome.Failure).message
        assertTrue(message.contains("IllegalStateException: worker pool exhausted"), message)
        assertTrue(message.contains("accepted results=0"), message)
        assertFalse(message.contains("call ids="), message)
        assertFalse(message.contains("PRIVATE_TOOL_RESULT"), "exception payload must stay private: $message")

        failureText = "PRIVATE_TOOL_RESULT as first line"
        val privateOutcome = bridge(runtime).interceptor(
            turn(sessionId = "other-session"),
            outer("other-call"),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome("other-call")) }.turn()
        assertTrue(privateOutcome is TurnOutcome.Failure)
        val privateMessage = (privateOutcome as TurnOutcome.Failure).message
        assertTrue("IllegalStateException: message withheld" in privateMessage, privateMessage)
        assertTrue(" at CodexCodeModeTransitionPersistenceTest.kt:" in privateMessage, privateMessage)
        assertFalse("PRIVATE_TOOL_RESULT" in privateMessage, "private worker text must never enter the log")
    }

    @Test
    fun `initial calls save failure retries captured callback without rerunning worker`() = runTest {
        val runtime = FailingSaveRuntime(
            stateFiles,
            listOf(CodeModeStep.Calls(listOf(call("read", "Read")))),
            failAt = 1,
        )
        val manager = bridge(runtime)
        val failedSink = RecordingSink()
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, failedSink) { RoundResult.Outcome(outerOutcome()) }
        assertPersistenceFailure(failed)
        assertTrue(failedSink.tools.isEmpty())
        assertEquals(1, runtime.starts)
        assertEquals(listOf(emptyList<CodeModeResult>()), runtime.cell.results)
        assertFalse(runtime.cell.closed)

        stateFiles.unblock()
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, retriedSink) { error("retry must not post upstream") }.turn()
        assertTrue(retried is TurnOutcome.Success)
        assertTrue((retried as TurnOutcome.Success).hasToolUse)
        assertEquals("Read", retriedSink.tools.single().name)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.results.size)
        assertFalse(runtime.cell.closed)
        manager.onHeadStop()
    }

    @Test
    fun `completed save failure retries exact output without rerunning worker`() = runTest {
        val output = "completed with \"quotes\"\nand Unicode é"
        val runtime = FailingSaveRuntime(stateFiles, listOf(CodeModeStep.Completed(output)), failAt = 1)
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome()) }
        assertPersistenceFailure(failed)
        assertEquals(1, runtime.starts)
        assertEquals(listOf(emptyList<CodeModeResult>()), runtime.cell.results)
        assertTrue(runtime.cell.closed)

        stateFiles.unblock()
        var upstreamBody = ""
        var posts = 0
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, retriedSink) {
                posts++
                upstreamBody = it
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(retried is TurnOutcome.Success)
        assertFalse((retried as TurnOutcome.Success).hasToolUse)
        assertTrue(retriedSink.tools.isEmpty())
        assertEquals(1, posts)
        val items = Json.parseToJsonElement(upstreamBody).jsonObject.getValue("input").jsonArray
        val completed = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject
        // V4-388: the exact script output, under codex's exec header (the test clock does not move).
        assertEquals(
            "Script completed\nWall time 0.0 seconds\nOutput:\n$output",
            completed.getValue("output").jsonPrimitive.content,
        )
        assertEquals("outer-call", completed.getValue("call_id").jsonPrimitive.content)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.results.size)
        assertTrue(runtime.cell.closed)
    }

    @Test
    fun `followup calls save failure retries new callback without redelivering accepted results`() = runTest {
        val runtime = FailingSaveRuntime(
            stateFiles,
            listOf(
                CodeModeStep.Calls(listOf(call("read", "Read"))),
                CodeModeStep.Calls(listOf(call("edit", "Edit"))),
            ),
            failAt = 2,
        )
        val manager = bridge(runtime)
        val firstSink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, firstSink) { RoundResult.Outcome(outerOutcome()) }
        val firstId = firstSink.tools.single().id
        val failedSink = RecordingSink()
        val failed = manager.interceptor(turn(firstId, "original result"), disableParallel = false)
            .intercept(requestWithResult(firstId, "original result"), failedSink) { error("must not post") }
        assertPersistenceFailure(failed)
        assertTrue(failedSink.tools.isEmpty())
        val delivered = listOf(emptyList(), listOf(CodeModeResult("read", "original result")))
        assertEquals(delivered, runtime.cell.results)
        assertFalse(runtime.cell.closed)

        stateFiles.unblock()
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(firstId, "original result"), disableParallel = false)
            .intercept(requestWithResult(firstId, "original result"), retriedSink) { error("must not post") }.turn()
        assertTrue(retried is TurnOutcome.Success)
        assertTrue((retried as TurnOutcome.Success).hasToolUse)
        assertEquals("Edit", retriedSink.tools.single().name)
        assertFalse(firstId == retriedSink.tools.single().id)
        assertEquals(delivered, runtime.cell.results)
        assertEquals(1, runtime.starts)
        assertFalse(runtime.cell.closed)
        manager.onHeadStop()
    }

    @Test
    fun `failed issued-step save cannot make a later retry serve an unpersisted callback`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome()) }
        val config = CodeModeBridgeConfig(
            { runtime },
            stateLocation(),
            clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        )
        val registry = CodexCodeModeRegistry(config, Json, 5.minutes)
        val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
        val record = registry.recordsFor(key).single()
        assertEquals(1, record.issued.size)
        record.progress.lastDigest = "next-request-digest"
        val machine = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
        stateFiles.block()
        var failed = false
        try {
            machine.emit(record, record.visiblePending(), RecordingSink())
        } catch (_: CodeModePersistenceException) {
            failed = true
        } finally {
            stateFiles.unblock()
        }
        assertTrue(failed, "the issued-step save really failed before any client callback")
        assertEquals(1, record.issued.size, "a failed save cannot leave a replayable in-memory step")
        machine.emit(record, record.visiblePending(), RecordingSink())
        assertEquals(2, registry.recordsFor(key).single().issued.size)
        assertEquals(2, stateFiles.records().single().getValue("issued").jsonArray.size)
    }

    /** Oct 2: the source reader saves a live record under its conversation key while the script's driver adds
     *  to it. The driver wrote outside the key, the save's snapshot met the write mid-iteration, and the
     *  ConcurrentModificationException killed the reader with the script's source unended. A driver's change
     *  now waits for the key the reader's save holds. */
    @Test
    fun `a driver change waits while a staged source client boundary saves its record`() {
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val writer = CodeModeStateWrite { path, text ->
            if (hold.compareAndSet(true, false)) {
                saving.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            CodeModeStateJournal.write(path, text)
        }
        val config = CodeModeBridgeConfig({ error("no script runs in this test") }, stateLocation())
        val registry = CodexCodeModeRegistry(config, Json, 5.minutes, writer)
        val record = CodeModeRecords.of("alpha", 1)
        assertTrue(registry.add(record))
        val machine = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
        val call = CodeModePending(
            runtimeId = "runtime-1",
            clientId = "${CODE_MODE_CLIENT_ID_PREFIX}one",
            name = "Read",
            arguments = JsonObject(emptyMap()),
            exposed = true,
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            hold.set(true)
            val reader = pool.submit { commitSourceBoundary(registry, record) }
            assertTrue(saving.await(WAIT_SECONDS, TimeUnit.SECONDS), "the reader's save never reached the disk")
            val driverThread = CompletableFuture<Thread>()
            val driver = pool.submit {
                driverThread.complete(Thread.currentThread())
                runBlocking { machine.emit(record, listOf(call), RecordingSink()) }
            }
            val thread = driverThread.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(Thread.State.WAITING, thread.state, "the driver never waited for the record's key")
            assertTrue(record.issued.isEmpty(), "the driver changed the record while the reader's save held its key")
            release.countDown()
            reader.get(WAIT_SECONDS, TimeUnit.SECONDS)
            driver.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(1, record.issued.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `new pending calls wait for a source client boundary after the worker advances`() {
        val advancing = CountDownLatch(1)
        val advance = CountDownLatch(1)
        val advanced = CountDownLatch(1)
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val writer = CodeModeStateWrite { path, text ->
            if (hold.compareAndSet(true, false)) {
                saving.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
            }
            CodeModeStateJournal.write(path, text)
        }
        val config = CodeModeBridgeConfig({ error("no runtime starts") }, stateLocation())
        val registry = CodexCodeModeRegistry(config, Json, 5.minutes, writer)
        val record = CodeModeRecords.of("pending-race", 1)
        assertTrue(registry.add(record))
        val cell = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                advancing.countDown()
                check(advance.await(WAIT_SECONDS, TimeUnit.SECONDS))
                advanced.countDown()
                return CodeModeStep.Calls(listOf(call("runtime-next", "Read")))
            }
            override fun close() = Unit
        }
        assertTrue(registry.attach(record, cell))
        val machine = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
        val pool = Executors.newFixedThreadPool(2)
        val driverThread = CompletableFuture<Thread>()
        try {
            val driver = pool.submit {
                driverThread.complete(Thread.currentThread())
                runBlocking { machine.advance(firstStep(record)) }
            }
            assertTrue(advancing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the worker never reached its advance")
            hold.set(true)
            val reader = pool.submit { commitSourceBoundary(registry, record) }
            assertTrue(saving.await(WAIT_SECONDS, TimeUnit.SECONDS), "the source reader never held its save")
            advance.countDown()
            assertTrue(advanced.await(WAIT_SECONDS, TimeUnit.SECONDS), "the worker never left its advance latch")
            val thread = driverThread.get(WAIT_SECONDS, TimeUnit.SECONDS)
            awaitPendingKey(thread)
            assertTrue(record.progress.pending.isEmpty(), "acceptCalls changed pending while the save held its key")
            release.countDown()
            reader.get(WAIT_SECONDS, TimeUnit.SECONDS)
            driver.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(1, record.progress.pending.size)
            assertEquals(1, stateFiles.records().single().getValue("pending").jsonArray.size)
        } finally {
            advance.countDown()
            release.countDown()
            pool.shutdownNow()
        }
    }

    /** The step a fresh worker takes: no results yet, parallel calls allowed. */
    private fun firstStep(record: CodeModeRecord) =
        CodeModeAdvanceRequest(record, turn(), false, emptyList(), RecordingSink())

    private fun commitSourceBoundary(registry: CodexCodeModeRegistry, record: CodeModeRecord) {
        registry.source.append(record, record.origin.source + "; await tools.Read({});")
        registry.changes.save(record) {}
    }

    private fun awaitPendingKey(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(Thread.State.WAITING, thread.state, "acceptCalls must wait for the source reader's key")
    }

    private fun assertPersistenceFailure(round: RoundResult) {
        val outcome = round.turn()
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("could not be saved"), outcome.message)
    }

    private class FailingSaveRuntime(
        private val state: CodeModeStateFiles,
        private val steps: List<CodeModeStep>,
        private val failAt: Int,
    ) : CodeModeRuntime {
        var starts = 0
        val cell = RecordingCell()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            return cell
        }

        override fun close() = cell.close()

        inner class RecordingCell : CodeModeCell {
            val results = mutableListOf<List<CodeModeResult>>()
            var closed = false

            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                this.results += results.toList()
                val step = steps[this.results.lastIndex]
                if (this.results.size == failAt) {
                    state.block()
                }
                return step
            }

            override fun close() {
                closed = true
            }
        }
    }
}

// why: a bound on each wait in the record-race test, far above the milliseconds each step takes, so a hang
// fails the test by name instead of reaching the suite's timeout.
private const val WAIT_SECONDS = 30L
