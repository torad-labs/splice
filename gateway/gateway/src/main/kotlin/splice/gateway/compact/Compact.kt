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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest
import splice.core.wire.TextBlock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Primary summarizer marker (kept for the canary test + shadow key). */
public const val COMPACT_MARKER: String = "tasked with summarizing conversations"

/** Every verbatim summarizer instruction Claude Code 2.1.207 emits (binary-traced).
 *  On drift: add the new verbatim sentence here + a fixture. */
@Suppress("TopLevelPropertyNaming") // ported constant list — SCREAMING_CASE is the contract name
public val COMPACT_MARKERS: List<String> = listOf(
    "tasked with summarizing conversations",
    "your task is to create a detailed summary of this conversation",
    "your task is to create a detailed summary of the conversation",
    "your task is to create a detailed summary of the recent portion",
    "summarize this portion of a claude code session transcript",
)

private val compactionTextOnlyRe = Regex("compaction agent should only produce text", RegexOption.IGNORE_CASE)
private val compactionNoToolsRe = Regex("tool use is not allowed during compaction", RegexOption.IGNORE_CASE)

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
public fun markerPresent(body: AnthropicRequest): Boolean {
    val hay = (systemText(body) + "\n" + lastUserTextOf(body)).lowercase()
    return COMPACT_MARKERS.any { hay.contains(it) }
}

/** Detect Claude Code's /compact summarization call (auto + manual). Positive marker only. */
public fun classifyCompact(body: AnthropicRequest): Boolean {
    if (markerPresent(body)) return true
    val lastUserText = lastUserTextOf(body)
    return compactionTextOnlyRe.containsMatchIn(lastUserText) ||
        compactionNoToolsRe.containsMatchIn(lastUserText)
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

    public fun record(body: AnthropicRequest, compact: Boolean): ShadowRow {
        val row = ShadowRow(
            ts = clock(),
            compact = compact,
            hasMarker = markerPresent(body),
            toolCount = body.tools.size,
            sysLen = systemText(body).length,
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

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // append is best-effort by design
    public fun record(fields: Map<String, Any?>) {
        try {
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
            Files.writeString(
                file,
                row.toString() + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // read is best-effort by design
    public fun read(tailN: Int = DEFAULT_TAIL): CompactStatsSummary {
        if (!Files.exists(file)) return CompactStatsSummary(0, emptyMap(), emptyList())
        val rows = try {
            Files.readString(file).trim().lines().filter { it.isNotEmpty() }.mapNotNull { line ->
                try {
                    json.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        val byOutcome = rows.groupingBy {
            (it["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "unknown"
        }.eachCount()
        return CompactStatsSummary(rows.size, byOutcome, rows.takeLast(tailN))
    }

    private companion object {
        const val DEFAULT_TAIL = 50
    }
}
