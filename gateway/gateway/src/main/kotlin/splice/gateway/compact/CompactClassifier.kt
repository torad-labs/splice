// NEW: the compact-request probe, the classifier that fills it, and the shadow
// instrument that records every request. Split from Compact.kt so the stats
// JSONL file is not billed for the marker scan or the ring (concentration,
// 2026-08-19 / 2026-08-20). ShadowRow is nested under ShadowClassifier — a
// column-0 second type here would push this file over 1.8 after the wire-package
// median drop.
package splice.gateway.compact

import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.wire.AnthropicRequest
import splice.core.wire.TextBlock

/** One-shot probe of a request for the compact classifier + shadow instrument. */
public data class CompactProbe(
    val compact: Boolean,
    val hasMarker: Boolean,
    val sysLen: Int,
)

/** The compaction classifier: the marker scan and the two text extractors it reads. Stateless;
 *  collaborators hold one (`private val compact = CompactClassifier()`). */
public class CompactClassifier {
    public fun systemText(body: AnthropicRequest): String = body.system.orEmpty()

    /**
     * The LAST user message's text — empty when that message carries none.
     *
     * DR-141: this used to keep walking backwards past every user message with no TEXT block,
     * returning the first one that had some. That is not "the last user message", and the shape it
     * skips is the DOMINANT one in Claude Code's agentic loop: a tool_result-only user turn. So
     * after a compaction — Claude Code re-seeds the summary as user text — every following tool
     * turn re-read that summary and re-classified as compact. A false compact is not cosmetic:
     * passthrough drops tools and tool_choice, chat sets emitTools=false, the mirror goes off and
     * compact rows start recording, so a mid-task turn silently becomes a tool-less summarizer turn
     * whose only trace looks like an ordinary compaction. An empty return is the honest answer —
     * a turn whose last user message is a tool result is not asking for a summary.
     */
    public fun lastUserTextOf(body: AnthropicRequest): String =
        body.messages.lastOrNull { it.role == "user" }
            ?.content
            ?.filterIsInstance<TextBlock>()
            ?.map { it.text }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            .orEmpty()

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
}

/** In-memory shadow ring + one log line per request — the marker-drift instrument. */
public class ShadowClassifier(
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    public data class ShadowRow(
        val ts: Long,
        val compact: Boolean,
        val hasMarker: Boolean,
        val toolCount: Int,
        val sysLen: Int,
        val model: String,
    )

    private val ring = ArrayDeque<ShadowRow>()
    private val lock = Any()
    private val classifier = CompactClassifier()

    /** Convenience for callers that only have the boolean; one classifyCompact scan, then override. */
    public fun record(body: AnthropicRequest, compact: Boolean): ShadowRow =
        record(body, classifier.classifyCompact(body).copy(compact = compact))

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
        // CMP-001: literal matching can't be made version-proof, but a PARTIAL drift — the pinned
        // compactMarkers miss while the looser fallback regexes still catch the turn as compact —
        // is mechanically detectable from signals this classifier already computes. Tagged and
        // logged separately from the per-request noise above so it is actually loud, not buried.
        if (row.compact && !row.hasMarker) {
            log(
                "[compact-drift] fallback-only match (has_marker=false, compact=true) — " +
                    "Claude Code's summarizer wording may have drifted from compactMarkers\n",
            )
        }
        return row
    }

    public fun tail(n: Int = SHADOW_DEFAULT_TAIL): List<ShadowRow> = synchronized(lock) { ring.takeLast(n) }
}

// FILE SCOPE ON PURPOSE: two compiled Regex singletons. As members of CompactClassifier they would
// recompile per instance, and the classifier is constructed per consumer.
private val compactionTextOnlyRe = Regex("compaction agent should only produce text", RegexOption.IGNORE_CASE)
private val compactionNoToolsRe = Regex("tool use is not allowed during compaction", RegexOption.IGNORE_CASE)

private const val RING_MAX = 500

// Was ShadowClassifier's companion `DEFAULT_TAIL`. RENAMED because CompactStats' companion carried
// a DIFFERENT value under the same name, and one file scope cannot hold two `DEFAULT_TAIL`s.
private const val SHADOW_DEFAULT_TAIL = 100
