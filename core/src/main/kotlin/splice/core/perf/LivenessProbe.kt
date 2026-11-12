// NEW: V4-454 — the refusal-only probe marker and narrow historical non-work fingerprint.
package splice.core.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val LEGACY_REQUEST_BYTES = JsonPrimitive(LivenessProbe.PROBE_REQUEST_JSON.toByteArray(Charsets.UTF_8).size)
private val WORK_FACTS: Set<String> = setOf(
    "session",
    "session_id",
    "account",
    PerfKeys.LOCAL_STEP,
    PerfKeys.IN_TOKENS,
    PerfKeys.OUT_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.CACHE_WRITE_TOKENS,
)

/** A probe marker only refuses dispatch. It never exempts a served request from accounting. */
public object LivenessProbe {
    public const val PROBE_HEADER_NAME: String = "x-splice-liveness-probe"
    public const val PROBE_HEADER_VALUE: String = "1"
    public const val PROBE_REQUEST_JSON: String = """{"splice_liveness_probe":true}"""

    /**
     * The pre-marker loop's measured, non-inference row shape. Every discriminator is required;
     * missing provenance or any session, account, compaction, step or token fact keeps a row as work.
     * This is a legacy fingerprint, not a claim that every row retains its original request body.
     */
    public fun legacyRow(row: JsonObject): Boolean =
        row["model"] == JsonPrimitive("") &&
            row["outcome"] == JsonPrimitive(OutcomeTag.UPSTREAM_FAILED.wire) &&
            row[PerfKeys.REQ_BYTES] == LEGACY_REQUEST_BYTES &&
            WORK_FACTS.none(row::containsKey) &&
            (row["compact"] == null || row["compact"] == JsonPrimitive(false))
}
