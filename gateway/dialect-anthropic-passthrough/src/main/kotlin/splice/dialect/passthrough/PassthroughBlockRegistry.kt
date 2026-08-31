// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the block map, the
// signature latch and the sink open/close are ONE three-event invariant with a wire write in the
// middle (start opens, a signature_delta latches, stop synthesizes-at-most-once then closes), so
// they stay in one file: split across two, the exactly-once signature contract degrades to
// never-or-twice, and TWICE persists a forged signature into the transcript. Statement order inside
// [applyDelta] and [onBlockStop] is byte-pinned by the translator goldens and is unchanged.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.spi.WireSink

// Short stable constant — Kimi never verifies signatures; Claude Code only needs one present.
private const val SYNTHETIC_SIGNATURE = "splice-synth-v1"

// FILE SCOPE ON PURPOSE: one shared empty object, read on the delta hot path — as a member it would
// be rebuilt per translator instance (one per turn).
private val EMPTY = JsonObject(emptyMap())

/** The open blocks of one turn, keyed by the upstream index, and the three content_block events
 *  that drive them. [prose] owns the text/thinking buffers the deltas append to; the signature
 *  latch on each [Block] stays here, next to the synthesis it gates. */
internal class PassthroughBlockRegistry(
    private val ctx: PassthroughTurnContext,
    private val quirks: PassthroughQuirks,
    private val prose: PassthroughProseChannels,
) {

    private val blocks = HashMap<Int, Block>()

    // NF-06: tool JSON bypasses the prose buffers, so retain its aggregate size as a count only.
    private var toolArgsCharCount = 0L
    internal val openBlockCount: Int get() = blocks.size
    internal val bufferedToolArgsChars: Int get() = minOf(Int.MAX_VALUE.toLong(), toolArgsCharCount).toInt()
    internal var hasToolUse = false

    // PT-001: latched after the first unmapped-index delta is logged (TurnDriver.malformedLogged's
    // idiom) — a chatty misbehaving upstream can emit many post-stop deltas in a row, and per-delta
    // logging is unbounded, synchronous daemon.log I/O on the hot path. The anomaly stays visible;
    // it just stops repeating.
    private var unmappedIndexLogged = false

    internal suspend fun onBlockStart(evt: JsonObject, sink: WireSink) {
        val index = JsonScalars.int(evt, "index") ?: return
        val cb = evt["content_block"] as? JsonObject
        blocks[index] = when (JsonScalars.strOrEmpty(cb?.get("type"))) {
            "text" -> Block(Kind.TEXT, sink.openText())
            "thinking" -> Block(Kind.THINKING, sink.openThinking())
            "tool_use" -> {
                // Pass Kimi's tool id VERBATIM: it round-trips back to Kimi on the next turn — a
                // JsonNull id must never leak as the literal string "null" into that round-trip (L3);
                // strOrEmpty keeps it filtered (review 2026-07-22 round 3).
                hasToolUse = true
                Block(
                    Kind.TOOL,
                    sink.openTool(JsonScalars.strOrEmpty(cb?.get("id")), JsonScalars.strOrEmpty(cb?.get("name"))),
                )
            }
            // DR-118: encrypted reasoning must SURVIVE the proxy — Claude Code replays assistant
            // turns from what it received, and Anthropic requires redacted_thinking back verbatim,
            // so a dropped block 400s the next signed-thinking request upstream. Data rides
            // content_block_start (the WireSink contract) and no delta ever targets the block, so
            // it emits complete here and the entry stays IGNORED for the delta/stop path.
            "redacted_thinking" -> {
                sink.addRedactedThinking(JsonScalars.strOrEmpty(cb?.get("data")))
                Block(Kind.IGNORED, null)
            }
            // DR-119: the server-tool result surface rides VERBATIM on the neutral head (CH-2 —
            // a head that declares nothing gets its bytes forwarded as sent); Claude Code renders
            // the search and keeps citations only if these blocks reach the transcript. Kimi's
            // profile keeps the historical swallow (quirk-gated, byte-identity law).
            "server_tool_use", "web_search_tool_result" -> openServerToolBlock(cb, sink)
            // unknown: record + swallow its deltas.
            else -> Block(Kind.IGNORED, null)
        }
    }

    private suspend fun openServerToolBlock(cb: JsonObject?, sink: WireSink): Block {
        if (quirks.dropServerToolBlocks || cb == null) return Block(Kind.IGNORED, null)
        // A sink that cannot forward raw blocks (openRawBlock's default) degrades to the
        // pre-DR-119 ignore, never to a half-opened block.
        val wire = sink.openRawBlock(cb) ?: return Block(Kind.IGNORED, null)
        return Block(Kind.RAW, wire)
    }

    // The upstream delta type already matches the (non-ignored) block it targets, so we dispatch on
    // the delta type; the open block's wire is the only thing we need. Ignored blocks have no wire.
    internal suspend fun onBlockDelta(evt: JsonObject, sink: WireSink) {
        val index = JsonScalars.int(evt, "index") ?: return
        val block = blocks[index]
        if (block == null) {
            // PT-001: an index with no live block entry (never opened, or already closed) drops
            // its content — never silently: this is the translator's only anomaly channel. Logged
            // ONCE per turn, not once per delta (a torn/misbehaving upstream can emit many).
            if (!unmappedIndexLogged) {
                ctx.log("[${quirks.providerTag}] content_block_delta for unmapped index=$index — dropped\n")
                unmappedIndexLogged = true
            }
            return
        }
        applyDelta(block, evt["delta"] as? JsonObject ?: EMPTY, sink)
    }

    private suspend fun applyDelta(block: Block, delta: JsonObject, sink: WireSink) {
        val wire = block.wire ?: return // ignored block: swallow
        when (JsonScalars.strOrEmpty(delta["type"])) {
            "text_delta" -> prose.textDelta(wire, JsonScalars.strOrEmpty(delta["text"]), sink)
            "thinking_delta" -> prose.thinkingDelta(wire, JsonScalars.strOrEmpty(delta["thinking"]), sink)
            "input_json_delta" -> {
                val partialJson = JsonScalars.strOrEmpty(delta["partial_json"])
                sink.inputJsonDelta(wire, partialJson)
                toolArgsCharCount += partialJson.length.toLong()
            }
            "signature_delta" -> onSignatureDelta(block, wire, delta, sink)
            // DR-119: citations ride text blocks; the neutral head forwards the delta object
            // verbatim. Kimi keeps the historical swallow (same gate as the server-tool blocks).
            "citations_delta" -> {
                if (!quirks.dropServerToolBlocks) sink.rawDelta(wire, delta)
            }
            else -> Unit
        }
    }

    /** DR-122 (empty-delta-latch family; DR-75/CX-09 fixed text/thinking): an EMPTY signature
     *  latches nothing — a thinking block closed with only an empty signature_delta must still
     *  take synthesize-at-close on the synthesizing profile, or Claude Code silently discards
     *  the unsigned block. The delta itself still forwards verbatim (neutral bytes unchanged). */
    private suspend fun onSignatureDelta(block: Block, wire: WireBlockIndex, delta: JsonObject, sink: WireSink) {
        val signature = JsonScalars.strOrEmpty(delta["signature"])
        sink.signatureDelta(wire, signature)
        if (signature.isNotEmpty()) block.signatureSeen = true
    }

    internal suspend fun onBlockStop(evt: JsonObject, sink: WireSink) {
        // PT-006: remove on close — a delta arriving after this index's content_block_stop must
        // find no entry (and drop honestly via onBlockDelta's unmapped-index path), not apply
        // itself to a logically closed block.
        val block = blocks.remove(JsonScalars.int(evt, "index") ?: return) ?: return
        val wire = block.wire ?: return // ignored block: nothing was opened
        val unsignedThinking = block.kind == Kind.THINKING && !block.signatureSeen
        if (quirks.synthesizeSignatures && unsignedThinking) {
            // Synthesize EXACTLY ONE signature so Claude Code keeps the thinking block. Quirk-gated:
            // an upstream that SIGNS and VERIFIES must never receive this back — a block truncated
            // before its signature would otherwise persist a forged one into the transcript.
            sink.signatureDelta(wire, SYNTHETIC_SIGNATURE)
            block.signatureSeen = true
        }
        sink.closeBlock(wire)
    }
}
