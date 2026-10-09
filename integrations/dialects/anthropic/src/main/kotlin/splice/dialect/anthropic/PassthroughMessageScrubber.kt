// NEW: the message content-block scrubbing family — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). Six functions that only call each other, the allowlist and
// the stripper — the largest self-contained cluster in the file, and the only one that reads
// quirks.blockAllowlist. Every relocated member kept its identical name and argument list.
//
// V4-39 (2026-09-16): the allowlist can empty a message that ARRIVED with blocks, and the emptied
// message used to ride upstream as `content: []` — a shape no backend here can act on. The live
// trigger is not exotic: it is what Claude Code sends the first time the operator pastes a
// screenshot with no accompanying text, because the allowlist drops `image`. Reachable on the FIRST
// screenshot, and shared by every anthropic-passthrough head, so deepseek, muse and kimi all
// inherited it wherever their own allowlist drops a type a message consists entirely of.
//
// THE CHOICE, deliberately, because the row asks for it to be made rather than discovered:
// SUBSTITUTE one honest text block, do not drop the message. A dropped message breaks turn
// alignment on the replayed conversation Claude Code resends every turn — an assistant turn with no
// preceding user turn, or a tool_result orphaned from the tool_use it answers, is a worse wire than
// a sentence — and silence tells the model nothing, where a sentence saying the content was removed
// and why is both true and actionable. An array that was ALREADY empty is left exactly as it is:
// that shape is the client's, not something this scrubber made, and rewriting it would move bytes
// for no defect of ours (never-below-status-quo).
//
// This COMPOSES with dropDisallowed below rather than replacing it: the same drop is reported once
// per type on the anomaly channel AND leaves the message with a substitute block, so a head that
// silently ate a screenshot now both says so in the log and tells the model something true.
//
// V4-157 (2026-09-18): [isEmptyThinking] used to require the SIGNATURE to be empty as well, so a
// blank-but-signed thinking block was judged content-bearing and rode upstream. Anthropic rejects
// the WHOLE request for it — 400 invalid_request_error, `messages.903.content.0.thinking: each
// thinking block must contain thinking`, request_id req_011CfBPZe8HG2qTVWNVXBmZm — and because
// Claude Code replays the transcript every turn, index 903 came back on every retry: no layer above
// this one could recover the turn. Measured that day on a live operator session, which lost the turn,
// had to switch model mid-session, and could not compact afterwards, since a compaction replays the
// same messages. A signature records WHO wrote a block, never that it contains anything, so the rule
// is now about the thinking text alone.
//
// V4-455 (2026-10-01): a head whose upstream verifies thinking signatures drops the thinking it did
// not mint. A GPT session resumed on claude-splice replayed 115 reasoning-summary blocks with
// `signature: ""` (the GPT head signs none, and Claude Code stores the empty string); Anthropic
// refused every turn with 400 `messages.1.content.0: Invalid signature in thinking block`, and the
// same history came back on the fallback model. Dropped rather than turned into text: they are status
// headers and wait notices, and as text the model would read them as prose it wrote.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.SpliceNotice
import splice.core.turn.SpliceSignatures
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.upstream.ToolNameShortener
import java.util.concurrent.ConcurrentHashMap

private const val SIGNATURE_FIELD = "signature"
private const val TYPE_REDACTED_THINKING = "redacted_thinking"
private const val DATA = "data"

