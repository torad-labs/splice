// NEW: typealias to splice.spi.RefreshedTokens plus the Codex quirk profile. The data class moved
// to provider-spi in V4-18 so TokenUrlRefreshCall is not typed on one vendor; CodexQuirks stays
// here so in-module call sites do not move.
package splice.provider.codex

import splice.dialect.responses.ResponsesQuirks

/** Same shape as splice.spi.RefreshedTokens; kept so in-module call sites do not move this row. */
public typealias RefreshedTokens = splice.spi.RefreshedTokens

/** Holder for the codex quirk profile. Split from CodexProvider.kt (concentration, 2026-08-19)
 *  so the provider is not billed for a second column-0 type. Same-package. */
public class CodexQuirks {
    /** The codex quirk profile — injectable so the TOML [providers.*.quirks] table is REAL. */
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "claudex",
        // codex-rs's non-optional instructions String serializes as "" on responses-lite turns.
        emitEmptyLiteInstructions = true,
        // richer titled reasoning sections from the ChatGPT backend (probed 2026-07-19)
        summaryDelivery = "sequential_cutoff",
        // codex-rs parity: hard-sets strict:false on every function tool (responses_api.rs:29-32);
        // OpenCode does the same, marked "Codex parity". Omitting it lets the backend attempt
        // strict auto-normalisation of ~87 MCP schemas and silently report whatever it settled on.
        // forceStrictFalse, NOT emitStrict (review 2026-07-24): emitStrict is grok's pre-existing,
        // never-consequential pass-through flag — reusing it here silently changed grok's bytes too.
        forceStrictFalse = true,
        // codex parity (tools byte-parity 2026-08-26): the codex CLI never sends a client schema
        // verbatim — every tool's input_schema is sanitized/pruned/compacted/keyword-subset before
        // riding the wire (json_schema.rs parse_tool_input_schema), so gpt-5.6 only ever trains
        // its expectations against normalized shapes. Splice mirrors that on this head only.
        normalizeToolSchemas = true,
        // ChatGPT-internal lite marker: only this provider declares the pair. A dialect default
        // would send x-openai-internal-codex-responses-lite to every openai-responses endpoint.
        responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
        responsesLiteHeader = "x-openai-internal-codex-responses-lite",
    )
}
