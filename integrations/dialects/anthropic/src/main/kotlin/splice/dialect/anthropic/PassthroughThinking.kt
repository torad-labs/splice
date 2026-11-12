// NEW: the thinking -> wire mapping — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). The two members that take the typed request and decide what reaches
// `thinking`/`output_config` on the wire. Every relocated member kept its identical name and
// argument list.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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
     *  shape with no `thinking` config. A turn that sends `thinking` and names no effort or budget
     *  sends this on the vendor's rungs, else high (Oct 28) — see [effortLadder]. */
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
        if (!quirks.thinking.mapThinkingToAdaptive) {
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

    /** Effort for TurnMeta when thinking is absent, and the wire rung when thinking is present.
     *
     *  Compact turns take the SAME derivation as session turns (inherit; v27) unless a pin is
     *  explicitly configured.
     *
     *  PT-002: a turn with NO thinking config AT ALL — the common shape of a Claude Code compaction
     *  call — falls back through [PassthroughEffortLadder.fallbackEffort]. That value can only ever
     *  inform TurnMeta.effort: [putThinking] omits BOTH thinking and output_config when thinking is
     *  absent, so it never reaches the wire. When quirks.thinking.effortRungs is null there is no vendor
     *  ladder: the trimmed config token rides (or MAX if absent). When rungs are set, an unrecognized
     *  configEffort falls to the cheapest rung (SCH-006), never silently to MAX.
     *
     *  A turn that DOES send thinking reaches the wire via output_config.effort. It sends the effort
     *  Claude Code's request names, else its budget's rung, else the configured effort, else high, each
     *  on the vendor's rungs (Marcos, Oct 28: "why are we running it on max by default and not letteint
     *  it be changed"; this ends KIMI BYTE-IDENTITY's unconditional max for the effort alone). */
    fun effortLadder(typed: AnthropicRequest, raw: JsonObject): String {
        val rungs = quirks.thinking.effortRungs
        val thinking = typed.thinking
            ?: return effortRules.fallbackEffort(
                configEffort,
                quirks.providerTag,
                configEffortFallbackWarned,
                log,
                rungs,
            )
        return effortRules.onRungs(clientEffort(raw), rungs)
            ?: thinking.budgetTokens?.let(effortRules::budgetEffort)
            ?: effortRules.configuredEffort(configEffort, quirks.providerTag, configEffortFallbackWarned, log, rungs)
    }

    /** The effort Claude Code's request names in output_config, a later message's first, as every head reads it. */
    private fun clientEffort(raw: JsonObject): String? {
        val inMessages = (raw[MESSAGES] as? JsonArray)?.asReversed()?.firstNotNullOfOrNull { askedIn(it) }
        return inMessages ?: askedIn(raw)
    }

    private fun askedIn(element: JsonElement?): String? =
        (((element as? JsonObject)?.get(OUTPUT_CONFIG) as? JsonObject)?.get("effort") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
}
