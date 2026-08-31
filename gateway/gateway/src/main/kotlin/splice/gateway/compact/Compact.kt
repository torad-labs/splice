// PORT-OF: server/src/codex/compact.mjs @ pre-public-port-baseline — invariants (the compaction doctrine):
// POSITIVE-marker detection ONLY, tools-agnostic (v29 rejected tooled bodies and could never
// match the real shape); the markers are Claude Code 2.1.207's VERBATIM summarizer
// instructions, checked in the system prompt OR the LAST user message only (a summary quoted
// in history must never re-trigger — the v13/v24 misfire class stays dead; size/content
// heuristics stay banned). The shadow classifier logs {has_marker, tool_count, sys_len} on
// EVERY request — the drift instrument. Stats JSONL is a contract file (HUD reads it).
// SEAM (recorded): the shadow log line is an injected writer (Node wrote stderr directly);
// stat appends are asynchronous best-effort on the bounded process-wide file lane.
package splice.gateway.compact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** Primary summarizer marker (kept for the canary test + shadow key). */
public const val COMPACT_MARKER: String = "tasked with summarizing conversations"

/** Every verbatim summarizer instruction Claude Code 2.1.207 emits (binary-traced).
 *  On drift: add the new verbatim sentence here + a fixture. The values are the ported contract;
 *  the identifier is camelCase per Kotlin convention (only the singular `const` stays UPPER_SNAKE). */
public val compactMarkers: List<String> = listOf(
    "tasked with summarizing conversations",
    "your task is to create a detailed summary of this conversation",
    "your task is to create a detailed summary of the conversation",
    "your task is to create a detailed summary of the recent portion",
    "summarize this portion of a claude code session transcript",
)

// CompactProbe + CompactClassifier + ShadowClassifier live in CompactClassifier.kt
// (concentration, 2026-08-19 / 2026-08-20).

// Was CompactStats' companion `DEFAULT_TAIL`. RENAMED because ShadowClassifier's companion carried
// a DIFFERENT value under the same name, and one file scope cannot hold two `DEFAULT_TAIL`s.
private const val STATS_DEFAULT_TAIL = 50

// ~256 KiB of trailing JSONL is plenty for the HUD window and bounds parse cost.
private const val READ_TAIL_BYTES = 256 * 1024

public data class CompactStatsSummary(
    val total: Int,
    val byOutcome: Map<String, Int>,
    val tail: List<JsonObject>,
)

/** Compact outcome stats — the JSONL contract file the HUD and dashboard read. */
public class CompactStats(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    private val unreadableLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true }

    // append is best-effort by design: the turn builds an immutable row and the bounded file lane
    // owns filesystem latency.
    public fun record(fields: Map<String, Any?>) {
        val row = buildJsonObject {
            put("ts", clock())
            fields.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }.toString()
        AsyncFileIo.submit {
            Cancellables.runCatchingCancellable {
                Files.createDirectories(file.parent)
                JsonlSink.appendLine(file, row)
            }
        }
    }

    // read is best-effort by design: a missing/corrupt file yields an empty summary, and a single
    // unparseable line is skipped — cancellation still propagates via runCatchingCancellable.
    // Large files are tail-read (last READ_TAIL_BYTES) so a multi-MB JSONL never becomes a full
    // heap load just to render the HUD; total/byOutcome then reflect the tailed window, not the
    // full history (acceptable for a drift instrument — the file itself is still append-only).
    public fun read(tailN: Int = STATS_DEFAULT_TAIL): CompactStatsSummary {
        AsyncFileIo.drain()
        // DR-60 (class law): only PROVEN absence — NoSuch with no NOFOLLOW entry — is the quiet
        // zero-stats empty; an inaccessible file degrades the same but leaves a trace (the old
        // exists() pre-gate blanked the drift instrument silently through a denied parent).
        val rows = Cancellables.runCatchingCancellable {
            JsonlSink.readTail(file, READ_TAIL_BYTES).mapNotNull { line ->
                Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent && unreadableLogged.compareAndSet(false, true)) {
                log("[compact] $file unreadable ($failure) — stats rendered empty\n")
            }
            if (genuinelyAbsent) unreadableLogged.set(false)
            emptyList()
        }.also { if (it.isNotEmpty()) unreadableLogged.set(false) }
        val byOutcome = rows.groupingBy {
            (it["outcome"] as? JsonPrimitive)?.content ?: "unknown"
        }.eachCount()
        return CompactStatsSummary(rows.size, byOutcome, rows.takeLast(tailN))
    }
}
