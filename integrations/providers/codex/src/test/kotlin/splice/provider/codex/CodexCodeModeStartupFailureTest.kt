package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.InterceptedRoundPost
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.EOFException
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

class CodexCodeModeStartupFailureTest : CodeModeBridgeTestSupport() {
    @Test
    fun `spawn EOF preserves the unstarted source and retries it once`() = runTest {
        assertRetry(EOFException("worker spawn ended"))
    }

    @Test
    fun `spawn IO preserves the unstarted source and retries it once`() = runTest {
        assertRetry(IOException("worker spawn refused"))
    }

    @Test
    fun `startup timeout preserves the unstarted source and retries it once`() = runTest {
        assertRetry(CodeModeTimeoutException(1))
    }

    @Test
    fun `startup host loss preserves the unstarted source and retries it once`() = runTest {
        assertRetry(CodeModeWorkerLostException())
    }

    @Test
    fun `startup infrastructure failure preserves the unstarted source and retries it once`() = runTest {
        assertRetry(
            CodeModeInfrastructureException(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO),
        )
    }

    @Test
    fun `startup runtime exception preserves the unstarted source and retries it once`() = runTest {
        assertRetry(IllegalStateException("worker unavailable"))
    }

    @Test
    fun `unstarted source retries after the bridge is restored from disk`() = runTest {
        assertRetry(EOFException("worker spawn ended"), restore = true)
    }

