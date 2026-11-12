// NEW: the OpenAI-platform provider — the shared openai-responses base (ResponsesProvider) with
// api-key auth (api.openai.com, OPENAI_API_KEY), no ChatGPT-Account-ID header, first-message-hash
// cache key. Proves the base is reused across THREE auth/quirk profiles (codex/xai/openai) with zero
// duplicated drive logic; this class adds ONLY its quirk profile.
package splice.provider.openai

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesOutputSchemaPolicy
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.StrictSchemaLimits
import splice.dialect.responses.StrictSchemaSubset
import splice.upstream.ProviderTuning

public class OpenAiResponsesProvider(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks = OpenAiQuirks().defaultQuirks(),
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
) : ResponsesProvider(tuning, reasoning, quirks, log = log) {
    /** The public Responses API supports WebSocket mode; the provider quirk keeps it default-off. */
    override val supportsWebSocket: Boolean = true
}

/** Holder for the openai-platform quirk profile. A class rather than a static namespace so the
 *  profile is constructed by whoever needs it (the daemon overlays TOML on top of it), and so the
 *  default is still re-evaluated per OpenAiResponsesProvider construction exactly as the companion's
 *  function was. */
public class OpenAiQuirks {
    /** The openai-platform quirk profile — injectable so TOML [providers.*.quirks] is REAL. */
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "openai",
        lite = ResponsesLiteQuirks(),
        backend = ResponsesBackendQuirks(
            store = false,
            promptCache = PromptCachePolicy(key = CacheKeyStrategy.FIRST_MESSAGE_HASH),
        ),
        reasoning = ResponsesReasoningQuirks(
            supportsSummary = true,
        ),
        tools = ResponsesToolQuirks(
            // This profile serves every OpenAI-compatible Responses endpoint, not only api.openai.com. OpenAI documents
            // the `additional_tools` input item, but OpenRouter's Responses beta refuses it (gakonst/nanocode#981), so
            // the tool list carries each tool change: one prompt-cache miss per change, and no refused request.
            acceptsInlineToolChanges = false,
            outputSchema = ResponsesOutputSchemaPolicy.StrictWithin(strictOutput()),
        ),
    )

    /** The strict subset OpenAI holds a caller's output schema to, as its Structured Outputs guide states it
     *  (https://developers.openai.com/api/docs/guides/structured-outputs, "Supported schemas"): the root an object,
     *  every object closed with every key required, an optional field a union with null, recursion through `#` or
     *  $defs, and these keywords, formats and sizes only. A strict schema outside it is refused with a 400. */
    private fun strictOutput(): StrictSchemaSubset =
        StrictSchemaSubset(
            closedObjects = true,
            everyKeyRequired = true,
            keywords = setOf(
                "type",
                "properties",
                "required",
                "additionalProperties",
                "items",
                "enum",
                "const",
                "anyOf",
                "\$ref",
                "\$defs",
                "definitions",
                "description",
                "title",
                "pattern",
                "format",
                "multipleOf",
                "maximum",
                "exclusiveMaximum",
                "minimum",
                "exclusiveMinimum",
                "minItems",
                "maxItems",
            ),
            formats = setOf("date-time", "time", "date", "duration", "email", "hostname", "ipv4", "ipv6", "uuid"),
            recursion = true,
            objectRoot = true,
            limits = StrictSchemaLimits(
                nestingLevels = 10,
                properties = 5000,
                enumValues = 1000,
                stringChars = 120_000,
            ),
        )
}
