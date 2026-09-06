// NEW: the thinking -> wire mapping — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). The two members that take the typed request and decide what reaches
// `thinking`/`output_config` on the wire. Every relocated member kept its identical name and
// argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.LogSink
import splice.core.wire.AnthropicRequest
import java.util.concurrent.atomic.AtomicBoolean

internal class PassthroughThinking(
    private val quirks: PassthroughQuirks,
    /** v27 doctrine (Knob.EFFORT doc): compaction MUST run on the session's own effort or the
     *  warm prompt-cache prefix invalidates ("compaction ate my subscription"). This builder is
     *  stateless per-request — it has no true session memory — so the daemon's configured default
     *  effort (restart-required, stable for the head's whole lifetime) is the closest available
     *  proxy for "the session's own effort" when a turn resends no `thinking` config AT ALL — the
     *  only shape [effortLadder] lets it answer for. KIMI BYTE-IDENTITY (review, PT-002): a turn
     *  that sends `thinking` WITHOUT `budget_tokens` DOES reach kimi's wire via
     *  `output_config.effort`, so that shape keeps the pre-existing unconditional EFFORT_MAX no
     *  matter what this is set to — see [effortLadder]. */
    private val configEffort: String?,
    /** Daemon log sink (Main.persistentLogger) — same injected-with-a-process-default idiom as
     *  PassthroughTurnContext.log. Its only use here is the SCH-006 one-shot unrecognized-effort
     *  notice in [effortLadder]. */
    private val log: LogSink,
    private val cache: PassthroughCacheControl,
) {

    // SCH-006: latched the first time a configured effort that is not one of kimi's own rungs
    // falls back — visible ONCE per builder lifetime (the builder rides the whole daemon
    // process), not once per turn.
    private val configEffortFallbackWarned = AtomicBoolean(false)

    // The budget->rung mapping rides a collaborator, not this class.
    private val effortRules = PassthroughEffortLadder()

    // KIMI BYTE-IDENTITY: [sink] is the former `JsonObjectBuilder` receiver, moved to the first
    // parameter by HD-20. Every emission below keeps its position and order, and the sole call site
    // still runs LAST inside build()'s buildJsonObject, so `thinking` / `output_config` land in the
    // same place in the serialized object as before.
    fun putThinking(
        sink: JsonObjectBuilder,
        typed: AnthropicRequest,
        rawThinking: JsonElement?,
        effort: String,
    ) {
        val thinking = typed.thinking ?: return // absent -> omit both keys
        if (!quirks.mapThinkingToAdaptive) {
            // Neutral surface: forward the raw thinking config verbatim (cache_control scrubbed) —
            // INCLUDING an explicit type:"disabled" (DR-120). Dropping the disable used to run
            // first, so a vendor whose models default thinking ON silently ran thinking anyway
            // (behavior AND cost change). Only the adaptive rewrite below owns omit-on-disabled.
            rawThinking?.let { sink.put(THINKING, cache.stripCacheControl(it)) }
            return
        }
        if (thinking.disabled) return // disabled -> OMIT thinking (never send type:"disabled" to kimi)
        sink.put(
            THINKING,
            buildJsonObject {
                put("type", "adaptive")
                put("display", "summarized")
            },
        )
        sink.put(OUTPUT_CONFIG, buildJsonObject { put("effort", effort) })
    }

    /** Kimi effort ladder — vocab is low|high|max (NO medium). Compact turns take the SAME
     *  derivation as session turns (inherit; v27) unless a pin is explicitly configured.
     *
     *  PT-002 (scoped after review): a turn with NO `thinking` config AT ALL — the common shape of
     *  a Claude Code compaction call, even on a session that runs its regular turns with real
     *  thinking — falls back to the configured default effort, ONLY when it is already one of
     *  Kimi's own three literal rungs (never a fuzzy floor/ceiling mapping of a foreign
     *  vocabulary). That fallback can only ever inform [TurnMeta.effort]: [putThinking] omits BOTH
     *  `thinking` and `output_config` when `thinking` is absent, so it never reaches kimi's wire.
     *
     *  A turn that DOES send `thinking` but omits `budget_tokens` is a DIFFERENT shape — it reaches
     *  the wire via `output_config.effort`, so it keeps the pre-existing unconditional EFFORT_MAX
     *  no matter what [configEffort] is set to (KIMI BYTE-IDENTITY: kimi's built request bytes are
     *  frozen for every request shape).
     *
     *  SCH-006: an unrecognized-but-set [configEffort] (e.g. "medium" — a valid rung for another
     *  provider sharing the same EFFORT knob, see CODEX_REASONING_EFFORT) used to fall all the way
     *  through to EFFORT_MAX — a silent cost ESCALATION for a realistic multi-provider config. See
     *  [PassthroughEffortLadder.fallbackEffort]: it now falls to the cheapest rung instead (never
     *  pricier than whatever the operator asked for) and logs the substitution once per builder
     *  lifetime, not once per turn. */
    fun effortLadder(typed: AnthropicRequest): String {
        // PT-002: the ONLY branch [configEffort] can reach — see this function's KDoc for why.
        val thinking = typed.thinking
            ?: return effortRules.fallbackEffort(configEffort, quirks.providerTag, configEffortFallbackWarned, log)
        return effortRules.budgetEffort(thinking.budgetTokens)
    }
}
