// NEW: Oct 10, 2026 — the transcript reader, plus where each session changed model.
package splice.client.transcript

import splice.client.resume.ModelMoves
import splice.sessions.transcript.ModelMove
import splice.sessions.transcript.SessionTranscripts

/** [reader] answers every read; the record of moves (ModelMoves) answers where a session changed model. */
public class MovedTranscripts(
    reader: SessionTranscripts,
    private val moved: ModelMoves,
) : SessionTranscripts by reader {
    override fun moves(sessionId: String): List<ModelMove> = moved.of(sessionId)
}
