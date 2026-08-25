// NEW: cross-head session visibility (2026-08-25). Claude Code discovers peer sessions by listing
// $CLAUDE_CONFIG_DIR/sessions/* while the message sockets already live in the machine-global
// $XDG_RUNTIME_DIR/cc-socks — so per-head config isolation is the ONLY thing hiding one head's
// sessions from another head's ListAgents. Discovery is pure filesystem visibility ("sessions
// reach each other exactly when they can see the same files"), so linking every head's sessions/
// at the operator's global registry makes claudex, claude-grok, claude-kimi and plain claude
// sessions all see and message each other.
//
// Unlike every other shared item, a pre-existing REAL sessions/ dir is not operator content —
// Claude Code generates it (per-pid registration json + key file) — so instead of linkOneShared's
// never-delete-a-real-dir rule, its entries are MOVED into the global registry and the dir is then
// replaced by the link. A live session keeps writing through the new link to the same moved file,
// so it stays registered and reachable across the swap. Anything unexpected inside (a subdir, a
// non-regular file) aborts the migration and leaves the real dir alone.
package splice.core.launch

import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

internal class SessionRegistryLink {

    /** Point [dst] (a head's sessions dir) at [globalSessions]. No-op when the global registry
     *  does not exist yet — same skip-if-missing semantics as every other shared item. */
    fun link(globalSessions: Path, dst: Path) {
        if (!Files.isDirectory(globalSessions, NOFOLLOW_LINKS)) return
        when {
            dst.isSymbolicLink() -> {
                val target = Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull()
                if (target == globalSessions) return
                Files.delete(dst)
            }
            Files.isDirectory(dst, NOFOLLOW_LINKS) -> if (!migrate(dst, globalSessions)) return
            Files.exists(dst, NOFOLLOW_LINKS) -> Files.delete(dst)
        }
        Files.createSymbolicLink(dst, globalSessions)
    }

    /** Move the head registry's entries into the global one, then drop the emptied dir. False
     *  (migration refused, dir left in place) on any content that is not a plain registry file. */
    private fun migrate(dst: Path, globalSessions: Path): Boolean {
        val entries = Files.newDirectoryStream(dst).use { it.toList() }
        if (entries.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) }) return false
        for (entry in entries) {
            Cancellables.runCatchingCancellable { Files.move(entry, globalSessions.resolve(entry.fileName)) }
        }
        return Cancellables.runCatchingCancellable { Files.delete(dst) }.isSuccess
    }
}
