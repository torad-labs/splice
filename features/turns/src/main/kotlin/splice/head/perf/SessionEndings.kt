// NEW: Oct 10, 2026 (step 3, At limit and Signed out) — how each session's newest request ended, as its perf row did.
//
// A request splice turns away at admission (every account spent, a plan window spent, no credential) ends in
// milliseconds and never sits in LiveTurns, so the console cannot see it in flight. Its perf row is the only record,
// and reading the file per listing would scan hours of rows every five seconds. So the row is noted here as it is
// appended: held in memory, newest row per session, bounded. A daemon restart does not forget it: PerfStats seeds it
// from the tail of its own row file at boot (restore), so a session at its limit still reads At limit.
package splice.head.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys

/** One request's ending: its outcome tag, the account it ran on, when a spent window comes back (epoch seconds,
 *  null when the provider named none), the row's own time, and the model splice last served the session: this
 *  row's, or the one before it when splice turned this request away before a model was chosen, and how many times
 *  splice re-sent it before it ended, and when this run of refusals began: the time of the first request splice
 *  gave up on since the session's last clean answer, null while no such run is open. */
public data class SessionEnding(
    val outcome: String,
    val account: String?,
    val resetEpochSeconds: Long?,
    val ts: Long,
    val model: String? = null,
    val retries: Long = 0,
    val runSince: Long? = null,
)

// why: a person runs tens of sessions a day, so this many covers every one the console lists with room to spare,
// and each note is a few short strings.
private const val MAX_SESSION_ENDINGS = 2_000

/** The endings where splice re-sent a request until it gave up, which open a run of refusals (Marlin, Oct 11). */
private val GAVE_UP = setOf(OutcomeTag.UPSTREAM_FAILED.wire, OutcomeTag.TURN_CAP.wire)

public class SessionEndings {
    private val lock = Any()

    // Guarded by [lock]. Insertion order is recency: a session is re-inserted on each of its rows.
    private val newest = LinkedHashMap<String, SessionEnding>()

    /** The row just appended for [sessionTag]. A row older than the one held (a streaming round's row is appended
     *  late) does not replace it. */
    public fun note(sessionTag: String, ending: SessionEnding) {
        if (sessionTag.isEmpty()) return
        synchronized(lock) {
            val held = newest.remove(sessionTag)
            newest[sessionTag] = when {
                held != null && held.ts > ending.ts -> held.copy(model = held.model ?: ending.model)
                else -> ending.copy(model = ending.model ?: held?.model, runSince = runSince(held, ending))
            }
            while (newest.size > MAX_SESSION_ENDINGS) newest.remove(newest.keys.first())
        }
    }

    /** The rows already on disk, noted as if they had just been appended: the file's tail is how a restarted daemon
     *  learns each session's newest ending. A row without a session, an outcome or a time is not an ending. */
    public fun restore(rows: List<JsonObject>) {
        rows.forEach { row ->
            val session = text(row, "session") ?: return@forEach
            val outcome = text(row, "outcome") ?: return@forEach
            val ts = (row["ts"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val reset = (row[PerfKeys.EARLIEST_RESET_EPOCH_SECONDS] as? JsonPrimitive)?.longOrNull
            val retries = (row[PerfKeys.RETRIES] as? JsonPrimitive)?.longOrNull ?: 0
            note(session, SessionEnding(outcome, text(row, "account"), reset, ts, text(row, "model"), retries))
        }
    }

    /** When the run of refusals [ending] belongs to began. A clean answer ends the run; a request splice gave up on
     *  opens one when none is open; any other ending (a stop, a client abort between the client's own re-sends)
     *  neither opens nor ends it, so the run is counted from its first refusal and never from a later one. */
    private fun runSince(held: SessionEnding?, ending: SessionEnding): Long? = when {
        OutcomeTags.isClean(ending.outcome) -> null
        held?.runSince != null -> held.runSince
        ending.outcome in GAVE_UP -> ending.ts
        else -> null
    }

    private fun text(row: JsonObject, key: String): String? =
        (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** [sessionId]'s newest ending, matched the way PerfStats matches a row: the stored tag is a truncation of the
     *  id the caller holds. Null for a session with no row in the tail the daemon read at boot or since. */
    public fun endingFor(sessionId: String): SessionEnding? {
        if (sessionId.isEmpty()) return null
        return synchronized(lock) {
            newest.entries.firstOrNull { (tag, _) -> sessionId.startsWith(tag) }?.value
        }
    }
}
