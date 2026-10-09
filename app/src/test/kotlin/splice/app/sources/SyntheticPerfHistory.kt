// Deterministic, operator-free seven-day usage history at the reported request and token scale.
package splice.app.sources

import splice.core.perf.PerfKeys
import java.nio.file.Files
import java.nio.file.Path

internal const val SCALE_REQUESTS = 87_559
internal const val SCALE_TOKENS = 25_440_000_000L
internal const val SCALE_SINCE = 1_800_000_000_000L
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1_000

internal class SyntheticPerfHistory(private val directory: Path) {
    internal val file: Path = directory.resolve("synthetic-perf.jsonl")

    internal fun create(requests: Int = SCALE_REQUESTS) {
        write(file.resolveSibling("${file.fileName}.1"), SCALE_SINCE - WEEK_MS, requests)
        write(file, SCALE_SINCE, requests)
    }

    private fun write(path: Path, start: Long, requests: Int) {
        val metrics = (0 until 35).joinToString(",") { "\"metric_$it\":12" }
        val detail = "x".repeat(192)
        Files.newBufferedWriter(path).use { writer ->
            repeat(requests) { index ->
                val tokens = SCALE_TOKENS / SCALE_REQUESTS + if (index < SCALE_TOKENS % SCALE_REQUESTS) 1 else 0
                val timestamp = start + index * WEEK_MS / SCALE_REQUESTS
                val input = tokens - 64
                writer.write(
                    """{"ts":$timestamp,"outcome":"ok","model":"synthetic-model","session":"synthetic",""" +
                        """"session_id":"synthetic-session-${index % 128}","response_message_id":"synthetic-response-$index",""" +
                        """"account":"synthetic-account","turn":"synthetic-turn-$index","compact":false,"cache_cold":false,""" +
                        """"${PerfKeys.IN_TOKENS}":$input,"${PerfKeys.OUT_TOKENS}":64,"${PerfKeys.CACHED_TOKENS}":0,""" +
                        """"${PerfKeys.FIRST_BYTE}":${index % 10_000 + 1},$metrics,"ignored_detail":"$detail"}""",
                )
                writer.newLine()
            }
        }
    }
}
