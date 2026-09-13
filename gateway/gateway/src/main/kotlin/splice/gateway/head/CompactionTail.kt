package splice.gateway.head

import splice.core.compaction.CompactionInstructions
import splice.core.compaction.EffectiveCompactionInstructions
import splice.core.compaction.SessionProject
import java.nio.file.Path

/** Resolves custom instructions only after the positive-marker classifier has identified a real
 *  compaction. Ordinary turns therefore perform no session lookup and cannot receive a tail. */
/** The project directory a session works in, or null when the session is unknown. */
public fun interface SessionProjectLookup {
    public operator fun invoke(sessionId: String?): Path?
}

public class CompactionTail(
    private val instructions: CompactionInstructions = CompactionInstructions(),
    sessions: SessionProject = SessionProject(),
    private val projectFor: SessionProjectLookup = SessionProjectLookup { sessions.projectFor(it) },
) {
    public fun resolve(
        compact: Boolean,
        wireModel: String,
        sessionId: String?,
    ): EffectiveCompactionInstructions? {
        if (!compact) return null
        return instructions.resolve(wireModel, projectFor(sessionId))
    }
}
