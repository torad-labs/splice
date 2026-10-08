// NEW: where a session's transcript is looked for. The head trees, the vanilla tree and the order between them
// change together (the header of SessionsRoutes names the order), so they live as one answer instead of two
// parameters every route rig repeats.
package splice.sessions.http

import splice.core.config.UserHome
import splice.sessions.query.SessionHead
import java.nio.file.Path

public class TranscriptRoots(
    private val heads: Map<String, SessionHead> = emptyMap(),
    /** The vanilla config root. Read only, never written (HEAD ISOLATION); a parameter so a test
     *  never reads the operator's own ~/.claude. */
    private val vanilla: Path = UserHome.dir().resolve(".claude"),
) {
    /** Every head's own tree, once. */
    public fun headTrees(): List<Path> = heads.values.mapNotNull { it.transcriptRoot }.distinct()

    /** The session's head tree, the vanilla tree, then every other head's. */
    public fun treesFor(head: String?): List<Path> {
        val own = head?.let { heads[it]?.transcriptRoot }
        val others = heads.values.mapNotNull { it.transcriptRoot }.filter { it != own }
        // listOf(vanilla), never `+ vanilla`: a Path is an Iterable of its own name elements, so
        // List<Path> + Path appends each component as a relative path of its own.
        return (listOfNotNull(own) + listOf(vanilla) + others).distinct()
    }
}
