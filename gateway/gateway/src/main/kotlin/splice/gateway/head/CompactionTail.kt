package splice.gateway.head

import splice.core.compaction.CompactionInstructions
import splice.core.compaction.EffectiveCompactionInstructions
import splice.core.compaction.SessionProject
import java.nio.file.Path

/** Resolves custom instructions only after the positive-marker classifier has identified a real
 *  compaction. Ordinary turns therefore perform no session lookup and cannot receive a tail. */
public class CompactionTail(
    private val instructions: CompactionInstructions = CompactionInstructions(),
    sessions: SessionProject = SessionProject(),
    private val projectFor: (String?) -> Path? = sessions::projectFor,
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
