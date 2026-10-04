// NEW: a worker task that dies of a JVM error answers its turn, retires its worker process and logs one line.
package splice.codemode

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.ConcurrentLinkedQueue

// why: GraalJS's parser recurses about eight frames per parenthesis; 300 levels parse, 1,000 overflow a worker thread.
private const val OVERFLOWING_DEPTH = 3_000

// why: a warm worker answers in well under a second; the stall this replaces waited 900 s for the progress timeout.
private const val ANSWER_BOUND_MS = 5_000L

private val DEATH_LINE = Regex(
    """\[code-mode] worker task died: StackOverflowError in [A-Za-z0-9_$]+\.[A-Za-z0-9_$<>-]+; """ +
        "worker process retired",
)

/** Oct 4: a worker task that died of a JVM error wrote no reply, so its turn waited 900 s for the progress timeout. */
@Timeout(90)
class CodeModeWorkerTaskDeathTest {
    private val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))
    private val deep = "const v = " + "(".repeat(OVERFLOWING_DEPTH) + "1" + ")".repeat(OVERFLOWING_DEPTH) + ";\n"
    private val spawned = ConcurrentLinkedQueue<Process>()
    private val messages = ConcurrentLinkedQueue<String>()

    @Test
    fun `a program nested too deep to parse ends with its own error, as a syntax error does`() = runBlocking {
        runtime().use { runtime ->
            val step = withTimeout(ANSWER_BOUND_MS) { run(runtime, "text('before');\n$deep", "text('after');") }
            val completed = step as CodeModeStep.Completed
            assertEquals("SyntaxError: program nesting too deep to parse", completed.error, completed.toString())
            assertEquals("before", completed.output)
        }
    }

    @Test
    fun `the worker whose parse overflowed is retired, and the next program runs on a fresh one`() = runBlocking {
        runtime().use { runtime ->
            withTimeout(ANSWER_BOUND_MS) { run(runtime, deep, "") }
            val overflowed = spawned.single()
            withTimeout(ANSWER_BOUND_MS) { overflowed.onExit().await() }
            val next = withTimeout(ANSWER_BOUND_MS) { run(runtime, "text('fresh');\n", "") }
            assertEquals("fresh", (next as CodeModeStep.Completed).output, next.toString())
            assertEquals(2, spawned.size)
        }
    }

    @Test
    fun `a task death is logged once by its throwable and frame, with no source text`() = runBlocking {
        runtime().use { runtime ->
            withTimeout(ANSWER_BOUND_MS) { run(runtime, deep, "") }
            HostLifecycleAwait.ended(messages)
            val deaths = messages.filter { "worker task died" in it }
            assertEquals(1, deaths.size, messages.toString())
            assertTrue(DEATH_LINE.matches(deaths.single()), deaths.single())
        }
    }

    @Test
    fun `a task that dies of a JVM error with no program cause ends as a lost worker and retires its process`() =
        runBlocking {
            val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
            JvmCodeModeRuntime(
                spawn = WorkerSpawn { SilentHostCloseProcess().also { it.startReply = died() }.also(processes::add) },
            ).also { it.observeHostLifecycle(LogSink(messages::add)) }.use { runtime ->
                val failure = withTimeout(ANSWER_BOUND_MS) {
                    runCatching { runtime.startSession("session", "return 1;", emptySet()) }
                }
                assertTrue(failure.exceptionOrNull() is CodeModeWorkerLostException, failure.toString())
                HostLifecycleAwait.ended(messages)
                assertFalse(processes.single().isAlive, "the worker whose task died must be retired")
                assertEquals(
                    listOf(
                        "[code-mode] worker task died: OutOfMemoryError in CodeModeStatementParser.tokens; " +
                            "worker process retired",
                    ),
                    messages.filter { "worker task died" in it },
                )
            }
        }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(
        workerClasspath = classpath,
        spawn = WorkerSpawn { builder -> builder.start().also(spawned::add) },
    ).also { it.observeHostLifecycle(LogSink(messages::add)) }

    /** Streams [first] as a part of its own, then completes the source with [last], and runs to the first step. */
    private suspend fun run(runtime: JvmCodeModeRuntime, first: String, last: String): CodeModeStep {
        val parts = ArrayDeque<CodeModeSourcePart>(
            listOf(CodeModeSourcePart.Delta(first), CodeModeSourcePart.Complete(last)),
        )
        return runtime.startStreamingSession("session", CodeModeSource { parts.removeFirst() }, emptySet()).advance()
    }

    /** The fatal frame a worker writes when a task dies of an OutOfMemoryError: class and frame names only. */
    private fun died(): JsonObject = buildJsonObject {
        put("type", "fatal")
        put("category", "HOST")
        put("faultClass", "RUNTIME")
        put("throwable", "OutOfMemoryError")
        put("at", "CodeModeStatementParser.tokens")
    }
}
