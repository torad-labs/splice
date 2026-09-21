// NEW: Kimi owns its passthrough deformation set, content-block allowlist, and effort rungs.
// The dialect stays vendor-neutral; assembly selects this profile for the kimi provider id.
package splice.provider.kimi

import splice.dialect.anthropic.PassthroughQuirks

/** Kimi's Anthropic-passthrough deformation set — one definition so wiring and goldens cannot drift. */
public class KimiQuirks {
    public fun kimi(providerTag: String): PassthroughQuirks = PassthroughQuirks(
        providerTag = providerTag,
        mapThinkingToAdaptive = true,
        mfjsSanitize = true,
        blockAllowlist = BLOCK_TYPES,
        stripCacheControl = true,
        synthesizeSignatures = true,
        dropServerToolBlocks = true,
        effortRungs = EFFORT_RUNGS,
        // V4-41: MEASURED 2026-09-16 against api.kimi.com/coding — a trailing assistant prefill is
        // continued (the answer resumed at 4 from a "1\n2\n3" prefill) in all three thinking modes:
        // enabled, disabled, and absent. So a truncated kimi turn can be resumed rather than lost.
        // This rides on the head's BASE profile rather than on operator TOML because it is a fact
        // about kimi's endpoint, not a preference — the same reason the six knobs above live here.
        reanchorPrefill = true,
    )
}

private val BLOCK_TYPES: Set<String> = setOf(
    "text",
    "image",
    "thinking",
    "tool_use",
    "tool_result",
    "server_tool_use",
    "web_search_tool_result",
)

private val EFFORT_RUNGS: List<String> = listOf("low", "high", "max")
