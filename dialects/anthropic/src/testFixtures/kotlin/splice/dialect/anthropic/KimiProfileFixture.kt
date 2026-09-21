// NEW: dialect-local copy of Kimi's passthrough profile so goldens stay byte-identical without
// importing provider-kimi. :app KimiQuirksFixtureTest pins this equal to KimiQuirks.
package splice.dialect.anthropic

public class KimiProfileFixture {
    public fun kimi(providerTag: String): PassthroughQuirks = PassthroughQuirks(
        providerTag = providerTag,
        mapThinkingToAdaptive = true,
        mfjsSanitize = true,
        blockAllowlist = setOf(
            "text",
            "image",
            "thinking",
            "tool_use",
            "tool_result",
            "server_tool_use",
            "web_search_tool_result",
        ),
        stripCacheControl = true,
        synthesizeSignatures = true,
        dropServerToolBlocks = true,
        effortRungs = listOf("low", "high", "max"),
        // V4-41: measured — kimi continues from a trailing assistant prefill in every thinking mode.
        reanchorPrefill = true,
    )
}