    @Test
    fun `a model tool protocol violation remains deterministic and never restarts its source`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("bad", "Unknown"))))))
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() } as TurnOutcome.Failure
        assertTrue(failed.deterministic)
        assertEquals(FailureCause.CODE_MODE_PROTOCOL, failed.cause)
        assertEquals(ErrorType.INVALID_REQUEST, failed.type)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `invalid runtime input stays deterministic and does not restart`() = runTest {
        val runtime = FailingStartRuntime(IllegalArgumentException("invalid script input"))
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() } as TurnOutcome.Failure
        assertTrue(failed.deterministic)
        assertEquals(ErrorType.INVALID_REQUEST, failed.type)
        assertEquals("LOST", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertTrue(runtime.executed.isEmpty())
        assertEquals(1, runtime.attempts)
    }

    @Test
    fun `simultaneous exact retries start the preserved source only once`() = runTest {
        val runtime = FailingStartRuntime(EOFException("worker spawn ended"))
        val manager = bridge(runtime)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        runtime.release = CompletableDeferred()
        val first = async {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        }
        runtime.entered.await()
        val second = async {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        }
        yield()
        assertEquals(2, runtime.attempts)
        runtime.release.complete(Unit)
        assertTrue(first.await() is TurnOutcome.Success)
        assertTrue(second.await() is TurnOutcome.Success)
        assertEquals(listOf("source"), runtime.executed)
        assertEquals(2, runtime.attempts)
    }

    @Test
    fun `a lost first reply is overloaded but executed source never runs again`() = runTest {
        var starts = 0
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                starts++
                throw EOFException("reply lost after executing source")
            }
            override fun close() = Unit
        }
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() } as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failed.type)
        assertFalse(failed.deterministic)
        assertEquals("LOST", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
        val restored = bridge(runtime)
        restored.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(1, starts)
    }

    @Test
    fun `a crash after admission cannot restart source with an unknown execution status`() = runTest {
        val runtime = FailingStartRuntime(EOFException("host boot failed"))
        val manager = bridge(runtime)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        runtime.release = CompletableDeferred()
        val starting = async {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        }
        runtime.entered.await()
        assertEquals("LOST", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
        val replacement = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run"))))
        val restored = bridge(replacement)
        restored.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(0, replacement.starts)
        runtime.release.complete(Unit)
        starting.await()
    }

    @Test
    fun `a failed retry admission save leaves unexecuted source eligible for the next retry`() = runTest {
        val runtime = FailingStartRuntime(EOFException("host boot failed"))
        val manager = bridge(runtime)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        stateFiles.block()
        val blocked = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertTrue(blocked is TurnOutcome.Failure)
        assertEquals(1, runtime.attempts)
        stateFiles.unblock()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(listOf("source"), runtime.executed)
        assertEquals(2, runtime.attempts)
    }

    @Test
    fun `failure opening the runtime retains source without another outer post`() = runTest {
        var opens = 0
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("finished"))))
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                {
                    if (++opens == 1) throw IOException("runtime factory unavailable")
                    runtime
                },
                stateLocation(),
            ),
        )
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() } as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failed.type)
        assertFalse(failed.deterministic)
        val retry = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertTrue(retry is TurnOutcome.Success)
        assertEquals(2, opens)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `head stop during first admission cannot adopt the new stop generation`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))))
        val config = CodeModeBridgeConfig({ runtime }, stateLocation())
        var registry: CodexCodeModeRegistry? = null
        var stopped = false
        val active = CodexCodeModeRegistry(
            config = config,
            json = Json,
            sweepInterval = 5.minutes,
            writer = CodeModeStateWrite { path, text ->
                CodeModeStateJournal.write(path, text)
                if (!stopped) {
                    stopped = true
                    checkNotNull(registry).onHeadStop()
                }
            },
        )
        registry = active
        val wire = CodexCodeModeWire(Json, config.log)
        val validation = CodexCodeModeValidation(config)
        val machine = CodexCodeModeMachine(config, active, validation)
        val driver = CodexCodeModeDriver(config, CodeModeRuntimeRun(config.runtimes), active, wire, validation, machine)
        val sink = RecordingSink()
        val context = CodeModeRunContext(
            turn(),
            false,
            "conversation",
            "digest",
            sink,
            upstreamPost(InterceptedRoundPost { completedOutcome() }),
        )
        val outcome = driver.drive(context, outer(), codeModeBody(BASE_REQUEST), outerOutcome())
        assertTrue(outcome is TurnOutcome.Failure)
        assertEquals(ErrorType.OVERLOADED, (outcome as TurnOutcome.Failure).type)
        assertFalse(outcome.deterministic)
        assertTrue(runtime.cell.closed)
        assertTrue(sink.tools.isEmpty())
        assertEquals("LOST", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
    }

    private suspend fun assertRetry(error: Exception, restore: Boolean = false) {
        val runtime = FailingStartRuntime(error)
        val manager = bridge(runtime)
        val first = RecordingSink()
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, first) { outerOutcome() } as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failed.type)
        assertEquals(FailureCause.INTERNAL, failed.cause)
        assertFalse(failed.deterministic)
        assertTrue(first.tools.isEmpty())
        assertEquals(0, runtime.executed.size)
        val kept = stateFiles.records().single()
        val id = kept.getValue("id").jsonPrimitive.content
        assertEquals("STARTING", kept.getValue("phase").jsonPrimitive.content)
        assertEquals("source", kept.getValue("source").jsonPrimitive.content)
        val retryManager = if (restore) {
            manager.onHeadStop()
            bridge(runtime)
        } else {
            manager
        }
        val retried = retryManager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertTrue(retried is TurnOutcome.Success)
        assertEquals(listOf("source"), runtime.executed)
        assertEquals(2, runtime.attempts)
        val completed = stateFiles.records().single()
        assertEquals(id, completed.getValue("id").jsonPrimitive.content)
        assertEquals("COMPLETED", completed.getValue("phase").jsonPrimitive.content)
        retryManager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(listOf("source"), runtime.executed)
        assertEquals(2, runtime.attempts)
    }

    private class FailingStartRuntime(private val error: Exception) : CodeModeRuntime {
        var attempts = 0
        val executed = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        var release = CompletableDeferred(Unit)

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            if (++attempts == 1) {
                if (error is IllegalArgumentException) throw error
                throw CodeModeStartException(error)
            }
            entered.complete(Unit)
            release.await()
            executed += source
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                    CodeModeStep.Completed("finished")

                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }
}
