// NEW: client-login model ownership is separate from foreign-head compaction window materialization.
package splice.launch.recipe

import splice.launch.LaunchSpec

internal object LaunchModelEnvironment {
    // A small pinned window must not plant a nonsense auto-compact cap. Claude Code takes
    // min() with its client window, so this floor is harmless when that window is smaller.
    private const val AUTO_COMPACT_FLOOR = 60_000L

    fun inherited(kept: Map<String, String>): List<String> = CLIENT_PICKED_ENV.filterNot(kept::containsKey)

    fun models(spec: LaunchSpec, slots: List<Pair<String, String>>): Map<String, String> =
        buildMap {
            // V4-449: an explicit pin selects the initial model only. Without one, the client chooses.
            if (spec.models.pinnedModel.isNotBlank()) put("ANTHROPIC_MODEL", spec.models.pinnedModel)
            // The picker lists one row per PLANTED TIER (Claude Code 2.1.257: fen()/hen()/uen()
            // emit a row whenever ANTHROPIC_DEFAULT_<tier>_MODEL is set, value = the alias, label =
            // _NAME) and dedupes rows by value only, so two tiers on one model drew that model
            // twice: Sol as opus and as fable on claudex, K2.7 Code as sonnet and as haiku on
            // claude-kimi (the 2026-09-04 release recording). The tier cannot be left unset: the
            // alias then resolves to Claude Code's built-in model, which this head rejects
            // ("proxies its own models only"), and every fable- or haiku-tiered subagent dies.
            // The one lever is the allowlist: a tier row whose model is not on availableModels is
            // hidden, while the head accepts its own DISCOVERY-WRAPPED spelling (catalog.contains
            // unwraps). So a repeated tier is planted as "<prefix><id>": routed like the first, drawn
            // never. Cache rows are untouched on purpose: Claude Code already drops a cache row that
            // repeats a tier's model, and the cache row is where the Default line's label comes from
            // (stripping them printed "currently gpt-5.6-sol[1m]"; verified live 2026-09-04). Known
            // cost: a subagent on the wrapped tier runs under a wrapped ACTIVE id, for which Claude
            // Code does not honor CLAUDE_CODE_MAX_CONTEXT_TOKENS (header note).
            // V4-232: a repeated tier of a PRESENTED row is planted as the Claude model the row is
            // presented as instead. The client resolves it through settings.json modelOverrides and
            // sends the row's own id (verified on 2.1.283), so it is routed like the first and, off
            // availableModels, drawn never, like the wrapped spelling, which the client does not know
            // and named in a [claude-code:unrecognized_model] line for every haiku-tier title and subagent.
            val presentedAs = spec.models.tiers.modelOverrides.entries.associate { (claude, row) -> row to claude }
            val planted = mutableSetOf<String>()
            slots.forEach { (slot, model) ->
                val spelling = when {
                    planted.add(model) -> spec.models.tiers.clientId(model)
                    model in presentedAs -> presentedAs.getValue(model)
                    spec.models.discoveryPrefix.isNotBlank() -> spec.models.discoveryPrefix + model
                    else -> model
                }
                put("ANTHROPIC_DEFAULT_${slot}_MODEL", spelling)
                val label = spec.models.modelLabels[model] ?: model
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_NAME", label)
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_DESCRIPTION", label)
            }
        }

    fun windows(spec: LaunchSpec): Map<String, String> {
        if (spec.gateway.forwardClientAuth) return emptyMap()
        // The pinned row's window remains the compaction target on foreign-provider heads.
        // Presented and [1m]-spelled rows must retain their client's larger context ceiling.
        val compactWindow = maxOf(
            AUTO_COMPACT_FLOOR,
            spec.models.contextWindow,
            spec.models.tiers.presentedWindow,
            spec.models.tiers.spelledWindow,
        )
        return mapOf(
            "CLAUDE_CODE_MAX_CONTEXT_TOKENS" to spec.models.contextWindow.toString(),
            "CLAUDE_CODE_AUTO_COMPACT_WINDOW" to compactWindow.toString(),
        )
    }
}

// A client-login head removes inherited selectors unless it explicitly supplies a launch default.
private val CLIENT_PICKED_ENV =
    listOf("ANTHROPIC_MODEL", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW")
