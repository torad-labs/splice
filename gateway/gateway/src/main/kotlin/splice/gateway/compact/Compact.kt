// PORT-OF: server/src/codex/compact.mjs @ 4ca99f7 — invariants (the compaction doctrine):
// POSITIVE-marker detection ONLY, tools-agnostic (v29 rejected tooled bodies and could never
// match the real shape); the markers are Claude Code 2.1.207's VERBATIM summarizer
// instructions, checked in the system prompt OR the LAST user message only (a summary quoted
// in history must never re-trigger — the v13/v24 misfire class stays dead; size/content
// heuristics stay banned). The shadow classifier logs {has_marker, tool_count, sys_len} on
// EVERY request — the drift instrument. Stats JSONL is a contract file (HUD reads it).
// SEAM (recorded): the shadow log line is an injected writer (Node wrote stderr directly);
// stat appends are synchronous best-effort (Node queued a microtask — same guarantee, simpler).
package splice.gateway.compact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonlSink
import splice.core.util.runCatchingCancellable
import splice.core.wire.AnthropicRequest
import splice.core.wire.TextBlock
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

private val compactionTextOnlyRe = Regex("compaction agent should only produce text", RegexOption.IGNORE_CASE)
private val compactionNoToolsRe = Regex("tool use is not allowed during compaction", RegexOption.IGNORE_CASE)

/** One-shot probe of a request for the compact classifier + shadow instrument. */
public data class CompactProbe(
    val compact: Boolean,
    val hasMarker: Boolean,
    val sysLen: Int,
)

public fun systemText(body: AnthropicRequest): String = body.system.orEmpty()

public fun lastUserTextOf(body: AnthropicRequest): String {
    for (msg in body.messages.asReversed()) {
        if (msg.role != "user") continue
        val t = msg.content.filterIsInstance<TextBlock>()
            .map { it.text }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (t.isNotEmpty()) return t
    }
    return ""
}

/** Marker in the system prompt OR the LAST user message — never the whole transcript. */
public fun markerPresent(body: AnthropicRequest): Boolean = classifyCompact(body).hasMarker

/**
 * Detect Claude Code's /compact summarization call (auto + manual). Positive marker only.
 * Returns the full probe so the shadow classifier can reuse sysLen/hasMarker without a second
 * scan of the system prompt + last user message.
 */
public fun classifyCompact(body: AnthropicRequest): CompactProbe {
    val system = systemText(body)
    val lastUser = lastUserTextOf(body)
    // Lowercase once; markers are already lowercase contract strings.
    val hay = buildString(system.length + lastUser.length + 1) {
        append(system)
        append('\n')
        append(lastUser)
    }.lowercase()
    val hasMarker = compactMarkers.any { hay.contains(it) }
    val compact = hasMarker ||
        compactionTextOnlyRe.containsMatchIn(lastUser) ||
        compactionNoToolsRe.containsMatchIn(lastUser)
    return CompactProbe(compact = compact, hasMarker = hasMarker, sysLen = system.length)
}

public data class ShadowRow(
    val ts: Long,
    val compact: Boolean,
    val hasMarker: Boolean,
    val toolCount: Int,
    val sysLen: Int,
    val model: String,
)

/** In-memory shadow ring + one log line per request — the marker-drift instrument. */
public class ShadowClassifier(
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ring = ArrayDeque<ShadowRow>()
    private val lock = Any()

    /** Convenience for callers that only have the boolean; one classifyCompact scan, then override. */
    public fun record(body: AnthropicRequest, compact: Boolean): ShadowRow =
        record(body, classifyCompact(body).copy(compact = compact))

    public fun record(body: AnthropicRequest, probe: CompactProbe): ShadowRow {
        val row = ShadowRow(
            ts = clock(),
            compact = probe.compact,
            hasMarker = probe.hasMarker,
            toolCount = body.tools.size,
            sysLen = probe.sysLen,
            model = body.model,
        )
        synchronized(lock) {
            ring.addLast(row)
            if (ring.size > RING_MAX) ring.removeFirst()
        }
        log(
            "[shadow-compact] compact=${row.compact} has_marker=${row.hasMarker} " +
                "tool_count=${row.toolCount} sys_len=${row.sysLen}\n",
        )
        return row
    }

    public fun tail(n: Int = DEFAULT_TAIL): List<ShadowRow> = synchronized(lock) { ring.takeLast(n) }

    private companion object {
        const val RING_MAX = 500
        const val DEFAULT_TAIL = 100
    }
}

public data class CompactStatsSummary(
    val total: Int,
    val byOutcome: Map<String, Int>,
    val tail: List<JsonObject>,
)

/** Compact outcome stats — the JSONL contract file the HUD and dashboard read. */
public class CompactStats(private val file: Path, private val clock: () -> Long = System::currentTimeMillis) {

    private val json = Json { ignoreUnknownKeys = true }

    // append is best-effort by design: I/O failure must not kill the turn (runCatchingCancellable
    // captures IOException etc. yet still lets coroutine cancellation propagate).
    public fun record(fields: Map<String, Any?>) {
        runCatchingCancellable {
            Files.createDirectories(file.parent)
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
            }
            JsonlSink.appendLine(file, row.toString())
        }
    }

    // read is best-effort by design: a missing/corrupt file yields an empty summary, and a single
    // unparseable line is skipped — cancellation still propagates via runCatchingCancellable.
    // Large files are tail-read (last READ_TAIL_BYTES) so a multi-MB JSONL never becomes a full
    // heap load just to render the HUD; total/byOutcome then reflect the tailed window, not the
    // full history (acceptable for a drift instrument — the file itself is still append-only).
    public fun read(tailN: Int = DEFAULT_TAIL): CompactStatsSummary {
        if (!Files.exists(file)) return CompactStatsSummary(0, emptyMap(), emptyList())
        val rows = runCatchingCancellable {
            JsonlSink.readTail(file, READ_TAIL_BYTES).mapNotNull { line ->
                runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val byOutcome = rows.groupingBy {
            (it["outcome"] as? JsonPrimitive)?.content ?: "unknown"
        }.eachCount()
        return CompactStatsSummary(rows.size, byOutcome, rows.takeLast(tailN))
    }

    private companion object {
        const val DEFAULT_TAIL = 50

        // ~256 KiB of trailing JSONL is plenty for the HUD window and bounds parse cost.
        const val READ_TAIL_BYTES = 256 * 1024
    }
}
