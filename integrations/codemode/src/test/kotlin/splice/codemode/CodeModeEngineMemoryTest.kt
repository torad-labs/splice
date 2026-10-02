// NEW: measures native-engine base RSS without private sessions or platform-specific proc files.
package splice.codemode

import kotlinx.coroutines.runBlocking
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.engine.WorkerSession
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

class CodeModeEngineMemoryTest {
    @Test
    @Timeout(120)
    fun `measure independently warmed engine base memory`() {
        val engines = mutableListOf<Engine>()
        val baseline = rssKiB()
        val samples = mutableListOf<Long>()
        try {
            repeat(4) {
                val engine = Engine.newBuilder("js")
                    .option("engine.SpawnIsolate", "true")
                    .option("engine.IsolateOption.MaxHeapSize", "${CodeModeHeap.guestBytes()}")
                    .build()
                engines += engine
                WorkerSession(engine).use { session ->
                    assertTrue(session.start(WorkerStart("return 'warm';", emptySet())).error == null)
                }
                samples += rssKiB()
            }
            println("engine-base-memory: baseline_kib=$baseline warmed_kib=$samples")
        } finally {
            engines.forEach(Engine::close)
        }
        println("engine-base-memory: closed_kib=${rssKiB()}")
    }

    @Test
    @Timeout(120)
    fun `measure one host with every engine retaining a near cap guest heap`() = runBlocking {
        var host: Process? = null
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerClasspath = checkNotNull(System.getProperty("codeMode.testClasspath")),
            spawn = WorkerSpawn { it.start().also { process -> host = process } },
        ).use { runtime ->
            val cells = (0 until CodeModeHeap.maxEnginesPerHost).map { index ->
                runtime.startSession(
                    "memory-fixture-$index",
                    "await tools.Read({}); globalThis.held = Array(72_000_000).fill(7); return await tools.Read({});",
                    setOf("Read"),
                ).also { assertTrue(it.advance() is CodeModeStep.Calls) }
            }
            val process = checkNotNull(host)
            val warmed = rssKiB(process.pid())
            cells.forEach { cell ->
                val next = cell.advance(listOf(CodeModeResult("1", "allocate")))
                assertTrue(next is CodeModeStep.Calls)
            }
            val pressure = rssKiB(process.pid())
            assertTrue(pressure - warmed > 800L * 1024, "RSS must observe retained guest heaps")
            println(
                "engine-pressure-memory: warmed_kib=$warmed retained_kib=$pressure " +
                    "engines=${CodeModeHeap.maxEnginesPerHost}",
            )
            cells.forEach { cell ->
                cell.advance(listOf(CodeModeResult("2", "release")))
            }
        }
    }

    private fun rssKiB(pid: Long = ProcessHandle.current().pid()): Long {
        val probe = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
        val value = probe.inputStream.bufferedReader().use { it.readText().trim().toLong() }
        check(probe.waitFor() == 0) { "RSS measurement failed" }
        return value
    }
}
