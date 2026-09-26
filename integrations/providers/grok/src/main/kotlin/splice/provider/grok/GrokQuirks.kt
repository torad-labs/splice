// NEW: Grok owns its responses quirk profile and the chat xhigh model regex.
// DR-155: both the chat and responses profiles are live depending on the TOML dialect; this
// module owns the 8px floor so :app does not re-declare a vendor fact, and :dialects-openai-chat
// never depends on :providers-grok.
package splice.provider.grok

import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.ResponsesQuirks

/** Holder for the grok quirk profile. Constructed by the daemon so TOML overlays a real table. */
public class GrokQuirks {
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "claude-grok",
        store = false,
        cacheKeyStrategy = CacheKeyStrategy.SESSION_ID,
        effortVocabulary = GrokEffortVocabulary(),
        supportsSummary = true,
        summaryRejectModelRegex = null,
        emitToolChoice = true,
        emitStrict = true,
        reasoningCache = false,
        // DR-155: xAI's documented and ENFORCED minimum image edge. Its verbatim HTTP 400 body is
        // Image dimensions 1x1 are too small. Both width and height must be at least 8 pixels.
        minImageEdgePx = XAI_MIN_IMAGE_EDGE_PX,
    )

    public fun xhighModels(): Regex = XHIGH_MODELS
}

private const val XAI_MIN_IMAGE_EDGE_PX = 8

// xhigh is native on grok-4.6 and later (4.6..4.9, 4.10+). grok-5+ lands here when it exists —
// a one-line extension, not a silent cap on a shipped model.
// FILE SCOPE ON PURPOSE: one compiled Regex; as a member it would recompile per instance.
private val XHIGH_MODELS = Regex("grok-4\\.(?:[6-9]|[1-9]\\d)")
