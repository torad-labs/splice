// NEW: actual host death, guest execution and engine retirement stay isolated by session ownership.
package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

@Timeout(90)
class CodeModeHostPoolTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `host death interrupts a cell waiting for live source without waiting for upstream EOF`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        val reading = CompletableDeferred<Unit>()
        val next = CompletableDeferred<CodeModeSourcePart>()
        var first = true
        val source = CodeModeSource {
            if (first) {
                first = false
                CodeModeSourcePart.Delta("const value = 1;\\n")
            } else {
                reading.complete(Unit)
                next.await()
            }
        }
        try {
            JvmCodeModeRuntime(
                limits = PoolLimits(maxWorkers = 1),
                launch = HostLaunch(
                    classpath = testClasspath,
                    spawn = WorkerSpawn { it.start().also(spawned::add) },
                ),
            ).use { runtime ->
                val cell = runtime.startStreamingSession("source-fixture", source, emptySet())
                val waiting = async {
                    try {
                        cell.advance()
                        error("A lost cell must not complete cleanly")
                    } catch (error: IOException) {
                        error
                    }
                }
                withTimeout(5_000) { reading.await() }
                spawned.single().destroyForcibly().waitFor()
                try {
                    assertTrue(withTimeout(5_000) { waiting.await() } is CodeModeWorkerLostException)
                } finally {
                    next.complete(CodeModeSourcePart.Complete(""))
                }
            }
        } finally {
            next.complete(CodeModeSourcePart.Complete(""))
        }
    }
}