internal class PassthroughMessageScrubber(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
    private val names: ToolNameShortener = ToolNameShortener(),
    /** The anomaly channel for [PassthroughQuirks.blockAllowlist]. An allowlist drop is CONTENT
     *  LOSS with no other symptom: the upstream answers normally about the text it did receive, so
     *  a pasted screenshot or an attached document simply has no effect and nothing anywhere says
     *  why. The response path has carried two such channels since PT-001 and DR-142; the request
     *  path had none, which is how six of DeepSeek's nine accepted block types stayed dropped for a
     *  whole campaign (wall kt-no-println). */
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    /** One line per dropped TYPE for the life of the head, never per block: tools and attachments
     *  re-ride every turn, so an unlatched line would repeat per block per turn. Same idiom as
     *  ToolNameShortener.fullLogged and the translator's two latches. */
    private val droppedLogged = ConcurrentHashMap.newKeySet<String>()

    fun scrubMessages(messages: JsonElement): JsonArray {
        val arr = messages as? JsonArray ?: return buildJsonArray { }
        return buildJsonArray {
            arr.forEach { msg -> (msg as? JsonObject)?.let { add(scrubMessage(it)) } }
        }
    }

    private fun scrubMessage(msg: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in msg) {
            if (key == CONTENT) put(CONTENT, scrubContent(value)) else put(key, cache.stripCacheControl(value))
        }
    }

    /** A content value is a bare string (verbatim) or a block list (allowlist-filtered). */
    private fun scrubContent(content: JsonElement): JsonElement = when (content) {
        is JsonArray -> scrubBlocks(content)
        is JsonObject, is JsonPrimitive -> content
    }

    /** Filter the blocks, and never hand back an EMPTY array for a message that had blocks: that is
     *  the V4-39 defect, and it is answered with one honest block rather than with nothing. The
     *  already-empty array is not ours to rewrite, so it passes through untouched. */
    private fun scrubBlocks(content: JsonArray): JsonArray {
        val kept = buildJsonArray {
            content.forEach { el -> (el as? JsonObject)?.let { scrubBlock(it) }?.let { add(it) } }
        }
        if (kept.isNotEmpty() || content.isEmpty()) return kept
        return buildJsonArray { add(omittedBlock(content.size)) }
    }

    /** What this message became: one block naming the count, the proxy that removed them, and why.
     *  The reasons a block does not survive are the allowlist and an empty thinking block; the
     *  sentence covers both without pretending to know which, because a wrong specific is worse
     *  than an honest general. */
    private fun omittedBlock(removed: Int): JsonObject = buildJsonObject {
        put(TYPE, TYPE_TEXT)
        put(
            TEXT,
            "$removed content block(s) omitted by ${quirks.providerTag} proxy: this endpoint does not " +
                "accept every content type, and this message carried nothing else.",
        )
    }

    /** Keep an accepted block (cache_control stripped, tool_result inner content filtered) or drop. */
    private fun scrubBlock(block: JsonObject): JsonObject? {
        val type = JsonScalars.strOrEmpty(block[TYPE])
        quirks.blockAllowlist?.let { if (type !in it) return dropDisallowed(type) }
        if (isDropped(type, block)) return null
        return rebuildBlock(block, type)
    }

    /** splice's own notice, thinking with no text (V4-157), or thinking a verifying upstream refuses (V4-455). */
    private fun isDropped(type: String, block: JsonObject): Boolean =
        isSpliceNotice(type, block) || isEmptyThinking(type, block) || isUnverifiable(type, block)

    /** V4-455: why a signature-verifying upstream would refuse this block, null when it would not. A
     *  string check cannot tell another vendor's non-empty signature from Anthropic's, so those ride. */
    private fun unverifiable(type: String, block: JsonObject): String? = when (type) {
        TYPE_THINKING -> {
            val signature = JsonScalars.strIfString(block[SIGNATURE_FIELD]).orEmpty()
            when {
                signature.isBlank() -> "thinking with no signature"
                signature in SpliceSignatures.MINTED -> "thinking signed by splice"
                else -> null
            }
        }
        // The decoder's own log line is for replay; here a non-envelope is Anthropic's data and rides.
        TYPE_REDACTED_THINKING -> "redacted_thinking holding a splice reasoning envelope".takeIf {
            ReasoningReplay.decodeReasoningEnvelope(JsonScalars.strIfString(block[DATA])) {} != null
        }
        else -> null
    }

    /** True when the block is dropped for [unverifiable], reporting each reason once for the life of the
     *  head (the history replays every turn). */
    private fun isUnverifiable(type: String, block: JsonObject): Boolean {
        if (!quirks.verifiesThinkingSignatures) return false
        val reason = unverifiable(type, block) ?: return false
        if (droppedLogged.add(reason)) {
            log(
                "[${quirks.providerTag}] $reason dropped from the request: this upstream verifies " +
                    "thinking signatures and refuses the whole request for one it did not mint\n",
            )
        }
        return true
    }

    /** Drops the block, reporting its type once. An allowlist is only ever as good as the evidence
     *  it was derived from, and the honest way to hold that is to say out loud what it is costing. */
    private fun dropDisallowed(type: String): JsonObject? {
        if (droppedLogged.add(type)) {
            log(
                "[${quirks.providerTag}] content block '$type' is absent from this head's " +
                    "block_allowlist; dropped from the request, so the upstream never sees it\n",
            )
        }
        return null
    }

    /** Exact marker only: model thinking with notice-shaped text or another signature stays intact. */
    private fun isSpliceNotice(type: String, block: JsonObject): Boolean =
        type == TYPE_THINKING && JsonScalars.strIfString(block[SIGNATURE_FIELD]) == SpliceNotice.SIGNATURE

    /** A whitespace-only thinking block holds nothing worth keeping, SIGNED OR NOT (V4-157 in the
     *  header). The shape has two possible authors and the rule must not care which: an upstream
     *  that signed a block it then sent no thinking for, and splice itself, since a synthesizing
     *  profile stamps `splice-synth-v1` at close on any thinking block that never saw a signature
     *  delta — an empty one included (PassthroughBlockRegistry.retire). */
    private fun isEmptyThinking(type: String, block: JsonObject): Boolean {
        if (type != TYPE_THINKING) return false
        return JsonScalars.strOrEmpty(block["thinking"]).isBlank()
    }

    private fun rebuildBlock(block: JsonObject, type: String): JsonObject = buildJsonObject {
        for ((key, value) in block) {
            when {
                key == CACHE_CONTROL && quirks.stripCacheControl -> Unit
                key == CONTENT && type == TYPE_TOOL_RESULT -> put(CONTENT, scrubContent(value))
                // V4-32: a replayed tool_use must shorten to the SAME string its declaration
                // did, or the upstream sees a call naming a tool it was never offered.
                key == NAME && type == TYPE_TOOL_USE -> put(NAME, names.shorten(JsonScalars.strOrEmpty(value)))
                else -> put(key, cache.stripCacheControl(value))
            }
        }
    }
}
