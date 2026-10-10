// NEW: Oct 10, 2026 — how many turns a session has run, on its /api/sessions row.
//
// WHY THE ROW AND NOT A SECOND ROUTE. Teams' vacant member card offers "Add existing", and its menu
// lists the sessions a slot could take. A session id and a name do not tell you which of two quiet
// sessions is the one you meant; the turn count does (fin, Oct 10). The Sessions page needs the same
// number on the same rows, so it is ONE field on the listing both pages already read, not a Teams-only
// read that Sessions would have to re-derive.
//
// WHY A PORT AND NOT A DIRECT READ. The counter is SessionTotals, which lives in features/turns, and
// features/sessions depends on core, integrations-http and integrations-topology — not on features/turns
// (LayerMapLawTest). So the daemon supplies the lookup, the same way `account` arrives through
// SessionAccountOf and `team` through TeamSource.
//
// WHY THE COUNT NEVER TRAVELS ALONE. A total is a LOWER BOUND whenever the session began before the
// accumulator began counting: SessionTotals' own header says so, and the cases are ordinary — a daemon
// restart, a total dropped for idleness, a totals file that was not written cleanly. A bare "12 turns"
// on a session that ran 400 is a misstatement, so the row publishes two more keys beside `turns`:
// `turns_from_ms`, the moment the count covers from, and `turns_partial`, whether that moment is after
// the session's own start. The BOOLEAN is what a surface draws from (`N+ turns` when true), because the
// comparison needs both values in the same unit and the wire does not say that `started_at` is
// milliseconds. The timestamp stays published anyway: a reader asking "a floor from when" needs it, and
// a number whose provenance is hidden behind a boolean cannot be checked.
//
// WHAT AN ABSENT `turns` MEANS, WHICH IS NOT ZERO. Nothing counted this session: it has no id, the
// daemon wired no accumulator, or no row of its has ever been tallied. That last case covers both a
// session that genuinely ran nothing (a messaging bridge that registered and never sent a turn) and
// one whose every row was counted before this store's start — and splice cannot tell those two apart
// from here, so it claims neither. A surface draws nothing in the count's place rather than a zero or
// a dash, because a dash reads as a measured nothing.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** One session's counted turns and the moment that count covers from. The two numbers the row
 *  publishes and nothing else: a caller combining several heads' accumulators sums [turns] and keeps
 *  the LATEST [fromMs] of the ones that contributed, because the combined figure covers the session
 *  only if every contributing counter was already running when it began. */
public data class SessionTurnCount(val turns: Long, val fromMs: Long) {
    /** Whether [turns] is a FLOOR rather than the whole figure, decided here and not by the surface
     *  that draws it (builder3, Oct 10): the comparison needs [fromMs] and the session's start to be
     *  the same unit, the row's `started_at` does not say which it is, and a client guessing wrong
     *  draws "12 turns" on a session that ran 400 or "12+" on every row. Both are milliseconds —
     *  SessionRegistry compares `startedAt` against PID_REUSE_TOLERANCE_MS — and that is exactly the
     *  fact a reader of the wire cannot see.
     *
     *  A session with NO known start reads partial, because a count that cannot be placed against the
     *  session's beginning has not been shown to cover it. Absence of evidence is not a whole figure. */
    public fun partialFor(startedAtMs: Long?): Boolean = startedAtMs == null || fromMs > startedAtMs
}

/** The daemon's per-session turn accumulator, read per row. Null = nothing counted this session. */
public fun interface SessionTurnsOf {
    public fun countOf(sessionId: String): SessionTurnCount?

    /** A request-owned snapshot, for a source that would rather resolve the whole listing at once than
     *  answer one row at a time. Mirrors [SessionAccountOf.forRecords]; the default keeps this source. */
    public fun forRecords(records: List<SessionRecord>): SessionTurnsOf = this

    /** All three keys, or none of them. Together on purpose: a count without the start it covers from
     *  cannot be told from an exact figure, and either number without [SessionTurnCount.partialFor]'s
     *  answer leaves the one comparison that matters to a reader who cannot see the units. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        val counted = record.sessionId?.let { countOf(it) } ?: return
        target.put("turns", counted.turns)
        target.put("turns_from_ms", counted.fromMs)
        target.put("turns_partial", counted.partialFor(record.process.startedAt))
    }
}

/** A control plane with no accumulator wired: every row keeps its `turns` absent. */
internal object NoSessionTurns : SessionTurnsOf {
    override fun countOf(sessionId: String): SessionTurnCount? = null
}
