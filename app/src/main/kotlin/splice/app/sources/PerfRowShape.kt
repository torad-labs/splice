// What one parsed perf JSON object says about its turn, read by name; split out of PerfRowsFileSource's scan.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.perf.PerfTurnIds
import splice.core.util.JsonScalars
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts

private const val REPLACEMENT_CHAR = '\uFFFD'
private const val UNATTRIBUTED = "?"

// V4-127: the perf ROW HEADER keys — the writer's string-and-flag facts, spelled as PerfStats.record
// writes them. NOT PerfKeys members, because PerfKeys is the catalogue of marks and counters and these
// five are the row's header; the header keys are literals inside PerfStats.record, one module away.
// NAMED HERE rather than inlined five more times so that a key renamed on the write side reads as ONE
// place to look rather than five (the split is reported; the writer is outside this row's fence).
private const val MODEL_KEY = "model"
private const val SESSION_KEY = "session"
private const val SESSION_ID_KEY = "session_id"
private const val RESPONSE_ID_KEY = "response_message_id"
private const val ACCOUNT_KEY = "account"
private const val CACHE_COLD_KEY = "cache_cold"
private const val COMPACT_KEY = "compact"

// Trace lookup and request ownership stay separate, including on a head with capture off.
private const val TURN_KEY = "turn"
private const val REQUEST_TURN_KEY = "turn_id"

// V4-444: the writer's own word on whether a capture was kept, and the upstream's own words about a failure it
// reported. Read by name like every other fact, so a torn or absent one stays absent rather than inventing a value.
private const val CAPTURE_KEY = "capture"
private const val PROVIDER_MESSAGE_KEY = "provider_message"

/** What one parsed perf JSON object says about its turn, read by name. Split out of Scan. */
internal class PerfRowShape {
    fun row(ts: Long, obj: JsonObject, fields: Map<String, Long>): PerfRow {
        val outcome = JsonScalars.str(obj, "outcome")?.takeUnless { REPLACEMENT_CHAR in it } ?: UNATTRIBUTED
        // V4-127: the writer's string-and-flag facts, read BY NAME off the same parsed object the
        // numeric bag came from. Read by name, never by position, because four of the five are
        // nullable and two of the strings are adjacent — a positional read silently swaps them.
        return PerfRow(
            ts = ts,
            outcome = outcome,
            cause = text(obj, "cause"),
            fields = fields,
            facts = PerfTurnFacts(
                model = text(obj, MODEL_KEY),
                session = text(obj, SESSION_KEY),
                account = text(obj, ACCOUNT_KEY),
                cacheCold = (obj[CACHE_COLD_KEY] as? JsonPrimitive)?.booleanOrNull,
                compact = (obj[COMPACT_KEY] as? JsonPrimitive)?.booleanOrNull,
                captured = (obj[CAPTURE_KEY] as? JsonPrimitive)?.booleanOrNull,
                providerMessage = text(obj, PROVIDER_MESSAGE_KEY),
            ),
            transcript = PerfTranscriptLink(
                sessionId = text(obj, SESSION_ID_KEY),
                responseMessageId = text(obj, RESPONSE_ID_KEY),
            ),
            turns = PerfTurnIds(trace = text(obj, TURN_KEY), request = text(obj, REQUEST_TURN_KEY)),
        )
    }

    /** A descriptive string field, ABSENT when the row does not carry it or carries it TORN.
     *  The replacement-char rule the outcome tag above already applies, for the same reason: a
     *  torn multi-byte char means the decoded text is not what was written, and a payload the
     *  operator is meant to trust never carries U+FFFD as if it were a value. Absent is the
     *  honest reading; a null and an empty string are different facts and stay different. */
    private fun text(obj: JsonObject, key: String): String? =
        JsonScalars.str(obj, key)?.takeUnless { REPLACEMENT_CHAR in it }
}
