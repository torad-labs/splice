// NEW: concurrent retained admissions share one real small-JVM ceiling across two registries.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapWeights
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.memory.JvmHeap
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

private const val RETAINED_ATTEMPTS = 139
private const val RETAINED_PAYLOAD_BYTES = 1024 * 1024
private const val RETAINED_CHILD_SECONDS = 120L

class CodeModeRetainedHeapTest {
    @Test
    fun `concurrent retained admissions complete or refuse in a 256 MiB JVM`(@TempDir dir: Path) {
        val stdout = dir.resolve("retained.stdout")
        val stderr = dir.resolve("retained.stderr")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Xmx256m",
            "-XX:ActiveProcessorCount=4",
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=${dir.resolve("retained.hprof")}",
            "-cp",
            System.getProperty("java.class.path"),
            CodeModeRetainedHeapProbe::class.java.name,
            dir.toString(),
        ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS), "retained child did not settle")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val evidence = Files.readString(stdout) + Files.readString(stderr)
        assertEquals(0, process.exitValue(), evidence)
        assertTrue(evidence.contains("retained attempts=$RETAINED_ATTEMPTS"), evidence)
        assertTrue(evidence.contains("ledger_limit_bytes=134217728"), evidence)
        assertTrue(!Files.exists(dir.resolve("retained.hprof")), evidence)
        println(Files.readString(stdout))
    }
}

private data class RetainedHeapOutcome(val refusal: HeapCapacityException? = null)

/** Real registry admission, snapshot, force and completion paths run without any provider request or worker. */
object CodeModeRetainedHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Runtime.getRuntime().maxMemory() == 256 * 1024 * 1024L)
        check(JvmHeap.budget.limitBytes == 128 * 1024 * 1024L)
        val resident = ByteArray(128 * 1024 * 1024)
        val registries = List(2) { index ->
            val dir = Path.of(args.single()).resolve("head-$index")
            CodexCodeModeRegistry(
                CodeModeBridgeConfig(
                    runtimes = { error("retained admission must not start a worker") },
                    state = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json")),
                ),
                Json { encodeDefaults = true },
                5.minutes,
            )
        }
        try {
            val outcomes = run(registries)
            val completed = outcomes.count { it.refusal == null }
            val refused = outcomes.count { it.refusal != null }
            check(completed > 0 && refused > 0 && completed + refused == RETAINED_ATTEMPTS)
            check(JvmHeap.budget.available.value in 0..JvmHeap.budget.limitBytes)
            println(
                "retained attempts=$RETAINED_ATTEMPTS completed=$completed refused=$refused " +
                    "ledger_limit_bytes=${JvmHeap.budget.limitBytes}",
            )
        } finally {
            registries.forEach { registry -> registry.timed.finish { registry.onHeadStop() } }
            Reference.reachabilityFence(resident)
        }
    }

    private fun run(registries: List<CodexCodeModeRegistry>): List<RetainedHeapOutcome> {
        val ready = CountDownLatch(RETAINED_ATTEMPTS)
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val attempts = List(RETAINED_ATTEMPTS) { index ->
                executor.submit<RetainedHeapOutcome> {
                    ready.countDown()
                    check(start.await(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS))
                    admit(registries[index % registries.size], index)
                }
            }
            try {
                check(ready.await(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS))
            } finally {
                start.countDown()
            }
            attempts.map { it.get(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun admit(registry: CodexCodeModeRegistry, index: Int): RetainedHeapOutcome {
        // Model the already admitted request owner before any source payload is materialized.
        val request = JvmHeap.budget.reserve(HeapWeights.request(RETAINED_PAYLOAD_BYTES.toLong()))
            ?: return RetainedHeapOutcome(HeapCapacityException())
        return request.use {
            val record = CodeModeRecords.of("retained-$index", index).copy(
                continuity = listOf(JsonPrimitive("x".repeat(RETAINED_PAYLOAD_BYTES))),
            )
            try {
                check(registry.add(record)) { "synthetic retention must not reject through another policy" }
                registry.complete(record, "done")
                RetainedHeapOutcome()
            } catch (capacity: HeapCapacityException) {
                RetainedHeapOutcome(capacity)
            } catch (persistence: CodeModePersistenceException) {
                val capacity = persistence.cause as? HeapCapacityException ?: throw persistence
                RetainedHeapOutcome(capacity)
            }
        }
    }
}
