// NEW: Oct 10, 2026 — the per-session facts a row carries that the registry does not hold.
//
// WHAT THESE HAVE IN COMMON, which is why they are one value and not two more constructor parameters.
// Each is a port the daemon supplies and features/sessions cannot reach for itself; each answers a
// question about ONE session that its registry entry cannot (which login proved it, how many turns it
// has run); each takes a request-scoped snapshot through `forRecords` before any row is serialized, so
// one bounded lookup serves a whole listing; and each writes ITS OWN keys onto the row through its own
// `write`, so SessionsRoutes never learns the shape of what it publishes. That is a role, not a bag:
// a third such fact — a session's plan limit, say — joins it by satisfying the same four properties,
// and anything that satisfies only some of them does not belong here.
//
// It arrived when `turnsOf` took SessionsRoutes' constructor to eight parameters and LongParameterList
// refused it. The wall was right to: the constructor had accumulated two populations, the registry and
// transcript machinery a row is BUILT from, and the optional daemon sources it is DECORATED with. This
// names the second one.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import splice.sessions.registry.SessionRecord

/** The daemon-supplied per-session facts a row carries. Every member defaults to "nothing wired", so a
 *  control plane built without a daemon behind it (tests, tools) publishes no claim about any of them. */
public data class SessionRowFacts(
    /** The session's own proved login (SessionAccountOf): `account`, `account_pin`, `account_state`. */
    val accountOf: SessionAccountOf = SessionAccountOf { _, _ -> null },
    /** How many turns it has run, and from when (SessionTurnsOf): `turns`, `turns_from_ms`. */
    val turnsOf: SessionTurnsOf = NoSessionTurns,
    /** How its newest request ended, when that holds it back (SessionEndingOf): `ended_by`. */
    val endingOf: SessionEndingOf = NoSessionEnding,
) {
    /** One snapshot of every source, taken once per request before the first row is written. A source
     *  without a bounded lookup returns itself, so this is free for the ones that do not need it. */
    public fun forRecords(records: List<SessionRecord>): SessionRowFacts =
        SessionRowFacts(accountOf.forRecords(records), turnsOf.forRecords(records), endingOf)

    /** Each source writes its own keys. The order is the order they are declared in, and nothing here
     *  reads or overwrites another's key: two sources that wanted the same key would be a conflict to
     *  resolve at the source, not a precedence to encode here. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        accountOf.write(record, target)
        turnsOf.write(record, target)
        endingOf.write(record, target)
    }
}
