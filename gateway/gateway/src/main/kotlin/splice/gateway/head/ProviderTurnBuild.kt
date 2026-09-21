// NEW: V4-165 (2026-09-19) — the provider's turn, shaped for the wire, split out of
// TurnPreparation (concentration: V4-165's lease guard pushed that file into the HIGH band). The
// compaction tail, the compaction request hash and the system/slot prompt layers moved verbatim;
// what is new is the guard: a provider's turn can now hold something (BuiltTurn.onEnd, a llama-server
// slot lease), and a build that throws between the provider and the drive must give it back.
package splice.gateway.head

import kotlinx.serialization.json.JsonObject
import splice.core.compaction.EffectiveCompactionInstructions
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.TurnPerf
import splice.upstream.BuiltTurn
import splice.upstream.Provider

internal class ProviderTurnBuild(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val replay: CompactionReplay,
) {
    private val prompts = TurnPrompts(provider, deps)

    fun build(parsed: AnthropicTurnBody, compact: Boolean, sessionId: String?, perf: TurnPerf): BuiltTurn {
        val effective = deps.compactionTail.resolve(
            compact,
            provider.catalog.stripSuffixes(parsed.typed.model),
            sessionId,
        )
        val base = provider.buildTurn(parsed, compact, sessionId)
        return endingOnFailure(base) { shaped(base, effective, compact, sessionId, perf) }
    }

    /** What [turn] holds (BuiltTurn.onEnd) ends here unless [block] hands the turn on — a build
     *  that throws between the provider and the drive must not keep a slot leased forever. */
    inline fun <T> endingOnFailure(turn: BuiltTurn, block: () -> T): T {
        var handedOn = false
        try {
            return block().also { handedOn = true }
        } finally {
            if (!handedOn) turn.onEnd?.ended()
        }
    }

    private fun shaped(
        base: BuiltTurn,
        effective: EffectiveCompactionInstructions?,
        compact: Boolean,
        sessionId: String?,
        perf: TurnPerf,
    ): BuiltTurn {
        val tailed = effective?.tailText?.let { provider.withCompactionTail(base, it) } ?: base
        // A dialect that cannot place the tail (no user text to extend) returns the request as it
        // was: the meta then says so instead of claiming instructions the wire never carried.
        val applied = effective?.tailText == null || tailed.requestBody != base.requestBody
        // V4-166: without the routing fields (id_slot), so a retry routed to another slot still matches.
        val hash = if (compact) replay.bodyHash(JsonObject(base.requestBody - base.routingFields).toString()) else null
        val withTail = effective?.let { eff ->
            tailed.copy(
                meta = tailed.meta.copy(
                    compactionInstructions = if (applied) eff.text else null,
                    compactionInstructionsSource = if (applied) eff.source else "${eff.source} (not applied)",
                    compactionRequestHash = hash,
                ),
            )
        } ?: tailed.copy(meta = tailed.meta.copy(compactionRequestHash = hash))
        // AFTER the tail, so the compaction request hash and its applied check keep reading the
        // provider body BEFORE any tail — a retry must still match its recording byte for byte.
        return prompts.applySlotPrompt(prompts.applySystemPrompt(withTail, sessionId, perf), sessionId, perf)
    }
}
