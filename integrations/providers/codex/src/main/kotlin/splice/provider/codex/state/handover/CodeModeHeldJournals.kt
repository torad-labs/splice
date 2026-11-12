// NEW: #525 (2026-11-12): the conversations another daemon still holds when this one starts.
package splice.provider.codex.state.handover

import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The conversation journals a retiring daemon still holds ([CodeModeHoldFiles]) when this daemon lists its state
 *  directory at boot. Such a journal is not read, rewritten or deleted here, and neither is its lock file, until the
 *  hold is free; the conversation's next turn then reads it from the disk ([CodeModeAdoption]). A daemon that never
 *  found a held journal pays one empty-set check per turn and nothing else. */
internal class CodeModeHeldJournals(private val dir: Path, private val log: LogSink) {
    private val names: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val isEmpty: Boolean get() = names.isEmpty()

    /** At boot: each hold file in [listed] another holder locks marks its conversation held; one nobody locks is
     *  stale and goes. Returns [listed] without the files of held conversations. */
    fun scan(listed: List<Path>): List<Path> {
        listed.filter { HELD_NAME.matches(it.fileName.toString()) }.forEach { hold ->
            if (CodeModeHoldFiles.heldElsewhere(hold)) {
                names += CodeModeHoldFiles.name(hold)
            } else {
                drop(hold)
            }
        }
        if (names.isNotEmpty()) {
            log("[code-mode] ${names.size} conversation(s) still held by the retiring daemon: read at their next turn")
        }
        return listed.filter { CodeModeHoldFiles.name(it) !in names && !HELD_NAME.matches(it.fileName.toString()) }
    }

    /** Whether [journal]'s conversation was held at boot and has not been read since. */
    fun holds(journal: Path): Boolean = names.isNotEmpty() && CodeModeHoldFiles.name(journal) in names

    /** True once nobody holds [journal]'s conversation any more; its stale hold file goes then. */
    fun free(journal: Path): Boolean {
        val hold = CodeModeHoldFiles.beside(journal)
        if (CodeModeHoldFiles.heldElsewhere(hold)) return false
        drop(hold)
        return true
    }

    /** This daemon has read [journal]'s conversation: it is an ordinary one from here on. */
    fun adopted(journal: Path) {
        names -= CodeModeHoldFiles.name(journal)
    }

    /** The files of the held conversations, which a Settings clear must leave on the disk. */
    fun inUse(): Set<Path> = names.flatMapTo(HashSet()) { name ->
        listOf(".jsonl", ".jsonl.lock", ".json", ".json.lock", CodeModeHoldFiles.HELD_SUFFIX)
            .map { dir.resolve(name + it).toAbsolutePath().normalize() }
    }

    private fun drop(hold: Path) {
        try {
            Files.deleteIfExists(hold)
        } catch (failure: IOException) {
            log("[code-mode] stale hold ${hold.fileName} not removed (${SafeFailureText.render(failure)})")
        }
    }
}

private val HELD_NAME = Regex("[0-9a-f]{64}\\.held")
