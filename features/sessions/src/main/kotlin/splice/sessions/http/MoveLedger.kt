// NEW: Oct 11, 2026 (the transcript's move mark) — where Continue on keeps a move that came back: the command and model
// it went on, the command and model it left, and the moment he pressed, so the mark reads "Continued on [to] · from
// [from]" (fin, hitstop f38853e). The daemon's ledger keeps them beside the move its launch's rewrite recorded.
package splice.sessions.http

import splice.sessions.registry.SessionRecord

public interface MoveLedger {
    /** [record] is about to move onto command [to] and [model]: what it leaves is read now, before it leaves. */
    public fun leaving(record: SessionRecord, to: String, model: String?)

    /** [session] came back on the command it left for: the move read at [leaving] is kept. */
    public fun arrived(session: String)
}
