// NEW: each provider session owns an isolate and an actual, failure-contained host assignment.
package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.ConcurrentLinkedQueue

@Timeout(90)
class CodeModeSessionIsolationTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `a session exhausting its guest heap cannot exhaust a concurrently parked sibling`() = runBlocking {
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath).use { runtime ->
            val offender = runtime.startSession(
                "session-a",
                """
                    globalThis.held = Array(60_000_000).fill(1);
                    await tools.Read({});
                    globalThis.more = [];
                    while (true) { more.push(Array(100_000).fill(2)); }
                """.trimIndent(),
                setOf("Read"),
            )
            assertTrue(offender.advance() is CodeModeStep.Calls)
            val sibling = runtime.startSession(
                "session-b",
                """
                    globalThis.held = Array(60_000_000).fill(3);
                    await tools.Read({});
                    return held[0] === 3 ? 'sibling survived' : 'corrupted';
                """.trimIndent(),
                setOf("Read"),
            )
            assertTrue(sibling.advance() is CodeModeStep.Calls)
            val failed = async {
                val outcome = offender.advance(listOf(CodeModeResult("1", "go")))
                assertTrue(outcome is CodeModeStep.Completed)
                assertEquals("Code-mode guest heap exhausted", (outcome as CodeModeStep.Completed).error)
            }
            val healthy = withTimeout(15_000) {
                sibling.advance(listOf(CodeModeResult("1", "go"))) as CodeModeStep.Completed
            }
            assertEquals("sibling survived", healthy.output)
            assertEquals(null, healthy.error)
            withTimeout(30_000) { failed.await() }
            offender.close()
            sibling.close()
        }
    }

    @Test
    fun `two active sessions use two on-demand hosts when maxWorkers is two`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        JvmCodeModeRuntime(
            maxWorkers = 2,
            workerClasspath = testClasspath,
            spawn = WorkerSpawn { builder -> builder.start().also { spawned += it } },
        ).use { runtime ->
            assertEquals(0, spawned.size, "constructing a runtime must not eagerly start a host")
            val first = runtime.startSession("session-a", "return await tools.Read({});", setOf("Read"))
            assertTrue(first.advance() is CodeModeStep.Calls)
            val next = runtime.startSession("session-b", "return await tools.Read({});", setOf("Read"))
            assertTrue(next.advance() is CodeModeStep.Calls)
            assertEquals(2, spawned.size, "maxWorkers must place separate active sessions on separate hosts")
            first.close()
            next.close()
        }
    }
}
