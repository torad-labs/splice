// NEW: Oct 11, 2026 — Continue on's ledger, lifted out of SessionsWiring (concentration, splice-lead).
//
// WHAT A MOVE LEAVES BEHIND, for as long as it is in flight and nothing after it. Two records, both opened when he
// presses and closed when the move ends, whichever way it ends:
//
//   THE MARK: the command and the model the session ran on before he pressed, read then, because the move is about
//   to end that client. A move that came back keeps it (ModelMoves.moved), so the transcript reads "Continued on
//   [to] · from [from]"; one that did not drops the mark its launch wrote, so the session's model is the one it went
//   on with and never the one it did not reach (Marlin, move check 5).
//
//   THE FOLDER: which folder the session is moving out of (MovingTrust), so the launch can carry the trust he
//   granted there. Claude Code keeps a home-folder trust for the session alone, so home is the one folder whose
//   grant leaves nothing to read; FolderTrust is what decides that, and this only says what is moving.
//
// ONE MOVE OF A SESSION AT A TIME (Oct 22, PR #247's second review). Sessions and the manager's head each build their
// own Continue on over this one ledger, so this is where a press finds a move already in flight and is refused. Both
// records are keyed by session, and only the move in flight writes or closes them: a second press never overwrites the
// first one's mark or closes it under it.
package splice.app.control.mount

import splice.client.resume.ModelMoves
import splice.client.resume.MovingTrust
import splice.core.util.WallClock
import splice.sessions.http.MoveLedger
import splice.sessions.http.MovingSessionModel
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.ModelMove
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Continue on's ledger over [moves] and [moving]. */
internal class ContinueLedger(
    private val moves: ModelMoves,
    private val moving: MovingTrust,
    private val modelOf: MovingSessionModel,
    private val pressedAt: WallClock = WallClock(System::currentTimeMillis),
) : MoveLedger {
    private val pending = ConcurrentHashMap<String, ModelMove>()

    /** The sessions with a move in flight, opened by [opens] and closed by [dropped]. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun opens(session: String): Boolean = inFlight.add(session)

    override fun leaving(record: SessionRecord, to: String, model: String?) {
        val id = record.sessionId ?: return
        val now = pressedAt()
        pending[id] = ModelMove(null, model.orEmpty(), to, now, record.head, modelOf(record))
        record.process.cwd?.let { moving.granted(id, Path.of(it), now) }
    }

    override fun arrived(session: String, arrivedAs: String) {
        moving.done(session)
        // the mark goes on the session that came back: the launch's copy when the move crossed heads
        pending.remove(session)?.let { moves.moved(arrivedAs, it) }
    }

    override fun failed(session: String): String? {
        moving.done(session)
        return pending.remove(session)?.also { move ->
            move.command?.let { moves.unmoved(session, it, move.movedAt) }
        }?.fromModel
    }

    override fun dropped(session: String) {
        moving.done(session)
        pending.remove(session)
        inFlight.remove(session)
    }
}
