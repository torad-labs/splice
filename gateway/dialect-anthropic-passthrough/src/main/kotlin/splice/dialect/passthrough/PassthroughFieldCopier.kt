// NEW: the top-level field forwarding decision — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). "Which top-level keys forward verbatim vs are owned by a
// specialized transform" is one decision expressed by one function plus its two tables; the
// tables have no other reader. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal class PassthroughFieldCopier(
    private val quirks: PassthroughQuirks,
    private val cache: PassthroughCacheControl,
) {

    private val handledKeys: Set<String> =
        if (quirks.mapThinkingToAdaptive) HANDLED_KEYS else HANDLED_KEYS - OUTPUT_CONFIG

    /** Copy every field the specialized scrubs do NOT own, cache_control stripped; sampling
     *  params optionally dropped. Unknown client fields ride through here verbatim. */
    fun copyUnhandledFields(sink: JsonObjectBuilder, raw: JsonObject) {
        for ((key, value) in raw) {
            val dropped = key in handledKeys || (key in SAMPLING_KEYS && quirks.stripSamplingParams)
            if (!dropped) sink.put(key, cache.stripCacheControl(value))
        }
    }
}

private const val TEMPERATURE = "temperature"
private const val TOP_P = "top_p"
private const val TOP_K = "top_k"

// Fields the specialized scrubs own (skipped by the verbatim copy); output_config is owned
// by the thinking mapping, so a client-sent one is dropped.
private val HANDLED_KEYS = setOf(
    MODEL,
    STREAM,
    THINKING,
    OUTPUT_CONFIG,
    MESSAGES,
    SYSTEM,
    TOOLS,
    TOOL_CHOICE,
)
private val SAMPLING_KEYS = setOf(TEMPERATURE, TOP_P, TOP_K)
