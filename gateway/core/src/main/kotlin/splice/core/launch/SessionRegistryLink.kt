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
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
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

    /** Point [dst] (a head's sessions dir) at [globalSessions], CREATING the global registry when it
     *  does not exist yet. Unlike every other shared item, missing is not a reason to skip: the
     *  registry is generated state, and a machine that never ran plain `claude` is exactly the fresh
     *  install where cross-head visibility is wanted and where its absence is least noticeable. */
    fun link(globalSessions: Path, dst: Path, log: LogSink = LogSink(DaemonLog::write)) {
        if (!ensureGlobalRegistry(globalSessions, log)) return
        if (dst.isSymbolicLink()) {
            val target = Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull()
            if (target == globalSessions) return
        } else if (Files.exists(dst, NOFOLLOW_LINKS) && !Files.isDirectory(dst, NOFOLLOW_LINKS)) {
            // Unexpected content is preserved, but no longer SILENTLY (DR-39, codex): a head
            // quietly keeping a private non-directory `sessions` contradicted the caller's
            // "link() logs its own declines" contract and sent the visibility hunt elsewhere.
            log(
                "[materialize] sessions registry NOT linked — $dst is unexpected non-directory " +
                    "content, kept as-is; move it aside to share the registry\n",
            )
            return
        }

        // Build the replacement before touching dst. A creation failure therefore preserves an
        // existing link or real registry directory for the next launch to retry.
        val staged = dst.resolveSibling(".${dst.fileName}.splice-link-${UUID.randomUUID()}")
        fs.createSymbolicLink(staged, globalSessions)
        try {
            if (Files.isDirectory(dst, NOFOLLOW_LINKS)) {
                migrateAndReplace(dst, globalSessions, staged, log)
            } else {
                fs.move(staged, dst, REPLACE_EXISTING, ATOMIC_MOVE)
            }
        } finally {
            // DR-104: the staged leftover is a courtesy — a cleanup throw must never REPLACE the
            // in-flight outcome: after a successful migration it converted success into the
            // caller's "sessions registry NOT linked", and after a move failure it renamed the
            // cause. Same rule as LoginInterception's writeHookScript teardown.
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(staged) },
                "staged-link cleanup — the link outcome must stand",
            )
        }
    }

    /** The registry directory, created if absent — false when this head must keep private sessions.
     *
     *  A path that exists but is not a directory is left exactly as found (never deleted to make room
     *  for generated state, the same rule linkOneShared follows), and a creation failure is reported
     *  rather than swallowed: both were silent `return`s, which is how "sessions not shared" reached
     *  the operator with no breadcrumb at all. */
    private fun ensureGlobalRegistry(globalSessions: Path, log: LogSink): Boolean {
        if (Files.isDirectory(globalSessions, NOFOLLOW_LINKS)) return true
        if (Files.exists(globalSessions, NOFOLLOW_LINKS)) {
            log("[sessions] $globalSessions exists but is not a directory — this head keeps private sessions\n")
            return false
        }
        val created = Cancellables.runCatchingCancellable { Files.createDirectories(globalSessions) }
        created.exceptionOrNull()?.let { cause ->
            log(
                "[sessions] could not create the global registry $globalSessions " +
                    "(${SafeFailureText.render(cause)}) — this head keeps private sessions\n",
            )
        }
        return created.isSuccess
    }

    /** Preflight every destination before moving anything, then roll confirmed moves back if a later
     *  transfer, directory delete, or staged-link promotion fails. Declining is never silent (DR-1):
     *  the refusal or rollback lands in the daemon log with its cause, because "sessions not shared"
     *  with zero breadcrumb was the audit's complaint. */
    private fun migrateAndReplace(dst: Path, globalSessions: Path, staged: Path, log: LogSink): Boolean {
        val entries = Files.newDirectoryStream(dst).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }
        }
        val transfers = entries.map { source -> source to globalSessions.resolve(source.fileName) }
        val nonRegular = entries.firstOrNull { !Files.isRegularFile(it, NOFOLLOW_LINKS) }
        val collision = transfers.firstOrNull { (_, target) -> Files.exists(target, NOFOLLOW_LINKS) }
        if (nonRegular != null || collision != null) {
            val cause = nonRegular?.let { "unexpected non-file entry '${it.fileName}'" }
                ?: "'${collision?.second?.fileName}' already exists in the global registry"
            // SAFE-RENDER-EXEMPT[2026-08-31]: `cause` here is a String this function composes from a file NAME and a fixed phrase, not a throwable — no exception text reaches it
            log(
                "[sessions] REFUSED to migrate $dst into $globalSessions ($cause) — " +
                    "this head keeps private sessions\n",
            )
            return false
        }

        val moved = mutableListOf<Pair<Path, Path>>()
        val commit = Cancellables.runCatchingCancellable {
            transfers.forEach { transfer ->
                fs.move(transfer.first, transfer.second)
                moved += transfer
            }
            Files.delete(dst)
            fs.move(staged, dst, ATOMIC_MOVE)
        }
        if (commit.isFailure) {
            log(
                "[sessions] migration of $dst failed " +
                    "(${commit.exceptionOrNull()?.let { SafeFailureText.render(it) }}) — " +
                    "rolled ${moved.size} confirmed moves back; this head keeps private sessions\n",
            )
            rollback(dst, moved)
        }
        return commit.isSuccess
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
            "best-effort rollback after a failed session-registry migration",
        )
    }
}
