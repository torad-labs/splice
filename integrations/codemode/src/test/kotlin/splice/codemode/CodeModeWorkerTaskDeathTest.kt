// NEW: a worker task that dies of a JVM error answers its turn, retires its worker process and logs one line.
package splice.codemode

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
import splice.codemode.host.HostLaunch
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.ConcurrentLinkedQueue

// why: GraalJS's parser recurses about eight frames per parenthesis; 300 levels parse, 1,000 overflow a worker thread.
private const val OVERFLOWING_DEPTH = 3_000

// why: 8,000 accesses overflowed a worker's stack on most workers, in the parse or at run time (CI run 37234377673).
private const val OVERFLOWING_CHAIN = 8_000

/** A program too deep for the worker's stack ends with the program's own error, from whichever side overflows: the
 *  engine's parse of the whole program, or its run. Either is the script's error, never a lost worker. */
private val PROGRAM_STACK_ERRORS = listOf("SyntaxError: ", "RangeError: ")

// why: a warm worker answers in well under a second; the stall this replaces waited 900 s for the progress timeout.
// The bound is on the ANSWER, so it starts after [warm]: a cold worker JVM can take longer than this alone on a host
// busy with a whole gate, and a bound that includes the spawn fails there for a reason this test does not pin.
private const val ANSWER_BOUND_MS = 5_000L

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
            warm(runtime)
            val step = withTimeout(ANSWER_BOUND_MS) { run(runtime, "text('before');\n$deep") }
            val completed = step as CodeModeStep.Completed
            assertTrue(PROGRAM_STACK_ERRORS.any { completed.error.orEmpty().startsWith(it) }, completed.toString())
        }
    }

    @Test
    fun `a program whose member chain overflows the worker's stack ends with its own error, never a lost worker`() =
        runBlocking {
            runtime().use { runtime ->
                val chain = "const a = {}; a.a = a; const v = " + "a.".repeat(OVERFLOWING_CHAIN) + "a;\n"
                warm(runtime)
                val step = withTimeout(ANSWER_BOUND_MS) { run(runtime, chain) }
                val completed = step as CodeModeStep.Completed
                assertTrue(PROGRAM_STACK_ERRORS.any { completed.error.orEmpty().startsWith(it) }, completed.toString())
            }
        }

    @Test
    fun `a task that dies of a JVM error with no program cause ends as a lost worker and retires its process`() =
        runBlocking {
            val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
            JvmCodeModeRuntime(
                launch = HostLaunch(
                    spawn = WorkerSpawn {
                        SilentHostCloseProcess().also { it.startReply = died() }.also(processes::add)
                    },
                ),
            ).also { it.observeHostLifecycle(LogSink(messages::add)) }.use { runtime ->
                val failure = withTimeout(ANSWER_BOUND_MS) {
                    runCatching { runtime.startSession("session", "return 1;", emptySet()) }
                }
                assertTrue(failure.exceptionOrNull() is CodeModeWorkerLostException, failure.toString())
                HostLifecycleAwait.ended(messages)
                assertFalse(processes.single().isAlive, "the worker whose task died must be retired")
                assertEquals(
                    listOf(
                        "[code-mode] worker task died: OutOfMemoryError in WorkerSession.start; " +
                            "worker process retired",
                    ),
                    messages.filter { "worker task died" in it },
                )
            }
        }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(
        launch = HostLaunch(
            classpath = classpath,
            spawn = WorkerSpawn { builder -> builder.start().also(spawned::add) },
        ),
    ).also { it.observeHostLifecycle(LogSink(messages::add)) }

    /** Boots the worker under its start deadline and checks its first program before timing a warm answer. */
    private suspend fun warm(runtime: JvmCodeModeRuntime) {
        val completed = run(runtime, "text('warm');") as CodeModeStep.Completed
        assertEquals("warm", completed.output, completed.toString())
        assertEquals(null, completed.error, completed.toString())
    }

    /** Hands [source] over as one whole item, as a model's exec item arrives, and runs to the first step. */
    private suspend fun run(runtime: JvmCodeModeRuntime, source: String): CodeModeStep =
        runtime.startStreamingSession("session", CodeModeSource { CodeModeSourcePart.Complete(source) }, emptySet())
            .advance()

    /** The fatal frame a worker writes when a task dies of an OutOfMemoryError: class and frame names only. */
    private fun died(): JsonObject = buildJsonObject {
        put("type", "fatal")
        put("category", "HOST")
        put("faultClass", "RUNTIME")
        put("throwable", "OutOfMemoryError")
        put("at", "WorkerSession.start")
    }
}
