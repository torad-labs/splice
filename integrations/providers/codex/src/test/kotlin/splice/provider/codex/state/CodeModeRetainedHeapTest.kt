// NEW: concurrent retained admissions share one real small-JVM ceiling across two registries, and the ledger charges
// at least the heap their graphs really hold.
package splice.provider.codex.state

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.memory.JvmHeap
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val RETAINED_ATTEMPTS = 139
private const val RETAINED_PAYLOAD_BYTES = 1024 * 1024
private const val RETAINED_CHILD_SECONDS = 120L

// why: two admissions in flight reserve at most about 89 MiB of the 128 MiB ledger (request 6.5, record 2, snapshot 4
// and the 16x encoding peak 32 MiB each), so the first retained graphs always fit and the first refusal came at attempt
// 8 or 9. Runs completed 14 to 19, idle and as eight probes at once under sixteen CPU burners; four is half of that.
private const val COMPLETED_FLOOR = 4

// why: a refused record's charge returns only once the collector finds it and the cleaner refunds it, so the ledger
// is read once a full collection brings no refund within the quiet window. Reading early only overstates the charge.
private const val SETTLE_ROUNDS = 40
private val SETTLE_QUIET = 500.milliseconds

class CodeModeRetainedHeapTest {
    @Test
    fun `concurrent retained admissions complete or refuse in a 256 MiB JVM, charged at least what they hold`(
        @TempDir dir: Path,
    ) {
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
            val before = settled()
            val outcomes = run(registries)
            val completed = outcomes.count { it.refusal == null }
            val refused = outcomes.count { it.refusal != null }
            val after = settled()
            val charged = after.charge - before.charge
            val live = after.live - before.live
            check(completed + refused == RETAINED_ATTEMPTS)
            // 139 one-MiB payloads cannot all be kept in a 128 MiB ledger, so refusals are certain, and the floor
            // above makes completions certain: both paths run on every box.
            check(completed >= COMPLETED_FLOOR && refused > 0) { "completed=$completed refused=$refused" }
            // The retained graphs are charged at least what they really hold, measured by the JVM, not by the
            // estimator under test. Heap the ledger does not see fails here once it outgrows the estimates' slack,
            // and fails the JVM itself past the heap.
            check(charged >= live) { "the ledger charges $charged bytes for $live live retained bytes" }
            check(JvmHeap.budget.available.value in 0..JvmHeap.budget.limitBytes)
            println(
                "retained attempts=$RETAINED_ATTEMPTS completed=$completed refused=$refused " +
                    "charged_bytes=$charged live_bytes=$live ledger_limit_bytes=${JvmHeap.budget.limitBytes}",
            )
        } finally {
            registries.forEach { registry -> registry.timed.finish { registry.onHeadStop() } }
            Reference.reachabilityFence(resident)
        }
    }

    /** One worker per registry: both heads admit at once against the shared ledger, one admission each in flight. */
    private fun run(registries: List<CodexCodeModeRegistry>): List<RetainedHeapOutcome> {
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val workers = registries.mapIndexed { head, registry ->
                executor.submit<List<Pair<Int, RetainedHeapOutcome>>> {
                    check(start.await(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS))
                    (head until RETAINED_ATTEMPTS step registries.size).map { index -> index to admit(registry, index) }
                }
            }
            start.countDown()
            workers.flatMap { it.get(RETAINED_CHILD_SECONDS, TimeUnit.SECONDS) }.sortedBy { it.first }.map { it.second }
        }
    }

    private fun admit(registry: CodexCodeModeRegistry, index: Int): RetainedHeapOutcome {
        // Model the already admitted request owner before any source payload is materialized.
        val request = JvmHeap.budget.reserve(HeapWeights.request(RETAINED_PAYLOAD_BYTES.toLong()))
            ?: return RetainedHeapOutcome(HeapCapacityException())
        return request.use {
            val record = record(index)
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

    private fun record(index: Int): CodeModeRecord = CodeModeRecords.of("retained-$index", index).copy(
        continuity = listOf(JsonPrimitive("x".repeat(RETAINED_PAYLOAD_BYTES))),
    )

    /** The ledger's charge and the JVM's live heap once every unreachable owner is collected and refunded. */
    private fun settled(): RetainedHeapReading = runBlocking {
        val budget = JvmHeap.budget
        repeat(SETTLE_ROUNDS) {
            val seen = budget.available.value
            System.gc()
            withTimeoutOrNull(SETTLE_QUIET) { budget.available.first { it != seen } }
                ?: return@runBlocking RetainedHeapReading(
                    budget.limitBytes - seen,
                    ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
                )
        }
        error("the ledger never settled")
    }
}

private data class RetainedHeapReading(val charge: Long, val live: Long)
