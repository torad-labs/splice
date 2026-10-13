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
package splice.app.control.mount

import splice.client.resume.ModelMoves
import splice.client.resume.MovingTrust
import splice.core.util.WallClock
import splice.sessions.http.MoveLedger
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.ModelMove
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The model a session's row reported, read at the moment he pressed Continue on, before the move ends its client. */
internal fun interface MovingSessionModel {
    operator fun invoke(record: SessionRecord): String?
}

/** Continue on's ledger over [moves] and [moving]. */
internal class ContinueLedger(
    private val moves: ModelMoves,
    private val moving: MovingTrust,
    private val modelOf: MovingSessionModel,
    private val pressedAt: WallClock = WallClock(System::currentTimeMillis),
) : MoveLedger {
    private val pending = ConcurrentHashMap<String, ModelMove>()

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
}
