// NEW: the only code in this module that reads UNTYPED client JSON defensively, split out of
// ResponsesRequestBuilder.kt (2026-08-17, concentration campaign) so the splice.core.util.JsonScalars
// dependency lives in one small file instead of smearing across the whole decomposition. Every
// relocated member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/**
 * Defensive reads of the client's own raw request JSON — effort/summary fields the wire types loosely
 * (or not at all) and the sent-summary readback build() uses for TurnMeta. The sole consumer of
 * [JsonScalars] on the request-shape side; keeping it isolated here is what keeps that dependency out
 * of [ResponsesReasoningKnobs] and the builder.
 */
internal class ResponsesLooseFields(private val quirks: ResponsesQuirks) {

    private val effortRules = ResponsesEffort()

    // NB: `as? JsonObject` NOT `?.jsonObject` — the latter THROWS on a non-object (e.g. a client
    // sending `"reasoning":"high"` as a bare string); Node's optional chaining degrades to default.
    internal fun looseEffort(raw: JsonObject): String? = sequenceOf(
        raw[FIELD_EFFORT],
        raw["reasoning_effort"],
        (raw["output_config"] as? JsonObject)?.get(FIELD_EFFORT),
        (raw["metadata"] as? JsonObject)?.get(FIELD_EFFORT),
        (raw[FIELD_REASONING] as? JsonObject)?.get(FIELD_EFFORT),
    ).mapNotNull { JsonScalars.str(it) }
        .mapNotNull { effortRules.normalizeEffort(it, quirks.effortLadder) }
        .firstOrNull()

    /** The client-requested summary sequence [ResponsesReasoningKnobs.resolveSummary] folds against
     *  the v27 visibility floor — extracted verbatim from that function's body. */
    internal fun requestedSummary(raw: JsonObject): String? = sequenceOf(
        (raw[FIELD_REASONING] as? JsonObject)?.get(FIELD_SUMMARY),
        raw["reasoning_summary"],
        (raw["output_config"] as? JsonObject)?.get("reasoning_summary"),
    ).mapNotNull { JsonScalars.str(it) }
        .mapNotNull { effortRules.normalizeSummary(it) }
        .firstOrNull()

    /** meta.summary reflects what was ACTUALLY sent (spark drops it → "none"), like Node's
     *  `req.reasoning?.summary ?? 'none'` — not the computed-but-maybe-dropped value. */
    internal fun sentSummary(reasoning: JsonObject?): String =
        JsonScalars.str(reasoning?.get(FIELD_SUMMARY)) ?: "none"
}
