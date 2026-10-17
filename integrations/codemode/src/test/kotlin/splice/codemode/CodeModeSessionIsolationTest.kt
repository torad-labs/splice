// NEW: each provider session owns an isolate and an actual, failure-contained host assignment.
package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep

// why: a warm host starts a cell in well under a second; 15 s separates a slow start from one that never comes.
private const val START_BOUND_MS = 15_000L

@Timeout(90)
class CodeModeSessionIsolationTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `a session exhausting its guest heap cannot exhaust a concurrently parked sibling`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
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

    /** A control for the Oct 4 900 s stalls, whose cause was the statement lexer's growth on a run of slashes: a
     *  session whose older program is parked and whose newer one was closed still starts its next program. */
    @Test
    fun `a program starts in a session whose older program is parked and whose newer one was closed`() = runBlocking {
        JvmCodeModeRuntime(launch = HostLaunch(classpath = testClasspath)).use { runtime ->
            val older = runtime.startSession("session-a", "await tools.Write({}); return 'older';", setOf("Write"))
            assertTrue(older.advance() is CodeModeStep.Calls)
            val newer = runtime.startSession("session-a", "await tools.Write({}); return 'newer';", setOf("Write"))
            assertTrue(newer.advance() is CodeModeStep.Calls)
            newer.close()
            val next = withTimeout(START_BOUND_MS) {
                runtime.startSession("session-a", "return 'next';", setOf("Write"))
            }
            val step = withTimeout(START_BOUND_MS) { next.advance() }
            assertEquals("next", (step as CodeModeStep.Completed).output)
            next.close()
            older.close()
        }
    }

    @Test
    fun `a streamed program starts in a session whose older program is parked and whose newer one was closed`() =
        runBlocking {
            JvmCodeModeRuntime(launch = HostLaunch(classpath = testClasspath)).use { runtime ->
                val older = runtime.startStreamingSession("session-a", streamed("return 'older';"), setOf("Write"))
                assertTrue(older.advance() is CodeModeStep.Calls)
                val newer = runtime.startStreamingSession("session-a", streamed("return 'newer';"), setOf("Write"))
                assertTrue(newer.advance() is CodeModeStep.Calls)
                newer.close()
                val next = withTimeout(START_BOUND_MS) {
                    runtime.startStreamingSession("session-a", streamed("return 'next';", call = false), setOf("Write"))
                }
                val step = withTimeout(START_BOUND_MS) { next.advance() }
                assertEquals("started\nnext", (step as CodeModeStep.Completed).output)
                next.close()
                older.close()
            }
        }

    /** A source that streams one statement and then completes, as an upstream exec round does. */
    private fun streamed(ending: String, call: Boolean = true): CodeModeSource {
        val parts = ArrayDeque(
            listOf(
                CodeModeSourcePart.Delta(if (call) "await tools.Write({});\n" else "text('started');\n"),
                CodeModeSourcePart.Complete(ending),
            ),
        )
        return CodeModeSource { parts.removeFirst() }
    }

    @Test
    @Timeout(180)
    fun `fifty parked sessions resume with their own callback results`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 32, memoryBudgetMb = 32_768),
            launch = HostLaunch(heapMb = 256, classpath = testClasspath),
        ).use { runtime ->
            val parked = mutableListOf<Pair<Int, CodeModeCell>>()
            try {
                repeat(50) { index ->
                    val marker = "session-$index"
                    val script = """
                        globalThis.marker = '$marker';
                        const reply = await tools.Read({});
                        return marker + ':' + reply;
                    """.trimIndent()
                    val cell = runtime.startSession(marker, script, setOf("Read"))
                    parked += index to cell
                    assertTrue(cell.advance() is CodeModeStep.Calls, marker)
                }

                val completed = coroutineScope {
                    parked.map { (index, cell) ->
                        async {
                            val step = cell.advance(listOf(CodeModeResult("1", "value-$index")))
                            index to (step as CodeModeStep.Completed)
                        }
                    }.map { it.await() }
                }
                val expected = (0 until 50).map { it to "session-$it:value-$it" }
                val actual = completed.map { (index, step) -> index to step.output }
                assertEquals(expected, actual)
                assertTrue(completed.all { it.second.error == null })
            } finally {
                parked.forEach { (_, cell) -> cell.close() }
            }
        }
    }
}
