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
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.isSymbolicLink

/** The two filesystem operations tests must fail deterministically without a custom provider. */
internal interface SessionRegistryFs {
    fun move(source: Path, target: Path, vararg options: CopyOption): Path
    fun createSymbolicLink(link: Path, target: Path): Path
}

private object ProcessSessionRegistryFs : SessionRegistryFs {
    override fun move(source: Path, target: Path, vararg options: CopyOption): Path =
        Files.move(source, target, *options)

    override fun createSymbolicLink(link: Path, target: Path): Path = Files.createSymbolicLink(link, target)
}

internal class SessionRegistryLink(
    private val fs: SessionRegistryFs = ProcessSessionRegistryFs,
) {

    /** Point [dst] (a head's sessions dir) at [globalSessions]. No-op when the global registry
     *  does not exist yet — same skip-if-missing semantics as every other shared item. */
    fun link(globalSessions: Path, dst: Path) {
        if (!Files.isDirectory(globalSessions, NOFOLLOW_LINKS)) return
        if (dst.isSymbolicLink()) {
            val target = Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull()
            if (target == globalSessions) return
        } else if (Files.exists(dst, NOFOLLOW_LINKS) && !Files.isDirectory(dst, NOFOLLOW_LINKS)) {
            return // unexpected content: never delete it to make room for the generated link
        }

        // Build the replacement before touching dst. A creation failure therefore preserves an
        // existing link or real registry directory for the next launch to retry.
        val staged = dst.resolveSibling(".${dst.fileName}.splice-link-${UUID.randomUUID()}")
        fs.createSymbolicLink(staged, globalSessions)
        try {
            if (Files.isDirectory(dst, NOFOLLOW_LINKS)) {
                migrateAndReplace(dst, globalSessions, staged)
            } else {
                fs.move(staged, dst, REPLACE_EXISTING, ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    /** Preflight every destination before moving anything, then roll confirmed moves back if a later
     *  transfer, directory delete, or staged-link promotion fails. */
    private fun migrateAndReplace(dst: Path, globalSessions: Path, staged: Path): Boolean {
        val entries = Files.newDirectoryStream(dst).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }
        }
        val transfers = entries.map { source -> source to globalSessions.resolve(source.fileName) }
        val refused = entries.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) } ||
            transfers.any { (_, target) -> Files.exists(target, NOFOLLOW_LINKS) }
        if (refused) return false

        val moved = mutableListOf<Pair<Path, Path>>()
        val committed = Cancellables.runCatchingCancellable {
            transfers.forEach { transfer ->
                fs.move(transfer.first, transfer.second)
                moved += transfer
            }
            Files.delete(dst)
            fs.move(staged, dst, ATOMIC_MOVE)
        }.isSuccess
        if (!committed) rollback(dst, moved)
        return committed
    }

    private fun rollback(dst: Path, moved: List<Pair<Path, Path>>) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                if (!Files.exists(dst, NOFOLLOW_LINKS)) Files.createDirectories(dst)
                moved.asReversed().forEach { (source, target) ->
                    if (!Files.exists(source, NOFOLLOW_LINKS) && Files.exists(target, NOFOLLOW_LINKS)) {
                        fs.move(target, source)
                    }
                }
            },
            "best-effort rollback after a refused session-registry migration",
        )
    }
}
