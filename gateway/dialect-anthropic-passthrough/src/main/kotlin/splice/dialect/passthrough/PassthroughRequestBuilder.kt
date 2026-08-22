// NEW: (no Node source) Anthropic Messages -> an Anthropic-surface upstream. The body is preserved
// (unknown fields ride through verbatim) and only these happen unconditionally: `model` is retargeted, `stream` is
// forced, compact turns drop tools + tool_choice, and (CX-02, 2026-08-10) the compaction directive
// is appended to `system` on a compact turn — without which a compaction turn is an ordinary
// tool-stripped turn and a chatty reply is stored silently as the session summary.
//
// EVERY OTHER TRANSFORM IS A DECLARED QUIRK, OFF BY DEFAULT (see PassthroughQuirks): cache_control
// stripping, the content-block allowlist, MFJS schema rewriting, and the adaptive-thinking +
// output_config.effort ladder. They were hardcoded while Kimi was the only consumer; a faithful
// upstream (api.anthropic.com) needs its prompt-cache markers, full JSON Schema, and its own
// thinking config to survive the trip. `PassthroughQuirksDefaults.kimi(tag)` is the one definition
// of Kimi's set, and its bytes are frozen by PassthroughGoldenTest.
//
// Invariants that hold for every head: thinking blocks pass VERBATIM (signature included), and the
// effort ladder never emits "medium" (Kimi vocab is low|high|max).
//
// SPLIT (2026-08-17, concentration campaign): the JSON-shaping and turn-metadata responsibilities
// that used to live here now ride dedicated collaborators in this package (PassthroughQuirks,
// PassthroughWireKeys, PassthroughCacheControl, PassthroughFieldCopier, PassthroughMessageScrubber,
// PassthroughToolSanitizer, PassthroughCompactSystem, PassthroughThinking, PassthroughEffortLadder,
// BuiltPassthroughRequest, PassthroughTurnMeta). This file keeps only the public entry point and
// the fixed emission order of build().
package splice.dialect.passthrough

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.AnthropicTurnBody
import splice.core.util.DaemonLog
import splice.core.util.LogSink

public class PassthroughRequestBuilder(
    private val quirks: PassthroughQuirks,
    private val configEffort: String? = null,
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    private val cache = PassthroughCacheControl(quirks.stripCacheControl)
    private val fields = PassthroughFieldCopier(quirks, cache)
    private val system = PassthroughCompactSystem(cache)
    private val messages = PassthroughMessageScrubber(quirks, cache)
    private val tools = PassthroughToolSanitizer(quirks, cache)
    private val thinking = PassthroughThinking(quirks, configEffort, log, cache)
    private val turnMeta = PassthroughTurnMeta()

    public fun build(
        body: AnthropicTurnBody,
        upstreamModel: String,
        originalModel: String,
        compact: Boolean,
    ): BuiltPassthroughRequest {
        val raw = body.raw
        val typed = body.typed
        val effort = thinking.effortLadder(typed, compact)

        val req = buildJsonObject {
            fields.copyUnhandledFields(this, raw)
            put(MODEL, upstreamModel)
            put(STREAM, true)
            system.compactAwareSystem(raw[SYSTEM], compact)?.let { put(SYSTEM, it) }
            raw[MESSAGES]?.let { put(MESSAGES, messages.scrubMessages(it)) }
            if (!compact) {
                raw[TOOLS]?.let { put(TOOLS, tools.sanitizeTools(it)) }
                raw[TOOL_CHOICE]?.let { put(TOOL_CHOICE, cache.stripCacheControl(it)) }
            }
            thinking.putThinking(this, typed, raw[THINKING], effort)
        }

        val meta = turnMeta.turnMeta(typed, compact, originalModel, upstreamModel, effort)
        return BuiltPassthroughRequest(req, meta)
    }
}
