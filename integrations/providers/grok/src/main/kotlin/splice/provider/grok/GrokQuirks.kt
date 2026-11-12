// NEW: Grok owns its responses quirk profile and the chat xhigh model regex.
// DR-155: both the chat and responses profiles are live depending on the TOML dialect; this
// module owns the 8px floor so :app does not re-declare a vendor fact, and :dialects-openai-chat
// never depends on :providers-grok.
package splice.provider.grok

import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesOutputSchemaPolicy
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.dialect.responses.ResponsesRequestControlQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.ResponsesServiceTierSupport
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.StrictSchemaSubset

/** Holder for the grok quirk profile. Constructed by the daemon so TOML overlays a real table. */
public class GrokQuirks {
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "claude-grok",
        lite = ResponsesLiteQuirks(),
        backend = ResponsesBackendQuirks(
            store = false,
            promptCache = PromptCachePolicy(key = CacheKeyStrategy.SESSION_ID),
        ),
        reasoning = ResponsesReasoningQuirks(
            effortVocabulary = GrokEffortVocabulary(),
            supportsSummary = true,
            summaryRejectModelRegex = null,
        ),
        tools = ResponsesToolQuirks(
            emitToolChoice = true,
            emitStrict = true,
            // xAI is not known to read the `additional_tools` input item, so the tool list carries each tool change.
            acceptsInlineToolChanges = false,
            outputSchema = ResponsesOutputSchemaPolicy.StrictWithin(strictOutput()),
        ),
        request = ResponsesRequestControlQuirks(
            serviceTier = ResponsesServiceTierSupport.UNSUPPORTED,
            // DR-155: xAI's documented and ENFORCED minimum image edge. Its verbatim HTTP 400 body is
            // Image dimensions 1x1 are too small. Both width and height must be at least 8 pixels.
            minImageEdgePx = XAI_MIN_IMAGE_EDGE_PX,
        ),
        roundTrip = ResponsesRoundTripQuirks(
            reasoningCache = false,
        ),
    )

    public fun xhighModels(): Regex = XHIGH_MODELS

    /** The strict subset xAI holds a caller's output schema to, as its Structured Outputs guide states it
     *  (https://docs.x.ai/docs/guides/structured-outputs): objects close by default and unlisted fields stay optional,
     *  so nothing is rewritten; a $ref must not be circular; maxContains, minContains and array-form items are
     *  refused; not, if/then/else and allOf beyond one schema are not enforced; formats past these are not enforced.
     *  A schema outside it goes as written with strict false. */
    private fun strictOutput(): StrictSchemaSubset = StrictSchemaSubset(
        closedObjects = false,
        everyKeyRequired = false,
        keywords = setOf(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "enum",
            "const",
            "anyOf",
            "oneOf",
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
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "minProperties",
            "maxProperties",
            "\$schema",
            "\$id",
            "\$comment",
            "default",
            "examples",
        ),
        formats = setOf("date", "time", "date-time", "email", "uuid", "ipv4", "ipv6", "uri"),
        recursion = false,
        objectRoot = false,
    )
}

private const val XAI_MIN_IMAGE_EDGE_PX = 8

// xhigh is native on grok-4.6 and later (4.6..4.9, 4.10+). grok-5+ lands here when it exists —
// a one-line extension, not a silent cap on a shipped model.
// FILE SCOPE ON PURPOSE: one compiled Regex; as a member it would recompile per instance.
private val XHIGH_MODELS = Regex("grok-4\\.(?:[6-9]|[1-9]\\d)")
