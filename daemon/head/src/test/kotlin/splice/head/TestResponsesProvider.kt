// NEW: test-local concrete ResponsesProvider so :daemon-head tests do not construct CodexProvider
// (V4-24). Same constructor shape the tests already pass; no account-id header, no routing
// headers, no websocket, no code-mode bridge.
//
// TestResponsesQuirks is deliberately independent of CodexQuirks: :daemon-head must not depend on
// provider-codex. This profile starts at the bare ResponsesQuirks constructor and carries only
// fields :daemon-head:test load-bears on. It does not stand in for Codex's live profile.
package splice.head

import splice.core.turn.ReasoningDisplay
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderTuning

internal class TestResponsesQuirks {
    fun profile(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "test-responses",
        // A neutral third-party responses provider is not lite: lite is a ChatGPT-internal
        // input shape, pinned in :provider-codex. Explicit null so this double does not inherit
        // a dialect default and emit x-openai-internal-codex-responses-lite.
        responsesLiteModelRegex = null,
        emitEmptyLiteInstructions = true,
        summaryDelivery = "sequential_cutoff",
    )
}

internal class TestResponsesProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String?,
    quirks: ResponsesQuirks = TestResponsesQuirks().profile(),
    foldConfig: FoldConfig? = null,
    log: LogSink = LogSink(DaemonLog::write),
) : ResponsesProvider(
    tuning,
    showReasoning,
    replayReasoning,
    configEffort,
    configSummary,
    quirks,
    foldConfig,
    log,
)
