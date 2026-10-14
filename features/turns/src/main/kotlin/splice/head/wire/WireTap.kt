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
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.turn.TurnMeta
import splice.core.util.JsonWire
import splice.core.util.WallClock
import splice.upstream.memory.JvmHeap

/** One upstream request as it left splice: [body] is the exact string the round POSTed. */
public data class WireRecord(
    val ts: Long,
    val session: String?,
    val model: String,
    val compact: Boolean,
    val body: String,
    /** The request's perf turn id, independent of the live-turn stop id. */
    val turnId: String? = null,
)

/** The last [keep] upstream request bodies of one head, newest last. CME-safe under one lock, the
 *  UsageRing idiom: the deque never escapes it. [keep] must be positive — a head with nothing to
 *  keep has no tap at all (null), never an empty one, so "off" cannot be mistaken for "empty". */
public class WireTap(
    public val keep: Int,
    private val now: WallClock = WallClock(System::currentTimeMillis),
    private val heap: HeapBudget = JvmHeap.budget,
) {
    private data class Entry(val record: WireRecord, val owner: HeapObservation, val cached: HeapObservation)
    init {
        require(keep > 0) { "a wire tap keeps at least one body; keep=$keep" }
    }

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()

    /** Every round's body passes through here — fold, re-anchor and tool-search rounds included,
     *  because each is its own upstream request and an audit that showed only the first would lie. */
    public fun record(meta: TurnMeta, body: String, turnId: String?) {
        val record = WireRecord(now(), meta.scope.sessionId, meta.route.upstreamModel, meta.compact, body, turnId)
        val owner = HeapOwners.observe(record, heap, body.length * 2L, HeapCategory.RECENT_HISTORY)
        val entry = Entry(record, owner, owner.share())
        HeapOwners.keep(entry, entry.cached)
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > keep) {
                val retired = ring.removeFirst()
                retired.owner.ownerFinished()
                retired.cached.close(HeapRelease.EVICTED)
            }
        }
    }

    /** The newest [last] records, oldest first; the whole ring when [last] is not positive. */
    public fun recent(last: Int = keep): List<WireRecord> = synchronized(lock) {
        (if (last <= 0) ring.toList() else ring.takeLast(last)).map(Entry::record)
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
                        record.turnId?.let { put("turn_id", it) }
                    },
                )
            }
        }
    }.let(JsonWire::string)
}
