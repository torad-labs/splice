// NEW: Muse owns its responses quirk profile, moved out of the app's Muse arm so its vendor facts sit in its
// provider module (the separation law), beside the one it adds: Muse ends rounds after reasoning alone.
package splice.provider.muse

import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.tools.ToolDeferralPolicy
import splice.dialect.responses.tools.ToolSearchMode

/** Holder for the Muse quirk profile. Constructed by the daemon so TOML overlays a real table. */
public class MuseQuirks {
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "muse",
        lite = ResponsesLiteQuirks(),
        backend = ResponsesBackendQuirks(
            store = false,
            promptCache = PromptCachePolicy(CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH, "24h"),
        ),
        reasoning = ResponsesReasoningQuirks(
            supportsSummary = true,
        ),
        tools = ResponsesToolQuirks(
            emitToolChoice = true,
            toolSurface = ToolDeferralPolicy(mode = ToolSearchMode.HOSTED),
        ),
        // Muse completes rounds that hold one encrypted reasoning item and nothing else
        // (status=completed items=[reasoning(summary=0,enc=~1400)]): 418 of 3,538 turns on 2026-10-13, in runs of
        // up to 25 in one session. The same bytes give the same empty, so the identical re-send healed 39 of 451;
        // carrying on from that reasoning with a request for the answer changes the input. Muse accepts the shape:
        // its mid-stream re-anchor already sends reasoning plus a commentary marker and was answered.
        roundTrip = ResponsesRoundTripQuirks(
            continueReasoningOnly = true,
        ),
    )
}
