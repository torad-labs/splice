// NEW: Kimi owns its passthrough deformation set, content-block allowlist, and effort rungs.
// The dialect stays vendor-neutral; assembly selects this profile for the kimi provider id.
package splice.provider.kimi

import splice.dialect.passthrough.PassthroughQuirks

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
