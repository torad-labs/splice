// NEW: V4-173 (operator 2026-09-20: "we're a proxy man, how aren't we able to check the system
// prompt from the requests?") — the ONE place splice keeps a body it sent upstream. Until this
// row nothing did: the perf row stamps `upstream_req_bytes`, a count, and the bytes died with the
// round, so the strip mode's `system_prompt_applied` could say "a paragraph was removed" and
// nothing anywhere could say WHICH. A proxy that cannot show what it sent cannot be audited.
//
// OPT-IN BY CONSTRUCTION (operator: "make the upstream request bodies opt-in so our users don't
// think we're spying on them"). A body carries the user's whole conversation, so this class is
// only ever built for a head whose operator named a count ([heads.KEY.overrides] wireTap = N;
// HeadServerFactory hands every other head null), it holds the LAST N in memory and nowhere else,
// and a restart forgets them. `splice doctor` names the tap on every run while it is on.
package splice.head.wire

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.turn.TurnMeta
import splice.core.util.WallClock

/** One upstream request as it left splice: [body] is the exact string the round POSTed. */
public data class WireRecord(
    val ts: Long,
    val session: String?,
    val model: String,
    val compact: Boolean,
    val body: String,
)

/** The last [keep] upstream request bodies of one head, newest last. CME-safe under one lock, the
 *  UsageRing idiom: the deque never escapes it. [keep] must be positive — a head with nothing to
 *  keep has no tap at all (null), never an empty one, so "off" cannot be mistaken for "empty". */
public class WireTap(public val keep: Int, private val now: WallClock = WallClock(System::currentTimeMillis)) {
    init {
        require(keep > 0) { "a wire tap keeps at least one body; keep=$keep" }
    }

    private val lock = Any()
    private val ring = ArrayDeque<WireRecord>()

    /** Every round's body passes through here — fold, re-anchor and tool-search rounds included,
     *  because each is its own upstream request and an audit that showed only the first would lie. */
    public fun record(meta: TurnMeta, body: String) {
        val record = WireRecord(now(), meta.sessionId, meta.upstreamModel, meta.compact, body)
        synchronized(lock) {
            ring.addLast(record)
            while (ring.size > keep) ring.removeFirst()
        }
    }

    /** The newest [last] records, oldest first; the whole ring when [last] is not positive. */
    public fun recent(last: Int = keep): List<WireRecord> = synchronized(lock) {
        if (last <= 0) ring.toList() else ring.takeLast(last)
    }

    /** `{key, keep, records: [{ts, session, model, compact, body}]}` for GET /wire. */
    public fun json(key: String, last: Int = keep): String = buildJsonObject {
        put("key", key)
        put("keep", keep)
        putJsonArray("records") {
            recent(last).forEach { record ->
                add(
                    buildJsonObject {
                        put("ts", record.ts)
                        record.session?.let { put("session", it) }
                        put("model", record.model)
                        put("compact", record.compact)
                        put("body", record.body)
                    },
                )
            }
        }
    }.toString()
}
