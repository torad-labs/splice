package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapCapacityException
import splice.core.util.LogSink
import splice.provider.codex.history.durable.CodeModeHistoryJournal
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.system.exitProcess

/**
 * Test-only incident reader. Run against private copies named state.jsonl, history.jsonl and key.txt.
 * Each mode uses a separate JVM and the production heap budget. It never writes or prints file contents.
 */
object CodeModeJournalReadProbe {
    private const val HISTORY_LIMIT_BYTES = 256L * 1024 * 1024
    private const val MAX_CAUSES = 8
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args[1] !in setOf("state", "history")) {
            println("{\"status\":\"invalid_arguments\"}")
            exitProcess(2)
        }
        val directory = Path.of(args[0])
        val mode = args[1]
        val started = System.nanoTime()
        var failed = false
        val result = buildJsonObject {
            put("operation", mode)
            put("jvm_max_bytes", JvmHeap.limitBytes)
            put("heap_budget_bytes", JvmHeap.budget.limitBytes)
            try {
                val key = Files.readString(directory.resolve("key.txt")).trim()
                require(key.length == 64)
                val file = directory.resolve("$mode.jsonl")
                require(Files.isRegularFile(file) && !Files.isSymbolicLink(file))
                put("file_bytes", Files.size(file))
                val measured = if (mode == "state") state(file, key) else history(file, key)
                measured.forEach { (name, value) -> put(name, value) }
                put("status", "readable")
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failed = true
                failure(this, failure)
            }
            put("duration_ms", (System.nanoTime() - started) / 1_000_000)
        }
        println(result)
        if (failed) exitProcess(1)
    }

    private fun state(file: Path, key: String): JsonObject {
        val state = CodeModeStateJournal.read(file, json, JvmHeap.budget)
        val matches = state.records.isNotEmpty() &&
            state.records.all { it.key == key } && state.expired.all { it.key == key }
        if (!matches) throw CodeModeStorageException.Invalid(CodeModeStorageProblem.COLD_STATE)
        val liveBytes = CodeModeStateJournal.liveBytes(state, json, JvmHeap.budget)
        val phases = state.records.groupingBy { it.phase }.eachCount()
        return buildJsonObject {
            put("records", state.records.size)
            put("expired", state.expired.size)
            put("live_bytes", liveBytes)
            put("identity_matches", matches)
            put(
                "phases",
                buildJsonObject {
                    phases.forEach { (phase, count) -> put(phase.name, count) }
                },
            )
            put("pending_total", state.records.sumOf { it.pending.size })
            put(
                "unresolved_pending",
                state.records.sumOf { record ->
                    record.pending.count { it.clientId !in record.results }
                },
            )
            put("native_completed_records", state.records.count { it.nativeCompleted })
            put("native_batch_records", state.records.count { it.nativeBatch != null })
            // Conservative candidates: a multi-cell batch also needs every member finished before it can be claimed.
            put(
                "unclaimed_output_candidates",
                state.records.count {
                    it.phase == CodeModePhase.COMPLETED && it.sourceState?.let { source ->
                        source.usage != null && !source.consumed
                    } == true
                },
            )
        }
    }

    private fun history(file: Path, key: String): JsonObject {
        val replay = CodeModeHistoryJournal(json, JvmHeap.budget, HISTORY_LIMIT_BYTES, LogSink {}).read(file, key)
        val state = replay.finish()
        return buildJsonObject {
            put("revision", state.revision)
            put("posts", state.posts.size)
            put("committed_bytes", replay.committedBytes)
            put("uncommitted_bytes", Files.size(file) - replay.committedBytes)
            put("history_limit_bytes", HISTORY_LIMIT_BYTES)
            put("memory_bytes", state.memoryBytes)
            put("identity_matches", state.key == key)
        }
    }

    private fun failure(into: JsonObjectBuilder, error: Throwable) {
        val causes = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        val storage = causes.filterIsInstance<CodeModeStorageException>().firstOrNull()
        val category = when {
            storage != null -> storage.fault.name
            causes.any { it is HeapCapacityException } -> "HEAP"
            error is IllegalArgumentException -> "INVALID"
            error is IOException -> "IO"
            else -> "UNCLASSIFIED"
        }
        into.put("status", "refused")
        into.put("category", category)
        into.put("exception_class", error.javaClass.simpleName)
        into.put("cause_classes", causes.joinToString(",") { it.javaClass.simpleName })
        if (storage is CodeModeStorageException.Limit) {
            into.put("required_bytes", storage.bytes)
            into.put("limit_bytes", storage.maxBytes)
        }
    }
}
