package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.PoolLimits
import splice.core.util.Cancellables
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.failure.CODE_MODE_RUNTIME_REPLACED_ERROR
import splice.upstream.failure.CODE_MODE_SESSION_CLOSED_ERROR
import splice.upstream.failure.CodeModeToolException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class NativeCodeModeRecoveryTest {
    @Test
    fun `a callback remains available after its observer is cancelled while collecting siblings`() = nativeProcessTest {
        NativeCodeModeRuntime().use { runtime ->
            val cell = runtime.startNativeSession(
                "cancel",
                "text(await tools.Read({key:'first'}));",
                setOf("Read"),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            val observer = launch(CancelBatchObservation()) { cell.observe(RECOVERY_WAIT_MS) }
            observer.join()
            assertTrue(observer.isCancelled)
            val call = (cell.observe(RECOVERY_WAIT_MS) as CodeModeStep.Calls).calls.single()
            assertEquals("{\"key\":\"first\"}", call.arguments.toString())
            val completed = cell.advance(listOf(CodeModeResult(call.id, "resumed"))) as CodeModeStep.Completed
            assertEquals("resumed", completed.output)
        }
    }

    @Test
    fun `host loss fails accepted work while a new module runs in a fresh session`() = nativeProcessTest {
        val process = AtomicReference<Process>()
        NativeCodeModeRuntime(spawn = WorkerSpawn { builder -> builder.start().also(process::set) }).use { runtime ->
            val cell = runtime.startNativeSession(
                "session",
                "store('accepted', true); await tools.checkpoint({});",
                setOf("checkpoint"),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            assertEquals("checkpoint", (cell.advance() as CodeModeStep.Calls).calls.single().name)
            process.get().destroyForcibly().waitFor()
            val failed = Cancellables.runCatchingBestEffort { cell.observe(RECOVERY_WAIT_MS) }
            assertTrue(failed.exceptionOrNull() is CodeModeToolException)
            assertEquals(CODE_MODE_SESSION_CLOSED_ERROR, failed.exceptionOrNull()?.message)
            val replaced = Cancellables.runCatchingBestEffort {
                runtime.startNativeSession(
                    "session",
                    "store('must-not-run', true);",
                    emptySet(),
                    emptyMap(),
                    NativeCodeModeOptions(),
                )
            }
            assertTrue(replaced.exceptionOrNull() is CodeModeToolException)
            assertEquals(CODE_MODE_RUNTIME_REPLACED_ERROR, replaced.exceptionOrNull()?.message)
            val fresh = runtime.startNativeSession(
                "session",
                "text(load('accepted')); text(load('must-not-run')); text('new module');",
                emptySet(),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            assertNotEquals(cell.cellId, fresh.cellId)
            val stillFailed = Cancellables.runCatchingBestEffort { cell.observe(RECOVERY_WAIT_MS) }
            assertTrue(stillFailed.exceptionOrNull() is CodeModeToolException)
            assertEquals("cell belongs to a stale code-mode host generation", stillFailed.exceptionOrNull()?.message)
            assertEquals("undefined\nundefined\nnew module", (fresh.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `completed modules release capacity before delayed callers collect their output`() = nativeProcessTest {
        DelayedCaller().use { caller ->
            NativeCodeModeRuntime(limits = PoolLimits(maxWorkers = 1)).use { runtime ->
                val cells = (0 until RECOVERY_CAPACITY_ATTEMPTS).map { index ->
                    withContext(caller) {
                        runtime.startNativeSession(
                            "capacity",
                            "text($index);",
                            emptySet(),
                            emptyMap(),
                            NativeCodeModeOptions(),
                        )
                    }
                }
                val output = cells.map { (it.observe(RECOVERY_WAIT_MS) as CodeModeStep.Completed).output }
                assertEquals((0 until RECOVERY_CAPACITY_ATTEMPTS).map(Int::toString), output)
            }
        }
    }

    private class CancelBatchObservation : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Dispatchers.Default.dispatch(context, block)
        }

        @OptIn(InternalCoroutinesApi::class)
        override fun dispatchYield(context: CoroutineContext, block: Runnable) {
            context[Job]?.cancel(CancellationException("Cancel foreground observation while its callback remains live"))
            Dispatchers.Default.dispatch(context, block)
        }
    }

    private class DelayedCaller : CoroutineDispatcher(), AutoCloseable {
        private val scheduler = Executors.newSingleThreadScheduledExecutor()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            scheduler.schedule(block, RECOVERY_RESUME_DELAY_MS, TimeUnit.MILLISECONDS)
        }

        override fun close() {
            scheduler.shutdownNow()
        }
    }
}

private const val RECOVERY_WAIT_MS: Long = 1_000
private const val RECOVERY_RESUME_DELAY_MS: Long = 150
private const val RECOVERY_CAPACITY_ATTEMPTS: Int = 5
