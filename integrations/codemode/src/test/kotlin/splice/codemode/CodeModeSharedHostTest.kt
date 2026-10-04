// NEW: parked script contexts share one prewarmed host, never one process or permit per script.
package splice.codemode

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.engine.WorkerSession
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.atomic.AtomicInteger

class CodeModeSharedHostTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    @Timeout(120)
    fun `a hundred parked scripts do not hold admission for the next script`() = runBlocking {
        val processes = AtomicInteger()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            heapMb = 512,
            workerClasspath = testClasspath,
            spawn = WorkerSpawn { builder ->
                processes.incrementAndGet()
                builder.start()
            },
        ).use { runtime ->
            val held = mutableListOf<CodeModeCell>()
            try {
                repeat(100) { index ->
                    val cell = runtime.start("return await tools.call(\"Read\", {index: $index});", setOf("Read"))
                    held += cell
                    assertTrue(cell.advance() is CodeModeStep.Calls)
                }
                val next = withTimeout(5_000) { runtime.start("return \"immediate\";", emptySet()) }
                assertEquals("immediate", (next.advance() as CodeModeStep.Completed).output)
                assertEquals(1, processes.get(), "parked contexts must share a host")
                held.forEachIndexed { index, cell ->
                    val result = cell.advance(listOf(CodeModeResult("1", "value-$index"))) as CodeModeStep.Completed
                    assertEquals("value-$index", result.output, "cell results stay isolated")
                }
            } finally {
                held.forEach { it.close() }
            }
        }
    }

    @Test
    @Timeout(60)
    fun `guest retained heap measurement sees allocations and the memory gate can fail`() {
        Engine.newBuilder("js")
            .option("engine.SpawnIsolate", "true")
            .option("engine.IsolateOption.MaxHeapSize", "256m")
            .build().use { engine ->
                WorkerSession(engine, heapLimitBytes = 8L * 1024 * 1024).use { session ->
                    val exhausted = assertThrows(PolyglotException::class.java) {
                        session.start(
                            WorkerStart(
                                "globalThis.retained = []; while (true) { retained.push({text: 'x'.repeat(1000)}); }",
                                emptySet(),
                            ),
                        )
                    }
                    assertTrue(exhausted.isResourceExhausted, exhausted.message)
                }
                java.util.concurrent.Executors.newCachedThreadPool().use { executor ->
                    executor.submit {
                        WorkerSession(engine).use { survivor ->
                            assertEquals("healthy", survivor.start(WorkerStart("return 'healthy';", emptySet())).output)
                        }
                    }.get()
                }
            }
    }

    @Test
    @Timeout(60)
    fun `a malformed cell frame replies with a fatal error without abandoning its sibling`() = runBlocking {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-Xmx512m",
            "-cp",
            testClasspath,
            CodeModeWorker::class.java.name,
            "host",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        SharedWorkerChannel(process, this).use { host ->
            withTimeout(10_000) { host.awaitReady() }
            val sibling = host.cell(1)
            val first = sibling.exchange(CodeModeWire.startFrame("return await tools.call('Read', {});", setOf("Read")))
            assertEquals(1, CodeModeFrames.parseReply(first, setOf("Read"), 1).calls?.size)
            val malformed = host.cell(2)
            val answer = withTimeout(5_000) {
                malformed.exchange(kotlinx.serialization.json.buildJsonObject { put("type", "start") })
            }
            val fault = assertThrows(splice.upstream.failure.CodeModeInfrastructureException::class.java) {
                CodeModeFrames.parseReply(answer, emptySet(), 1)
            }
            assertEquals(splice.upstream.failure.CodeModeInfrastructureCategory.PROTOCOL, fault.category)
            val resumed = sibling.exchange(CodeModeWire.resultFrame(listOf(CodeModeResult("1", "alive"))))
            assertEquals("alive", CodeModeFrames.parseReply(resumed, setOf("Read"), 2).output)
            sibling.close()
            malformed.close()
        }
    }

    @Test
    @Timeout(60)
    fun `aggregate isolate exhaustion leaves parked sibling contexts alive`() {
        Engine.newBuilder("js")
            .option("engine.SpawnIsolate", "true")
            .option("engine.IsolateOption.MaxHeapSize", "256m")
            .build().use { engine ->
                WorkerSession(engine, heapLimitBytes = 192L * 1024 * 1024).use { first ->
                    WorkerSession(engine, heapLimitBytes = 192L * 1024 * 1024).use { second ->
                        val held = "globalThis.held = Array(8_000_000).fill(7); return await tools.call('Read', {});"
                        assertEquals(1, first.start(WorkerStart(held, setOf("Read"))).calls?.size)
                        assertEquals(1, second.start(WorkerStart(held, setOf("Read"))).calls?.size)
                        assertAggregatePressureIsIsolated(engine)
                        assertEquals("first", first.advance(listOf(CodeModeResult("1", "first"))).output)
                        assertEquals("second", second.advance(listOf(CodeModeResult("1", "second"))).output)
                    }
                }
            }
    }

    private fun assertAggregatePressureIsIsolated(engine: Engine) {
        WorkerSession(engine, heapLimitBytes = 192L * 1024 * 1024).use { offender ->
            val exhausted = assertThrows(PolyglotException::class.java) {
                offender.start(
                    WorkerStart(
                        "globalThis.retained = []; while (true) { retained.push(Array(100_000).fill(1)); }",
                        emptySet(),
                    ),
                )
            }
            assertTrue(exhausted.isResourceExhausted, exhausted.message)
            println("code-mode aggregate heap receipt: ${exhausted.message}")
        }
        WorkerSession(engine).use { next ->
            assertEquals("next", next.start(WorkerStart("return 'next';", emptySet())).output)
        }
    }

    @Test
    @Timeout(60)
    fun `the second script reuses the booted host and measures its start cost`() = runBlocking {
        val processes = AtomicInteger()
        JvmCodeModeRuntime(
            workerClasspath = testClasspath,
            spawn = WorkerSpawn { builder ->
                processes.incrementAndGet()
                builder.start()
            },
        ).use { runtime ->
            val first = System.nanoTime()
            val firstCell = runtime.start("return \"first\";", emptySet())
            assertEquals("first", (firstCell.advance() as CodeModeStep.Completed).output)
            val firstMs = (System.nanoTime() - first) / 1_000_000
            val second = System.nanoTime()
            val secondCell = runtime.start("return \"second\";", emptySet())
            assertEquals("second", (secondCell.advance() as CodeModeStep.Completed).output)
            val secondMs = (System.nanoTime() - second) / 1_000_000
            println("code-mode boot receipt: first_ms=$firstMs second_ms=$secondMs processes=${processes.get()}")
            assertEquals(1, processes.get(), "a second script must never pay for another JVM boot")
        }
    }
}
