// NEW: V4-174/V4-387 — durable per-head trace of the client's request, every upstream attempt,
// each response and client frame, and the turn's outcome and perf. One record per JSONL line,
// one file per UTC day under the state dir's `trace/` directory.
//
// ON BY DEFAULT (operator 2026-09-28): every head constructs this store unless its own override
// explicitly says `trace = false`. `splice doctor` names opted-out heads, and `splice trace
// <head> --purge` deletes retained days. The directory is OWNER-ONLY because records carry
// whole conversations (ActivityDays ownerOnly → SecureFile.ownerOnlyDirectory).
//
// DERIVED FROM ActivityDays, not a second file store: UTC day files, the AsyncFileIo lane (a trace
// append never blocks the turn), page-cache telemetry appends, cross-process mutation locking,
// and retention at open and UTC midnight. JSONL stamps reference shared daily body chunks.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.storage.ActivityDays
import splice.core.storage.DayRecord
import splice.core.turn.TurnMeta
import splice.core.util.WallClock
import splice.head.trace.body.TraceBodies
import java.util.UUID

/** The record kinds a trace file holds, spelled once for the writer and the `splice trace` reader. */
internal object TraceKinds {
    const val ATTEMPT: String = "attempt"
    const val TURN: String = "turn"
}

/** The client's request as the head received it. Headers arrive already redacted
 *  (HeaderRedaction) — the credential the client presented is never held here. */
public data class ClientInbound(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/** Mints the id every record of one turn carries; injectable so a test can pin it. */
public fun interface TurnIdMint {
    public fun next(): String
}

private val randomTurnIds = TurnIdMint { UUID.randomUUID().toString().replace("-", "").take(TURN_ID_CHARS) }

// why: 48 random bits — unique across a head's retention window, short enough to type after --turn
private const val TURN_ID_CHARS = 12

/** One head's trace: begins a [TurnTrace] per admitted turn and writes what each turn reports. */
public class TraceStore(
    private val days: ActivityDays,
    public val head: String,
    /** The longest body a record keeps whole; a longer one is cut there and flagged. */
    public val maxBodyChars: Int,
    private val now: WallClock = WallClock(System::currentTimeMillis),
    private val ids: TurnIdMint = randomTurnIds,
) {
    private val bodies = TraceBodies()

    init {
        require(maxBodyChars > 0) { "a trace keeps at least one character of a body; maxBodyChars=$maxBodyChars" }
    }

    /** A new turn's trace, from the request as it arrived. Every record it writes carries the same id. */
    public fun begin(meta: TurnMeta, inbound: ClientInbound): TurnTrace =
        TurnTrace(this, ids.next(), meta, inbound, now)

    /** A body cut to [maxBodyChars]: the kept text and whether anything was dropped. */
    internal fun bounded(text: CharSequence): Pair<String, Boolean> =
        if (text.length <= maxBodyChars) text.toString() to false else text.substring(0, maxBodyChars) to true

    internal fun write(record: JsonObject) {
        days.append(DayRecord { file -> bodies.encode(record, file) })
    }

    /** The fields every record of this head carries, in front of the kind's own. */
    internal fun stamp(kind: String, turnId: String, meta: TurnMeta): JsonObject = buildJsonObject {
        put("kind", kind)
        put("turn", turnId)
        put("ts", now())
        put("head", head)
        meta.sessionId?.let { put("session", it) }
        put("model", meta.upstreamModel)
        put("clientModel", meta.originalModel)
        put("compact", meta.compact)
    }
}
